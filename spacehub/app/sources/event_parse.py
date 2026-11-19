"""캘린더 일정(iCal, 구글 캘린더) 1건 -> RawBooking. 두 경로가 같은 규칙으로 예약을 만들어야 한 예약으로 합쳐진다."""
from __future__ import annotations

import re
from datetime import datetime
from zoneinfo import ZoneInfo

from ..models import RawBooking
from ..parser import _find_keyword, _name_from_title, normalize_phone
from .text import strip_html

RESERVATION_NO_RE = re.compile(r"예약\s*번호\s*[:：#]?\s*([A-Za-z0-9\-]+)")


def raw_from_event(*, channel: str, uid: str, summary: str, description: str, start: datetime, end: datetime,
                   cancelled: bool, tz: ZoneInfo, resolve_room) -> RawBooking:
    """예약번호가 제목/설명에 있으면 그 번호를, 없으면 iCal UID 를 ID 로 쓴다.

    스페이스클라우드 캘린더를 직접 읽든 구글 캘린더 구독으로 읽든 UID 가 같으므로 같은 예약으로 합쳐지고,
    예약번호가 있으면 알림 메일로 들어온 예약과도 합쳐진다.
    """
    desc = strip_html(description or "")
    both = f"{summary}\n{desc}"
    m = RESERVATION_NO_RE.search(both)
    name = _find_keyword(desc, ("이름", "예약자명", "예약자", "성함")) or _name_from_title(summary) or _name_from_title(desc)
    return RawBooking(
        channel=channel,
        external_id=m.group(1) if m else uid,
        status="cancelled" if cancelled else "confirmed",
        name=name.split()[0] if name else None,
        phone=normalize_phone(both),
        product=_find_keyword(desc, ("상품", "예약공간")) or summary or None,
        room=resolve_room(summary, desc),
        start=start.astimezone(tz),
        end=end.astimezone(tz),
    )
