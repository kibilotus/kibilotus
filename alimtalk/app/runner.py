"""폴링 1회분(tick) 처리: 캘린더 조회 -> 파싱 -> 발송 대상 판정 -> 발송 -> 이력 기록."""
from __future__ import annotations

import logging
from datetime import datetime, timedelta
from typing import Protocol

from .config import Config
from .models import CalendarEvent
from .parser import mask_phone, parse_event
from .scheduler import dedupe_key, is_due
from .sender import Sender, SendError
from .store import Store
from .template import TemplateError, build_variables, render

log = logging.getLogger(__name__)


class CalendarSource(Protocol):
    def fetch(self, time_min: datetime, time_max: datetime) -> list[CalendarEvent]: ...


def tick(cfg: Config, calendar: CalendarSource, sender: Sender, store: Store, now: datetime, seed: bool = False) -> None:
    """seed=True 이면 실제 발송 없이 현재 발송 대상을 'skipped' 로 기록한다 (최초 도입 시 과거 예약 일괄 발송 방지)."""
    events = calendar.fetch(now, now + timedelta(days=cfg.lookahead_days))
    for event in events:
        booking = parse_event(event, default_product=cfg.default_product, **cfg.parser)
        for rule in cfg.rules:
            if not is_due(rule, booking, now, cfg.timezone):
                continue
            key = dedupe_key(rule, booking)
            if not store.should_attempt(key):
                continue

            meta = dict(event_id=booking.event_id, rule_id=rule.id, name=booking.name, phone_masked=mask_phone(booking.phone))

            if seed:
                store.record(key, status="skipped", attempted=False, **meta)
                continue
            if not booking.is_valid:
                reason = ", ".join(booking.errors)
                log.warning("발송 불가 event=%s title=%r: %s", event.id, event.summary, reason)
                store.record(key, status="invalid", error=reason, attempted=False, **meta)
                continue

            template = cfg.templates[rule.template]
            try:
                text, variables = render(template, build_variables(booking, cfg.studio_name, cfg.timezone))
                sender.send(booking.phone, template.solapi_template_id, variables, text)
            except TemplateError as e:
                log.error("템플릿 오류 key=%s: %s", key, e)
                store.record(key, status="invalid", error=str(e), attempted=False, **meta)
            except SendError as e:
                prev = store.get(key)
                attempts = (prev["attempts"] if prev else 0) + 1
                final = not e.retryable or attempts >= cfg.max_attempts
                status = "failed" if final else "retry"
                store.record(key, status=status, error=str(e), **meta)
                log.error("발송 실패(%s, %d/%d) key=%s: %s", status, attempts, cfg.max_attempts, key, e)
            else:
                store.record(key, status="sent", **meta)
                log.info("발송 완료 key=%s to=%s", key, meta["phone_masked"])
