from datetime import datetime, timedelta
from pathlib import Path
from zoneinfo import ZoneInfo

import pytest

from app.calendar_client import to_calendar_event
from app.config import Config, load_config
from app.models import CalendarEvent, Rule, Template
from app.parser import mask_phone, parse_event
from app.runner import tick
from app.scheduler import dedupe_key, due_time, is_due
from app.sender import SendError, SolapiSender
from app.store import Store
from app.template import TemplateError, render

KST = ZoneInfo("Asia/Seoul")
ROOT = Path(__file__).resolve().parent.parent


def kst(*args):
    return datetime(*args, tzinfo=KST)


def make_event(summary="홍길동 / 010-1234-5678", description="", start=None, end=None, created=None, id="ev1"):
    start = start or kst(2026, 10, 20, 11)
    return CalendarEvent(
        id=id,
        summary=summary,
        description=description,
        start=start,
        end=end or start + timedelta(hours=6),
        created=created or kst(2026, 10, 13, 9),
    )


# ---------- 1단계: 파싱 ----------

@pytest.mark.parametrize(
    "summary,description,name,phone",
    [
        ("홍길동 / 010-1234-5678", "", "홍길동", "01012345678"),
        ("[A룸] 김민선 01098765432", "", "김민선", "01098765432"),
        ("낮 패키지 예약", "이름: 서은수<br>연락처: 010.2222.3333", "서은수", "01022223333"),
        ("예약", "예약자 : 이지우\n전화번호: 011 123 4567", "이지우", "0111234567"),
    ],
)
def test_parse_success(summary, description, name, phone):
    b = parse_event(make_event(summary, description), default_product="기본상품")
    assert (b.name, b.phone, b.is_valid) == (name, phone, True)


def test_parse_product_keyword_and_default():
    assert parse_event(make_event(description="상품: B. 밤 패키지"), default_product="X").product == "B. 밤 패키지"
    assert parse_event(make_event(), default_product="X").product == "X"


def test_parse_invalid_phone():
    b = parse_event(make_event(summary="홍길동 / 02-123-4567"), default_product="X")
    assert not b.is_valid and b.phone is None


def test_mask_phone():
    assert mask_phone("01012345678") == "010-****-5678"


def test_to_calendar_event_skips_all_day_and_cancelled():
    base = {"id": "a", "created": "2026-10-01T00:00:00Z"}
    assert to_calendar_event({**base, "start": {"date": "2026-10-20"}, "end": {"date": "2026-10-21"}}) is None
    assert to_calendar_event({**base, "status": "cancelled"}) is None
    ev = to_calendar_event(
        {**base, "start": {"dateTime": "2026-10-20T11:00:00+09:00"}, "end": {"dateTime": "2026-10-20T17:00:00+09:00"}}
    )
    assert ev.start == kst(2026, 10, 20, 11)


# ---------- 2단계: 발송 타이밍 ----------

BOOKING = parse_event(make_event(), default_product="X")


def test_due_time_rules():
    assert due_time(Rule("c", "created", "t"), BOOKING, KST) == kst(2026, 10, 13, 9)
    assert due_time(Rule("d", "start", "t", day_before_at="18:00"), BOOKING, KST) == kst(2026, 10, 19, 18)
    assert due_time(Rule("e", "end", "t", offset_minutes=-180), BOOKING, KST) == kst(2026, 10, 20, 14)


def test_is_due_window():
    rule = Rule("e", "end", "t", offset_minutes=-180)
    assert not is_due(rule, BOOKING, kst(2026, 10, 20, 13, 59), KST)
    assert is_due(rule, BOOKING, kst(2026, 10, 20, 14), KST)
    assert not is_due(rule, BOOKING, kst(2026, 10, 20, 17), KST)  # 이용 종료 후에는 발송 안 함


def test_dedupe_key_changes_on_reschedule_only_for_time_rules():
    moved = parse_event(make_event(start=kst(2026, 10, 21, 11)), default_product="X")
    created_rule, start_rule = Rule("c", "created", "t"), Rule("d", "start", "t", day_before_at="18:00")
    assert dedupe_key(created_rule, BOOKING) == dedupe_key(created_rule, moved)
    assert dedupe_key(start_rule, BOOKING) != dedupe_key(start_rule, moved)


# ---------- 3단계: 템플릿 / 발송 ----------

def test_render_template():
    t = Template("t", "KA01", "[#{스튜디오}] #{이름}님 #{이름}")
    text, variables = render(t, {"스튜디오": "S", "이름": "홍길동"})
    assert text == "[S] 홍길동님 홍길동"
    assert variables == {"#{스튜디오}": "S", "#{이름}": "홍길동"}


def test_render_missing_variable():
    with pytest.raises(TemplateError):
        render(Template("t", "KA01", "#{없는변수}"), {})


def test_solapi_signature():
    s = SolapiSender("KEY", "SECRET", "PF", "0212345678")
    header = s.auth_header(date="2026-10-13T00:00:00+00:00", salt="abc")
    assert header.startswith("HMAC-SHA256 apiKey=KEY, date=2026-10-13T00:00:00+00:00, salt=abc, signature=")
    assert len(header.rsplit("=", 1)[1]) == 64


def test_example_config_loads():
    cfg = load_config(str(ROOT / "config.example.yaml"))
    assert [r.id for r in cfg.rules] == ["booking_confirmed", "day_before", "before_end"]
    for t in cfg.templates.values():
        render(t, {"이름": "a", "상품": "b", "이용일시": "c", "스튜디오": "d"})


# ---------- 4단계: 통합 (중복 방지 / 재시도 / 오류) ----------

class FakeCalendar:
    def __init__(self, events):
        self.events = events

    def fetch(self, time_min, time_max):
        return [e for e in self.events if e.end > time_min and e.start < time_max]


class FakeSender:
    def __init__(self, fail_times=0, retryable=True):
        self.sent = []
        self.fail_times = fail_times
        self.retryable = retryable

    def send(self, to, template_id, variables, text):
        if self.fail_times > 0:
            self.fail_times -= 1
            raise SendError("boom", self.retryable)
        self.sent.append((to, template_id, text))


def make_cfg():
    return Config(
        calendar_id="c",
        studio_name="스튜디오어디",
        default_product="A. 낮 패키지",
        rules=[Rule("booking_confirmed", "created", "confirm"), Rule("before_end", "end", "end", offset_minutes=-180)],
        templates={
            "confirm": Template("confirm", "T1", "#{이름}님 #{상품} #{이용일시} 예약완료"),
            "end": Template("end", "T2", "#{이름}님 종료 3시간 전"),
        },
        max_attempts=3,
    )


def test_tick_sends_once_and_dedupes():
    cfg, store, sender = make_cfg(), Store(), FakeSender()
    cal = FakeCalendar([make_event()])
    now = kst(2026, 10, 13, 10)
    tick(cfg, cal, sender, store, now)
    tick(cfg, cal, sender, store, now + timedelta(minutes=5))
    assert sender.sent == [("01012345678", "T1", "홍길동님 A. 낮 패키지 2026-10-20(화) 11:00~17:00 예약완료")]

    tick(cfg, cal, sender, store, kst(2026, 10, 20, 14, 1))
    assert [s[1] for s in sender.sent] == ["T1", "T2"]


def test_tick_retries_then_succeeds():
    cfg, store, sender = make_cfg(), Store(), FakeSender(fail_times=2)
    cal = FakeCalendar([make_event()])
    for i in range(4):
        tick(cfg, cal, sender, store, kst(2026, 10, 13, 10, i))
    assert len(sender.sent) == 1
    row = store.get("ev1:booking_confirmed")
    assert (row["status"], row["attempts"]) == ("sent", 3)


def test_tick_gives_up_after_max_attempts():
    cfg, store, sender = make_cfg(), Store(), FakeSender(fail_times=99)
    cal = FakeCalendar([make_event()])
    for i in range(5):
        tick(cfg, cal, sender, store, kst(2026, 10, 13, 10, i))
    row = store.get("ev1:booking_confirmed")
    assert (row["status"], row["attempts"]) == ("failed", 3)


def test_tick_non_retryable_fails_immediately():
    cfg, store, sender = make_cfg(), Store(), FakeSender(fail_times=99, retryable=False)
    tick(cfg, FakeCalendar([make_event()]), sender, store, kst(2026, 10, 13, 10))
    assert store.get("ev1:booking_confirmed")["status"] == "failed"


def test_tick_invalid_phone_recorded_not_sent():
    cfg, store, sender = make_cfg(), Store(), FakeSender()
    tick(cfg, FakeCalendar([make_event(summary="홍길동 전화없음")]), sender, store, kst(2026, 10, 13, 10))
    assert sender.sent == []
    assert store.get("ev1:booking_confirmed")["status"] == "invalid"


def test_seed_marks_without_sending():
    cfg, store, sender = make_cfg(), Store(), FakeSender()
    cal = FakeCalendar([make_event()])
    tick(cfg, cal, sender, store, kst(2026, 10, 13, 10), seed=True)
    tick(cfg, cal, sender, store, kst(2026, 10, 13, 10, 5))
    assert sender.sent == []
    assert store.get("ev1:booking_confirmed")["status"] == "skipped"
