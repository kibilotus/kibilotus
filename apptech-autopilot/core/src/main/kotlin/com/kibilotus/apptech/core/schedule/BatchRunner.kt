package com.kibilotus.apptech.core.schedule

import com.kibilotus.apptech.core.Mission
import com.kibilotus.apptech.core.MissionResult
import com.kibilotus.apptech.core.Status
import java.time.ZonedDateTime

/**
 * 미션 상태 저장소. 키는 (미션 ID, 주기 키) — 주기 키는 [com.kibilotus.apptech.core.Period.key] 참고.
 * 2단계는 SharedPreferences, 3단계에서 DB로 교체한다.
 */
interface MissionStateStore {
    fun status(missionId: String, periodKey: String): Status?
    fun save(missionId: String, periodKey: String, result: MissionResult)
}

class InMemoryStateStore : MissionStateStore {
    private val map = mutableMapOf<Pair<String, String>, Status>()
    override fun status(missionId: String, periodKey: String) = map[missionId to periodKey]
    override fun save(missionId: String, periodKey: String, result: MissionResult) {
        map[missionId to periodKey] = result.status
    }
}

data class BatchReport(val results: List<MissionResult>, val skipped: List<String>) {
    val allDone: Boolean get() = results.all { it.isDoneForToday }
}

/**
 * 이번 주기에 아직 끝나지 않은 미션만 순서대로 실행하고, 결과를 저장한다.
 * - SUCCESS, ALREADY_DONE: 같은 주기 안에서는 다시 시도하지 않음
 * - FAILED, BLOCKED: 다음 슬롯에 다시 시도
 * - BLOCKED가 나오거나 사용자가 중지(인터럽트)하면 남은 미션을 실행하지 않음
 */
class BatchRunner(
    private val missions: List<Mission>,
    private val store: MissionStateStore,
    private val runMission: (Mission) -> MissionResult,
    /** 미션 사이에 쉬는 시간 (sleep 함수 주입) */
    private val pauseBetween: () -> Unit = {},
) {
    fun pending(now: ZonedDateTime): List<Mission> = missions.filter { m ->
        val s = store.status(m.id, m.period.key(now))
        s != Status.SUCCESS && s != Status.ALREADY_DONE
    }

    fun run(now: ZonedDateTime): BatchReport {
        val todo = pending(now)
        val skipped = missions.map { it.id } - todo.map { it.id }.toSet()
        val results = mutableListOf<MissionResult>()
        for ((i, mission) in todo.withIndex()) {
            if (Thread.currentThread().isInterrupted) break
            if (i > 0) {
                try {
                    pauseBetween()
                } catch (e: InterruptedException) {
                    Thread.currentThread().interrupt()
                    break
                }
            }
            val result = runMission(mission)
            // 실행 시작 시각 기준 주기에 기록 (회차 경계를 넘겨 끝나도 시작한 회차로 집계)
            store.save(mission.id, mission.period.key(now), result)
            results += result
            if (result.status == Status.BLOCKED || Thread.currentThread().isInterrupted) break
        }
        return BatchReport(results, skipped)
    }
}
