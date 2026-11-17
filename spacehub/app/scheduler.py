"""발송 타이밍 계산 (순수 함수).

개별 작업을 예약해 두지 않고, 주기마다 '지금 보내야 하는가'를 판정한다.
- 예약 변경/취소는 다음 주기에 자동 반영된다.
- 발송 시점이 이미 지난 예약은 이용 종료 전이라면 곧바로 발송된다.
"""
from __future__ import annotations

from datetime import datetime, time, timedelta
from zoneinfo import ZoneInfo

from .models import Booking, Rule


def due_time(rule: Rule, booking: Booking, tz: ZoneInfo) -> datetime | None:
    base = {
        "created": booking.created_at,
        "start": booking.start,
        "end": booking.end,
        "cancelled": booking.cancelled_at,
    }[rule.trigger]
    if base is None:
        return None
    if rule.day_before_at:
        hh, mm = map(int, rule.day_before_at.split(":"))
        local_day = base.astimezone(tz).date() - timedelta(days=1)
        return datetime.combine(local_day, time(hh, mm), tzinfo=tz)
    return base + timedelta(minutes=rule.offset_minutes)


def is_due(rule: Rule, booking: Booking, now: datetime, tz: ZoneInfo) -> bool:
    """발송 시점이 지났고 아직 이용이 끝나지 않았으면 발송 대상. 취소 규칙은 취소된 예약에만, 나머지는 확정 예약에만 적용."""
    if not rule.enabled:
        return False
    if rule.channels and booking.channel not in rule.channels:
        return False
    wanted = "cancelled" if rule.trigger == "cancelled" else "confirmed"
    if booking.status != wanted:
        return False
    due = due_time(rule, booking, tz)
    return due is not None and due <= now < booking.end


def dedupe_key(rule: Rule, booking: Booking) -> str:
    """중복 발송 방지 키. 시작/종료 기준 규칙은 이용 시각이 바뀌면 다시 발송되도록 시작 시각을 포함한다."""
    if rule.trigger in ("created", "cancelled"):
        return f"b{booking.id}:{rule.id}"
    return f"b{booking.id}:{rule.id}:{booking.start.isoformat()}"
