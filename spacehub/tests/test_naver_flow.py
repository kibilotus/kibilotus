"""네이버 예약: 자동 알림 메일(연락처 없음) + 사장님이 직접 보낸 예약 상세(연락처 있음)를 예약번호로 합친다."""
from datetime import timedelta
from email.message import EmailMessage

from app.notifier import notify
from app.sources import FetchResult
from app.sources.email_source import EmailSource, parse_mail
from app.sources.text import parse_amount, parse_period_from_product

from .conftest import KST, kst

NOW = kst(2026, 11, 20, 9, 20)


def resolve(*texts):
    return "루프탑 파티룸"


# 네이버 예약 '새로운 예약이 확정 되었습니다' 메일 (2026-11-20 실제 메일 구조. 이름·예약번호는 가상 값)
NAVER_CONFIRM_HTML = """
<div>N 예약</div><h1>스튜디오버디<br>새로운 예약이 확정 되었습니다.</h1><p>예약내역을 확인해 보세요.</p>
<table>
<tr><td>예약자명</td><td>이*상님</td></tr>
<tr><td>예약신청 일시</td><td>2026.11.20. 09:16:28</td></tr>
</table>
<h3>예약내역</h3>
<table>
<tr><td>예약번호</td><td>1400000001 <img alt="N Pay"></td></tr>
<tr><td>예약상품</td><td>[12월&amp;1월] 크리스마스&amp;신년회 시즌 Open</td></tr>
<tr><td>이용일시</td><td>2026.12.27.(일)</td></tr>
<tr><td>결제상태</td><td>결제완료</td></tr>
<tr><td>결제수단</td><td>신용카드 간편결제</td></tr>
<tr><td>결제금액</td><td>Xmas &amp; New Year(1) 200,000원 + 체크아웃시간 동일[12시-24시](1) 0원 = 200,000원</td></tr>
<tr><td>요청사항</td><td>-</td></tr>
</table>
"""

# 사장님이 스마트플레이스 '예약 상세정보' 화면 내용을 복사해 Gmail 로 보낸 메일 (가상 값)
PASTED_DETAIL = """예약자
이가상
전화번호
010-1111-2222
예약번호
1400000001
상품
[12월&1월] 크리스마스&신년회 시즌 Open
이용일
2026. 12. 27.(일)
결제금액
200,000원
"""


def test_parse_naver_confirm_mail():
    b = parse_mail("naver", "[네이버 예약] 스튜디오버디 새로운 예약이 확정 되었습니다", NAVER_CONFIRM_HTML, {}, KST, resolve)
    assert (b.external_id, b.status, b.name, b.phone, b.amount) == ("1400000001", "confirmed", "이*상", None, 200000)
    assert b.product == "[12월&1월] 크리스마스&신년회 시즌 Open"
    # 이용일시에는 날짜만 있고, 시간은 결제금액 옵션 '[12시-24시]' 에 있다 ('예약신청 일시'는 이용 시각이 아님)
    assert (b.start, b.end) == (kst(2026, 12, 27, 12), kst(2026, 12, 28, 0))


def test_hour_range_sources():
    assert parse_period_from_product("2026.12.27.(일)", "[11월/올나잇] 18시 - 익일 9시", KST) == (kst(2026, 12, 27, 18), kst(2026, 12, 28, 9))
    assert parse_period_from_product("2026.12.27.(일)", "[12월&1월] 시즌", KST, "옵션[12시-24시] 0원") == (kst(2026, 12, 27, 12), kst(2026, 12, 28, 0))
    assert parse_period_from_product("2026.12.27.(일)", "[12월&1월] 시즌", KST, "200,000원") is None
    assert parse_amount("A(1) 200,000원 + B(1) 30,000원 = 230,000원") == 230000


def test_pasted_detail_fills_phone_and_name(store):
    auto = parse_mail("naver", "[네이버 예약] 새로운 예약", NAVER_CONFIRM_HTML, {}, KST, resolve)
    pasted = parse_mail("naver", "네이버 예약", PASTED_DETAIL, {}, KST, resolve)
    bid = store.upsert_booking(auto, NOW)
    assert store.upsert_booking(pasted, NOW + timedelta(minutes=5)) == bid
    store.upsert_booking(auto, NOW + timedelta(minutes=10))  # 같은 알림 메일을 다시 읽어도 전체 이름이 유지된다
    b = store.get_booking(bid)
    assert (b.name, b.phone, b.start, b.amount) == ("이가상", "01011112222", kst(2026, 12, 27, 12), 200000)


class Sender:
    def __init__(self):
        self.sent = []

    def send(self, to, template_id, variables, text):
        self.sent.append(to)


def test_alimtalk_waits_for_phone_then_sends(cfg, store):
    bid = store.upsert_booking(parse_mail("naver", "새로운 예약", NAVER_CONFIRM_HTML, {}, KST, resolve), NOW)
    sender = Sender()
    notify(cfg, store, sender, NOW)
    notify(cfg, store, sender, NOW + timedelta(minutes=5))
    row = store.get_send(f"b{bid}:confirm")
    assert sender.sent == [] and (row["status"], row["last_error"]) == ("waiting", "휴대폰 번호 없음/형식 오류")

    store.upsert_booking(parse_mail("naver", "네이버 예약", PASTED_DETAIL, {}, KST, resolve), NOW + timedelta(minutes=30))
    notify(cfg, store, sender, NOW + timedelta(minutes=35))
    assert sender.sent == ["01011112222"] and store.get_send(f"b{bid}:confirm")["status"] == "sent"


def mail(sender, subject, body):
    m = EmailMessage()
    m["From"], m["Subject"], m["Date"] = sender, subject, "Fri, 20 Nov 2026 09:30:00 +0900"
    m.set_content(body)
    return m


def handle(store, msg, cfg_extra=None):
    cfg = {"user": "studio@gmail.com", "forwarders": ["owner@naver.com", "studio@gmail.com"],
           "channels": {"naver": {"from": ["navercorp.com"]}}, **(cfg_extra or {})}
    src = EmailSource(cfg, "pw", store, KST, resolve)
    result = FetchResult(bookings=[])
    src._handle("k1", msg, result)
    return result


def test_owner_sent_mails_are_read(store):
    fwd = "---------- Forwarded message ---------\nFrom: 네이버 예약\nSubject: 새로운 예약이 확정 되었습니다\n\n" + PASTED_DETAIL
    r = handle(store, mail("Owner <owner@naver.com>", "Fwd: [네이버 예약] 새로운 예약", fwd))
    assert [(b.channel, b.external_id, b.phone) for b in r.bookings] == [("naver", "1400000001", "01011112222")]
    # 제목 없이 붙여넣기만 해도 기본 채널(네이버)로 읽는다
    r = handle(store, mail("studio@gmail.com", "예약 추가", PASTED_DETAIL))
    assert r.bookings[0].channel == "naver"
    # 스페이스클라우드라고 적으면 스페이스클라우드 예약
    r = handle(store, mail("studio@gmail.com", "스페이스클라우드 예약", PASTED_DETAIL))
    assert r.bookings[0].channel == "spacecloud"


def test_unknown_sender_is_ignored(store):
    r = handle(store, mail("someone@example.com", "[네이버 예약] 새로운 예약", PASTED_DETAIL))
    assert r.bookings == [] and not store.mail_seen("k1")


def test_product_hours_config_for_season_product():
    ch = {"product_hours": {"크리스마스": "12시-24시"}}
    b = parse_mail("naver", "네이버 예약", PASTED_DETAIL, ch, KST, resolve)
    assert (b.start, b.end) == (kst(2026, 12, 27, 12), kst(2026, 12, 28, 0))


def test_unknown_booking_without_time_is_reported(store):
    from app.sync import run_sync

    class Src:
        name = "email"

        def fetch(self, now):
            return FetchResult([parse_mail("naver", "네이버 예약", PASTED_DETAIL, {}, KST, resolve)])

    run_sync(store, [Src()], NOW)
    assert "1400000001" in store.sync_statuses()["email"]["last_error"]
    assert store.list_bookings(NOW, NOW + timedelta(days=60)) == []
