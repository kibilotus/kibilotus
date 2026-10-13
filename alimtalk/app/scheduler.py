"""발송 타이밍 계산 (순수 함수).

개별 작업을 예약해 두는 대신, 폴링할 때마다 '지금 보내야 하는가'를 판정한다.
- 일정 변경/삭제 시 별도 취소 로직이 필요 없다 (다음 폴링에서 자동 반영).
- 발송 시점을 앞당겨 이미 지난 예약은 이용 종료 전이라면 곧바로 발송된다 (참조 서비스와 동일).
"""
from __future__ import annotations

from datetime import datetime, time, timedelta
from zoneinfo import ZoneInfo

from .models import Booking, Rule


def due_time(rule: Rule, booking: Booking, tz: ZoneInfo) -> datetime:
    base = {"created": booking.created, "start": booking.start, "end": booking.end}[rule.trigger]
    if rule.day_before_at:
        hh, mm = map(int, rule.day_before_at.split(":"))
        local_day = base.astimezone(tz).date() - timedelta(days=1)
        return datetime.combine(local_day, time(hh, mm), tzinfo=tz)
    return base + timedelta(minutes=rule.offset_minutes)


def is_due(rule: Rule, booking: Booking, now: datetime, tz: ZoneInfo) -> bool:
    """발송 시점이 지났고, 아직 이용이 끝나지 않았으면 발송 대상."""
    return due_time(rule, booking, tz) <= now < booking.end


def dedupe_key(rule: Rule, booking: Booking) -> str:
    """중복 발송 방지 키.

    시작/종료 기준 규칙은 이용 시각이 바뀌면 다시 발송되도록 시작 시각을 키에 포함한다.
    '예약 확정' 규칙은 일정 시간 변경 시 재발송하지 않는다.
    """
    if rule.trigger == "created":
        return f"{booking.event_id}:{rule.id}"
    return f"{booking.event_id}:{rule.id}:{booking.start.isoformat()}"
