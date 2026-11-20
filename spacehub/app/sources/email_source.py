"""예약 알림 메일 읽기 (네이버 예약, 스페이스클라우드).

두 플랫폼 모두 공개 API 가 없어서, 사업자에게 오는 예약 알림 메일을 IMAP 으로 읽어 예약을 만든다.
메일 양식은 플랫폼이 바꿀 수 있으므로 발신 주소, 취소 키워드, 항목 라벨을 config.yaml 에서 고칠 수 있게 했다.
"""
from __future__ import annotations

import email
import hashlib
import imaplib
import logging
from datetime import datetime, timedelta
from email.header import decode_header, make_header
from email.message import Message
from email.utils import parseaddr, parsedate_to_datetime
from zoneinfo import ZoneInfo

from ..db import Store
from ..models import RawBooking
from ..parser import normalize_phone
from . import FetchResult
from .text import label_value, parse_amount, parse_period, parse_period_from_product, strip_html

log = logging.getLogger(__name__)

DEFAULT_LABELS = {
    "reservation_no": ["예약번호", "예약 번호", "주문번호"],
    "name": ["예약자명", "예약자", "이용자명", "이용자", "고객명", "이름"],
    "phone": ["예약자 연락처", "연락처", "전화번호", "휴대폰번호", "휴대폰"],
    # '예약신청 일시'(신청한 시각)는 이용 일시가 아니므로 넣지 않는다
    "period": ["이용일시", "이용 일시", "예약일시", "예약 일시", "이용시간", "예약내용", "이용일"],
    "product": ["상품명", "예약상품", "예약공간", "상품", "공간명", "공간"],
    "amount": ["결제금액", "결제 금액", "총 금액", "금액"],
}
DEFAULT_CANCEL_KEYWORDS = ["취소"]
# 제목에 '취소'가 없을 때 본문 문구로 취소 메일을 알아본다.
# 예약완료 메일 본문에도 "취소하기를 눌러 취소환불처리..." 같은 안내가 있으므로 '취소' 한 단어로 판단하면 안 된다.
DEFAULT_CANCEL_BODY_KEYWORDS = ["예약이 취소되었습니다", "예약취소가 완료", "예약 취소가 완료"]
DEFAULT_IGNORE_KEYWORDS = ["리뷰", "정산", "문의"]
# 사장님이 직접 보낸 메일의 채널 판단 단어 (앞에 있는 채널부터 확인)
DEFAULT_FORWARD_KEYWORDS = {"spacecloud": ["스페이스클라우드", "spacecloud"], "naver": ["네이버", "naver"]}


class ParseError(ValueError):
    pass


def parse_mail(channel: str, subject: str, body: str, ch_cfg: dict, tz: ZoneInfo, resolve_room) -> RawBooking | None:
    """메일 1통 -> RawBooking. 예약과 무관한 메일이면 None, 예약 메일인데 값을 못 읽으면 ParseError."""
    if any(k in subject for k in ch_cfg.get("ignore_keywords", DEFAULT_IGNORE_KEYWORDS)):
        return None
    labels = {**DEFAULT_LABELS, **ch_cfg.get("labels", {})}
    text = strip_html(body) if "<" in body else body

    reservation_no = label_value(text, labels["reservation_no"])
    period_text = label_value(text, labels["period"])
    name = label_value(text, labels["name"])
    phone = normalize_phone(label_value(text, labels["phone"]) or "")
    product = label_value(text, labels["product"])
    amount = parse_amount(label_value(text, labels["amount"]))
    amount_text = label_value(text, labels["amount"])
    period = (parse_period(period_text, tz) or parse_period_from_product(period_text, product, tz, amount_text)) if period_text else None
    if period_text and not period and product:
        # 시간이 상품명에도 결제금액에도 없는 상품(예: 시즌 상품)은 설정의 product_hours 로 보완
        # 예) product_hours: {"크리스마스": "12시-24시"}
        hours = next((h for k, h in ch_cfg.get("product_hours", {}).items() if k in product), None)
        if hours:
            period = parse_period_from_product(period_text, None, tz, f"{hours}")
    cancelled = any(k in subject for k in ch_cfg.get("cancel_keywords", DEFAULT_CANCEL_KEYWORDS)) or any(
        k in text for k in ch_cfg.get("cancel_body_keywords", DEFAULT_CANCEL_BODY_KEYWORDS))

    if not reservation_no and not period:
        if cancelled or "예약" in subject:
            raise ParseError("예약번호와 이용일시를 찾지 못했어요")
        return None
    if period_text and not period and not reservation_no:
        raise ParseError(f"이용일시를 해석하지 못했어요: {period_text[:60]}")
    # 예약번호는 있는데 시간을 모르면(예: 예약 상세 화면을 붙여넣었는데 상품명에 시간이 없음) 시간 없이 돌려준다.
    # 같은 예약번호의 예약이 이미 있으면 연락처·이름만 채워지고, 없으면 저장되지 않고 동기화 상태에 표시된다.

    if reservation_no:
        external_id = reservation_no.split()[0]
    else:  # 예약번호가 없으면 예약자+이용일시로 식별 (취소 메일과 짝을 맞추기 위함)
        external_id = "h-" + hashlib.sha1(f"{name}|{period[0].isoformat()}".encode()).hexdigest()[:12]

    if name:
        name = name.split()[0].removesuffix("님")  # 네이버 메일: '최*정님'

    return RawBooking(
        channel=channel,
        external_id=external_id,
        status="cancelled" if cancelled else "confirmed",
        name=name,
        phone=phone,
        product=product,
        room=resolve_room(product) if (product or period) else None,
        start=period[0] if period else None,
        end=period[1] if period else None,
        amount=amount,
    )


def _decode(value: str | None) -> str:
    return str(make_header(decode_header(value))) if value else ""


def message_text(msg: Message) -> str:
    plain, html = [], []
    for part in msg.walk():
        ctype = part.get_content_type()
        if part.get_content_maintype() == "multipart" or part.get_filename():
            continue
        payload = part.get_payload(decode=True)
        if payload is None:
            continue
        text = payload.decode(part.get_content_charset() or "utf-8", errors="replace")
        (plain if ctype == "text/plain" else html if ctype == "text/html" else []).append(text)
    return "\n".join(plain) if plain else strip_html("\n".join(html))


class EmailSource:
    """IMAP 메일함에서 채널별 발신 주소의 메일만 골라 읽는다. 한 번 읽은 메일(UID)은 다시 읽지 않는다."""

    name = "email"

    def __init__(self, cfg: dict, password: str, store: Store, tz: ZoneInfo, resolve_room):
        self.cfg = cfg
        self.password = password
        self.store = store
        self.tz = tz
        self.resolve_room = resolve_room

    def _channel_for(self, sender: str, subject: str, body: str) -> str | None:
        """어느 채널의 예약 메일인지. 모르는 발신자의 메일은 None (읽지 않음).

        - 플랫폼이 보낸 메일: 채널별 발신 주소(from)로 판단
        - 사장님이 직접 보낸 메일(forwarders): 알림 메일 전달(Fwd), 또는 예약 상세 화면 내용을 붙여넣은 메일.
          제목, 없으면 본문의 단어로 채널을 정한다. 아무 단어도 없으면 forward_default_channel.
        """
        sender = sender.lower()
        if sender in [a.lower() for a in self.cfg.get("forwarders", [])]:
            keywords = self.cfg.get("forward_channel_keywords", DEFAULT_FORWARD_KEYWORDS)
            for text in (subject, body):
                for channel, words in keywords.items():
                    if any(w.lower() in text.lower() for w in words):
                        return channel
            return self.cfg.get("forward_default_channel", "naver")
        for channel, ch_cfg in self.cfg.get("channels", {}).items():
            if any(addr.lower() in sender for addr in ch_cfg.get("from", [])):
                return channel
        return None

    def fetch(self, now: datetime) -> FetchResult:
        user = self.cfg["user"]
        since = (now - timedelta(days=int(self.cfg.get("lookback_days", 14)))).strftime("%d-%b-%Y")
        result = FetchResult(bookings=[])
        imap = imaplib.IMAP4_SSL(self.cfg.get("host", "imap.gmail.com"))
        try:
            imap.login(user, self.password)
            imap.select(self.cfg.get("mailbox", "INBOX"), readonly=True)
            _, data = imap.uid("search", None, f"(SINCE {since})")
            for uid in (data[0] or b"").split():
                key = f"{user}:{uid.decode()}"
                if self.store.mail_seen(key):
                    continue
                _, msg_data = imap.uid("fetch", uid, "(RFC822)")
                msg = email.message_from_bytes(msg_data[0][1])
                self._handle(key, msg, result)
        finally:
            try:
                imap.logout()
            except Exception:
                pass
        return result

    def _handle(self, key: str, msg: Message, result: FetchResult) -> None:
        sender = parseaddr(msg.get("From", ""))[1]
        subject = _decode(msg.get("Subject"))
        try:
            received = parsedate_to_datetime(msg["Date"]).astimezone(self.tz).isoformat(timespec="seconds")
        except Exception:
            received = None
        body = message_text(msg)
        channel = self._channel_for(sender, subject, body)
        if channel is None:  # 예약 플랫폼 메일이 아니면 건너뛰되 기록은 남기지 않는다 (나중에 설정을 고치면 다시 읽도록)
            return
        try:
            raw = parse_mail(channel, subject, body, self.cfg.get("channels", {}).get(channel, {}), self.tz, self.resolve_room)
        except ParseError as e:
            log.warning("예약 메일 해석 실패 [%s] %s: %s", channel, subject, e)
            result.errors.append(f"{subject}: {e}")
            self.store.record_mail(key, channel=channel, subject=subject, ok=False, error=str(e), received_at=received)
            return
        if raw:
            result.bookings.append(raw)
        self.store.record_mail(key, channel=channel, subject=subject, ok=True, error=None, received_at=received)
