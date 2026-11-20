"""채널 동기화: 각 소스에서 예약을 읽어 통합 예약 DB 에 반영한다."""
from __future__ import annotations

import logging
from datetime import datetime

from .config import Config
from .db import Store
from .sources import Source
from .sources.email_source import EmailSource
from .sources.ical_source import ICalSource

log = logging.getLogger(__name__)


def build_sources(cfg: Config, store: Store) -> list[Source]:
    sources: list[Source] = []
    em = cfg.source("email")
    if em.get("enabled"):
        sources.append(EmailSource(em, cfg.imap_password, store, cfg.timezone, cfg.resolve_room))
    ic = cfg.source("spacecloud_ical")
    if ic.get("enabled") and ic.get("url"):
        sources.append(ICalSource("spacecloud_ical", "spacecloud", ic["url"], cfg.timezone, cfg.resolve_room,
                                  int(ic.get("lookahead_days", 60))))
    for gc in cfg.google_calendars():
        if not gc.get("enabled"):
            continue
        from .sources.gcal_source import GoogleCalendarSource  # 구글 라이브러리는 쓸 때만 불러온다

        try:
            sources.append(GoogleCalendarSource(gc, cfg.timezone, cfg.resolve_room))
        except Exception as e:  # 토큰 파일이 없는 등 설정 문제: 나머지 채널은 계속 돈다
            log.error("구글 캘린더 %s 를 열지 못했어요: %s", gc.get("name"), e)
            if isinstance(e, FileNotFoundError):
                msg = (f"설정 확인 필요: 구글 로그인 토큰 파일({gc.get('token_file', 'google-token.json')})이 없어요. "
                       "python -m app.main google-login 으로 만들어 서버에 올려주세요.")
            else:
                msg = f"설정 확인 필요: {e}"
            store.set_sync(gc.get("name", "google_calendar"), datetime.now(cfg.timezone), error=msg[:300])
    return sources


def run_sync(store: Store, sources: list[Source], now: datetime) -> None:
    for src in sources:
        try:
            result = src.fetch(now)
            snapshot = src.name if result.snapshot_window else None
            seen, unsaved = set(), []
            for raw in result.bookings:
                if store.upsert_booking(raw, now, snapshot_src=snapshot) is not None:
                    seen.add((raw.channel, raw.external_id))
                elif raw.status == "confirmed":
                    unsaved.append(raw.external_id)  # 처음 보는 예약인데 이용 시간을 모름
            cancelled = store.cancel_missing(src.name, seen, result.snapshot_window, now) if snapshot else 0
            problems = [f"{len(result.errors)}건 해석 실패"] if result.errors else []
            if unsaved:
                problems.append(f"이용 시간을 몰라 새 예약으로 만들지 못했어요 (예약번호 {', '.join(unsaved[:5])})")
            error = " / ".join(problems) or None
            store.set_sync(src.name, now, count=len(result.bookings), error=error)
            log.info("동기화 %s: %d건 반영, %d건 취소 처리", src.name, len(seen), cancelled)
        except Exception as e:  # 한 채널이 실패해도 나머지 채널과 알림은 계속 돈다
            log.exception("동기화 실패: %s", src.name)
            store.set_sync(src.name, now, error=str(e)[:300])
