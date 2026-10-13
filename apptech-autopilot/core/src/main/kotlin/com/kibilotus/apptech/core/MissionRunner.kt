package com.kibilotus.apptech.core

import kotlin.random.Random

/**
 * [Mission]의 단계들을 [Driver] 위에서 실행하고 [MissionResult]를 돌려준다.
 *
 * - 매 화면 폴링마다 [Mission.blockers]를 검사해 앱 잠금/자동화 경고 화면이면 즉시 BLOCKED로 종료
 * - 탭은 요소 영역 안쪽의 랜덤 좌표, 단계 사이에는 랜덤 지연을 두어 기계적인 패턴을 줄인다
 * - 어떤 예외도 밖으로 던지지 않고 결과 객체로 변환한다 (배치 스케줄러가 죽지 않도록)
 */
class MissionRunner(
    private val driver: Driver,
    private val clock: Clock = Clock.System,
    private val random: Random = Random.Default,
    private val pollIntervalMs: Long = 400,
    /** 각 동작 후 사람처럼 쉬는 시간 범위 */
    private val actionDelayMs: LongRange = 500L..1_400L,
    private val logger: (String) -> Unit = {},
) {
    fun run(mission: Mission): MissionResult {
        val ctx = Context(mission, clock.nowMs())
        return try {
            ctx.log("start ${mission.id}")
            execute(ctx, mission.steps)
            ctx.result(Status.SUCCESS)
        } catch (e: Finish) {
            ctx.result(e.status)
        } catch (e: Abort) {
            ctx.log("abort ${e.code}: ${e.message}")
            ctx.result(e.status, e.code, e.message, e.step, safeDump())
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            ctx.result(Status.FAILED, FailureCode.CANCELLED, "interrupted", null, null)
        } catch (e: Exception) {
            ctx.log("unexpected: $e")
            ctx.result(Status.FAILED, FailureCode.UNEXPECTED, e.toString(), null, safeDump())
        }
    }

    private fun execute(ctx: Context, steps: List<Step>) {
        for (step in steps) {
            ctx.log("> ${step.description}")
            when (step) {
                is Step.Launch -> launch(ctx, step)
                is Step.WaitFor -> await(ctx, step.selector, step.timeoutMs)
                    ?: throw Abort(Status.FAILED, FailureCode.ELEMENT_NOT_FOUND, "not found: ${step.selector}", step)
                is Step.Tap -> tap(ctx, step)
                is Step.ScrollTo -> scrollTo(ctx, step)
                is Step.Back -> repeat(step.times) { driver.back(); humanPause() }
                is Step.Pause -> clock.sleep(randomIn(step.minMs, step.maxMs))
                is Step.FinishIfPresent -> if (await(ctx, step.selector, step.timeoutMs) != null) {
                    ctx.log("finish: ${step.status}")
                    throw Finish(step.status)
                }
                is Step.FinishIfAbsent -> if (await(ctx, step.selector, step.timeoutMs) == null) {
                    ctx.log("finish (absent): ${step.status}")
                    throw Finish(step.status)
                }
                is Step.ReturnToApp -> returnTo(ctx, step)
                is Step.If -> execute(ctx, if (await(ctx, step.selector, step.timeoutMs) != null) step.then else step.otherwise)
                is Step.WaitUntilGone -> waitUntilGone(ctx, step)
                is Step.ReadPoints -> readPoints(ctx, step)
            }
        }
    }

    private fun launch(ctx: Context, step: Step.Launch) {
        if (!driver.launch(step.packageName, step.deepLink)) {
            throw Abort(Status.FAILED, FailureCode.LAUNCH_FAILED, "cannot launch ${step.packageName}", step)
        }
        val deadline = clock.nowMs() + step.timeoutMs
        while (clock.nowMs() < deadline) {
            if (driver.foregroundPackage() == step.packageName) {
                humanPause()
                return
            }
            clock.sleep(pollIntervalMs)
        }
        throw Abort(Status.FAILED, FailureCode.APP_NOT_FOREGROUND, "${step.packageName} did not come to foreground", step)
    }

    private fun tap(ctx: Context, step: Step.Tap) {
        val node = await(ctx, step.selector, step.timeoutMs)
        if (node == null) {
            if (step.optional) return
            throw Abort(Status.FAILED, FailureCode.ELEMENT_NOT_FOUND, "not found: ${step.selector}", step)
        }
        val target = node.clickTarget()
        val b = target.bounds
        // 정중앙이 아닌, 영역 안쪽 60% 범위의 랜덤 좌표를 탭
        val x = b.left + (b.width * (0.2 + random.nextDouble() * 0.6)).toInt()
        val y = b.top + (b.height * (0.2 + random.nextDouble() * 0.6)).toInt()
        ctx.log("tap '${node.label}' at ($x,$y) within ${target.bounds}")
        if (!driver.tap(target, x, y)) {
            throw Abort(Status.FAILED, FailureCode.TAP_FAILED, "tap failed: ${step.selector}", step)
        }
        humanPause()
    }

    private fun scrollTo(ctx: Context, step: Step.ScrollTo) {
        for (i in 0..step.maxScrolls) {
            if (await(ctx, step.selector, 0) != null) return
            if (i == step.maxScrolls || !driver.scrollForward()) break
            humanPause()
        }
        throw Abort(Status.FAILED, FailureCode.ELEMENT_NOT_FOUND, "not found after scrolling: ${step.selector}", step)
    }

    private fun waitUntilGone(ctx: Context, step: Step.WaitUntilGone) {
        val deadline = clock.nowMs() + step.timeoutMs
        while (true) {
            val root = snapshot(ctx)
            if (root == null || step.selector.findIn(root) == null) return
            if (clock.nowMs() >= deadline) {
                throw Abort(Status.FAILED, FailureCode.NOT_CONFIRMED, "still visible: ${step.selector}", step)
            }
            clock.sleep(pollIntervalMs)
        }
    }

    private fun returnTo(ctx: Context, step: Step.ReturnToApp) {
        for (i in 0..step.maxBacks) {
            val deadline = clock.nowMs() + 2_000
            while (clock.nowMs() < deadline) {
                if (driver.foregroundPackage() == step.packageName) {
                    humanPause()
                    return
                }
                clock.sleep(pollIntervalMs)
            }
            if (i < step.maxBacks) {
                ctx.log("back (foreground=${driver.foregroundPackage()})")
                driver.back()
            }
        }
        throw Abort(Status.FAILED, FailureCode.APP_NOT_FOREGROUND, "could not return to ${step.packageName}", step)
    }

    private fun readPoints(ctx: Context, step: Step.ReadPoints) {
        val node = await(ctx, step.selector, step.timeoutMs) ?: return
        val match = step.pattern.find(node.label) ?: return
        val value = match.groupValues[1].replace(",", "").toIntOrNull() ?: return
        ctx.points = (ctx.points ?: 0) + value
        ctx.log("points +$value (from '${node.label}')")
    }

    /** [timeoutMs] 동안 폴링하며 요소를 찾는다. 0이면 한 번만 확인 */
    private fun await(ctx: Context, selector: Selector, timeoutMs: Long): UiNode? {
        val deadline = clock.nowMs() + timeoutMs
        while (true) {
            snapshot(ctx)?.let { root -> selector.findIn(root)?.let { return it } }
            if (clock.nowMs() >= deadline) return null
            clock.sleep(pollIntervalMs)
        }
    }

    /** 스냅샷을 찍고 차단 조건을 검사한다 */
    private fun snapshot(ctx: Context): UiNode? {
        val root = driver.snapshot() ?: return null
        for (blocker in ctx.mission.blockers) {
            val hit = blocker.selector.findIn(root) ?: continue
            throw Abort(blocker.status, blocker.code, "blocker '${hit.label}' (${blocker.selector})", null)
        }
        return root
    }

    private fun safeDump(): String? = try {
        driver.snapshot()?.dump()
    } catch (e: Exception) {
        null
    }

    private fun humanPause() = clock.sleep(randomIn(actionDelayMs.first, actionDelayMs.last))

    private fun randomIn(min: Long, max: Long): Long = if (max <= min) min else random.nextLong(min, max + 1)

    private inner class Context(val mission: Mission, val startedAt: Long) {
        val logs = mutableListOf<String>()
        var points: Int? = null

        fun log(msg: String) {
            val line = "[${mission.id}] $msg"
            logs += line
            logger(line)
        }

        fun result(
            status: Status,
            code: FailureCode? = null,
            message: String? = null,
            step: Step? = null,
            dump: String? = null,
        ) = MissionResult(
            missionId = mission.id,
            status = status,
            code = code,
            message = message,
            failedStep = step?.description,
            points = if (status == Status.SUCCESS) points else null,
            startedAtMs = startedAt,
            durationMs = clock.nowMs() - startedAt,
            screenDump = dump,
            log = logs.toList(),
        )
    }

    private class Finish(val status: Status) : RuntimeException()

    private class Abort(val status: Status, val code: FailureCode, message: String, val step: Step?) :
        RuntimeException(message)
}
