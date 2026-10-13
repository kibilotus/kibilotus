package com.kibilotus.apptech.core

import java.time.ZonedDateTime

enum class Status {
    /** 이번 실행에서 미션을 완료함 */
    SUCCESS,

    /** 이미 오늘 완료되어 있어 스킵 */
    ALREADY_DONE,

    /** 실패 (재시도 대상) */
    FAILED,

    /** 사람의 조치가 필요한 상태(앱 잠금, 자동화 차단 등). 재시도해도 소용없음 */
    BLOCKED,
}

enum class FailureCode {
    ELEMENT_NOT_FOUND,
    LAUNCH_FAILED,
    APP_NOT_FOREGROUND,
    TAP_FAILED,
    NO_SCREEN_ACCESS,
    APP_LOCKED,
    AUTOMATION_DETECTED,
    DEVICE_LOCKED,

    /** 보상 버튼을 눌렀지만 지급이 확인되지 않음 */
    NOT_CONFIRMED,

    /** 사용자가 실행 중 중지함 */
    CANCELLED,
    UNEXPECTED,
}

/** 화면에 나타나면 미션을 즉시 중단시키는 조건 (예: 앱 잠금 화면, 원격제어 경고) */
data class Blocker(val selector: Selector, val code: FailureCode, val status: Status = Status.BLOCKED)

/** 미션 완료 상태가 초기화되는 주기 */
sealed interface Period {
    /** 같은 주기 안이면 같은 키 */
    fun key(time: ZonedDateTime): String

    /** 하루 1회 (현지 자정 초기화) */
    data object Daily : Period {
        override fun key(time: ZonedDateTime) = time.toLocalDate().toString()
    }

    /** 하루를 [hours]시간 회차로 나눔 (예: 토스 버튼 누르기는 3시간 x 8회) */
    data class EveryHours(val hours: Int) : Period {
        init {
            require(hours in 1..24 && 24 % hours == 0) { "hours must divide 24" }
        }

        override fun key(time: ZonedDateTime) = "${time.toLocalDate()}#${time.hour / hours}"
    }
}

data class Mission(
    val id: String,
    val title: String,
    val packageName: String,
    val steps: List<Step>,
    val blockers: List<Blocker> = emptyList(),
    val period: Period = Period.Daily,
)

data class MissionResult(
    val missionId: String,
    val status: Status,
    val code: FailureCode? = null,
    val message: String? = null,
    /** 실패한 단계 설명 */
    val failedStep: String? = null,
    /** 화면에서 읽어 온 획득 포인트. SUCCESS일 때만 값이 있음 */
    val points: Int? = null,
    val startedAtMs: Long,
    val durationMs: Long,
    /** 실패 시점 화면 트리 덤프 */
    val screenDump: String? = null,
    val log: List<String> = emptyList(),
) {
    /** 이번 주기(하루/회차)에 다시 시도할 필요가 없는 결과인가 */
    val isDoneForToday: Boolean get() = status == Status.SUCCESS || status == Status.ALREADY_DONE
}
