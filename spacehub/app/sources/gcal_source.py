"""구글 캘린더 읽기 (선택). 전화·현장 예약을 캘린더에 적어 두는 경우 '직접 예약' 채널로 가져온다."""
from __future__ import annotations

from datetime import datetime, timedelta

from ..calendar_client import GoogleCalendarClient
from ..models import RawBooking
from ..parser import parse_event
from . import FetchResult


class GoogleCalendarSource:
    name = "google_calendar"

    def __init__(self, cfg: dict, resolve_room, client: GoogleCalendarClient | None = None):
        self.client = client or GoogleCalendarClient(cfg["service_account_file"], cfg["calendar_id"])
        self.channel = cfg.get("channel", "direct")
        self.lookahead_days = int(cfg.get("lookahead_days", 60))
        self.resolve_room = resolve_room

    def fetch(self, now: datetime) -> FetchResult:
        window = (now, now + timedelta(days=self.lookahead_days))
        bookings = []
        for ev in self.client.fetch(*window):
            b = parse_event(ev, default_product="")
            bookings.append(RawBooking(
                channel=self.channel, external_id=f"gcal-{ev.id}", name=b.name, phone=b.phone,
                product=b.product or ev.summary, room=self.resolve_room(ev.summary, ev.description),
                start=ev.start, end=ev.end,
            ))
        return FetchResult(bookings=bookings, snapshot_window=window)
