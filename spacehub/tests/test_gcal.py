"""구글 캘린더(스페이스클라우드 구독 캘린더) 연동."""
from datetime import timedelta

from app.calendar_client import to_calendar_event
from app.config import load_config
from app.models import CalendarEvent
from app.sources.gcal_source import GoogleCalendarSource
from app.sources.ical_source import parse_ics
from app.sync import build_sources, run_sync

from .conftest import KST, ROOT, kst

NOW = kst(2026, 11, 19, 10)


def resolve(*texts):
    return "루프탑 파티룸"


class FakeClient:
    def __init__(self, *snapshots):
        self.snapshots = list(snapshots)

    def fetch(self, time_min, time_max):
        return self.snapshots.pop(0)


def ev(uid, summary="이가상 / 루프탑 감성파티룸", desc="", start=kst(2026, 12, 19, 18), end=kst(2026, 12, 20, 9), id_="g1"):
    return CalendarEvent(id=id_, summary=summary, description=desc, start=start, end=end, created=NOW, ical_uid=uid)


def source(client):
    cfg = {"name": "spacecloud_gcal", "channel": "spacecloud", "calendar_id": "x@import.calendar.google.com"}
    return GoogleCalendarSource(cfg, KST, resolve, client=client)


def test_to_calendar_event_keeps_ical_uid():
    item = {"id": "abc", "iCalUID": "sc-123@spacecloud.kr", "summary": "예약", "created": "2026-11-19T01:00:00Z",
            "start": {"dateTime": "2026-12-19T18:00:00+09:00"}, "end": {"dateTime": "2026-12-20T09:00:00+09:00"}}
    e = to_calendar_event(item)
    assert (e.ical_uid, e.start) == ("sc-123@spacecloud.kr", kst(2026, 12, 19, 18))


def test_gcal_source_builds_spacecloud_bookings():
    res = source(FakeClient([ev("sc-1@spacecloud.kr", desc="연락처: 010-1111-2222"),
                             ev("sc-2@spacecloud.kr", desc="예약번호: 777")])).fetch(NOW)
    a, b = res.bookings
    assert (a.channel, a.external_id, a.name, a.phone, a.room) == (
        "spacecloud", "sc-1@spacecloud.kr", "이가상", "01011112222", "루프탑 파티룸")
    assert b.external_id == "777"  # 예약번호가 있으면 알림 메일과 같은 ID
    assert res.snapshot_window == (NOW, NOW + timedelta(days=60))


def test_gcal_and_direct_ical_give_same_id():
    """같은 스페이스클라우드 일정을 구글 구독으로 읽든 주소로 직접 읽든 같은 예약이 된다 (나중에 방식을 바꿔도 중복 없음)."""
    ics = """BEGIN:VCALENDAR
VERSION:2.0
BEGIN:VEVENT
UID:sc-1@spacecloud.kr
DTSTART;TZID=Asia/Seoul:20261219T180000
DTEND;TZID=Asia/Seoul:20261220T090000
SUMMARY:이가상 / 루프탑 감성파티룸
END:VEVENT
END:VCALENDAR
"""
    direct = parse_ics(ics, "spacecloud", KST, resolve)[0]
    via_google = source(FakeClient([ev("sc-1@spacecloud.kr")])).fetch(NOW).bookings[0]
    assert (direct.external_id, direct.start, direct.end, direct.name) == (
        via_google.external_id, via_google.start, via_google.end, via_google.name)


def test_gcal_removed_event_is_cancelled(store):
    src = source(FakeClient([ev("sc-1"), ev("sc-2", id_="g2", start=kst(2026, 12, 21, 18), end=kst(2026, 12, 22, 9))],
                            [ev("sc-1")]))
    run_sync(store, [src], NOW)
    run_sync(store, [src], NOW + timedelta(hours=6))
    status = {b.external_id: b.status for b in store.list_bookings(NOW, NOW + timedelta(days=60))}
    assert status == {"sc-1": "confirmed", "sc-2": "cancelled"}


def test_missing_token_reports_error_without_crashing(cfg, store, tmp_path):
    cfg.sources = {"google_calendars": [{"name": "spacecloud_gcal", "enabled": True, "channel": "spacecloud",
                                         "calendar_id": "x", "auth": "oauth", "token_file": str(tmp_path / "none.json")}]}
    assert build_sources(cfg, store) == []
    assert "설정 확인 필요" in store.sync_statuses()["spacecloud_gcal"]["last_error"]


def test_example_config_google_calendars_and_legacy(cfg):
    ex = load_config(str(ROOT / "config.example.yaml"))
    assert [g["name"] for g in ex.google_calendars()] == ["spacecloud_gcal", "direct_gcal"]
    cfg.sources = {"google_calendar": {"enabled": True, "calendar_id": "c", "service_account_file": "sa.json"}}
    assert cfg.google_calendars() == [{"name": "google_calendar", "auth": "service_account", "enabled": True,
                                       "calendar_id": "c", "service_account_file": "sa.json"}]


def test_skip_host_blocks_for_naver_bookings():
    """2026-11 실제 구독 캘린더: 사장님이 네이버 예약 날짜를 막아 둔 '이름(네이버예약)' 일정과 제목 없는 일정이 섞여 있다."""
    cfg = {"name": "spacecloud_gcal", "channel": "spacecloud", "calendar_id": "x", "skip_keywords": ["네이버예약"]}
    client = FakeClient([ev("u1", summary="", start=kst(2026, 11, 20, 0), end=kst(2026, 11, 21, 0)),
                         ev("u2", summary="이가상(네이버예약)", start=kst(2026, 11, 23, 12), end=kst(2026, 11, 24, 0))])
    bookings = GoogleCalendarSource(cfg, KST, resolve, client=client).fetch(NOW).bookings
    assert [(b.external_id, b.name, b.phone) for b in bookings] == [("u1", None, None)]


def test_skip_untitled_blocks():
    cfg = {"name": "spacecloud_gcal", "channel": "spacecloud", "calendar_id": "x", "skip_untitled": True}
    client = FakeClient([ev("u1", summary=""), ev("u2", summary="이가상 / 루프탑", id_="g2")])
    assert [b.external_id for b in GoogleCalendarSource(cfg, KST, resolve, client=client).fetch(NOW).bookings] == ["u2"]
