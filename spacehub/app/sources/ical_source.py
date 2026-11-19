"""캘린더 주소(iCal, .ics) 읽기. 스페이스클라우드 호스트센터의 '캘린더 내보내기' 주소 등에 쓴다.

iCal 은 기간 내 전체 목록을 주므로, 목록에서 사라진 예약은 취소로 처리한다.
예약번호가 일정 제목/설명에 있으면 그 번호를 ID 로 써서 같은 채널의 알림 메일과 한 예약으로 합친다.
"""
from __future__ import annotations

from datetime import datetime, timedelta
from zoneinfo import ZoneInfo

import requests
from icalendar import Calendar

from ..models import RawBooking
from . import FetchResult
from .event_parse import raw_from_event


def parse_ics(data: bytes | str, channel: str, tz: ZoneInfo, resolve_room) -> list[RawBooking]:
    cal = Calendar.from_ical(data)
    out = []
    for ev in cal.walk("VEVENT"):
        start, end = ev.get("DTSTART"), ev.get("DTEND")
        if start is None or end is None or not isinstance(start.dt, datetime):
            continue  # 종일 일정은 예약으로 보지 않는다
        s, e = start.dt, end.dt
        if s.tzinfo is None:
            s, e = s.replace(tzinfo=tz), e.replace(tzinfo=tz)
        out.append(raw_from_event(
            channel=channel, uid=str(ev.get("UID")), summary=str(ev.get("SUMMARY", "")),
            description=str(ev.get("DESCRIPTION", "")), start=s, end=e,
            cancelled=str(ev.get("STATUS", "")).upper() == "CANCELLED", tz=tz, resolve_room=resolve_room,
        ))
    return out


class ICalSource:
    def __init__(self, name: str, channel: str, url: str, tz: ZoneInfo, resolve_room, lookahead_days: int = 60):
        self.name = name
        self.channel = channel
        self.url = url
        self.tz = tz
        self.resolve_room = resolve_room
        self.lookahead_days = lookahead_days

    def fetch(self, now: datetime) -> FetchResult:
        res = requests.get(self.url, timeout=20)
        res.raise_for_status()
        window = (now, now + timedelta(days=self.lookahead_days))
        bookings = [b for b in parse_ics(res.content, self.channel, self.tz, self.resolve_room)
                    if b.end > window[0] and b.start < window[1]]
        return FetchResult(bookings=bookings, snapshot_window=window)
