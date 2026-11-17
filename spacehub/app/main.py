"""실행 진입점.

    python -m app.main serve           # 관리 화면 + 백그라운드 동기화/발송 (상시 실행)
    python -m app.main once            # 동기화와 발송을 1회만 실행 (cron 용)
    python -m app.main seed            # 최초 도입 시: 현재 예약을 가져오고, 지금 발송 대상은 보내지 않고 '처리됨'으로 기록
"""
from __future__ import annotations

import argparse
import logging
import secrets

from .config import load_config
from .db import Store
from .engine import Engine

log = logging.getLogger("spacehub")


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("command", choices=["serve", "once", "seed"])
    ap.add_argument("--config", default="config.yaml")
    ap.add_argument("--host", default="127.0.0.1")
    ap.add_argument("--port", type=int, default=8000)
    args = ap.parse_args()

    logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(name)s: %(message)s")
    cfg = load_config(args.config)
    engine = Engine(cfg, Store(cfg.db_path, cfg.timezone))
    if cfg.dry_run:
        log.info("dry_run 모드: 알림톡을 실제로 보내지 않고 로그만 남깁니다")

    if args.command in ("once", "seed"):
        engine.run_once(seed=args.command == "seed")
        return

    import uvicorn

    from .web import create_app

    if not cfg.admin_password:
        cfg.admin_password = secrets.token_urlsafe(12)
        log.warning("ADMIN_PASSWORD 가 없어 임시 비밀번호를 만들었습니다. 아이디 admin / 비밀번호 %s", cfg.admin_password)
    engine.start_background()
    uvicorn.run(create_app(engine), host=args.host, port=args.port)


if __name__ == "__main__":
    main()
