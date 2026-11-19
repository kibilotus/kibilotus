"""Google Calendar API 연동.

인증은 두 가지를 지원한다.
- oauth: 사장님 구글 계정으로 한 번 로그인해 받은 토큰 파일. 'URL로 추가'한 구독 캘린더(스페이스클라우드 등)는
  서비스 계정과 공유할 수 없으므로 이 방식으로만 읽을 수 있다. (python -m app.main google-login)
- service_account: 서비스 계정 키 파일. 직접 만든 캘린더를 서비스 계정 이메일과 공유한 경우.
"""
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
        created=_parse_dt(item["created"]) if item.get("created") else _parse_dt(start["dateTime"]),
        ical_uid=item.get("iCalUID", ""),
    )


def load_credentials(*, auth: str, token_file: str | None = None, service_account_file: str | None = None):
    if auth == "oauth":
        from google.oauth2.credentials import Credentials

        return Credentials.from_authorized_user_file(token_file, SCOPES)
    if auth == "service_account":
        from google.oauth2 import service_account

        return service_account.Credentials.from_service_account_file(service_account_file, scopes=SCOPES)
    raise ValueError(f"구글 캘린더 auth 는 oauth 또는 service_account 여야 합니다: {auth}")


def build_service(creds):
    from googleapiclient.discovery import build

    return build("calendar", "v3", credentials=creds, cache_discovery=False)


class GoogleCalendarClient:
    def __init__(self, calendar_id: str, creds):
        self.service = build_service(creds)
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


def list_calendars(creds) -> list[dict]:
    items, token = [], None
    service = build_service(creds)
    while True:
        res = service.calendarList().list(pageToken=token).execute()
        items += res.get("items", [])
        token = res.get("nextPageToken")
        if not token:
            return items
