package com.kibilotus.apptech.core.schedule

import java.time.Instant

/**
 * 알람이나 잠금 해제 같은 트리거가 왔을 때 지금 실행할지 정한다.
 *
 * 규칙
 * - 한 슬롯(2시간)에서는 최대 1번만 시도한다. 실행했거나 사용자가 취소했으면 그 슬롯은 시도한 것으로 친다.
 * - 오늘 남은 미션이 없으면 아무것도 하지 않는다.
 * - 기기가 잠겨 있거나 화면이 꺼져 있으면 다음 잠금 해제까지 기다린다.
 * - 실행은 항상 사용자가 취소할 수 있는 카운트다운을 거친다. 메인 폰을 쓰는 도중에 화면을 갑자기 빼앗지 않기 위해서다.
 */
class TriggerPolicy(private val schedule: SlotSchedule) {

    enum class Decision {
        /** 오늘 남은 미션이 없거나, 이번 슬롯은 이미 시도함 */
        NOTHING,

        /** 실행할 차례지만 기기가 잠겨 있음. 잠금 해제 이벤트를 기다린다 */
        WAIT_FOR_UNLOCK,

        /** 취소 가능한 카운트다운 후 실행 */
        START_COUNTDOWN,
    }

    fun decide(now: Instant, deviceReady: Boolean, pendingCount: Int, lastAttemptedSlotMs: Long?): Decision {
        if (pendingCount == 0) return Decision.NOTHING
        val currentSlot = schedule.slotStart(now).toInstant().toEpochMilli()
        if (lastAttemptedSlotMs != null && lastAttemptedSlotMs >= currentSlot) return Decision.NOTHING
        return if (deviceReady) Decision.START_COUNTDOWN else Decision.WAIT_FOR_UNLOCK
    }
}
