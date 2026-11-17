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
from .text import label_value, parse_amount, parse_period, strip_html

log = logging.getLogger(__name__)

DEFAULT_LABELS = {
    "reservation_no": ["예약번호", "예약 번호", "주문번호"],
    "name": ["예약자명", "예약자", "이용자명", "이용자", "고객명", "이름"],
    "phone": ["예약자 연락처", "연락처", "전화번호", "휴대폰번호", "휴대폰"],
    "period": ["이용일시", "이용 일시", "예약일시", "예약 일시", "이용시간", "일시"],
    "product": ["상품명", "예약상품", "상품", "공간명", "공간"],
    "amount": ["결제금액", "결제 금액", "총 금액", "금액"],
}
DEFAULT_CANCEL_KEYWORDS = ["취소"]
DEFAULT_IGNORE_KEYWORDS = ["리뷰", "정산", "문의"]


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
    period = parse_period(period_text, tz) if period_text else None
    cancelled = any(k in subject for k in ch_cfg.get("cancel_keywords", DEFAULT_CANCEL_KEYWORDS))

    if not reservation_no and not period:
        if cancelled or "예약" in subject:
            raise ParseError("예약번호와 이용일시를 찾지 못했어요")
        return None
    if period_text and not period:
        raise ParseError(f"이용일시를 해석하지 못했어요: {period_text[:60]}")

    if reservation_no:
        external_id = reservation_no.split()[0]
    else:  # 예약번호가 없으면 예약자+이용일시로 식별 (취소 메일과 짝을 맞추기 위함)
        external_id = "h-" + hashlib.sha1(f"{name}|{period[0].isoformat()}".encode()).hexdigest()[:12]

    if name:
        name = name.split()[0]
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

    def _channel_for(self, sender: str) -> str | None:
        sender = sender.lower()
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
        channel = self._channel_for(sender)
        if channel is None:  # 예약 플랫폼 메일이 아니면 건너뛰되 기록은 남기지 않는다 (나중에 설정을 고치면 다시 읽도록)
            return
        try:
            raw = parse_mail(channel, subject, message_text(msg), self.cfg["channels"][channel], self.tz, self.resolve_room)
        except ParseError as e:
            log.warning("예약 메일 해석 실패 [%s] %s: %s", channel, subject, e)
            result.errors.append(f"{subject}: {e}")
            self.store.record_mail(key, channel=channel, subject=subject, ok=False, error=str(e), received_at=received)
            return
        if raw:
            result.bookings.append(raw)
        self.store.record_mail(key, channel=channel, subject=subject, ok=True, error=None, received_at=received)
