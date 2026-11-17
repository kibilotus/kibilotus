from __future__ import annotations

import re
from zoneinfo import ZoneInfo

from .models import CHANNELS, Booking

VAR_RE = re.compile(r"#\{([^}]+)\}")
WEEKDAYS = "월화수목금토일"
VARIABLES = ("이름", "상품", "공간", "이용일시", "채널", "금액", "스튜디오")


class TemplateError(ValueError):
    pass


def format_period(booking: Booking, tz: ZoneInfo) -> str:
    s, e = booking.start.astimezone(tz), booking.end.astimezone(tz)
    day = f"{s:%Y-%m-%d}({WEEKDAYS[s.weekday()]})"
    if s.date() == e.date():
        return f"{day} {s:%H:%M}~{e:%H:%M}"
    return f"{day} {s:%H:%M} ~ {e:%Y-%m-%d} {e:%H:%M}"


def build_variables(booking: Booking, studio_name: str, tz: ZoneInfo) -> dict[str, str]:
    return {
        "이름": booking.name or "",
        "상품": booking.product or booking.room,
        "공간": booking.room,
        "이용일시": format_period(booking, tz),
        "채널": CHANNELS.get(booking.channel, booking.channel),
        "금액": f"{booking.amount:,}원" if booking.amount else "",
        "스튜디오": studio_name,
    }


def render(text: str, variables: dict[str, str]) -> tuple[str, dict[str, str]]:
    """본문 렌더링 + 솔라피 variables 형식({"#{이름}": "홍길동"}) 반환."""
    used = VAR_RE.findall(text)
    missing = [v for v in dict.fromkeys(used) if not variables.get(v)]
    if missing:
        raise TemplateError(f"템플릿 변수 값 없음: {missing}")
    rendered = VAR_RE.sub(lambda m: variables[m.group(1)], text)
    return rendered, {f"#{{{v}}}": variables[v] for v in dict.fromkeys(used)}
