"""설정 로딩. 일반 설정은 config.yaml, 비밀값(API 키)은 환경변수로 받는다."""
from __future__ import annotations

import os
from dataclasses import dataclass, field
from zoneinfo import ZoneInfo

import yaml

from .models import Rule, Template


@dataclass
class Config:
    calendar_id: str
    studio_name: str
    default_product: str
    rules: list[Rule]
    templates: dict[str, Template]
    timezone: ZoneInfo = field(default_factory=lambda: ZoneInfo("Asia/Seoul"))
    poll_interval_seconds: int = 300
    lookahead_days: int = 8
    max_attempts: int = 3
    dry_run: bool = True
    db_path: str = "alimtalk.db"
    service_account_file: str = "service-account.json"
    parser: dict = field(default_factory=dict)

    # 환경변수
    solapi_api_key: str = ""
    solapi_api_secret: str = ""
    solapi_pf_id: str = ""
    solapi_sender: str = ""


def load_config(path: str) -> Config:
    with open(path, encoding="utf-8") as f:
        raw = yaml.safe_load(f)

    templates = {
        tid: Template(id=tid, solapi_template_id=t.get("solapi_template_id", ""), text=t["text"])
        for tid, t in raw.pop("templates").items()
    }
    rules = [Rule(**r) for r in raw.pop("rules")]
    for r in rules:
        if r.trigger not in ("created", "start", "end"):
            raise ValueError(f"rule {r.id}: trigger 는 created/start/end 중 하나여야 합니다")
        if r.template not in templates:
            raise ValueError(f"rule {r.id}: 템플릿 '{r.template}' 이 없습니다")

    tz = ZoneInfo(raw.pop("timezone", "Asia/Seoul"))
    cfg = Config(rules=rules, templates=templates, timezone=tz, **raw)
    cfg.solapi_api_key = os.environ.get("SOLAPI_API_KEY", "")
    cfg.solapi_api_secret = os.environ.get("SOLAPI_API_SECRET", "")
    cfg.solapi_pf_id = os.environ.get("SOLAPI_PF_ID", "")
    cfg.solapi_sender = os.environ.get("SOLAPI_SENDER", "")
    return cfg
