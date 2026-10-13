from __future__ import annotations

from dataclasses import dataclass, field
from datetime import datetime


@dataclass(frozen=True)
class CalendarEvent:
    """구글 캘린더 이벤트 중 우리가 쓰는 필드만 추린 값 객체."""

    id: str
    summary: str
    description: str
    start: datetime
    end: datetime
    created: datetime


@dataclass(frozen=True)
class Booking:
    event_id: str
    name: str | None
    phone: str | None  # 숫자만 남긴 정규화 값 (예: 01012345678)
    product: str
    start: datetime
    end: datetime
    created: datetime
    errors: tuple[str, ...] = field(default=())

    @property
    def is_valid(self) -> bool:
        return not self.errors


@dataclass(frozen=True)
class Rule:
    """발송 규칙. trigger 기준 시각에 offset 을 더하거나, day_before_at 으로 '전날 HH:MM' 을 지정한다."""

    id: str
    trigger: str  # created | start | end
    template: str
    offset_minutes: int = 0
    day_before_at: str | None = None  # "18:00"


@dataclass(frozen=True)
class Template:
    id: str
    solapi_template_id: str
    text: str
