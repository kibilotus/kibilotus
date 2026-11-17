"""예약 알림 메일/일정 본문에서 값을 뽑는 공통 함수."""
from __future__ import annotations

import re
from collections.abc import Sequence
from datetime import date, datetime, time, timedelta
from zoneinfo import ZoneInfo

DATE_RE = re.compile(r"(\d{4})\s*[.\-/년]\s*(\d{1,2})\s*[.\-/월]\s*(\d{1,2})\s*일?")
TIME_RE = re.compile(r"(오전|오후)?\s*(\d{1,2})\s*(?::\s*(\d{2})|시(?:\s*(\d{1,2})\s*분)?)")
AMOUNT_RE = re.compile(r"(\d[\d,]*)\s*원")
SEP_RE = re.compile(r"^[\s:：\-|]*")


def strip_html(text: str) -> str:
    text = re.sub(r"(?is)<(script|style).*?</\1>", "", text)
    text = re.sub(r"(?i)<br\s*/?>|</(p|div|tr|li|h\d|td)>", "\n", text)
    text = re.sub(r"(?i)</th>", " : ", text)
    text = re.sub(r"<[^>]+>", "", text)
    text = text.replace("&nbsp;", " ").replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
    return re.sub(r"[ \t]+", " ", text)


def label_value(text: str, labels: Sequence[str]) -> str | None:
    """'라벨 : 값' 형태의 줄에서 값을 찾는다. 라벨 다음 줄에 값이 오는 표 형태도 처리한다."""
    lines = [ln.strip() for ln in text.splitlines()]
    for label in labels:
        for i, line in enumerate(lines):
            if not line.startswith(label):
                continue
            rest = SEP_RE.sub("", line[len(label):]).strip()
            if rest:
                return rest
            nxt = next((ln for ln in lines[i + 1:] if ln), "")
            if nxt:
                return SEP_RE.sub("", nxt).strip()
    return None


def _to_time(m: re.Match | None) -> time | None:
    if not m:
        return None
    ampm, h, mm1, mm2 = m.groups()
    hour, minute = int(h), int(mm1 or mm2 or 0)
    if ampm == "오후" and hour < 12:
        hour += 12
    elif ampm == "오전" and hour == 12:
        hour = 0
    if hour == 24:
        return time(0, minute)  # 24:00 -> 다음날 00:00 (호출부에서 날짜 보정)
    if hour > 23 or minute > 59:
        return None
    return time(hour, minute)


def _to_date(m: re.Match) -> date:
    y, mo, d = map(int, m.groups())
    return date(y, mo, d)


def parse_period(text: str, tz: ZoneInfo) -> tuple[datetime, datetime] | None:
    """'2026.11.20.(목) 오후 2:00 ~ 오후 4:00', '2026-11-20 14:00~2026-11-21 02:00' 같은 이용 기간을 해석한다."""
    left, sep, right = text.partition("~")
    d1 = DATE_RE.search(left)
    if not d1 or not sep:
        return None
    t1m = TIME_RE.search(left[d1.end():])
    d2 = DATE_RE.search(right)
    t2m = TIME_RE.search(right[d2.end():] if d2 else right)
    t1, t2 = _to_time(t1m), _to_time(t2m)
    if t1 is None or t2 is None:
        return None

    day1 = _to_date(d1)
    day2 = _to_date(d2) if d2 else day1
    start = datetime.combine(day1, t1, tzinfo=tz)
    end = datetime.combine(day2, t2, tzinfo=tz)
    if t2m and t2m.group(2) == "24":
        end += timedelta(days=1)
    if end <= start and not d2:
        end += timedelta(days=1)  # 자정을 넘기는 예약
    return (start, end) if end > start else None


def parse_amount(text: str | None) -> int | None:
    if not text:
        return None
    m = AMOUNT_RE.search(text) or re.search(r"(\d[\d,]{2,})", text)
    return int(m.group(1).replace(",", "")) if m else None
