"""실행 진입점.

    python -m app.main                # 상시 실행 (poll_interval_seconds 마다 폴링)
    python -m app.main --once         # 1회만 실행 (cron 으로 돌릴 때)
    python -m app.main --seed         # 최초 도입 시: 현재 발송 대상을 발송 없이 '처리됨'으로 기록
"""
from __future__ import annotations

import argparse
import logging
import time
from datetime import datetime

from .calendar_client import GoogleCalendarClient
from .config import load_config
from .runner import tick
from .sender import DryRunSender, SolapiSender
from .store import Store

log = logging.getLogger("alimtalk")


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--config", default="config.yaml")
    ap.add_argument("--once", action="store_true")
    ap.add_argument("--seed", action="store_true")
    args = ap.parse_args()

    logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(name)s: %(message)s")
    cfg = load_config(args.config)

    calendar = GoogleCalendarClient(cfg.service_account_file, cfg.calendar_id)
    if cfg.dry_run:
        sender = DryRunSender()
        log.info("dry_run 모드: 실제 발송하지 않습니다")
    else:
        sender = SolapiSender(cfg.solapi_api_key, cfg.solapi_api_secret, cfg.solapi_pf_id, cfg.solapi_sender)
    store = Store(cfg.db_path)

    while True:
        try:
            tick(cfg, calendar, sender, store, datetime.now(cfg.timezone), seed=args.seed)
        except Exception:
            log.exception("폴링 중 오류 (다음 주기에 재시도)")
        if args.once or args.seed:
            return
        time.sleep(cfg.poll_interval_seconds)


if __name__ == "__main__":
    main()
