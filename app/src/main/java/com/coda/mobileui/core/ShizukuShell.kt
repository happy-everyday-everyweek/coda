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

    /** 本应用的无障碍服务组件名，供启停时精确定位。 */
    const val SELF_ACCESSIBILITY = "com.coda.mobileui/com.coda.mobileui.core.PhoneAccessibilityService"

    /** 合法的组件名：包名/类名，只允许字母数字下划线与点。 */
    private val COMPONENT = Regex("^[A-Za-z0-9_.]+/[A-Za-z0-9_.$]+$")

    /** 单条命令输出读取期限：settings/input 正常毫秒级返回，30s 已极宽裕。 */
    private const val EXEC_TIMEOUT_MS = 30_000L

    /** Shizuku.newProcess 的反射句柄；解析一次后复用（#384）。 */
    @Volatile
    private var newProcessCached: java.lang.reflect.Method? = null

    private fun newProcessMethod(): java.lang.reflect.Method =
        newProcessCached ?: synchronized(this) {
            newProcessCached ?: Shizuku::class.java
                .getMethod(
                    "newProcess",
                    Array<String>::class.java,
                    Array<String>::class.java,
                    String::class.java,
                ).also { newProcessCached = it }
        }

    fun exec(command: String): Result<String> {
        if (!isReady()) return Result.failure(IllegalStateException("Shizuku 未授权"))
        return runCatching {
            // 合并 stderr 后再读：避免两个管道各自缓冲导致 waitFor 卡死。
            // Shizuku 13.1.5 把这个静态方法标成库内可见，Kotlin 编译期不让直接调；
            // 它在运行时仍是公开方法，反射调用同一份实现，参数与返回类型不变。
            // exec 是 tap/swipe/key 的基础路径，每次都 getMethod 会在高频操作下
            // 反复做反射查找（#384）。方法对象只解析一次并缓存。
            val method = newProcessMethod()
            val process =
                method.invoke(
                    null,
                    arrayOf("sh", "-c", "$command 2>&1"),
                    arrayOf<String>(),
                    "/",
                ) as Process
            try {
                // OCR #819：readText/waitFor 都可能被挂死的远端命令卡住——tap/swipe/key
                // 是交互热路径，不能无限等。读输出放独立线程，主调用侧带期限收口；
                // 超时/中断都 destroy 进程并按失败返回，绝不把调用线程钉死。
                val outRef = java.util.concurrent.atomic.AtomicReference<String>("")
                val errRef = java.util.concurrent.atomic.AtomicReference<Throwable?>(null)
                val reader =
                    Thread({
                        try {
                            outRef.set(process.inputStream.bufferedReader().use { it.readText() })
                        } catch (t: Throwable) {
                            errRef.set(t)
                        }
                    }, "shizuku-exec-read").apply { isDaemon = true }
                reader.start()
                reader.join(EXEC_TIMEOUT_MS)
                if (reader.isAlive) {
                    runCatching { process.destroy() }
                    reader.join(500)
                    throw IllegalStateException("命令输出读取超时（${EXEC_TIMEOUT_MS / 1000}s），已终止")
                }
                errRef.get()?.let { throw it }
                val output = outRef.get()
                // waitFor 同样给期限：stdout EOF 后远端进程仍可能不退出。
                val waitDeadline = System.currentTimeMillis() + 5_000L
                while (process.isAlive && System.currentTimeMillis() < waitDeadline) {
                    Thread.sleep(20)
                }
                if (process.isAlive) {
                    runCatching { process.destroy() }
                    throw IllegalStateException("命令未在期限内退出，已终止：${output.trim().take(200)}")
                }
                val code = process.waitFor()
                // 退出码必须检查：settings/input 失败时同样是空输出，
                // 不看退出码就会把「没写进去」当成执行成功。
                if (code != 0) {
                    throw IllegalStateException("命令失败（退出码 $code）：${output.trim().take(200)}")
                }
                output.trim()
            } finally {
                // 管道不关会漏 FD，调用几百次后进程就开不出新管道了。
                runCatching { process.inputStream.close() }
                runCatching { process.errorStream.close() }
                runCatching { process.outputStream.close() }
                if (process.isAlive) runCatching { process.destroy() }
            }
        }
    }

    /** 当前已启用的无障碍服务列表（过滤掉非法项，避免把设置里的脏数据拼进命令）。 */
    private fun readEnabledAccessibility(): Result<List<String>> {
        val raw =
            exec("settings get secure enabled_accessibility_services")
                .getOrElse { return Result.failure(it) }
        return Result.success(
            raw.split(':')
                .map { it.trim() }
                .filter { it.isNotEmpty() && it != "null" && COMPONENT.matches(it) },
        )
    }

    /** 打开指定无障碍服务，参数形如 `com.coda.mobileui/com.coda.mobileui.XxxService`。 */
    fun enableAccessibility(service: String): Result<String> {
        if (!COMPONENT.matches(service)) {
            return Result.failure(IllegalArgumentException("非法的服务组件名：$service"))
        }
        // 直接 put 会覆盖整个列表，把用户在系统设置里开过的其它无障碍服务一起关掉；
        // 这里先读现有列表再追加，本应用已在列表里时不重复添加。
        // 读失败必须中止：把「读不到」当成「列表为空」会写掉用户已启用的其它服务。
        val list =
            readEnabledAccessibility().getOrElse {
                return Result.failure(IllegalStateException("读取无障碍服务列表失败，已放弃写入", it))
            }
        val merged = if (list.contains(service)) list else list + service
        return exec(
            // && 串联（#760）：分号只查末条退出码，服务列表写失败仍报成功。
            "settings put secure enabled_accessibility_services '${merged.joinToString(":")}' && " +
                "settings put secure accessibility_enabled 1",
        )
    }

    /**
     * 关掉本应用的无障碍服务。
     *
     * 只从列表里摘掉自己这一项：把 accessibility_enabled 置 0 会把用户开着的所有
     * 无障碍服务一起关掉（读屏、输入法等），影响远超本应用。
     */
    fun disableAccessibility(service: String = SELF_ACCESSIBILITY): Result<String> {
        if (!COMPONENT.matches(service)) {
            return Result.failure(IllegalArgumentException("非法的服务组件名：$service"))
        }
        val current =
            readEnabledAccessibility().getOrElse {
                return Result.failure(IllegalStateException("读取无障碍服务列表失败，已放弃写入", it))
            }
        val rest = current.filter { it != service }
        val command =
            if (rest.isEmpty()) {
                "settings put secure enabled_accessibility_services null && " +
                    "settings put secure accessibility_enabled 0"
            } else {
                "settings put secure enabled_accessibility_services '${rest.joinToString(":")}'"
            }
        return exec(command)
    }

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

    fun screenSize(): Pair<Int, Int>? {
        val text = exec("wm size").getOrNull() ?: return null
        // 有 Override size 时它才是当前生效的分辨率；按行序取第一条会拿到 Physical size，
        // 虚拟显示或改过分辨率的情况下坐标就会整体偏移。
        val line =
            text.lines().firstOrNull { it.contains("Override size") }
                ?: text.lines().firstOrNull { it.contains("Physical size") }
                ?: return null
        return line
            .substringAfter(':')
            .trim()
            .split('x')
            .takeIf { it.size == 2 }
            ?.let { (w, h) ->
                w.trim().toIntOrNull()?.let { ww -> h.trim().toIntOrNull()?.let { hh -> ww to hh } }
            }
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