"""동기화 + 알림 발송을 묶어 주기적으로 실행한다."""
from __future__ import annotations

import logging
import threading
from datetime import datetime

from .config import Config
from .db import Store
from .notifier import notify
from .sender import DryRunSender, Sender, SolapiSender
from .sources import Source
from .sync import build_sources, run_sync

log = logging.getLogger(__name__)


def build_sender(cfg: Config) -> Sender:
    if cfg.dry_run:
        return DryRunSender()
    return SolapiSender(cfg.solapi_api_key, cfg.solapi_api_secret, cfg.solapi_pf_id, cfg.solapi_sender)


class Engine:
    def __init__(self, cfg: Config, store: Store, sources: list[Source] | None = None, sender: Sender | None = None):
        self.cfg = cfg
        self.store = store
        self.sources = build_sources(cfg, store) if sources is None else sources
        self.sender = sender or build_sender(cfg)
        self._run_lock = threading.Lock()
        self._stop = threading.Event()
        store.seed_rules(cfg.rules)

    def now(self) -> datetime:
        return datetime.now(self.cfg.timezone)

    def run_once(self, seed: bool = False) -> None:
        with self._run_lock:  # 주기 실행과 '지금 동기화' 버튼이 겹치지 않게
            now = self.now()
            run_sync(self.store, self.sources, now)
            notify(self.cfg, self.store, self.sender, now, seed=seed)

    def loop(self) -> None:
        while not self._stop.is_set():
            try:
                self.run_once()
            except Exception:
                log.exception("주기 실행 중 오류 (다음 주기에 재시도)")
            self._stop.wait(self.cfg.poll_interval_seconds)

    def start_background(self) -> threading.Thread:
        t = threading.Thread(target=self.loop, name="spacehub-worker", daemon=True)
        t.start()
        return t

    def stop(self) -> None:
        self._stop.set()
