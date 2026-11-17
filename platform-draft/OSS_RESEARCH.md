# 공간대여 통합관리 플랫폼: 오픈소스 조사 (2026-11)

목표: 공집사 같은 공간대여 예약 통합관리 플랫폼. 필수 조건은 네이버 예약, 스페이스클라우드 연동과 알림톡 자동 발송.

## 결론

세 조건(네이버 예약 + 스페이스클라우드 + 알림톡)을 함께 갖춘 오픈소스는 찾지 못했다.
그래서 모듈별로 나눠 조합한다. 이미 만든 `alimtalk/` 모듈(발송 시점 계산, 중복 방지, 재시도, 솔라피 발송)이 알림 부분의 핵심이 된다.

| 모듈 | 방식 | 사용할 소스 |
|---|---|---|
| 네이버 예약 수집 | 예약 알림 메일 파싱 (공개 API 없음, 공식 연동은 제휴 필요) | 자체 개발. 동기화 규칙은 djmonnar/sky 참고 |
| 스페이스클라우드 수집 | 호스트센터 캘린더 내보내기(iCal) + 예약 알림 메일 | ical 라이브러리, Jaemnani 설계 참고 |
| 예약 통합, 중복 감지 | 자체 DB (PostgreSQL) | 자체 개발 |
| 다른 채널 자동 마감 | 마감용 iCal 피드 제공 (받아주는 채널만 가능) | Jaemnani 설계 참고 |
| 알림톡 | 솔라피 | solapi 공식 SDK + 기존 `alimtalk/` |
| 관리 화면 | 웹 | FullCalendar(MIT) 등 |

## 검토한 프로젝트

| 프로젝트 | 판단 | 메모 | 라이선스 |
|---|---|---|---|
| [solapi 공식 SDK](https://github.com/solapi/solapi-kotlin) (Kotlin/Java, Python, PHP, C#) | 사용 | 알림톡, 문자 대체 발송, 예약 발송 | 저장소에서 확인 필요 |
| [LibreBooking/app](https://github.com/LibreBooking/app) | 설계 참고 | 리소스 예약, 할당량, 대기자, REST API. PHP 8.4/MySQL. 7.2.0 미만에 고위험 XSS 경고 | GPL-3.0 |
| [calcom/cal.com](https://github.com/calcom/cal.com) | 설계 참고 | 저장소 페이지가 'Cal.diy'(MIT)로 안내되며 Workflows(SMS 리마인더 등) 엔터프라이즈 기능이 빠져 있다고 설명. 사람 일정 중심 | MIT (페이지 기준) |
| [alextselegidis/easyappointments](https://github.com/alextselegidis/easyappointments) | 사용 안 함 | 상담·진료형 예약. 이메일 알림, 구글 캘린더 동기화 | GPL-3.0 |
| [djmonnar/sky PR #27](https://github.com/djmonnar/sky/pull/27) | 설계 참고 | 네이버 예약 신규/변경/취소 동기화. 데이터는 외부 업체(오너비스타) 파트너 API 경유 | 표시 없음 |
| [Jaemnani/instagram_application #1](https://github.com/Jaemnani/instagram_application/issues/1) | 설계 참고 | 공유 캘린더 중심, 스페이스클라우드·아워플레이스 iCal 읽기, 마감용 iCal 피드 | 표시 없음 |
| [gyusika/capatrage](https://github.com/gyusika/capatrage) | 사용 안 함 | 스페이스클라우드 공개 페이지 수집(시장 조사). 호스트 예약 정보 아님. 일부 경로는 robots.txt disallow | 표시 없음 |

라이선스 표시가 없는 저장소는 코드를 복사하지 않고 설계만 참고한다.

## 확인이 필요한 사항

- 네이버, 스페이스클라우드 예약 알림 메일에 고객 전화번호가 가려지지 않고 들어 있는지 (샘플 필요)
- 스페이스클라우드 호스트센터 캘린더 내보내기 주소에 어떤 정보가 담기는지, 외부 iCal 가져오기(자동 마감)를 지원하는지
- 네이버 공식 연동이 필요하면 제휴 심사 또는 제휴 업체(예: 오피스아워 '네이버 프로그램 서비스') 경유 검토
