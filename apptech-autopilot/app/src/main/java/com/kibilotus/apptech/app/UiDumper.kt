package com.kibilotus.apptech.app

import android.accessibilityservice.AccessibilityService
import android.graphics.Bitmap
import android.os.Build
import android.view.Display
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * 셀렉터 보정용 화면 덤프.
 * 토스 화면 트리를 텍스트로 저장한다. 잔액/계좌번호 노출을 줄이기 위해 4자리 이상 숫자는 마스킹한다.
 */
class UiDumper(private val service: AccessibilityService, private val dir: File) {

    data class Dump(val textFile: File, val text: String, val screenshot: File?, val screenshotError: String?)

    fun dump(tag: String): Dump? {
        val root = AccessibilityDriver(service).snapshot() ?: return null
        val pkg = service.rootInActiveWindow?.packageName
        val text = buildString {
            append("# package=").append(pkg)
            append(" device=").append(Build.MANUFACTURER).append(' ').append(Build.MODEL)
            append(" sdk=").append(Build.VERSION.SDK_INT).append('\n')
            append("# flags: C=clickable S=scrollable D=disabled, [left,top][right,bottom]\n")
            append(mask(root.dump()))
        }
        dir.mkdirs()
        val name = "${tag}_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())}"
        val textFile = File(dir, "$name.txt").apply { writeText(text) }
        val (shot, error) = screenshot(File(dir, "$name.png"))
        return Dump(textFile, text, shot, error)
    }

    /**
     * 토스는 FLAG_SECURE로 캡처를 막을 가능성이 높다(검은 화면/실패).
     * 그 경우에도 텍스트 트리 덤프만으로 보정이 가능하다.
     */
    private fun screenshot(out: File): Pair<File?, String?> {
        val latch = CountDownLatch(1)
        var result: Pair<File?, String?> = null to "timeout"
        val executor = Executors.newSingleThreadExecutor()
        service.takeScreenshot(Display.DEFAULT_DISPLAY, executor, object : AccessibilityService.TakeScreenshotCallback {
            override fun onSuccess(screenshot: AccessibilityService.ScreenshotResult) {
                result = try {
                    val hw = Bitmap.wrapHardwareBuffer(screenshot.hardwareBuffer, screenshot.colorSpace)
                    val bmp = hw?.copy(Bitmap.Config.ARGB_8888, false)
                    screenshot.hardwareBuffer.close()
                    if (bmp == null) {
                        null to "bitmap null"
                    } else {
                        out.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 90, it) }
                        out to null
                    }
                } catch (e: Exception) {
                    null to e.toString()
                }
                latch.countDown()
            }

            override fun onFailure(errorCode: Int) {
                result = null to "takeScreenshot error=$errorCode (FLAG_SECURE 가능성)"
                latch.countDown()
            }
        })
        latch.await(5, TimeUnit.SECONDS)
        executor.shutdown()
        return result
    }

    companion object {
        private val longNumber = Regex("""\d[\d,\-]{2,}\d""")

        fun mask(s: String): String = longNumber.replace(s) { m ->
            // 좌표([0,0][1080,2400])는 마스킹하지 않도록 대괄호 안 숫자는 유지
            val before = s.getOrNull(m.range.first - 1)
            if (before == '[' || before == ',') m.value else "#".repeat(m.value.length)
        }
    }
}
