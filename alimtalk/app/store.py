"""발송 이력 저장소 (SQLite). 중복 발송 방지와 재시도 횟수 관리를 담당한다."""
from __future__ import annotations

import sqlite3
from datetime import datetime, timezone

SCHEMA = """
CREATE TABLE IF NOT EXISTS sends (
    dedupe_key   TEXT PRIMARY KEY,
    event_id     TEXT NOT NULL,
    rule_id      TEXT NOT NULL,
    name         TEXT,
    phone_masked TEXT,
    status       TEXT NOT NULL,   -- sent | retry | failed | invalid | skipped
    attempts     INTEGER NOT NULL DEFAULT 0,
    last_error   TEXT,
    updated_at   TEXT NOT NULL
)
"""

FINAL_STATUSES = {"sent", "failed", "invalid", "skipped"}


class Store:
    def __init__(self, path: str = ":memory:"):
        self.conn = sqlite3.connect(path)
        self.conn.row_factory = sqlite3.Row
        self.conn.execute(SCHEMA)

    def get(self, key: str) -> sqlite3.Row | None:
        return self.conn.execute("SELECT * FROM sends WHERE dedupe_key = ?", (key,)).fetchone()

    def should_attempt(self, key: str) -> bool:
        row = self.get(key)
        return row is None or row["status"] not in FINAL_STATUSES

    def record(
        self,
        key: str,
        *,
        event_id: str,
        rule_id: str,
        name: str | None,
        phone_masked: str,
        status: str,
        error: str | None = None,
        attempted: bool = True,
    ) -> int:
        """결과를 기록하고 누적 시도 횟수를 반환한다."""
        now = datetime.now(timezone.utc).isoformat()
        inc = 1 if attempted else 0
        with self.conn:
            self.conn.execute(
                """
                INSERT INTO sends (dedupe_key, event_id, rule_id, name, phone_masked, status, attempts, last_error, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT(dedupe_key) DO UPDATE SET
                    status = excluded.status,
                    attempts = sends.attempts + ?,
                    last_error = excluded.last_error,
                    updated_at = excluded.updated_at
                """,
                (key, event_id, rule_id, name, phone_masked, status, inc, error, now, inc),
            )
        return self.get(key)["attempts"]

