"""예약 수집: 기간 해석, 메일 해석, iCal 해석.

메일 샘플은 실제 플랫폼 메일을 받기 전 가정한 형태다. 실제 샘플을 받으면 여기에 추가해 회귀 테스트로 쓴다.
"""
import pytest

from app.sources.email_source import ParseError, parse_mail
from app.sources.ical_source import parse_ics
from app.sources.text import label_value, parse_amount, parse_period, strip_html

from .conftest import KST, kst


@pytest.mark.parametrize(
    "text,start,end",
    [
        ("2026.11.20.(목) 오후 2:00 ~ 오후 4:00", kst(2026, 11, 20, 14), kst(2026, 11, 20, 16)),
        ("2026.11.20.(목) 오후14:00~오후16:00", kst(2026, 11, 20, 14), kst(2026, 11, 20, 16)),
        ("2026-11-20 11:00 ~ 17:00", kst(2026, 11, 20, 11), kst(2026, 11, 20, 17)),
        ("2026년 11월 20일 (목) 18시 ~ 2026년 11월 21일 (금) 2시", kst(2026, 11, 20, 18), kst(2026, 11, 21, 2)),
        ("2026.11.20 22:00~02:00", kst(2026, 11, 20, 22), kst(2026, 11, 21, 2)),
        ("2026.11.20 18:00~24:00", kst(2026, 11, 20, 18), kst(2026, 11, 21, 0)),
        ("2026.11.20 오전 12:30~오전 3:00", kst(2026, 11, 20, 0, 30), kst(2026, 11, 20, 3)),
        ("2026.11.20 14:00~16:00 (2시간)", kst(2026, 11, 20, 14), kst(2026, 11, 20, 16)),
        # 네이버 '올나잇' 상품처럼 다음날 끝나는 예약
        ("2026.11.20.(금) 18:00~익일 09:00", kst(2026, 11, 20, 18), kst(2026, 11, 21, 9)),
        ("2026.11.20.(금) 18시 - 익일 9시", kst(2026, 11, 20, 18), kst(2026, 11, 21, 9)),
        ("2026.11.20.(금) 오후 6:00 ~ 2026.11.21.(토) 오전 9:00", kst(2026, 11, 20, 18), kst(2026, 11, 21, 9)),
    ],
)
def test_parse_period(text, start, end):
    assert parse_period(text, KST) == (start, end)


@pytest.mark.parametrize("text", ["2026.11.20 14:00", "내일 오후 2시~4시", ""])
def test_parse_period_rejects(text):
    assert parse_period(text, KST) is None


def test_label_value_inline_and_table():
    text = "예약번호 : 123\n예약자명\n\n홍길동\n연락처: 010-1234-5678"
    assert label_value(text, ["예약번호"]) == "123"
    assert label_value(text, ["예약자명"]) == "홍길동"
    assert label_value(text, ["전화번호", "연락처"]) == "010-1234-5678"
    assert label_value(text, ["없는라벨"]) is None


def test_strip_html_table():
    html = "<table><tr><th>예약자명</th><td>홍길동</td></tr><tr><th>예약번호</th><td>77</td></tr></table>"
    text = strip_html(html)
    assert label_value(text, ["예약자명"]) == "홍길동"
    assert label_value(text, ["예약번호"]) == "77"


def test_parse_amount():
    assert parse_amount("60,000원 (카드)") == 60000
    assert parse_amount("결제 예정") is None


def resolve(*texts):
    return "B룸" if any(t and "B." in t for t in texts) else "A룸"


NAVER_CONFIRM = """
[네이버 예약] 새로운 예약이 확정되었습니다.
예약번호 : 1234567890
예약자명 : 김도윤
연락처 : 010-2222-3333
상품명 : B. 밤 패키지 (18-23시)
이용일시 : 2026.11.20.(목) 오후 6:00~오후 11:00
결제금액 : 150,000원
"""


def test_parse_naver_confirm_mail():
    b = parse_mail("naver", "[네이버 예약] 예약 확정 안내", NAVER_CONFIRM, {}, KST, resolve)
    assert (b.external_id, b.status, b.name, b.phone, b.room, b.amount) == (
        "1234567890", "confirmed", "김도윤", "01022223333", "B룸", 150000)
    assert (b.start, b.end) == (kst(2026, 11, 20, 18), kst(2026, 11, 20, 23))


def test_parse_cancel_mail_only_reservation_no():
    b = parse_mail("naver", "[네이버 예약] 예약이 취소되었습니다", "예약번호 : 1234567890\n", {}, KST, resolve)
    assert (b.external_id, b.status, b.start) == ("1234567890", "cancelled", None)


def test_parse_mail_ignores_unrelated():
    assert parse_mail("naver", "[네이버] 리뷰가 등록되었습니다", NAVER_CONFIRM, {}, KST, resolve) is None
    assert parse_mail("naver", "스마트플레이스 소식", "공지사항입니다", {}, KST, resolve) is None


def test_parse_mail_errors_when_unreadable():
    with pytest.raises(ParseError):
        parse_mail("naver", "새 예약이 들어왔어요", "양식이 바뀐 본문", {}, KST, resolve)
    with pytest.raises(ParseError):
        parse_mail("naver", "새 예약", "예약번호: 1\n이용일시: 내일 오후", {}, KST, resolve)


def test_parse_mail_custom_labels():
    body = "주문 ID : SC-99\n게스트 : 한지민\n휴대전화 : 01088887777\n사용시간 : 2026.11.21 10:00~16:00"
    ch = {"labels": {"reservation_no": ["주문 ID"], "name": ["게스트"], "phone": ["휴대전화"], "period": ["사용시간"]}}
    b = parse_mail("spacecloud", "예약 확정", body, ch, KST, resolve)
    assert (b.external_id, b.name, b.phone, b.start) == ("SC-99", "한지민", "01088887777", kst(2026, 11, 21, 10))


ICS = """BEGIN:VCALENDAR
VERSION:2.0
BEGIN:VEVENT
UID:abc@spacecloud
DTSTART;TZID=Asia/Seoul:20261121T100000
DTEND;TZID=Asia/Seoul:20261121T160000
SUMMARY:한지민 / B. 밤 패키지
DESCRIPTION:예약번호: 555\\n연락처 010-8888-7777
END:VEVENT
BEGIN:VEVENT
UID:allday
DTSTART;VALUE=DATE:20261122
DTEND;VALUE=DATE:20261123
SUMMARY:휴무
END:VEVENT
BEGIN:VEVENT
UID:gone
DTSTART:20261123T010000Z
DTEND:20261123T030000Z
SUMMARY:취소된 예약
STATUS:CANCELLED
END:VEVENT
END:VCALENDAR
"""


def test_parse_ics():
    items = parse_ics(ICS, "spacecloud", KST, resolve)
    assert len(items) == 2  # 종일 일정 제외
    a, c = items
    assert (a.external_id, a.name, a.phone, a.room, a.start) == ("555", "한지민", "01088887777", "B룸", kst(2026, 11, 21, 10))
    assert (c.external_id, c.status, c.start) == ("gone", "cancelled", kst(2026, 11, 23, 10))


# 스페이스클라우드 호스트 '예약 완료' 메일 (2026-11 실제 샘플의 구조. 개인정보는 가상 값으로 바꿈)
SPACECLOUD_CONFIRM_HTML = """
<div>홍호스트 호스트님,<br>[인원추가무료] 루프탑 감성파티룸, BUDDY NIGHT 의<br>예약이 완료되었습니다.</div>
<p>호스트님의 사정으로 예약을 취소하는 경우,<br>MY &gt; 예약 상세 페이지에서 취소하기를 눌러 취소환불처리를 진행하실 수 있습니다.</p>
<h3>예약/결제 정보</h3>
<table>
<tr><td>예약공간</td><td>[인원추가무료] 루프탑 감성파티룸</td></tr>
<tr><td>예약내용</td><td>2026/12/19 18시 - 2026/12/20 09시</td></tr>
<tr><td>예약인원</td><td>5명</td></tr>
<tr><td>예약옵션</td><td>바베큐전기그릴</td></tr>
<tr><td>요청사항</td><td></td></tr>
<tr><td>예약자명</td><td>이가상</td></tr>
<tr><td>결제수단</td><td>신용카드</td></tr>
<tr><td>공간사용료</td><td>₩430,000</td></tr>
<tr><td>쿠폰적용</td><td>-₩5,000</td></tr>
</table>
"""

SC_CFG = {"labels": {"period": ["예약내용"], "product": ["예약공간"], "name": ["예약자명"],
                     "amount": ["최종 결제금액", "총 결제금액", "결제금액", "공간사용료"]}}


def test_parse_spacecloud_confirm_mail():
    b = parse_mail("spacecloud", "[스페이스클라우드] 예약이 완료되었습니다", SPACECLOUD_CONFIRM_HTML, SC_CFG, KST, resolve)
    assert b.status == "confirmed"  # 본문의 '취소하기를 눌러...' 안내 때문에 취소로 보면 안 된다
    assert (b.name, b.product, b.amount) == ("이가상", "[인원추가무료] 루프탑 감성파티룸", 430000)
    assert (b.start, b.end) == (kst(2026, 12, 19, 18), kst(2026, 12, 20, 9))
    assert b.phone is None  # 샘플에서 보이는 부분에는 연락처가 없다
    assert b.external_id.startswith("h-")  # 예약번호가 없어 예약자+이용일시로 식별


def test_spacecloud_default_labels_without_config():
    b = parse_mail("spacecloud", "예약 완료", SPACECLOUD_CONFIRM_HTML, {}, KST, resolve)
    assert (b.start, b.product) == (kst(2026, 12, 19, 18), "[인원추가무료] 루프탑 감성파티룸")


def test_spacecloud_cancel_by_body_matches_confirm():
    confirm = parse_mail("spacecloud", "예약 완료", SPACECLOUD_CONFIRM_HTML, SC_CFG, KST, resolve)
    cancel_html = SPACECLOUD_CONFIRM_HTML.replace("예약이 완료되었습니다", "예약이 취소되었습니다")
    cancel = parse_mail("spacecloud", "[스페이스클라우드] 알림", cancel_html, SC_CFG, KST, resolve)
    assert cancel.status == "cancelled" and cancel.external_id == confirm.external_id


def test_parse_period_dash_does_not_split_iso_dates():
    assert parse_period("2026-12-19 18:00 - 2026-12-20 09:00", KST) == (kst(2026, 12, 19, 18), kst(2026, 12, 20, 9))
