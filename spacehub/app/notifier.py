"""알림톡 발송 1회분: 통합 예약 x 발송 규칙을 돌며 지금 보낼 메시지를 보낸다."""
from __future__ import annotations

import logging
from datetime import datetime

from .config import Config
from .db import Store
from .parser import mask_phone
from .scheduler import dedupe_key, in_quiet_hours, is_due
from .sender import Sender, SendError
from .template import TemplateError, build_variables, render

log = logging.getLogger(__name__)


def notify(cfg: Config, store: Store, sender: Sender, now: datetime, seed: bool = False) -> None:
    """seed=True 이면 실제 발송 없이 현재 발송 대상을 'skipped' 로 기록한다 (최초 도입 시 기존 예약에 일괄 발송 방지)."""
    if not seed and in_quiet_hours(now, cfg.quiet_hours, cfg.timezone):
        return  # 발송 금지 시간대: 다음 주기에 다시 판정한다 (이용 종료 전이면 금지 시간이 끝난 뒤 발송)
    rules = [r for r in store.rules() if r.enabled]
    for booking in store.bookings_ending_after(now):
        for rule in rules:
            if not is_due(rule, booking, now, cfg.timezone):
                continue
            key = dedupe_key(rule, booking)
            if not store.should_attempt(key):
                continue
            meta = dict(booking_id=booking.id, rule_id=rule.id)

            if seed:
                store.record_send(key, status="skipped", attempted=False, **meta)
                continue
            if booking.errors:
                reason = ", ".join(booking.errors)
                log.warning("발송 불가 booking=%s: %s", booking.id, reason)
                store.record_send(key, status="invalid", error=reason, attempted=False, **meta)
                continue
            if not rule.solapi_template_id and not cfg.dry_run:
                store.record_send(key, status="invalid", error="알림톡 템플릿 ID 없음", attempted=False, **meta)
                continue

            try:
                text, variables = render(rule.text, build_variables(booking, cfg.studio_name, cfg.timezone))
                sender.send(booking.phone, rule.solapi_template_id, variables, text)
            except TemplateError as e:
                store.record_send(key, status="invalid", error=str(e), attempted=False, **meta)
            except SendError as e:
                prev = store.get_send(key)
                attempts = (prev["attempts"] if prev else 0) + 1
                status = "failed" if (not e.retryable or attempts >= cfg.max_attempts) else "retry"
                store.record_send(key, status=status, error=str(e), **meta)
                log.error("발송 실패(%s, %d/%d) %s: %s", status, attempts, cfg.max_attempts, key, e)
            else:
                store.record_send(key, status="sent", **meta)
                log.info("발송 완료 %s to=%s", key, mask_phone(booking.phone))
