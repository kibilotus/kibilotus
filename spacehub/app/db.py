"""SQLite 저장소. 통합 예약, 발송 이력, 알림 규칙, 채널 동기화 상태를 보관한다.

웹 요청(스레드풀)과 백그라운드 작업이 연결 하나를 같이 쓰므로 모든 접근을 RLock 으로 직렬화한다.
시각은 모두 설정된 타임존 기준 ISO 문자열로 저장해 문자열 비교로 기간 조회가 되게 한다.
"""
from __future__ import annotations

import functools
import json
import sqlite3
import threading
from datetime import datetime, timezone
from zoneinfo import ZoneInfo

from .models import Booking, RawBooking, Rule

SCHEMA = """
CREATE TABLE IF NOT EXISTS bookings (
    id            INTEGER PRIMARY KEY,
    channel       TEXT NOT NULL,
    external_id   TEXT NOT NULL,
    status        TEXT NOT NULL,          -- confirmed | cancelled
    name          TEXT,
    phone         TEXT,
    product       TEXT,
    room          TEXT NOT NULL,
    start         TEXT NOT NULL,
    "end"         TEXT NOT NULL,
    amount        INTEGER,
    snapshot_src  TEXT,                   -- 전체 목록을 주는 소스(iCal 등)에서 본 예약이면 그 소스 이름
    created_at    TEXT NOT NULL,
    updated_at    TEXT NOT NULL,
    cancelled_at  TEXT,
    UNIQUE (channel, external_id)
);
CREATE INDEX IF NOT EXISTS bookings_start ON bookings (start);

CREATE TABLE IF NOT EXISTS sends (
    dedupe_key   TEXT PRIMARY KEY,
    booking_id   INTEGER NOT NULL,
    rule_id      TEXT NOT NULL,
    status       TEXT NOT NULL,           -- sent | retry | waiting | failed | invalid | skipped
    attempts     INTEGER NOT NULL DEFAULT 0,
    last_error   TEXT,
    updated_at   TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS rules (
    id    TEXT PRIMARY KEY,
    pos   INTEGER NOT NULL,
    data  TEXT NOT NULL                   -- Rule 필드 JSON
);

CREATE TABLE IF NOT EXISTS sync_status (
    source      TEXT PRIMARY KEY,
    last_run    TEXT,
    last_ok     TEXT,
    last_error  TEXT,
    count       INTEGER DEFAULT 0
);

CREATE TABLE IF NOT EXISTS mails (
    key         TEXT PRIMARY KEY,         -- 계정:UID
    channel     TEXT,
    subject     TEXT,
    ok          INTEGER NOT NULL,
    error       TEXT,
    received_at TEXT
);
"""

FINAL_SEND_STATUSES = {"sent", "failed", "invalid", "skipped"}
BOOKING_FIELDS = ("name", "phone", "product", "room", "start", "end", "amount")


def locked(fn):
    @functools.wraps(fn)
    def wrapper(self, *args, **kwargs):
        with self.lock:
            return fn(self, *args, **kwargs)

    return wrapper


class Store:
    def __init__(self, path: str = ":memory:", tz: ZoneInfo = ZoneInfo("Asia/Seoul")):
        self.tz = tz
        self.lock = threading.RLock()
        self.conn = sqlite3.connect(path, check_same_thread=False)
        self.conn.row_factory = sqlite3.Row
        self.conn.executescript(SCHEMA)

    # ---------- 공통 ----------
    def iso(self, dt: datetime) -> str:
        return dt.astimezone(self.tz).isoformat(timespec="seconds")

    def _dt(self, s: str | None) -> datetime | None:
        return datetime.fromisoformat(s) if s else None

    def _booking(self, row: sqlite3.Row) -> Booking:
        return Booking(
            id=row["id"], channel=row["channel"], external_id=row["external_id"], status=row["status"],
            name=row["name"], phone=row["phone"], product=row["product"], room=row["room"],
            start=self._dt(row["start"]), end=self._dt(row["end"]), amount=row["amount"],
            created_at=self._dt(row["created_at"]), cancelled_at=self._dt(row["cancelled_at"]),
        )

    # ---------- 예약 ----------
    @locked
    def upsert_booking(self, raw: RawBooking, now: datetime, snapshot_src: str | None = None) -> int | None:
        """같은 (채널, 외부ID) 예약이 있으면 비어 있지 않은 값만 덮어쓴다. 새 예약인데 이용 시각이 없으면 저장하지 않는다."""
        now_s = self.iso(now)
        row = self.conn.execute(
            "SELECT * FROM bookings WHERE channel = ? AND external_id = ?", (raw.channel, raw.external_id)
        ).fetchone()
        values = {
            "name": raw.name, "phone": raw.phone, "product": raw.product, "room": raw.room,
            "start": self.iso(raw.start) if raw.start else None,
            "end": self.iso(raw.end) if raw.end else None,
            "amount": raw.amount,
        }

        if row is None:
            if not (raw.start and raw.end and raw.room):
                return None
            cancelled_at = now_s if raw.status == "cancelled" else None
            with self.conn:
                cur = self.conn.execute(
                    """INSERT INTO bookings (channel, external_id, status, name, phone, product, room, start, "end",
                                             amount, snapshot_src, created_at, updated_at, cancelled_at)
                       VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""",
                    (raw.channel, raw.external_id, raw.status, *[values[f] for f in BOOKING_FIELDS],
                     snapshot_src, now_s, now_s, cancelled_at),
                )
            return cur.lastrowid

        updates = {f: v for f, v in values.items() if v not in (None, "")}
        if "*" in (updates.get("name") or "") and row["name"] and "*" not in row["name"]:
            del updates["name"]  # 네이버 메일의 가려진 이름이 수기로 보낸 전체 이름을 덮어쓰지 않게
        if snapshot_src:
            updates["snapshot_src"] = snapshot_src
        if raw.status != row["status"]:
            updates["status"] = raw.status
            updates["cancelled_at"] = now_s if raw.status == "cancelled" else None
        changed = {k: v for k, v in updates.items() if row[k] != v}
        if changed:
            changed["updated_at"] = now_s
            cols = ", ".join(f'"{k}" = ?' for k in changed)
            with self.conn:
                self.conn.execute(f"UPDATE bookings SET {cols} WHERE id = ?", (*changed.values(), row["id"]))
        return row["id"]

    @locked
    def cancel_missing(self, snapshot_src: str, seen: set[tuple[str, str]], window: tuple[datetime, datetime], now: datetime) -> int:
        """전체 목록 소스에서 이전에 보였는데 이번에 사라진 (기간 내) 예약을 취소 처리한다."""
        rows = self.conn.execute(
            """SELECT id, channel, external_id FROM bookings
               WHERE snapshot_src = ? AND status = 'confirmed' AND start >= ? AND start < ?""",
            (snapshot_src, self.iso(window[0]), self.iso(window[1])),
        ).fetchall()
        gone = [r["id"] for r in rows if (r["channel"], r["external_id"]) not in seen]
        now_s = self.iso(now)
        with self.conn:
            for bid in gone:
                self.conn.execute(
                    "UPDATE bookings SET status = 'cancelled', cancelled_at = ?, updated_at = ? WHERE id = ?",
                    (now_s, now_s, bid),
                )
        return len(gone)

    @locked
    def create_direct(self, *, name: str, phone: str | None, room: str, product: str | None,
                      start: datetime, end: datetime, amount: int | None, now: datetime) -> int:
        now_s = self.iso(now)
        with self.conn:
            cur = self.conn.execute(
                """INSERT INTO bookings (channel, external_id, status, name, phone, product, room, start, "end",
                                         amount, created_at, updated_at)
                   VALUES ('direct', '', 'confirmed', ?, ?, ?, ?, ?, ?, ?, ?, ?)""",
                (name, phone, product, room, self.iso(start), self.iso(end), amount, now_s, now_s),
            )
            self.conn.execute("UPDATE bookings SET external_id = ? WHERE id = ?", (f"direct-{cur.lastrowid}", cur.lastrowid))
        return cur.lastrowid

    @locked
    def cancel_booking(self, booking_id: int, now: datetime) -> bool:
        now_s = self.iso(now)
        with self.conn:
            cur = self.conn.execute(
                "UPDATE bookings SET status = 'cancelled', cancelled_at = ?, updated_at = ? WHERE id = ? AND status != 'cancelled'",
                (now_s, now_s, booking_id),
            )
        return cur.rowcount > 0

    @locked
    def get_booking(self, booking_id: int) -> Booking | None:
        row = self.conn.execute("SELECT * FROM bookings WHERE id = ?", (booking_id,)).fetchone()
        return self._booking(row) if row else None

    @locked
    def list_bookings(self, start_from: datetime, start_to: datetime, include_cancelled: bool = True) -> list[Booking]:
        sql = "SELECT * FROM bookings WHERE start >= ? AND start < ?"
        if not include_cancelled:
            sql += " AND status = 'confirmed'"
        rows = self.conn.execute(sql + " ORDER BY start", (self.iso(start_from), self.iso(start_to))).fetchall()
        return [self._booking(r) for r in rows]

    @locked
    def bookings_ending_after(self, t: datetime) -> list[Booking]:
        rows = self.conn.execute('SELECT * FROM bookings WHERE "end" > ? ORDER BY start', (self.iso(t),)).fetchall()
        return [self._booking(r) for r in rows]

    @locked
    def conflicts(self, after: datetime) -> list[tuple[Booking, Booking]]:
        """같은 공간에서 시간이 겹치는 확정 예약 쌍."""
        rows = self.conn.execute(
            """SELECT a.id AS a_id, b.id AS b_id FROM bookings a JOIN bookings b
               ON a.room = b.room AND a.id < b.id AND a.start < b."end" AND b.start < a."end"
               WHERE a.status = 'confirmed' AND b.status = 'confirmed' AND a."end" > ?
               ORDER BY a.start""",
            (self.iso(after),),
        ).fetchall()
        return [(self.get_booking(r["a_id"]), self.get_booking(r["b_id"])) for r in rows]

    # ---------- 발송 이력 ----------
    @locked
    def get_send(self, key: str) -> sqlite3.Row | None:
        return self.conn.execute("SELECT * FROM sends WHERE dedupe_key = ?", (key,)).fetchone()

    def should_attempt(self, key: str) -> bool:
        row = self.get_send(key)
        return row is None or row["status"] not in FINAL_SEND_STATUSES

    @locked
    def record_send(self, key: str, *, booking_id: int, rule_id: str, status: str,
                    error: str | None = None, attempted: bool = True) -> int:
        """결과를 기록하고 누적 시도 횟수를 반환한다."""
        now = self.iso(datetime.now(timezone.utc))
        inc = 1 if attempted else 0
        with self.conn:
            self.conn.execute(
                """INSERT INTO sends (dedupe_key, booking_id, rule_id, status, attempts, last_error, updated_at)
                   VALUES (?, ?, ?, ?, ?, ?, ?)
                   ON CONFLICT(dedupe_key) DO UPDATE SET
                       status = excluded.status, attempts = sends.attempts + ?,
                       last_error = excluded.last_error, updated_at = excluded.updated_at""",
                (key, booking_id, rule_id, status, inc, error, now, inc),
            )
        return self.get_send(key)["attempts"]

    @locked
    def list_sends(self, limit: int = 100) -> list[dict]:
        rows = self.conn.execute(
            """SELECT s.*, b.name, b.phone, b.channel, b.start, b.room FROM sends s
               LEFT JOIN bookings b ON b.id = s.booking_id ORDER BY s.updated_at DESC LIMIT ?""",
            (limit,),
        ).fetchall()
        return [dict(r) for r in rows]

    @locked
    def sends_for(self, booking_ids: list[int]) -> dict[int, list[dict]]:
        if not booking_ids:
            return {}
        q = ",".join("?" * len(booking_ids))
        rows = self.conn.execute(f"SELECT booking_id, rule_id, status FROM sends WHERE booking_id IN ({q})", booking_ids).fetchall()
        out: dict[int, list[dict]] = {}
        for r in rows:
            out.setdefault(r["booking_id"], []).append({"rule_id": r["rule_id"], "status": r["status"]})
        return out

    # ---------- 알림 규칙 ----------
    @locked
    def seed_rules(self, rules: list[Rule]) -> None:
        """규칙 테이블이 비어 있을 때만 config.yaml 의 규칙을 넣는다. 이후에는 관리 화면에서 고친 값이 우선."""
        if self.conn.execute("SELECT COUNT(*) FROM rules").fetchone()[0]:
            return
        with self.conn:
            for pos, r in enumerate(rules):
                self.conn.execute("INSERT INTO rules (id, pos, data) VALUES (?, ?, ?)", (r.id, pos, json.dumps(_rule_dict(r))))

    @locked
    def rules(self) -> list[Rule]:
        rows = self.conn.execute("SELECT data FROM rules ORDER BY pos").fetchall()
        return [_rule_from(json.loads(r["data"])) for r in rows]

    @locked
    def save_rule(self, rule: Rule) -> None:
        with self.conn:
            cur = self.conn.execute("UPDATE rules SET data = ? WHERE id = ?", (json.dumps(_rule_dict(rule)), rule.id))
            if cur.rowcount == 0:
                pos = self.conn.execute("SELECT COALESCE(MAX(pos), -1) + 1 FROM rules").fetchone()[0]
                self.conn.execute("INSERT INTO rules (id, pos, data) VALUES (?, ?, ?)", (rule.id, pos, json.dumps(_rule_dict(rule))))

    # ---------- 동기화 상태 / 메일 ----------
    @locked
    def set_sync(self, source: str, now: datetime, *, count: int = 0, error: str | None = None) -> None:
        now_s = self.iso(now)
        with self.conn:
            self.conn.execute(
                """INSERT INTO sync_status (source, last_run, last_ok, last_error, count) VALUES (?, ?, ?, ?, ?)
                   ON CONFLICT(source) DO UPDATE SET last_run = excluded.last_run,
                       last_ok = COALESCE(excluded.last_ok, sync_status.last_ok),
                       last_error = excluded.last_error, count = excluded.count""",
                (source, now_s, None if error else now_s, error, count),
            )

    @locked
    def sync_statuses(self) -> dict[str, dict]:
        return {r["source"]: dict(r) for r in self.conn.execute("SELECT * FROM sync_status").fetchall()}

    @locked
    def mail_seen(self, key: str) -> bool:
        return self.conn.execute("SELECT 1 FROM mails WHERE key = ?", (key,)).fetchone() is not None

    @locked
    def record_mail(self, key: str, *, channel: str | None, subject: str, ok: bool, error: str | None, received_at: str | None) -> None:
        with self.conn:
            self.conn.execute(
                "INSERT OR REPLACE INTO mails (key, channel, subject, ok, error, received_at) VALUES (?, ?, ?, ?, ?, ?)",
                (key, channel, subject, int(ok), error, received_at),
            )

    @locked
    def failed_mails(self, limit: int = 20) -> list[dict]:
        rows = self.conn.execute("SELECT * FROM mails WHERE ok = 0 ORDER BY received_at DESC LIMIT ?", (limit,)).fetchall()
        return [dict(r) for r in rows]


def _rule_dict(r: Rule) -> dict:
    return {
        "id": r.id, "name": r.name, "trigger": r.trigger, "text": r.text, "title": r.title,
        "solapi_template_id": r.solapi_template_id, "offset_minutes": r.offset_minutes,
        "day_before_at": r.day_before_at, "channels": list(r.channels), "enabled": r.enabled,
    }


def _rule_from(d: dict) -> Rule:
    return Rule(**{**d, "channels": tuple(d.get("channels") or ())})
