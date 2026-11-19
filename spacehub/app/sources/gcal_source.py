"""구글 캘린더 읽기.

- 스페이스클라우드: 호스트센터 '캘린더 내보내기' 주소를 구글 캘린더에 'URL로 추가'(구독)해 두고 그 캘린더를 읽는다.
  구글이 구독 캘린더를 다시 받아오는 주기는 구글이 정하며 보통 몇 시간 걸린다. 그만큼 예약 반영과 알림톡이 늦어진다.
- 직접 예약: 전화·현장 예약을 적어 두는 캘린더.
캘린더는 기간 내 전체 목록을 주므로, 목록에서 사라진 예약은 취소로 처리한다.
"""
from __future__ import annotations

from datetime import datetime, timedelta
from zoneinfo import ZoneInfo

from ..calendar_client import GoogleCalendarClient, load_credentials
from . import FetchResult
from .event_parse import raw_from_event


class GoogleCalendarSource:
    def __init__(self, cfg: dict, tz: ZoneInfo, resolve_room, client: GoogleCalendarClient | None = None):
        self.name = cfg.get("name", "google_calendar")
        self.channel = cfg.get("channel", "direct")
        self.lookahead_days = int(cfg.get("lookahead_days", 60))
        self.tz = tz
        self.resolve_room = resolve_room
        if client is None:
            creds = load_credentials(auth=cfg.get("auth", "oauth"), token_file=cfg.get("token_file", "google-token.json"),
                                     service_account_file=cfg.get("service_account_file"))
            client = GoogleCalendarClient(cfg["calendar_id"], creds)
        self.client = client

    def fetch(self, now: datetime) -> FetchResult:
        window = (now, now + timedelta(days=self.lookahead_days))
        bookings = [
            raw_from_event(channel=self.channel, uid=ev.ical_uid or f"gcal-{ev.id}", summary=ev.summary,
                           description=ev.description, start=ev.start, end=ev.end, cancelled=False,
                           tz=self.tz, resolve_room=self.resolve_room)
            for ev in self.client.fetch(*window)
        ]
        return FetchResult(bookings=bookings, snapshot_window=window)
