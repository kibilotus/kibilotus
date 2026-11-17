"""설정 로딩. 일반 설정은 config.yaml, 비밀값(API 키, 비밀번호)은 환경변수로 받는다."""
from __future__ import annotations

import os
from dataclasses import dataclass, field
from datetime import time
from zoneinfo import ZoneInfo

import yaml

from .models import Rule

TRIGGERS = ("created", "start", "end", "cancelled")


@dataclass
class Room:
    name: str
    keywords: list[str] = field(default_factory=list)


@dataclass
class Config:
    studio_name: str
    rooms: list[Room]
    rules: list[Rule]
    sources: dict = field(default_factory=dict)
    timezone: ZoneInfo = field(default_factory=lambda: ZoneInfo("Asia/Seoul"))
    db_path: str = "spacehub.db"
    poll_interval_seconds: int = 300
    max_attempts: int = 3
    dry_run: bool = True
    quiet_hours: str | None = None  # "22:00-08:00": 이 시간에는 보내지 않고 끝난 뒤 보낸다
    feed_token: str = ""
    public_url: str = "http://localhost:8000"

    # 환경변수
    admin_password: str = ""
    imap_password: str = ""
    solapi_api_key: str = ""
    solapi_api_secret: str = ""
    solapi_pf_id: str = ""
    solapi_sender: str = ""

    def source(self, name: str) -> dict:
        return self.sources.get(name) or {}

    def resolve_room(self, *texts: str | None) -> str:
        """공간명/상품명에 들어 있는 키워드로 공간을 정한다. 못 찾으면 첫 번째 공간."""
        joined = " ".join(t for t in texts if t)
        for room in self.rooms:
            if room.name in joined or any(k in joined for k in room.keywords):
                return room.name
        return self.rooms[0].name


def parse_quiet_hours(spec: str) -> tuple[time, time]:
    a, b = (s.strip() for s in spec.split("-"))
    return time.fromisoformat(a), time.fromisoformat(b)


def parse_rule(r: dict) -> Rule:
    if r["trigger"] not in TRIGGERS:
        raise ValueError(f"rule {r['id']}: trigger 는 {TRIGGERS} 중 하나여야 합니다")
    return Rule(
        id=r["id"],
        name=r.get("name", r["id"]),
        trigger=r["trigger"],
        text=r["text"],
        title=r.get("title", ""),
        solapi_template_id=r.get("solapi_template_id", ""),
        offset_minutes=int(r.get("offset_minutes", 0)),
        day_before_at=r.get("day_before_at"),
        channels=tuple(r.get("channels") or ()),
        enabled=bool(r.get("enabled", True)),
    )


def load_config(path: str) -> Config:
    with open(path, encoding="utf-8") as f:
        raw = yaml.safe_load(f)

    rooms = [Room(name=r["name"], keywords=r.get("keywords", [])) for r in raw.pop("rooms")]
    if not rooms:
        raise ValueError("rooms 에 공간을 1개 이상 등록하세요")
    rules = [parse_rule(r) for r in raw.pop("rules", [])]
    tz = ZoneInfo(raw.pop("timezone", "Asia/Seoul"))

    cfg = Config(rooms=rooms, rules=rules, timezone=tz, **raw)
    if cfg.quiet_hours:
        parse_quiet_hours(cfg.quiet_hours)  # 형식 오류는 시작할 때 알린다
    cfg.admin_password = os.environ.get("ADMIN_PASSWORD", "")
    cfg.imap_password = os.environ.get("IMAP_PASSWORD", "")
    cfg.solapi_api_key = os.environ.get("SOLAPI_API_KEY", "")
    cfg.solapi_api_secret = os.environ.get("SOLAPI_API_SECRET", "")
    cfg.solapi_pf_id = os.environ.get("SOLAPI_PF_ID", "")
    cfg.solapi_sender = os.environ.get("SOLAPI_SENDER", "")
    return cfg
