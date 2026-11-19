"""관리자 웹 (FastAPI). 관리 화면(static/index.html)과 JSON API, 다른 채널 마감용 iCal 피드를 제공한다."""
from __future__ import annotations

import secrets
import threading
from datetime import datetime, timedelta, timezone
from pathlib import Path

from fastapi import Depends, FastAPI, HTTPException, Response, status
from fastapi.responses import FileResponse
from fastapi.security import HTTPBasic, HTTPBasicCredentials
from pydantic import BaseModel, Field

from .config import TRIGGERS, parse_rule
from .engine import Engine
from .models import CHANNELS, Booking
from .template import VARIABLES, TemplateError, build_variables, render

STATIC = Path(__file__).parent / "static"


class BookingIn(BaseModel):
    name: str = Field(min_length=1, max_length=40)
    phone: str = ""
    room: str
    product: str = ""
    start: datetime
    end: datetime
    amount: int | None = None


class RuleIn(BaseModel):
    name: str = Field(min_length=1, max_length=40)
    trigger: str
    offset_minutes: int = 0
    day_before_at: str | None = None
    channels: list[str] = []
    enabled: bool = True
    title: str = ""
    solapi_template_id: str = ""
    text: str = Field(min_length=1, max_length=1000)


def create_app(engine: Engine) -> FastAPI:
    cfg, store = engine.cfg, engine.store
    tz = cfg.timezone
    app = FastAPI(title="SpaceHub", docs_url=None, redoc_url=None)
    basic = HTTPBasic()

    def auth(cred: HTTPBasicCredentials = Depends(basic)) -> None:
        ok_user = secrets.compare_digest(cred.username.encode(), b"admin")
        ok_pw = secrets.compare_digest(cred.password.encode(), cfg.admin_password.encode())
        if not (cfg.admin_password and ok_user and ok_pw):
            raise HTTPException(status.HTTP_401_UNAUTHORIZED, "로그인이 필요해요", {"WWW-Authenticate": "Basic"})

    def booking_json(b: Booking, sends: list[dict] | None = None) -> dict:
        return {
            "id": b.id, "channel": b.channel, "status": b.status, "name": b.name, "phone": b.phone,
            "room": b.room, "product": b.product, "amount": b.amount,
            "start": b.start.isoformat(), "end": b.end.isoformat(),
            "created_at": b.created_at.isoformat(), "errors": list(b.errors), "sends": sends or [],
        }

    def aware(d: datetime) -> datetime:
        # 화면의 datetime-local 값은 시간대가 없으므로 스튜디오 시간대로 본다 (서버 시간대와 무관하게)
        return d.replace(tzinfo=tz) if d.tzinfo is None else d.astimezone(tz)

    def day_start(d: datetime) -> datetime:
        return aware(d).replace(hour=0, minute=0, second=0, microsecond=0)

    # ---------- 화면 ----------
    @app.get("/", include_in_schema=False, dependencies=[Depends(auth)])
    def index():
        return FileResponse(STATIC / "index.html")

    @app.get("/api/meta", dependencies=[Depends(auth)])
    def meta():
        return {
            "studio": cfg.studio_name, "timezone": tz.key, "rooms": [r.name for r in cfg.rooms], "channels": CHANNELS,
            "dry_run": cfg.dry_run, "triggers": TRIGGERS, "variables": VARIABLES,
            "now": engine.now().isoformat(),
        }

    @app.get("/api/summary", dependencies=[Depends(auth)])
    def summary():
        now = engine.now()
        today = day_start(now)
        week = today - timedelta(days=today.weekday())
        todays = store.list_bookings(today, today + timedelta(days=1), include_cancelled=False)
        weeks = store.list_bookings(week, week + timedelta(days=7), include_cancelled=False)
        month = store.list_bookings(today.replace(day=1), today.replace(day=1) + timedelta(days=32), include_cancelled=False)
        upcoming = [b for b in store.bookings_ending_after(now) if b.status == "confirmed"]
        sends = [s for s in store.list_sends(500) if s["updated_at"] >= week.isoformat()]
        by_status: dict[str, int] = {}
        for s in sends:
            by_status[s["status"]] = by_status.get(s["status"], 0) + 1
        failed_sends = [s for s in store.list_sends(50) if s["status"] == "failed"]
        return {
            "today": [booking_json(b) for b in todays],
            "today_by_channel": {c: sum(1 for b in todays if b.channel == c) for c in CHANNELS},
            "week_revenue": sum(b.amount or 0 for b in weeks),
            "week_sends": by_status,
            "month_by_channel": {c: sum(1 for b in month if b.channel == c) for c in CHANNELS},
            "sync": store.sync_statuses(),
            "issues": {
                "conflicts": [[booking_json(a), booking_json(b)] for a, b in store.conflicts(now)],
                "missing_info": [booking_json(b) for b in upcoming if b.errors],
                "failed_mails": store.failed_mails(),
                "failed_sends": failed_sends,
            },
        }

    @app.get("/api/bookings", dependencies=[Depends(auth)])
    def bookings(start: datetime | None = None, days: int = 7):
        frm = day_start(start or engine.now())
        items = store.list_bookings(frm, frm + timedelta(days=min(days, 62)))
        sends = store.sends_for([b.id for b in items])
        return [booking_json(b, sends.get(b.id)) for b in items]

    @app.post("/api/bookings", dependencies=[Depends(auth)], status_code=201)
    def add_booking(body: BookingIn):
        from .parser import normalize_phone

        if body.room not in [r.name for r in cfg.rooms]:
            raise HTTPException(400, "등록되지 않은 공간이에요")
        start, end = aware(body.start), aware(body.end)
        if end <= start:
            raise HTTPException(400, "종료 시각이 시작 시각보다 늦어야 해요")
        phone = normalize_phone(body.phone) if body.phone else None
        if body.phone and not phone:
            raise HTTPException(400, "휴대폰 번호 형식이 아니에요 (010-0000-0000)")
        bid = store.create_direct(name=body.name.strip(), phone=phone, room=body.room, product=body.product or None,
                                  start=start, end=end, amount=body.amount, now=engine.now())
        return booking_json(store.get_booking(bid))

    @app.post("/api/bookings/{booking_id}/cancel", dependencies=[Depends(auth)])
    def cancel_booking(booking_id: int):
        b = store.get_booking(booking_id)
        if not b:
            raise HTTPException(404, "예약을 찾지 못했어요")
        if b.channel != "direct":
            raise HTTPException(400, f"{CHANNELS[b.channel]} 예약은 해당 플랫폼에서 취소해주세요. 취소 메일이 오면 자동 반영돼요")
        store.cancel_booking(booking_id, engine.now())
        return booking_json(store.get_booking(booking_id))

    # ---------- 알림 규칙 ----------
    @app.get("/api/rules", dependencies=[Depends(auth)])
    def rules():
        return [r.__dict__ | {"channels": list(r.channels)} for r in store.rules()]

    @app.put("/api/rules/{rule_id}", dependencies=[Depends(auth)])
    def save_rule(rule_id: str, body: RuleIn):
        if body.trigger not in TRIGGERS:
            raise HTTPException(400, "발송 기준이 올바르지 않아요")
        if body.day_before_at and (body.trigger != "start" or len(body.day_before_at) != 5):
            raise HTTPException(400, "'전날 지정 시각'은 이용 시작 기준에서 HH:MM 형식으로만 쓸 수 있어요")
        unknown = [c for c in body.channels if c not in CHANNELS]
        if unknown:
            raise HTTPException(400, f"알 수 없는 채널: {unknown}")
        try:
            render(body.text, {v: "x" for v in VARIABLES})
        except TemplateError as e:
            raise HTTPException(400, f"쓸 수 없는 변수가 있어요: {e}")
        rule = parse_rule({"id": rule_id, **body.model_dump()})
        store.save_rule(rule)
        return rule.__dict__ | {"channels": list(rule.channels)}

    @app.get("/api/rules/{rule_id}/preview", dependencies=[Depends(auth)])
    def preview(rule_id: str, booking_id: int | None = None):
        rule = next((r for r in store.rules() if r.id == rule_id), None)
        if not rule:
            raise HTTPException(404)
        b = store.get_booking(booking_id) if booking_id else None
        if b is None:
            now = engine.now().replace(minute=0, second=0, microsecond=0) + timedelta(days=3)
            b = Booking(0, "naver", "", "confirmed", "홍길동", "01012345678", f"{cfg.rooms[0].name} 예시 상품",
                        cfg.rooms[0].name, now, now + timedelta(hours=3), 60000, now, None)
        try:
            text, _ = render(rule.text, build_variables(b, cfg.studio_name, tz))
        except TemplateError as e:
            text = str(e)
        return {"title": rule.title, "text": text}

    @app.get("/api/messages", dependencies=[Depends(auth)])
    def messages(limit: int = 100):
        return store.list_sends(min(limit, 500))

    # ---------- 채널 ----------
    @app.get("/api/channels", dependencies=[Depends(auth)])
    def channels():
        em, ic = cfg.source("email"), cfg.source("spacecloud_ical")
        sources = [
            {"name": "email", "kind": "email", "label": f"예약 알림 메일 ({em.get('user') or '-'})",
             "channels": list(em.get("channels", {}).keys()), "enabled": bool(em.get("enabled"))},
            {"name": "spacecloud_ical", "kind": "ical", "label": "캘린더 주소 직접 읽기",
             "channels": ["spacecloud"], "enabled": bool(ic.get("enabled") and ic.get("url"))},
        ]
        for gc in cfg.google_calendars():
            sources.append({
                "name": gc.get("name", "google_calendar"), "kind": "gcal",
                "label": f"구글 캘린더 ({gc.get('label') or gc.get('calendar_id', '-')})",
                "channels": [gc.get("channel", "direct")], "enabled": bool(gc.get("enabled")),
                # 'URL로 추가'한 구독 캘린더는 구글이 몇 시간마다 다시 받아온다
                "subscribed": str(gc.get("calendar_id", "")).endswith("@import.calendar.google.com"),
            })
        return {
            "sources": sources,
            "sync": store.sync_statuses(),
            "feed_url": f"{cfg.public_url.rstrip('/')}/feed/{cfg.feed_token}.ics" if cfg.feed_token else None,
        }

    @app.post("/api/sync", dependencies=[Depends(auth)])
    def sync_now():
        threading.Thread(target=engine.run_once, daemon=True).start()
        return {"started": True}

    # ---------- 마감용 iCal 피드 (개인정보 없이 '예약됨'만) ----------
    @app.get("/feed/{token}.ics", include_in_schema=False)
    def feed(token: str):
        if not cfg.feed_token or not secrets.compare_digest(token, cfg.feed_token):
            raise HTTPException(404)
        now = engine.now()
        lines = ["BEGIN:VCALENDAR", "VERSION:2.0", f"PRODID:-//SpaceHub//{cfg.studio_name}//KO", "CALSCALE:GREGORIAN"]
        stamp = now.astimezone(timezone.utc).strftime("%Y%m%dT%H%M%SZ")
        for b in store.bookings_ending_after(now):
            if b.status != "confirmed":
                continue
            # VTIMEZONE 정의 없이도 어느 캘린더든 같은 시각으로 읽도록 UTC 로 쓴다
            fmt = lambda d: d.astimezone(timezone.utc).strftime("%Y%m%dT%H%M%SZ")  # noqa: E731
            lines += ["BEGIN:VEVENT", f"UID:spacehub-{b.id}@spacehub", f"DTSTAMP:{stamp}",
                      f"DTSTART:{fmt(b.start)}", f"DTEND:{fmt(b.end)}",
                      f"SUMMARY:예약됨 ({b.room})", "TRANSP:OPAQUE", "END:VEVENT"]
        lines.append("END:VCALENDAR")
        return Response("\r\n".join(lines) + "\r\n", media_type="text/calendar; charset=utf-8")

    return app
