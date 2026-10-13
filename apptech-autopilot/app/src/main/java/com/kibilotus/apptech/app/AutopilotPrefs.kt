package com.kibilotus.apptech.app

import android.content.Context
import com.kibilotus.apptech.core.MissionResult
import com.kibilotus.apptech.core.Status
import com.kibilotus.apptech.core.schedule.MissionStateStore
import java.time.LocalDate

/**
 * 미션 상태와 스케줄 상태를 SharedPreferences에 저장한다.
 * (3단계에서 실행 이력은 DB로 옮기고, 여기에는 스케줄 상태만 남긴다)
 */
class AutopilotPrefs(context: Context) : MissionStateStore {
    private val sp = context.getSharedPreferences("autopilot", Context.MODE_PRIVATE)

    override fun status(missionId: String, periodKey: String): Status? =
        sp.getString(key(missionId, periodKey), null)?.let { runCatching { Status.valueOf(it) }.getOrNull() }

    override fun save(missionId: String, periodKey: String, result: MissionResult) {
        val edit = sp.edit().putString(key(missionId, periodKey), result.status.name)
        // 3일 넘은 상태는 정리 (주기 키는 yyyy-MM-dd로 시작)
        val cutoff = LocalDate.now().minusDays(3).toString()
        sp.all.keys
            .filter { it.startsWith(PREFIX) && it.substringAfterLast('|').take(10) < cutoff }
            .forEach { edit.remove(it) }
        edit.apply()
    }

    /** 마지막으로 시도(실행 또는 사용자 취소)한 슬롯의 시작 시각 */
    var lastAttemptedSlotMs: Long?
        get() = sp.getLong("lastAttemptedSlot", -1).takeIf { it >= 0 }
        set(value) = sp.edit().putLong("lastAttemptedSlot", value ?: -1).apply()

    var nextAlarmAtMs: Long
        get() = sp.getLong("nextAlarmAt", 0)
        set(value) = sp.edit().putLong("nextAlarmAt", value).apply()

    var lastReport: String?
        get() = sp.getString("lastReport", null)
        set(value) = sp.edit().putString("lastReport", value).apply()

    private fun key(missionId: String, periodKey: String) = "$PREFIX$missionId|$periodKey"

    private companion object {
        const val PREFIX = "s|"
    }
}
