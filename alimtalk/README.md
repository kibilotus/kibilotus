# 구글 캘린더 연동 카카오 알림톡 자동 발송 (초안)

구글 캘린더 일정에서 예약자명/전화번호를 추출해, 설정한 시점(예약 확정 즉시, 이용 전날 18시, 이용 종료 3시간 전 등)에 솔라피를 통해 카카오 알림톡을 자동 발송합니다.

## 동작 방식

```
[5분마다 폴링] → Google Calendar 조회 (앞으로 8일)
             → 제목/설명 파싱 (이름, 전화번호, 상품)
             → 규칙별로 "발송 시점 ≤ 지금 < 이용 종료" 판정
             → SQLite 이력으로 중복 확인 → 알림톡 발송 → 결과 기록
```

- **일정 변경/삭제**: 매 폴링마다 다시 계산하므로 별도 처리 불필요. 이용 시각이 바뀌면 '전날/종료 전' 안내는 새 시각 기준으로 다시 발송, '예약완료'는 재발송하지 않음.
- **발송 시점이 이미 지난 예약**: 이용 종료 전이면 곧바로 발송 (참조 서비스와 동일).
- **재시도**: 네트워크/5xx/429 오류는 다음 폴링 때 재시도(최대 `max_attempts`회), 4xx 등은 즉시 실패 처리.
- **전화번호 오류/이름 없음**: 발송하지 않고 `invalid` 로 기록 + 경고 로그.

| 파일 | 역할 |
|---|---|
| `app/calendar_client.py` | Google Calendar API (서비스 계정) |
| `app/parser.py` | 예약자명/전화번호/상품 추출 |
| `app/scheduler.py` | 발송 시점 계산, 중복 방지 키 |
| `app/template.py` | `#{변수}` 템플릿 렌더링 |
| `app/sender.py` | 솔라피 알림톡 API, dry-run |
| `app/store.py` | 발송 이력 (SQLite) |
| `app/runner.py` | 폴링 1회 처리 흐름 |

## 일정 입력 형식

아래 중 아무 형식이나 인식합니다.

- 제목: `홍길동 / 010-1234-5678`, `[A룸] 홍길동 01012345678`
- 설명: `이름: 홍길동` / `연락처: 010-1234-5678` / `상품: B. 밤 패키지` (줄 단위)

상품이 없으면 `default_product` 를 사용합니다. 종일 일정은 무시합니다.

## 설정 및 실행

1. **Google 서비스 계정**
   - Google Cloud Console → 프로젝트 생성 → *Google Calendar API* 사용 설정
   - 서비스 계정 생성 → 키(JSON) 발급 → `service-account.json` 으로 저장
   - 구글 캘린더 → 대상 캘린더 설정 → *특정 사용자와 공유* 에 서비스 계정 이메일 추가 (일정 세부정보 보기 권한)
   - 캘린더 설정의 *캘린더 ID* 를 `config.yaml` 의 `calendar_id` 에 입력
2. **솔라피**
   - 카카오 채널 연동 → 발신프로필 ID(`pfId`) 확인
   - 알림톡 템플릿 등록/검수 승인 → 템플릿 ID 를 `config.yaml` 에 입력 (본문은 승인 문구와 완전히 동일해야 함)
   - API Key/Secret 발급, 발신번호 등록
3. **실행**

```bash
cd alimtalk
python3 -m venv .venv && . .venv/bin/activate
pip install -r requirements.txt
cp config.example.yaml config.yaml   # 값 수정

export SOLAPI_API_KEY=... SOLAPI_API_SECRET=... SOLAPI_PF_ID=... SOLAPI_SENDER=021234567

python -m app.main --seed   # 최초 1회: 기존 예약에 '예약완료'가 일괄 발송되지 않도록 현재 대상만 처리됨으로 기록
python -m app.main          # 상시 실행 (dry_run: true 상태에서 로그로 먼저 확인 후 false 로 변경)
```

서버 상시 실행은 `nohup`/`systemd`/`tmux` 등 편한 방법을 쓰면 됩니다. cron 으로 돌리려면 `*/5 * * * * cd /path/alimtalk && .venv/bin/python -m app.main --once` 처럼 `--once` 를 사용하세요.

## 테스트

```bash
pip install -r requirements.txt && python -m pytest -q
```

## 초안 범위에서 제외한 것 (필요 시 추가)

- 관리 웹 UI (현재는 `config.yaml` 수정 후 재시작)
- 최종 실패 시 관리자 알림 (현재는 로그만)
- 웹훅/증분 동기화 (`syncToken`) — 소규모 캘린더는 5분 폴링으로 충분
- 야간 발송 제한, 취소 안내 메시지
