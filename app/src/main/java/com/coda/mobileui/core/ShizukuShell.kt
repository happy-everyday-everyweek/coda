package com.coda.mobileui.core

import android.content.pm.PackageManager
import rikka.shizuku.Shizuku

/**
 * 借 Shizuku 的 shell 身份做系统级操作。
 *
 * 这里只做三件基础事：申请授权、执行命令、把常用屏幕操作包成短函数。
 * 无障碍开关与虚拟窗口都建立在 [exec] 之上；未授权时统一返回失败，不抛异常。
 */
object ShizukuShell {
    const val REQUEST_CODE = 4217

    fun available(): Boolean = runCatching { Shizuku.pingBinder() }.getOrDefault(false)

    fun isReady(): Boolean =
        available() &&
            runCatching { Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED }
                .getOrDefault(false)

    /** 申请授权；Shizuku 自己会弹确认框，这里不重复解释。 */
    fun requestPermission() {
        if (!available() || isReady()) return
        runCatching { Shizuku.requestPermission(REQUEST_CODE) }
    }

    /** 把 Activity 的授权结果转成布尔值，供调用方继续执行。 */
    fun handlePermissionResult(
        requestCode: Int,
        grantResults: IntArray,
    ): Boolean =
        requestCode == REQUEST_CODE &&
            grantResults.isNotEmpty() &&
            grantResults[0] == PackageManager.PERMISSION_GRANTED

    fun exec(command: String): Result<String> {
        if (!isReady()) return Result.failure(IllegalStateException("Shizuku 未授权"))
        return runCatching {
            // 合并 stderr 后再读：避免两个管道各自缓冲导致 waitFor 卡死。
            val process = Shizuku.newProcess(arrayOf("sh", "-c", "$command 2>&1"), null, null)
            val output = process.inputStream.bufferedReader().readText()
            process.waitFor()
            output.trim()
        }
    }

    /** 打开指定无障碍服务，参数形如 `com.coda.mobileui/com.coda.mobileui.XxxService`。 */
    fun enableAccessibility(service: String): Result<String> =
        exec(
            "settings put secure enabled_accessibility_services $service; " +
                "settings put secure accessibility_enabled 1",
        )

    fun disableAccessibility(): Result<String> =
        exec("settings put secure accessibility_enabled 0")

    fun tap(
        x: Int,
        y: Int,
    ): Result<String> = exec("input tap $x $y")

    fun swipe(
        x1: Int,
        y1: Int,
        x2: Int,
        y2: Int,
        durationMs: Int = 240,
    ): Result<String> = exec("input swipe $x1 $y1 $x2 $y2 $durationMs")

    fun key(code: Int): Result<String> = exec("input keyevent $code")

    fun screenSize(): Pair<Int, Int>? =
        exec("wm size")
            .getOrNull()
            ?.lines()
            ?.firstOrNull { it.contains("Override size") || it.contains("Physical size") }
            ?.substringAfter(':')
            ?.trim()
            ?.split('x')
            ?.takeIf { it.size == 2 }
            ?.let { (w, h) ->
                w.trim().toIntOrNull()?.let { ww -> h.trim().toIntOrNull()?.let { hh -> ww to hh } }
            }

    /**
     * 起一块虚拟副屏（系统自带的「模拟副屏」开关），spec 形如 `1280x720/160`。
     *
     * 副屏起来后可以用 [launchOnDisplay] 把某个组件放上去，配合 tap/swipe 做 GUI 操作，
     * 主屏保持不被占用。
     */
    fun enableVirtualDisplay(spec: String = "1280x720/160"): Result<String> =
        exec("settings put global overlay_display_devices \"$spec\"")

    fun disableVirtualDisplay(): Result<String> =
        exec("settings put global overlay_display_devices null")

    /** 在指定显示上启动组件，component 形如 `com.example/.MainActivity`。 */
    fun launchOnDisplay(
        component: String,
        displayId: Int = 1,
    ): Result<String> = exec("am start --display $displayId -n $component")
}