from __future__ import annotations

from dataclasses import dataclass, field
from datetime import datetime
from typing import Protocol

from ..models import RawBooking


@dataclass
class FetchResult:
    bookings: list[RawBooking]
    # 소스가 기간 내 '전체 목록'을 주는 경우(iCal, 캘린더) 그 기간. 목록에서 사라진 예약을 취소로 처리할 때 쓴다.
    snapshot_window: tuple[datetime, datetime] | None = None
    errors: list[str] = field(default_factory=list)


class Source(Protocol):
    name: str

    def fetch(self, now: datetime) -> FetchResult: ...
