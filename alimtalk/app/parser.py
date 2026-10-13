"""캘린더 일정 제목/설명에서 예약자명, 전화번호, 상품명을 추출한다."""
from __future__ import annotations

import re
from collections.abc import Sequence

from .models import Booking, CalendarEvent

PHONE_RE = re.compile(r"(?<!\d)(01[016789])[-.\s]?(\d{3,4})[-.\s]?(\d{4})(?!\d)")
HANGUL_NAME_RE = re.compile(r"^[가-힣]{2,5}$")
TITLE_SPLIT_RE = re.compile(r"[/|,()\[\]]+")


def strip_html(text: str) -> str:
    text = re.sub(r"<br\s*/?>", "\n", text, flags=re.I)
    return re.sub(r"<[^>]+>", "", text)


def normalize_phone(raw: str) -> str | None:
    m = PHONE_RE.search(raw)
    return "".join(m.groups()) if m else None


def mask_phone(phone: str | None) -> str:
    if not phone:
        return ""
    return f"{phone[:3]}-****-{phone[-4:]}"


def _find_keyword(text: str, keywords: Sequence[str]) -> str | None:
    for kw in keywords:
        m = re.search(rf"{re.escape(kw)}\s*[:：]\s*(.+)", text)
        if m:
            return m.group(1).strip()
    return None


def _name_from_title(title: str) -> str | None:
    title = PHONE_RE.sub(" ", title)
    tokens = [t.strip() for t in TITLE_SPLIT_RE.split(title) if t.strip()]
    for token in tokens:
        for word in token.split():
            if HANGUL_NAME_RE.match(word):
                return word
    return None


def parse_event(
    event: CalendarEvent,
    *,
    default_product: str,
    name_keywords: Sequence[str] = ("이름", "예약자", "성함"),
    phone_keywords: Sequence[str] = ("연락처", "전화번호", "전화", "휴대폰"),
    product_keywords: Sequence[str] = ("상품", "패키지"),
) -> Booking:
    """설명의 '키워드: 값' 형식을 우선 사용하고, 없으면 제목에서 추출한다."""
    desc = strip_html(event.description)

    name = _find_keyword(desc, name_keywords) or _name_from_title(event.summary)
    if name:
        name = name.split()[0]

    phone_src = _find_keyword(desc, phone_keywords)
    phone = normalize_phone(phone_src or "") or normalize_phone(event.summary) or normalize_phone(desc)

    product = _find_keyword(desc, product_keywords) or default_product

    errors = []
    if not name:
        errors.append("예약자명 없음")
    if not phone:
        errors.append("휴대폰 번호 없음/형식 오류")

    return Booking(
        event_id=event.id,
        name=name,
        phone=phone,
        product=product,
        start=event.start,
        end=event.end,
        created=event.created,
        errors=tuple(errors),
    )
