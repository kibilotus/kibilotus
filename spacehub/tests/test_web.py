"""관리자 API."""
from datetime import datetime

import pytest
from fastapi.testclient import TestClient

from app.engine import Engine
from app.web import create_app

from .conftest import KST, kst, raw

NOW = kst(2026, 11, 17, 10)


class FixedEngine(Engine):
    def now(self) -> datetime:
        return NOW


@pytest.fixture
def client(cfg, store):
    engine = FixedEngine(cfg, store, sources=[], sender=object())
    c = TestClient(create_app(engine))
    c.auth = ("admin", "pw")
    return c, store


def test_requires_login(cfg, store):
    c = TestClient(create_app(FixedEngine(cfg, store, sources=[], sender=object())))
    assert c.get("/api/meta").status_code == 401
    assert c.get("/api/meta", auth=("admin", "wrong")).status_code == 401


def test_add_list_cancel_direct_booking(client):
    c, store = client
    res = c.post("/api/bookings", json={"name": "문채원", "phone": "010-6672-1111", "room": "B룸",
                                        "start": "2026-11-20T13:00", "end": "2026-11-20T16:00", "amount": 90000})
    assert res.status_code == 201
    b = res.json()
    assert (b["channel"], b["phone"], b["start"]) == ("direct", "01066721111", "2026-11-20T13:00:00+09:00")

    items = c.get("/api/bookings", params={"start": "2026-11-17T00:00", "days": 7}).json()
    assert [x["name"] for x in items] == ["문채원"]
    assert c.post(f"/api/bookings/{b['id']}/cancel").json()["status"] == "cancelled"


@pytest.mark.parametrize("body,msg", [
    ({"room": "C룸"}, "등록되지 않은 공간"),
    ({"end": "2026-11-20T12:00"}, "종료 시각"),
    ({"phone": "02-123-4567"}, "휴대폰 번호"),
])
def test_add_booking_validation(client, body, msg):
    c, _ = client
    base = {"name": "a", "room": "A룸", "start": "2026-11-20T13:00", "end": "2026-11-20T16:00"}
    res = c.post("/api/bookings", json={**base, **body})
    assert res.status_code == 400 and msg in res.json()["detail"]


def test_cannot_cancel_platform_booking(client):
    c, store = client
    bid = store.upsert_booking(raw(), NOW)
    res = c.post(f"/api/bookings/{bid}/cancel")
    assert res.status_code == 400 and "네이버" in res.json()["detail"]


def test_summary_reports_conflicts_and_missing_phone(client):
    c, store = client
    store.upsert_booking(raw(start=kst(2026, 11, 17, 11), end=kst(2026, 11, 17, 17)), NOW)
    store.upsert_booking(raw(channel="spacecloud", external_id="S1", phone=None,
                             start=kst(2026, 11, 17, 15), end=kst(2026, 11, 17, 18), amount=None), NOW)
    s = c.get("/api/summary").json()
    assert len(s["today"]) == 2 and s["today_by_channel"] == {"naver": 1, "spacecloud": 1, "direct": 0}
    assert s["week_revenue"] == 180000
    assert len(s["issues"]["conflicts"]) == 1
    assert [b["external_id"] if "external_id" in b else b["channel"] for b in s["issues"]["missing_info"]] == ["spacecloud"]


def test_save_rule_and_preview(client):
    c, _ = client
    body = {"name": "전날", "trigger": "start", "day_before_at": "19:30", "channels": ["naver"],
            "title": "[스튜디오버디] 안내", "solapi_template_id": "T9", "text": "#{이름}님 #{공간} 안내"}
    assert c.put("/api/rules/confirm", json=body).status_code == 200
    rule = next(r for r in c.get("/api/rules").json() if r["id"] == "confirm")
    assert (rule["day_before_at"], rule["channels"]) == ("19:30", ["naver"])
    assert c.get("/api/rules/confirm/preview").json()["text"] == "홍길동님 A룸 안내"

    bad = c.put("/api/rules/confirm", json={**body, "text": "#{없는변수}"})
    assert bad.status_code == 400
    bad = c.put("/api/rules/confirm", json={**body, "trigger": "end"})
    assert bad.status_code == 400


def test_feed_hides_personal_info(client):
    c, store = client
    store.upsert_booking(raw(), NOW)
    store.upsert_booking(raw(external_id="N2", status="cancelled"), NOW)
    assert TestClient(c.app).get("/feed/wrong.ics").status_code == 404
    body = TestClient(c.app).get("/feed/tok.ics").text
    assert body.count("BEGIN:VEVENT") == 1
    assert "DTSTART:20261120T020000Z" in body
    assert "홍길동" not in body and "01012345678" not in body


def test_index_served(client):
    c, _ = client
    res = c.get("/")
    assert res.status_code == 200 and "예약 관리" in res.text


def test_channels_lists_sources(client, cfg):
    c, _ = client
    cfg.sources = {"google_calendars": [{"name": "spacecloud_gcal", "label": "스페이스클라우드 구독", "enabled": True,
                                         "channel": "spacecloud", "calendar_id": "abc@import.calendar.google.com"}]}
    srcs = {s["name"]: s for s in c.get("/api/channels").json()["sources"]}
    g = srcs["spacecloud_gcal"]
    assert (g["channels"], g["enabled"], g["subscribed"], g["label"]) == (
        ["spacecloud"], True, True, "구글 캘린더 (스페이스클라우드 구독)")
    assert srcs["email"]["enabled"] is False
