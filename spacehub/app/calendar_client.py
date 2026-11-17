"""Google Calendar API 연동 (서비스 계정 인증)."""
from __future__ import annotations

import logging
from datetime import datetime

from .models import CalendarEvent

log = logging.getLogger(__name__)

SCOPES = ["https://www.googleapis.com/auth/calendar.readonly"]


def _parse_dt(value: str) -> datetime:
    return datetime.fromisoformat(value.replace("Z", "+00:00"))


def to_calendar_event(item: dict) -> CalendarEvent | None:
    """API 응답 item -> CalendarEvent. 종일 일정(date만 있는 경우)과 취소된 일정은 제외."""
    if item.get("status") == "cancelled":
        return None
    start, end = item.get("start", {}), item.get("end", {})
    if "dateTime" not in start or "dateTime" not in end:
        return None
    return CalendarEvent(
        id=item["id"],
        summary=item.get("summary", ""),
        description=item.get("description", ""),
        start=_parse_dt(start["dateTime"]),
        end=_parse_dt(end["dateTime"]),
        created=_parse_dt(item["created"]),
    )


class GoogleCalendarClient:
    def __init__(self, service_account_file: str, calendar_id: str):
        from google.oauth2 import service_account
        from googleapiclient.discovery import build

        creds = service_account.Credentials.from_service_account_file(service_account_file, scopes=SCOPES)
        self.service = build("calendar", "v3", credentials=creds, cache_discovery=False)
        self.calendar_id = calendar_id

    def fetch(self, time_min: datetime, time_max: datetime) -> list[CalendarEvent]:
        """time_min 이후 끝나고 time_max 이전에 시작하는 일정 목록 (반복 일정은 개별 회차로 전개)."""
        events: list[CalendarEvent] = []
        page_token = None
        while True:
            res = (
                self.service.events()
                .list(
                    calendarId=self.calendar_id,
                    timeMin=time_min.isoformat(),
                    timeMax=time_max.isoformat(),
                    singleEvents=True,
                    orderBy="startTime",
                    pageToken=page_token,
                )
                .execute()
            )
            for item in res.get("items", []):
                ev = to_calendar_event(item)
                if ev:
                    events.append(ev)
            page_token = res.get("nextPageToken")
            if not page_token:
                return events
