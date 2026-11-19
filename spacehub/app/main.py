"""실행 진입점.

    python -m app.main serve           # 관리 화면 + 백그라운드 동기화/발송 (상시 실행)
    python -m app.main once            # 동기화와 발송을 1회만 실행 (cron 용)
    python -m app.main seed            # 최초 도입 시: 현재 예약을 가져오고, 지금 발송 대상은 보내지 않고 '처리됨'으로 기록
    python -m app.main google-login    # 구글 계정으로 로그인해 토큰 파일을 만들고 캘린더 ID 목록을 보여줌 (브라우저가 있는 PC에서)
    python -m app.main google-calendars  # 토큰으로 읽을 수 있는 캘린더 ID 목록
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
    ap.add_argument("command", choices=["serve", "once", "seed", "google-login", "google-calendars"])
    ap.add_argument("--config", default="config.yaml")
    ap.add_argument("--client-secret", default="client_secret.json", help="구글 OAuth 클라이언트 파일 (데스크톱 앱)")
    ap.add_argument("--token", default="google-token.json", help="로그인 후 저장할/읽을 토큰 파일")
    ap.add_argument("--host", default="127.0.0.1")
    ap.add_argument("--port", type=int, default=8000)
    args = ap.parse_args()

    logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(name)s: %(message)s")
    if args.command in ("google-login", "google-calendars"):
        google_setup(args)
        return
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


def google_setup(args) -> None:
    from pathlib import Path

    from .calendar_client import SCOPES, list_calendars, load_credentials

    if args.command == "google-login":
        from google_auth_oauthlib.flow import InstalledAppFlow

        flow = InstalledAppFlow.from_client_secrets_file(args.client_secret, SCOPES)
        creds = flow.run_local_server(port=0, prompt="consent")
        Path(args.token).write_text(creds.to_json(), encoding="utf-8")
        print(f"로그인 완료: {args.token} 에 저장했어요. 이 파일을 서버의 spacehub 폴더에 올려주세요.")
    creds = load_credentials(auth="oauth", token_file=args.token)
    print("\n읽을 수 있는 캘린더 (config.yaml 의 calendar_id 에 넣으세요):")
    for c in list_calendars(creds):
        mark = "  ← URL로 추가한 구독 캘린더" if c["id"].endswith("@import.calendar.google.com") else ""
        print(f"- {c.get('summary', '')}\n    {c['id']}{mark}")


if __name__ == "__main__":
    main()
