"""통합 예약 저장, 동기화, 발송 시점, 알림톡 발송."""
from datetime import timedelta

from app.config import load_config
from app.db import Store
from app.models import Rule
from app.notifier import notify
from app.scheduler import dedupe_key, due_time, in_quiet_hours, is_due
from app.sender import SendError, SolapiSender
from app.sources import FetchResult
from app.sync import run_sync
from app.template import VARIABLES, render

from .conftest import KST, ROOT, kst, raw

NOW = kst(2026, 11, 17, 10)


# ---------- 저장 / 병합 ----------

def test_upsert_merges_without_erasing(store):
    bid = store.upsert_booking(raw(phone=None), NOW)
    store.upsert_booking(raw(name=None, phone="01099998888", product=None), NOW)
    b = store.get_booking(bid)
    assert (b.name, b.phone, b.product) == ("홍길동", "01099998888", "A. 낮 패키지")


def test_upsert_cancel_then_ignore_unknown_cancel(store):
    bid = store.upsert_booking(raw(), NOW)
    store.upsert_booking(raw(status="cancelled", start=None, end=None, room=None), NOW)
    b = store.get_booking(bid)
    assert b.status == "cancelled" and b.cancelled_at == NOW
    # 처음 보는 예약의 취소 메일(시각 없음)은 저장하지 않는다
    assert store.upsert_booking(raw(external_id="X", status="cancelled", start=None, end=None, room=None), NOW) is None


def test_conflicts_across_channels(store):
    store.upsert_booking(raw(), NOW)
    store.upsert_booking(raw(channel="spacecloud", external_id="S1", start=kst(2026, 11, 20, 16), end=kst(2026, 11, 20, 18)), NOW)
    store.upsert_booking(raw(channel="spacecloud", external_id="S2", room="B룸"), NOW)
    store.upsert_booking(raw(external_id="N2", start=kst(2026, 11, 20, 17), end=kst(2026, 11, 20, 19)), NOW)  # 끝-시작 맞닿음
    pairs = store.conflicts(NOW)
    assert [(a.external_id, b.external_id) for a, b in pairs] == [("N1", "S1"), ("S1", "N2")]


class FakeSource:
    def __init__(self, name, results):
        self.name = name
        self.results = list(results)

    def fetch(self, now):
        r = self.results.pop(0)
        if isinstance(r, Exception):
            raise r
        return r


def test_sync_snapshot_cancels_missing(store):
    window = (NOW, NOW + timedelta(days=60))
    src = FakeSource("spacecloud_ical", [
        FetchResult([raw(channel="spacecloud", external_id="S1"), raw(channel="spacecloud", external_id="S2", room="B룸")], window),
        FetchResult([raw(channel="spacecloud", external_id="S1")], window),
        RuntimeError("timeout"),
    ])
    run_sync(store, [src], NOW)
    run_sync(store, [src], NOW + timedelta(minutes=5))
    statuses = {b.external_id: b.status for b in store.list_bookings(NOW, NOW + timedelta(days=10))}
    assert statuses == {"S1": "confirmed", "S2": "cancelled"}
    run_sync(store, [src], NOW + timedelta(minutes=10))
    st = store.sync_statuses()["spacecloud_ical"]
    assert st["last_error"] == "timeout" and st["last_ok"] == store.iso(NOW + timedelta(minutes=5))


# ---------- 발송 시점 ----------

def booking(store, **kw):
    return store.get_booking(store.upsert_booking(raw(**kw), NOW))


def test_due_time_rules(store):
    b = booking(store)
    assert due_time(Rule("c", "c", "created", "x"), b, KST) == NOW
    assert due_time(Rule("d", "d", "start", "x", day_before_at="18:00"), b, KST) == kst(2026, 11, 19, 18)
    assert due_time(Rule("e", "e", "end", "x", offset_minutes=-180), b, KST) == kst(2026, 11, 20, 14)


def test_is_due_window_status_and_channel(store):
    b = booking(store)
    rule = Rule("e", "e", "end", "x", offset_minutes=-180)
    assert not is_due(rule, b, kst(2026, 11, 20, 13, 59), KST)
    assert is_due(rule, b, kst(2026, 11, 20, 14), KST)
    assert not is_due(rule, b, kst(2026, 11, 20, 17), KST)
    assert not is_due(Rule("e", "e", "end", "x", offset_minutes=-180, channels=("spacecloud",)), b, kst(2026, 11, 20, 14), KST)
    assert not is_due(Rule("e", "e", "end", "x", offset_minutes=-180, enabled=False), b, kst(2026, 11, 20, 14), KST)


def test_dedupe_key_reschedule(store):
    b = booking(store)
    moved = booking(store, start=kst(2026, 11, 21, 11), end=kst(2026, 11, 21, 17))
    created, start = Rule("c", "c", "created", "x"), Rule("d", "d", "start", "x", day_before_at="18:00")
    assert dedupe_key(created, b) == dedupe_key(created, moved)
    assert dedupe_key(start, b) != dedupe_key(start, moved)


# ---------- 알림톡 발송 ----------

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


def test_notify_sends_once_then_end_rule(cfg, store):
    booking(store)
    sender = FakeSender()
    notify(cfg, store, sender, NOW)
    notify(cfg, store, sender, NOW + timedelta(minutes=5))
    assert sender.sent == [("01012345678", "T1", "홍길동님 네이버 예약 2026-11-20(금) 11:00~17:00 예약완료")]
    notify(cfg, store, sender, kst(2026, 11, 20, 14, 1))
    assert [s[1] for s in sender.sent] == ["T1", "T2"]


def test_notify_cancel_rule(cfg, store):
    bid = booking(store).id
    sender = FakeSender()
    notify(cfg, store, sender, NOW)
    store.cancel_booking(bid, NOW + timedelta(hours=1))
    notify(cfg, store, sender, NOW + timedelta(hours=1))
    notify(cfg, store, sender, NOW + timedelta(hours=2))
    assert [s[1] for s in sender.sent] == ["T1", "T3"]


def test_notify_retries_then_gives_up(cfg, store):
    booking(store)
    sender = FakeSender(fail_times=99)
    for i in range(5):
        notify(cfg, store, sender, NOW + timedelta(minutes=i))
    row = store.list_sends()[0]
    assert (row["status"], row["attempts"]) == ("failed", 3)


def test_notify_retry_success(cfg, store):
    booking(store)
    sender = FakeSender(fail_times=2)
    for i in range(4):
        notify(cfg, store, sender, NOW + timedelta(minutes=i))
    row = store.list_sends()[0]
    assert (row["status"], row["attempts"], len(sender.sent)) == ("sent", 3, 1)


def test_notify_non_retryable(cfg, store):
    booking(store)
    notify(cfg, store, FakeSender(fail_times=9, retryable=False), NOW)
    assert store.list_sends()[0]["status"] == "failed"


def test_notify_invalid_phone_and_missing_template(cfg, store):
    booking(store, phone=None)
    booking(store, external_id="N2", room="B룸")
    store.save_rule(Rule("confirm", "예약완료", "created", "#{이름}님", solapi_template_id=""))
    cfg.dry_run = False
    sender = FakeSender()
    notify(cfg, store, sender, NOW)
    errors = sorted(r["last_error"] for r in store.list_sends())
    assert sender.sent == [] and errors == ["알림톡 템플릿 ID 없음", "휴대폰 번호 없음/형식 오류"]


def test_seed_marks_without_sending(cfg, store):
    booking(store)
    sender = FakeSender()
    notify(cfg, store, sender, NOW, seed=True)
    notify(cfg, store, sender, NOW + timedelta(minutes=5))
    assert sender.sent == [] and store.list_sends()[0]["status"] == "skipped"


def test_rules_seed_only_once(cfg, store):
    store.save_rule(Rule("confirm", "바뀐 이름", "created", "x"))
    store.seed_rules(cfg.rules)
    assert store.rules()[0].name == "바뀐 이름"


# ---------- 설정 / 기타 ----------

def test_example_config_loads_and_renders(tmp_path):
    cfg = load_config(str(ROOT / "config.example.yaml"))
    assert [r.id for r in cfg.rules] == ["booking_confirmed", "day_before", "before_end", "cancelled"]
    assert [r.name for r in cfg.rooms] == ["루프탑 파티룸"]
    assert cfg.resolve_room("[인원추가무료] 루프탑 감성파티룸") == "루프탑 파티룸"
    assert cfg.resolve_room("[11월/올나잇] 18시 - 익일 9시") == "루프탑 파티룸"  # 공간이 하나라 모든 상품이 같은 공간
    assert cfg.quiet_hours == "22:00-08:00"
    for r in cfg.rules:
        render(r.text, {v: "x" for v in VARIABLES})


def test_solapi_signature():
    s = SolapiSender("KEY", "SECRET", "PF", "0212345678")
    header = s.auth_header(date="2026-10-13T00:00:00+00:00", salt="abc")
    assert header.startswith("HMAC-SHA256 apiKey=KEY, date=2026-10-13T00:00:00+00:00, salt=abc, signature=")
    assert len(header.rsplit("=", 1)[1]) == 64


def test_store_file_roundtrip(tmp_path, cfg):
    path = str(tmp_path / "t.db")
    s = Store(path, KST)
    s.upsert_booking(raw(), NOW)
    assert len(Store(path, KST).list_bookings(NOW, NOW + timedelta(days=7))) == 1


# ---------- 발송 금지 시간 ----------

def test_in_quiet_hours():
    spec = "22:00-08:00"
    assert in_quiet_hours(kst(2026, 11, 20, 23), spec, KST)
    assert in_quiet_hours(kst(2026, 11, 21, 7, 59), spec, KST)
    assert not in_quiet_hours(kst(2026, 11, 21, 8), spec, KST)
    assert not in_quiet_hours(kst(2026, 11, 21, 21, 59), spec, KST)
    assert in_quiet_hours(kst(2026, 11, 21, 13), "12:00-14:00", KST)
    assert not in_quiet_hours(kst(2026, 11, 21, 3), None, KST)


def test_allnight_checkout_notice_waits_until_quiet_hours_end(cfg, store):
    """올나잇(18시~익일 9시)의 '종료 3시간 전' 안내는 06시가 아니라 금지 시간이 끝나는 08시에 나간다."""
    cfg.quiet_hours = "22:00-08:00"
    booking(store, start=kst(2026, 11, 20, 18), end=kst(2026, 11, 21, 9))
    sender = FakeSender()
    notify(cfg, store, sender, kst(2026, 11, 20, 18, 5))   # 예약완료 (금지 시간 아님)
    notify(cfg, store, sender, kst(2026, 11, 21, 6))       # 금지 시간: 보내지 않음
    assert [s[1] for s in sender.sent] == ["T1"]
    notify(cfg, store, sender, kst(2026, 11, 21, 8))
    assert [s[1] for s in sender.sent] == ["T1", "T2"]
