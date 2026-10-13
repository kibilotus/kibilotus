package com.kibilotus.apptech.app

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Path
import android.graphics.Rect
import android.net.Uri
import android.view.accessibility.AccessibilityNodeInfo
import com.kibilotus.apptech.core.Bounds
import com.kibilotus.apptech.core.Driver
import com.kibilotus.apptech.core.UiNode
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.random.Random

/** AccessibilityService 기반 [Driver] 구현. 반드시 메인 스레드가 아닌 곳에서 호출할 것 */
class AccessibilityDriver(private val service: AccessibilityService) : Driver {

    override fun snapshot(): UiNode? {
        val root = service.rootInActiveWindow ?: return null
        val budget = intArrayOf(MAX_NODES)
        return convert(root, 0, budget)
    }

    private fun convert(info: AccessibilityNodeInfo, depth: Int, budget: IntArray): UiNode {
        budget[0]--
        val rect = Rect().also(info::getBoundsInScreen)
        val children = if (depth >= MAX_DEPTH) {
            emptyList()
        } else {
            (0 until info.childCount).mapNotNull { i ->
                if (budget[0] <= 0) null else info.getChild(i)?.let { convert(it, depth + 1, budget) }
            }
        }
        return UiNode(
            text = info.text?.toString(),
            desc = info.contentDescription?.toString(),
            viewId = info.viewIdResourceName,
            className = info.className?.toString(),
            clickable = info.isClickable,
            scrollable = info.isScrollable,
            enabled = info.isEnabled,
            visible = info.isVisibleToUser,
            bounds = Bounds(rect.left, rect.top, rect.right, rect.bottom),
            children = children,
            handle = info,
        )
    }

    override fun foregroundPackage(): String? = service.rootInActiveWindow?.packageName?.toString()

    override fun launch(packageName: String, deepLink: String?): Boolean {
        val intent = if (deepLink != null) {
            Intent(Intent.ACTION_VIEW, Uri.parse(deepLink)).setPackage(packageName)
        } else {
            service.packageManager.getLaunchIntentForPackage(packageName)
        } ?: return false
        // 이전에 보던 화면이 아니라 항상 앱 첫 화면부터 시작해야 시나리오가 결정적이다
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        return try {
            service.startActivity(intent)
            true
        } catch (e: Exception) {
            false
        }
    }

    override fun tap(node: UiNode, x: Int, y: Int): Boolean {
        // 실제 터치 제스처 우선 (WebView 요소는 ACTION_CLICK이 안 먹는 경우가 많음)
        val path = Path().apply { moveTo(x.toFloat(), y.toFloat()) }
        val duration = Random.nextLong(40, 120)
        if (dispatch(GestureDescription.StrokeDescription(path, 0, duration))) return true
        return (node.handle as? AccessibilityNodeInfo)?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true
    }

    override fun scrollForward(): Boolean {
        // 가장 큰 스크롤 가능 영역에 스크롤 액션
        val root = snapshot()
        val scrollable = root?.walk()
            ?.filter { it.scrollable && it.visible }
            ?.maxByOrNull { it.bounds.width.toLong() * it.bounds.height }
        val info = scrollable?.handle as? AccessibilityNodeInfo
        if (info?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD) == true) return true

        // 폴백: 손가락 스와이프
        val dm = service.resources.displayMetrics
        val x = dm.widthPixels * Random.nextDouble(0.4, 0.6)
        val path = Path().apply {
            moveTo(x.toFloat(), (dm.heightPixels * 0.75).toFloat())
            lineTo((x + Random.nextInt(-20, 20)).toFloat(), (dm.heightPixels * 0.30).toFloat())
        }
        return dispatch(GestureDescription.StrokeDescription(path, 0, Random.nextLong(250, 450)))
    }

    override fun back() = service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)

    override fun home() = service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME)

    /** 제스처를 보내고 완료/취소될 때까지 블로킹 대기 */
    private fun dispatch(stroke: GestureDescription.StrokeDescription): Boolean {
        val latch = CountDownLatch(1)
        var completed = false
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        val accepted = service.dispatchGesture(gesture, object : AccessibilityService.GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                completed = true
                latch.countDown()
            }

            override fun onCancelled(gestureDescription: GestureDescription?) = latch.countDown()
        }, null)
        if (!accepted) return false
        latch.await(3, TimeUnit.SECONDS)
        return completed
    }

    private companion object {
        const val MAX_DEPTH = 80
        const val MAX_NODES = 4_000
    }
}
