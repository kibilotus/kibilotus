from __future__ import annotations

from dataclasses import dataclass, field
from datetime import datetime

CHANNELS = {"naver": "네이버 예약", "spacecloud": "스페이스클라우드", "direct": "직접 예약"}


@dataclass(frozen=True)
class CalendarEvent:
    """구글 캘린더 이벤트 중 우리가 쓰는 필드만 추린 값 객체."""

    id: str
    summary: str
    description: str
    start: datetime
    end: datetime
    created: datetime
    ical_uid: str = ""  # 구독 캘린더(iCal URL)에서 온 일정이면 원래 iCal 의 UID 가 그대로 들어 있다


@dataclass
class RawBooking:
    """채널(메일, iCal, 구글 캘린더)에서 읽어 온 예약 1건. 모르는 값은 None 으로 둔다."""

    channel: str
    external_id: str
    status: str = "confirmed"  # confirmed | cancelled
    name: str | None = None
    phone: str | None = None
    product: str | None = None
    room: str | None = None
    start: datetime | None = None
    end: datetime | None = None
    amount: int | None = None


@dataclass(frozen=True)
class Booking:
    """DB 에 저장된 통합 예약."""

    id: int
    channel: str
    external_id: str
    status: str
    name: str | None
    phone: str | None
    product: str | None
    room: str
    start: datetime
    end: datetime
    amount: int | None
    created_at: datetime
    cancelled_at: datetime | None

    @property
    def errors(self) -> tuple[str, ...]:
        errs = []
        if not self.name:
            errs.append("예약자명 없음")
        if not self.phone:
            errs.append("휴대폰 번호 없음/형식 오류")
        return tuple(errs)


@dataclass(frozen=True)
class Rule:
    """알림톡 발송 규칙과 메시지 템플릿.

    trigger: created(예약 등록) | start(이용 시작) | end(이용 종료) | cancelled(예약 취소)
    offset_minutes 를 더하거나, day_before_at("18:00") 으로 기준일 전날 지정 시각에 발송한다.
    channels 가 비어 있으면 모든 채널에 적용한다.
    """

    id: str
    name: str
    trigger: str
    text: str
    title: str = ""
    solapi_template_id: str = ""
    offset_minutes: int = 0
    day_before_at: str | None = None
    channels: tuple[str, ...] = field(default=())
    enabled: bool = True
