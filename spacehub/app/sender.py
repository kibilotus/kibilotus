"""알림톡 발송. 솔라피 REST API(v4)를 SDK 없이 직접 호출한다."""
from __future__ import annotations

import hashlib
import hmac
import logging
import uuid
from datetime import datetime, timezone
from typing import Protocol

import requests

log = logging.getLogger(__name__)


class SendError(Exception):
    def __init__(self, message: str, retryable: bool):
        super().__init__(message)
        self.retryable = retryable


class Sender(Protocol):
    def send(self, to: str, template_id: str, variables: dict[str, str], text: str) -> None: ...


class DryRunSender:
    def send(self, to: str, template_id: str, variables: dict[str, str], text: str) -> None:
        log.info("[DRY-RUN] to=%s template=%s\n%s", to, template_id, text)


class SolapiSender:
    URL = "https://api.solapi.com/messages/v4/send"

    def __init__(self, api_key: str, api_secret: str, pf_id: str, sender_number: str, timeout: float = 10):
        self.api_key = api_key
        self.api_secret = api_secret
        self.pf_id = pf_id
        self.sender_number = sender_number
        self.timeout = timeout

    def auth_header(self, date: str | None = None, salt: str | None = None) -> str:
        date = date or datetime.now(timezone.utc).isoformat(timespec="seconds")
        salt = salt or uuid.uuid4().hex
        signature = hmac.new(self.api_secret.encode(), (date + salt).encode(), hashlib.sha256).hexdigest()
        return f"HMAC-SHA256 apiKey={self.api_key}, date={date}, salt={salt}, signature={signature}"

    def send(self, to: str, template_id: str, variables: dict[str, str], text: str) -> None:
        body = {
            "message": {
                "to": to,
                "from": self.sender_number,
                # 알림톡은 text 를 넣으면 발송이 실패한다(솔라피 문서). 본문은 승인된 템플릿 + variables 로 만들어지고,
                # 카카오톡 미사용자에게는 같은 내용이 문자로 대체 발송된다(disableSms=False).
                "kakaoOptions": {"pfId": self.pf_id, "templateId": template_id, "variables": variables, "disableSms": False},
            }
        }
        try:
            res = requests.post(
                self.URL, json=body, headers={"Authorization": self.auth_header()}, timeout=self.timeout
            )
        except requests.RequestException as e:
            raise SendError(f"network: {e}", retryable=True) from e

        if res.status_code >= 500 or res.status_code == 429:
            raise SendError(f"HTTP {res.status_code}: {res.text[:300]}", retryable=True)
        if res.status_code >= 400:
            raise SendError(f"HTTP {res.status_code}: {res.text[:300]}", retryable=False)

        status_code = str(res.json().get("statusCode", "2000"))
        if not status_code.startswith("2"):
            raise SendError(f"solapi statusCode={status_code}: {res.text[:300]}", retryable=False)
