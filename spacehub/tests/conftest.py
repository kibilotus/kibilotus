from datetime import datetime
from pathlib import Path
from zoneinfo import ZoneInfo

import pytest

from app.config import Config, Room
from app.db import Store
from app.models import RawBooking, Rule

KST = ZoneInfo("Asia/Seoul")
ROOT = Path(__file__).resolve().parent.parent


def kst(*args):
    return datetime(*args, tzinfo=KST)


def raw(**kw):
    base = dict(channel="naver", external_id="N1", name="홍길동", phone="01012345678", product="A. 낮 패키지",
                room="A룸", start=kst(2026, 11, 20, 11), end=kst(2026, 11, 20, 17), amount=180000)
    return RawBooking(**{**base, **kw})


@pytest.fixture
def cfg():
    return Config(
        studio_name="스튜디오버디",
        rooms=[Room("A룸", ["A.", "낮 패키지"]), Room("B룸", ["B.", "밤 패키지"])],
        rules=[
            Rule("confirm", "예약완료", "created", "#{이름}님 #{채널} #{이용일시} 예약완료", solapi_template_id="T1"),
            Rule("before_end", "퇴실", "end", "#{이름}님 퇴실 3시간 전", solapi_template_id="T2", offset_minutes=-180),
            Rule("cancel", "취소", "cancelled", "#{이름}님 취소", solapi_template_id="T3"),
        ],
        max_attempts=3,
        admin_password="pw",
        feed_token="tok",
    )


@pytest.fixture
def store(cfg):
    s = Store(":memory:", KST)
    s.seed_rules(cfg.rules)
    return s
