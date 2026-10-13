package com.kibilotus.apptech.core

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
    UNEXPECTED,
}

/** 화면에 나타나면 미션을 즉시 중단시키는 조건 (예: 앱 잠금 화면, 원격제어 경고) */
data class Blocker(val selector: Selector, val code: FailureCode, val status: Status = Status.BLOCKED)

data class Mission(
    val id: String,
    val title: String,
    val packageName: String,
    val steps: List<Step>,
    val blockers: List<Blocker> = emptyList(),
)

data class MissionResult(
    val missionId: String,
    val status: Status,
    val code: FailureCode? = null,
    val message: String? = null,
    /** 실패한 단계 설명 */
    val failedStep: String? = null,
    /** 화면에서 읽어 온 획득 포인트 (못 읽으면 null) */
    val points: Int? = null,
    val startedAtMs: Long,
    val durationMs: Long,
    /** 실패 시점 화면 트리 덤프 */
    val screenDump: String? = null,
    val log: List<String> = emptyList(),
) {
    /** 오늘 다시 시도할 필요가 없는 결과인가 */
    val isDoneForToday: Boolean get() = status == Status.SUCCESS || status == Status.ALREADY_DONE
}
