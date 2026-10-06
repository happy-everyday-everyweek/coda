package com.coda.mobileui.core

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.os.Handler
import android.os.Looper
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.UUID
import kotlin.math.floor
import kotlin.math.max

/** 定时任务业务错误（应答端会将 message 回传给内核）。 */
class AutomationException(message: String) : Exception(message)

/**
 * 调度计算层：标准五段 cron 解析与下次触发时间、scheduleRule（自定义重复规则）推进。
 * 语义与桌面端一致：cron 仅用于「解析 + 算下次时间」，调度循环由本地 tick 完成。
 */
object AutomationSchedule {

    class CronField(val set: BooleanArray, val star: Boolean, val min: Int)

    class CronExpr(
        val minute: CronField,
        val hour: CronField,
        val dom: CronField,
        val month: CronField,
        val dow: CronField,
    )

    private val ws = Regex("\\s+")
    private val fixedCalendar = Regex("^\\d+\\s+\\d+\\s+\\d+\\s+\\d+\\s+\\*$")

    /** 解析标准五段 cron；不合法返回 null。 */
    fun parseCron(expr: String): CronExpr? {
        val parts = expr.trim().split(ws)
        if (parts.size < 5) return null
        val minute = parseField(parts[0], 0, 59) ?: return null
        val hour = parseField(parts[1], 0, 23) ?: return null
        val dom = parseField(parts[2], 1, 31) ?: return null
        val month = parseField(parts[3], 1, 12) ?: return null
        val dow = parseField(parts[4], 0, 7) ?: return null
        if (dow.set.size > 7 && dow.set[7]) dow.set[0] = true
        return CronExpr(minute, hour, dom, month, dow)
    }

    private fun parseField(seg: String, min: Int, max: Int): CronField? {
        val set = BooleanArray(max + 1)
        if (seg == "*" || seg == "?") {
            for (i in min..max) set[i] = true
            return CronField(set, true, min)
        }
        for (part in seg.split(',')) {
            if (part.isEmpty()) return null
            var body = part
            var step = 1
            val slash = part.indexOf('/')
            if (slash >= 0) {
                body = part.substring(0, slash)
                step = part.substring(slash + 1).toIntOrNull() ?: return null
                if (step <= 0) return null
            }
            var lo: Int
            var hi: Int
            if (body == "*" || body == "?") {
                lo = min
                hi = max
            } else {
                val dash = body.indexOf('-')
                if (dash >= 0) {
                    lo = body.substring(0, dash).toIntOrNull() ?: return null
                    hi = body.substring(dash + 1).toIntOrNull() ?: return null
                } else {
                    lo = body.toIntOrNull() ?: return null
                    hi = if (slash >= 0) max else lo
                }
            }
            if (lo < min || hi > max || lo > hi) return null
            var v = lo
            while (v <= hi) {
                set[v] = true
                v += step
            }
        }
        var any = false
        var i = min
        while (i <= max) {
            if (set[i]) {
                any = true
                break
            }
            i++
        }
        return if (any) CronField(set, false, min) else null
    }

    private fun dayMatches(e: CronExpr, date: LocalDate): Boolean {
        val domMatch = e.dom.set[date.dayOfMonth]
        val dowIdx = date.dayOfWeek.value % 7
        val dowMatch = e.dow.set[dowIdx]
        return when {
            e.dom.star && e.dow.star -> true
            e.dom.star -> dowMatch
            e.dow.star -> domMatch
            else -> domMatch || dowMatch
        }
    }

    /** cron 的下一次触发（严格晚于 from）；无未来触发返回 null。 */
    fun computeCronNext(expr: String, from: Long): Long? {
        val e = parseCron(expr) ?: return null
        val zone = ZoneId.systemDefault()
        var t = LocalDateTime.ofInstant(Instant.ofEpochMilli(from / 60_000L * 60_000L + 60_000L), zone)
        var guard = 0
        while (guard++ < 400_000) {
            if (!e.month.set[t.monthValue]) {
                t = t.plusMonths(1).withDayOfMonth(1).withHour(0).withMinute(0).withSecond(0).withNano(0)
                continue
            }
            if (!dayMatches(e, t.toLocalDate())) {
                t = t.plusDays(1).withHour(0).withMinute(0).withSecond(0).withNano(0)
                continue
            }
            if (!e.hour.set[t.hour]) {
                t = t.plusHours(1).withMinute(0).withSecond(0).withNano(0)
                continue
            }
            if (!e.minute.set[t.minute]) {
                t = t.plusMinutes(1).withSecond(0).withNano(0)
                continue
            }
            return t.atZone(zone).toInstant().toEpochMilli()
        }
        return null
    }

    /** 解析 scheduleRule JSON（库内 schedule_rule 列）。 */
    fun parseRule(json: String?): Rule? {
        if (json.isNullOrBlank() || json == "null") return null
        return try {
            val o = JSONObject(json)
            val unit = o.optString("unit")
            if (unit.isEmpty()) return null
            Rule(
                unit = unit,
                interval = max(1, o.optInt("interval", 1)),
                hour = o.optInt("hour", 0),
                minute = o.optInt("minute", 0),
                weekdays = intListOf(o.optJSONArray("weekdays")),
                monthDays = intListOf(o.optJSONArray("monthDays")),
                months = intListOf(o.optJSONArray("months")),
                monthlyMode = o.optString("monthlyMode").takeIf { it.isNotEmpty() },
                anchorAt = o.optLong("anchorAt", 0L),
            )
        } catch (_: Throwable) {
            null
        }
    }

    private fun intListOf(a: JSONArray?): List<Int>? {
        if (a == null) return null
        val out = mutableListOf<Int>()
        for (i in 0 until a.length()) out += a.optInt(i)
        return out.ifEmpty { null }
    }

    class Rule(
        val unit: String,
        val interval: Int,
        val hour: Int,
        val minute: Int,
        val weekdays: List<Int>?,
        val monthDays: List<Int>?,
        val months: List<Int>?,
        val monthlyMode: String?,
        val anchorAt: Long,
    )

    /** 自定义重复规则的下一次运行（本地日历时间）。 */
    fun computeRuleNext(rule: Rule, from: Long): Long? {
        val zone = ZoneId.systemDefault()
        val interval = max(1, rule.interval)
        val anchor = Instant.ofEpochMilli(rule.anchorAt).atZone(zone)
        when (rule.unit) {
            "minute" -> {
                val step = interval * 60_000L
                val steps = max(1L, floor((from - rule.anchorAt).toDouble() / step).toLong() + 1)
                return rule.anchorAt + steps * step
            }
            "hourly" -> {
                val base = anchor.toLocalDateTime().withMinute(rule.minute).withSecond(0).withNano(0)
                val step = interval * 3_600_000L
                val steps = max(0L, floor((from - base.atZone(zone).toInstant().toEpochMilli()).toDouble() / step).toLong() + 1)
                return base.atZone(zone).toInstant().toEpochMilli() + steps * step
            }
            "daily" -> {
                for (index in 0 until 36_600) {
                    val date = anchor.toLocalDate().plusDays(index.toLong() * interval)
                    val candidate = atTime(date, rule.hour, rule.minute, zone)
                    if (candidate > from) return candidate
                }
                return null
            }
            "weekly" -> {
                var anchorWeek = anchor.toLocalDate()
                val dowSun0 = anchorWeek.dayOfWeek.value % 7
                anchorWeek = anchorWeek.minusDays(((dowSun0 + 6) % 7).toLong())
                val weekdays = (rule.weekdays?.takeIf { it.isNotEmpty() } ?: listOf(1)).sorted()
                var week = 0
                while (week < 5_220) {
                    for (weekday in weekdays) {
                        val dayOffset = (weekday + 6) % 7
                        val date = anchorWeek.plusDays((week * 7 + dayOffset).toLong())
                        val candidate = atTime(date, rule.hour, rule.minute, zone)
                        if (candidate > from) return candidate
                    }
                    week += interval
                }
                return null
            }
            "monthly" -> {
                var offset = 0
                while (offset <= 1_200) {
                    val monthBase = LocalDate.of(anchor.year, anchor.monthValue, 1).plusMonths(offset.toLong())
                    val candidates: List<LocalDate> = if (rule.monthlyMode == "weekday") {
                        listOf(firstWeekdayOfMonth(monthBase.year, monthBase.monthValue, rule.weekdays?.firstOrNull() ?: 1))
                    } else {
                        (rule.monthDays?.takeIf { it.isNotEmpty() } ?: listOf(1)).sorted().mapNotNull { day ->
                            val d = monthBase.plusDays((day - 1).toLong())
                            if (d.monthValue == monthBase.monthValue) d else null
                        }
                    }
                    for (d in candidates) {
                        val candidate = atTime(d, rule.hour, rule.minute, zone)
                        if (candidate > from) return candidate
                    }
                    offset += interval
                }
                return null
            }
            "yearly" -> {
                val targetMonth = rule.months?.firstOrNull()?.let { (((it - 1) % 12) + 12) % 12 } ?: (anchor.monthValue - 1)
                val targetDay = rule.monthDays?.firstOrNull() ?: anchor.dayOfMonth
                var offset = 0
                while (offset < 400) {
                    val year = anchor.year + offset
                    val d = LocalDate.of(year, 1, 1).plusMonths(targetMonth.toLong()).plusDays((targetDay - 1).toLong())
                    if (d.monthValue - 1 != targetMonth) {
                        offset += interval
                        continue
                    }
                    val candidate = atTime(d, rule.hour, rule.minute, zone)
                    if (candidate > from) return candidate
                    offset += interval
                }
                return null
            }
        }
        return null
    }

    private fun firstWeekdayOfMonth(year: Int, month: Int, weekday: Int): LocalDate {
        val first = LocalDate.of(year, month, 1)
        val firstDow = first.dayOfWeek.value % 7
        return first.plusDays(((weekday - firstDow + 7) % 7).toLong())
    }

    private fun atTime(date: LocalDate, hour: Int, minute: Int, zone: ZoneId): Long =
        date.atTime(hour, minute).atZone(zone).toInstant().toEpochMilli()

    /** 统一入口：有 scheduleRule 用规则推进，否则回退 cron 解析。 */
    fun computeNext(cronExpr: String, rule: Rule?, from: Long): Long? =
        if (rule != null) computeRuleNext(rule, from) else computeCronNext(cronExpr, from)

    /**
     * 新建任务的首次触发。一次性固定月日 cron 刚错过目标分钟（小于 1 分钟）立即补执行；
     * 已陈旧（超过 1 分钟、30 分钟窗口内）的目标视为模型算错，拒绝并提示改用相对延迟。
     */
    fun computeInitialNext(cronExpr: String, rule: Rule?, oneShot: Boolean, from: Long): Long? {
        val next = if (rule != null) computeRuleNext(rule, from) else computeCronNext(cronExpr, from)
        if (!oneShot || rule != null) return next
        if (!fixedCalendar.matches(cronExpr.trim())) return next
        val firedAt = computeCronNext(cronExpr, from - 30 * 60_000L)
        val targetAge = if (firedAt != null && firedAt <= from) from - firedAt else null
        val rolledFar = next == null || next - from > 30 * 60_000L
        if (targetAge != null && rolledFar) {
            if (targetAge < 60_000L) return from
            throw AutomationException(
                "一次性定时任务的目标时间已过去；相对时间请使用 relativeDelayMinutes，绝对时间请确认未来时刻后重试",
            )
        }
        return next
    }

    /** 「每 N 单位」carrier（intervalUnit + interval）归一化为权威 scheduleRule。 */
    fun buildIntervalRule(unit: String, interval: Int, cronExpr: String, anchorAt: Long): JSONObject {
        val parts = cronExpr.trim().split(ws)
        fun single(idx: Int): Int? {
            if (idx >= parts.size) return null
            val s = parts[idx]
            if (s == "*" || s == "?") return null
            return s.toIntOrNull()
        }
        fun list(idx: Int): List<Int>? {
            if (idx >= parts.size) return null
            val s = parts[idx]
            if (s == "*" || s == "?") return null
            val v = s.split(',').mapNotNull { it.toIntOrNull() }
            return v.ifEmpty { null }
        }
        val zone = ZoneId.systemDefault()
        val anchor = Instant.ofEpochMilli(anchorAt).atZone(zone)
        val minute = single(0) ?: anchor.minute
        val hour = single(1) ?: anchor.hour
        val monthDays = list(2) ?: listOf(anchor.dayOfMonth)
        val months = list(3) ?: listOf(anchor.monthValue)
        val weekdays = list(4) ?: listOf(1)
        val o = JSONObject().put("interval", interval).put("anchorAt", anchorAt)
        when (unit) {
            "minute" -> o.put("unit", "minute").put("hour", anchor.hour).put("minute", anchor.minute)
            "hourly" -> o.put("unit", "hourly").put("hour", 0).put("minute", minute)
            "daily" -> o.put("unit", "daily").put("hour", hour).put("minute", minute)
            "weekly" -> o.put("unit", "weekly").put("hour", hour).put("minute", minute)
                .put("weekdays", JSONArray(weekdays))
            "monthly" -> o.put("unit", "monthly").put("hour", hour).put("minute", minute)
                .put("monthDays", JSONArray(monthDays)).put("monthlyMode", "date")
            "yearly" -> o.put("unit", "yearly").put("hour", hour).put("minute", minute)
                .put("months", JSONArray(months)).put("monthDays", JSONArray(monthDays))
            else -> throw AutomationException("不支持的 intervalUnit: $unit")
        }
        return o
    }
}

/**
 * automations 表的本地读写层。与内核共用 tasks-index.sqlite；
 * 写入仅在应答内核请求与本地调度时发生，读取全部经由本类。
 */
class AutomationStore(private val ctx: Context) {

    @Volatile
    private var db: SQLiteDatabase? = null

    private val schemaStatements = listOf(
        "CREATE TABLE IF NOT EXISTS automations (" +
            "automation_id TEXT PRIMARY KEY, title TEXT NOT NULL DEFAULT '', cron_expr TEXT NOT NULL, " +
            "prompt TEXT NOT NULL, model TEXT, provider TEXT, mode TEXT, thought_level TEXT, " +
            "model_selection TEXT, workspace_key TEXT NOT NULL, workspace_path TEXT NOT NULL, " +
            "workspace_identity TEXT, target_task_id TEXT, bot_delivery_target TEXT, " +
            "location_kind TEXT NOT NULL DEFAULT 'local', recurring INTEGER NOT NULL DEFAULT 1, " +
            "max_runs INTEGER, end_at INTEGER, schedule_rule TEXT, " +
            "schedule_edited_by_user INTEGER NOT NULL DEFAULT 0, run_count INTEGER NOT NULL DEFAULT 0, " +
            "scheduled_run_count INTEGER NOT NULL DEFAULT 0, enabled INTEGER NOT NULL DEFAULT 1, " +
            "lifecycle_status TEXT NOT NULL DEFAULT 'active', next_run_at INTEGER, last_run_at INTEGER, " +
            "running INTEGER NOT NULL DEFAULT 0, claimed_at INTEGER, " +
            "dispatch_status TEXT NOT NULL DEFAULT 'idle', dispatch_attempts INTEGER NOT NULL DEFAULT 0, " +
            "retry_at INTEGER, last_error TEXT, created_at INTEGER NOT NULL, updated_at INTEGER NOT NULL)",
        "CREATE INDEX IF NOT EXISTS idx_automations_due ON automations (enabled, next_run_at)",
        "CREATE INDEX IF NOT EXISTS idx_automations_retry ON automations (enabled, retry_at)",
        "CREATE INDEX IF NOT EXISTS idx_automations_workspace ON automations (workspace_key)",
    )

    private fun open(): SQLiteDatabase? {
        db?.let { if (it.isOpen) return it }
        synchronized(this) {
            db?.let { if (it.isOpen) return it }
            return try {
                val f = File(ctx.filesDir, "zcode-data/.zcode/v2/tasks-index.sqlite")
                f.parentFile?.mkdirs()
                val d = SQLiteDatabase.openDatabase(
                    f.absolutePath,
                    null,
                    SQLiteDatabase.OPEN_READWRITE or SQLiteDatabase.CREATE_IF_NECESSARY,
                )
                try {
                    d.execSQL("PRAGMA busy_timeout=5000")
                } catch (_: Throwable) {
                }
                for (stmt in schemaStatements) {
                    try {
                        d.execSQL(stmt)
                    } catch (_: Throwable) {
                    }
                }
                db = d
                d
            } catch (_: Throwable) {
                null
            }
        }
    }

    private fun rowToJson(c: Cursor): JSONObject {
        val o = JSONObject()
        for (i in 0 until c.columnCount) {
            val name = c.getColumnName(i)
            when (c.getType(i)) {
                Cursor.FIELD_TYPE_NULL -> {
                }
                Cursor.FIELD_TYPE_INTEGER -> o.put(name, c.getLong(i))
                Cursor.FIELD_TYPE_FLOAT -> o.put(name, c.getDouble(i))
                else -> o.put(name, c.getString(i))
            }
        }
        return o
    }

    fun insert(values: ContentValues): Boolean {
        val d = open() ?: return false
        return try {
            d.insertWithOnConflict("automations", null, values, SQLiteDatabase.CONFLICT_REPLACE) != -1L
        } catch (_: Throwable) {
            false
        }
    }

    fun getRow(id: String): JSONObject? {
        val d = open() ?: return null
        return try {
            d.rawQuery("SELECT * FROM automations WHERE automation_id = ?", arrayOf(id)).use { c ->
                if (c.moveToNext()) rowToJson(c) else null
            }
        } catch (_: Throwable) {
            null
        }
    }

    fun listRows(): List<JSONObject> {
        val d = open() ?: return emptyList()
        val out = mutableListOf<JSONObject>()
        try {
            d.rawQuery("SELECT * FROM automations ORDER BY created_at DESC", null).use { c ->
                while (c.moveToNext()) out += rowToJson(c)
            }
        } catch (_: Throwable) {
        }
        return out
    }

    fun update(id: String, values: ContentValues): Boolean {
        val d = open() ?: return false
        return try {
            d.update("automations", values, "automation_id = ?", arrayOf(id)) > 0
        } catch (_: Throwable) {
            false
        }
    }

    fun delete(id: String): Boolean {
        val d = open() ?: return false
        return try {
            d.delete("automations", "automation_id = ?", arrayOf(id)) > 0
        } catch (_: Throwable) {
            false
        }
    }

    fun checkBinding(workspaceKey: String, targetTaskId: String): Boolean {
        val d = open() ?: return false
        return try {
            d.rawQuery(
                "SELECT COUNT(*) FROM automations WHERE workspace_key = ? AND target_task_id = ?",
                arrayOf(workspaceKey, targetTaskId),
            ).use { c -> c.moveToNext() && c.getLong(0) > 0 }
        } catch (_: Throwable) {
            false
        }
    }

    /** 到期任务：启用、未运行、生命周期 active，且已到 next_run_at 或重试时刻。 */
    fun listDue(now: Long): List<JSONObject> {
        val d = open() ?: return emptyList()
        val out = mutableListOf<JSONObject>()
        try {
            d.rawQuery(
                "SELECT * FROM automations WHERE enabled = 1 AND running = 0 AND lifecycle_status = 'active' AND " +
                    "((next_run_at IS NOT NULL AND next_run_at <= ?) OR (retry_at IS NOT NULL AND retry_at <= ?))",
                arrayOf(now.toString(), now.toString()),
            ).use { c ->
                while (c.moveToNext()) out += rowToJson(c)
            }
        } catch (_: Throwable) {
        }
        return out
    }

    /** 原子认领：仅当 running = 0 时置为运行中。 */
    fun claim(id: String, now: Long): Boolean {
        val d = open() ?: return false
        return try {
            val st = d.compileStatement(
                "UPDATE automations SET running = 1, claimed_at = ?, updated_at = ? WHERE automation_id = ? AND running = 0",
            )
            try {
                st.bindLong(1, now)
                st.bindLong(2, now)
                st.bindString(3, id)
                st.executeUpdateDelete() > 0
            } finally {
                st.close()
            }
        } catch (_: Throwable) {
            false
        }
    }

    /** 运行完成结算：计数 +1，清运行态；nextRunAt 为 null 时进入 completed 终态。 */
    fun settle(id: String, now: Long, nextRunAt: Long?) {
        val d = open() ?: return
        try {
            if (nextRunAt == null) {
                d.execSQL(
                    "UPDATE automations SET run_count = run_count + 1, scheduled_run_count = scheduled_run_count + 1, " +
                        "last_run_at = ?, running = 0, claimed_at = NULL, retry_at = NULL, dispatch_status = 'idle', " +
                        "dispatch_attempts = 0, last_error = NULL, next_run_at = NULL, lifecycle_status = 'completed', " +
                        "enabled = 0, updated_at = ? WHERE automation_id = ?",
                    arrayOf<Any?>(now, now, id),
                )
            } else {
                d.execSQL(
                    "UPDATE automations SET run_count = run_count + 1, scheduled_run_count = scheduled_run_count + 1, " +
                        "last_run_at = ?, running = 0, claimed_at = NULL, retry_at = NULL, dispatch_status = 'idle', " +
                        "dispatch_attempts = 0, last_error = NULL, next_run_at = ?, lifecycle_status = 'active', " +
                        "updated_at = ? WHERE automation_id = ?",
                    arrayOf<Any?>(now, nextRunAt, now, id),
                )
            }
        } catch (_: Throwable) {
        }
    }

    /** 派发失败：记录错误，最多重试 3 次（5 分钟退避）。 */
    fun fail(id: String, message: String, now: Long) {
        val d = open() ?: return
        try {
            var attempts = 0L
            d.rawQuery("SELECT dispatch_attempts FROM automations WHERE automation_id = ?", arrayOf(id)).use { c ->
                if (c.moveToNext()) attempts = c.getLong(0)
            }
            val attemptsAfter = attempts + 1
            val retryAt: Long? = if (attemptsAfter < 3) now + 300_000L else null
            d.execSQL(
                "UPDATE automations SET running = 0, claimed_at = NULL, dispatch_status = 'failed_to_dispatch', " +
                    "dispatch_attempts = ?, retry_at = ?, last_error = ?, updated_at = ? WHERE automation_id = ?",
                arrayOf<Any?>(attemptsAfter, retryAt, message.take(500), now, id),
            )
        } catch (_: Throwable) {
        }
    }

    /** 进程重启后清理僵死的运行标记。 */
    fun resetStaleRunning() {
        val d = open() ?: return
        try {
            d.execSQL("UPDATE automations SET running = 0, claimed_at = NULL WHERE running = 1")
        } catch (_: Throwable) {
        }
    }

    fun setEnabled(id: String, enabled: Boolean, nextRunAt: Long?, now: Long): Boolean {
        val cv = ContentValues()
        cv.put("enabled", if (enabled) 1 else 0)
        if (enabled) {
            if (nextRunAt != null) cv.put("next_run_at", nextRunAt)
            cv.put("lifecycle_status", "active")
        } else {
            cv.put("lifecycle_status", "paused")
        }
        cv.put("updated_at", now)
        return update(id, cv)
    }
}

/**
 * automation 反向请求应答端（内核工具 CronCreate/CronList 等的落地实现）。
 * 五个方法均为「内核向客户端发起」，在此完成校验、落库与应答。
 */
object AutomationHost {

    @Volatile
    private var inst: AutomationHost? = null

    fun get(ctx: Context): AutomationHost =
        inst ?: synchronized(this) {
            inst ?: AutomationHost.also { inst = it }
        }

    /** 返回 true 表示该请求已受理（异步应答，勿在调用线程等待）。 */
    fun handleReverse(
        rt: CoreRuntime,
        ctrl: ZController,
        id: Any,
        method: String,
        params: JSONObject,
    ): Boolean {
        if (!method.startsWith("automation/")) return false
        Thread({
            try {
                when (method) {
                    "automation/create" -> {
                        val a = create(ctrl, params)
                        rt.respond(id, JSONObject().put("automation", a))
                    }
                    "automation/list" -> {
                        val arr = JSONArray()
                        list(ctrl).forEach { arr.put(it) }
                        rt.respond(id, JSONObject().put("automations", arr))
                    }
                    "automation/update" -> {
                        val a = update(ctrl, params)
                            ?: throw AutomationException("Scheduled task not found in the current workspace.")
                        rt.respond(id, JSONObject().put("automation", a))
                    }
                    "automation/delete" -> {
                        rt.respond(id, JSONObject().put("deleted", delete(ctrl, params)))
                    }
                    "automation/checkTaskBinding" -> {
                        rt.respond(id, JSONObject().put("bound", checkBinding(ctrl, params)))
                    }
                    else -> rt.respond(id, JSONObject())
                }
            } catch (e: AutomationException) {
                rt.respondError(id, -32603, e.message ?: "automation error")
            } catch (t: Throwable) {
                rt.respondError(id, -32603, t.toString())
            }
        }, "coda-automation-reverse").start()
        return true
    }

    // ------------------------------------------------------------ 各方法实现

    fun create(ctrl: ZController, params: JSONObject): JSONObject {
        val now = System.currentTimeMillis()
        val title = params.optString("title", "")
        val prompt = params.optString("prompt").trim()
        if (prompt.isEmpty()) throw AutomationException("prompt is required")
        var cronExpr = params.optString("cronExpr").trim()
        if (cronExpr.isEmpty()) throw AutomationException("cronExpr is required")
        if (AutomationSchedule.parseCron(cronExpr) == null) throw AutomationException("无效的 cron 表达式: $cronExpr")
        val relativeDelayMinutes =
            if (params.has("relativeDelayMinutes") && !params.isNull("relativeDelayMinutes")) params.optInt("relativeDelayMinutes") else null
        val intervalUnit = params.optString("intervalUnit").takeIf { it.isNotEmpty() }
        val interval = if (params.has("interval") && !params.isNull("interval")) params.optInt("interval") else null
        if ((intervalUnit == null) != (interval == null)) throw AutomationException("intervalUnit 与 interval 必须配对")
        if (relativeDelayMinutes != null && intervalUnit != null) {
            throw AutomationException("relativeDelayMinutes 不能与 intervalUnit 同传")
        }
        if (interval != null && (interval < 1 || interval > 200)) throw AutomationException("interval 必须在 1-200 之间")
        val mode = params.optString("mode").takeIf { it.isNotEmpty() }
        val targetTaskId = params.optString("targetTaskId").takeIf { it.isNotEmpty() }
        val modelSelectionJson = params.optJSONObject("modelSelection")?.toString()
        val recurring = if (params.has("recurring")) params.optBoolean("recurring", true) else true
        val maxRuns = if (params.has("maxRuns") && !params.isNull("maxRuns")) params.optInt("maxRuns") else null
        val botDeliveryTarget = params.optJSONObject("botDeliveryTarget")?.toString()

        var ruleJson: String? = null
        if (relativeDelayMinutes != null) {
            val zone = ZoneId.systemDefault()
            val target = Instant.ofEpochMilli(now + relativeDelayMinutes * 60_000L).atZone(zone)
            cronExpr = "${target.minute} ${target.hour} ${target.dayOfMonth} ${target.monthValue} *"
            ruleJson = JSONObject()
                .put("unit", "minute")
                .put("interval", relativeDelayMinutes)
                .put("hour", target.hour)
                .put("minute", target.minute)
                .put("anchorAt", now)
                .toString()
        } else if (intervalUnit != null && interval != null) {
            ruleJson = AutomationSchedule.buildIntervalRule(intervalUnit, interval, cronExpr, now).toString()
        }

        val rule = AutomationSchedule.parseRule(ruleJson)
        val oneShot = !recurring && (maxRuns ?: 1) <= 1
        val nextRunAt = AutomationSchedule.computeInitialNext(cronExpr, rule, oneShot, now)

        val wp = ctrl.workspacePath()
        val id = UUID.randomUUID().toString()
        val cv = ContentValues()
        cv.put("automation_id", id)
        cv.put("title", title)
        cv.put("cron_expr", cronExpr)
        cv.put("prompt", prompt)
        cv.put("model_selection", modelSelectionJson ?: "null")
        if (mode != null) cv.put("mode", mode) else cv.putNull("mode")
        cv.put("workspace_key", wp)
        cv.put("workspace_path", wp)
        if (targetTaskId != null) cv.put("target_task_id", targetTaskId) else cv.putNull("target_task_id")
        if (botDeliveryTarget != null) cv.put("bot_delivery_target", botDeliveryTarget) else cv.putNull("bot_delivery_target")
        cv.put("location_kind", "local")
        cv.put("recurring", if (recurring) 1 else 0)
        if (maxRuns != null) cv.put("max_runs", maxRuns) else cv.putNull("max_runs")
        cv.putNull("end_at")
        if (ruleJson != null) cv.put("schedule_rule", ruleJson) else cv.putNull("schedule_rule")
        cv.put("schedule_edited_by_user", 0)
        cv.put("run_count", 0)
        cv.put("scheduled_run_count", 0)
        cv.put("enabled", if (nextRunAt == null) 0 else 1)
        cv.put("lifecycle_status", if (nextRunAt == null) "completed" else "active")
        if (nextRunAt != null) cv.put("next_run_at", nextRunAt) else cv.putNull("next_run_at")
        cv.putNull("last_run_at")
        cv.put("running", 0)
        cv.putNull("claimed_at")
        cv.put("dispatch_status", "idle")
        cv.put("dispatch_attempts", 0)
        cv.putNull("retry_at")
        cv.putNull("last_error")
        cv.put("created_at", now)
        cv.put("updated_at", now)

        val store = AutomationStore(ctrl.appContext())
        if (!store.insert(cv)) throw AutomationException("写入 automations 表失败")
        val row = store.getRow(id) ?: throw AutomationException("读取新任务失败")
        return toProtocol(row)
    }

    fun list(ctrl: ZController): List<JSONObject> =
        AutomationStore(ctrl.appContext()).listRows().map { toProtocol(it) }

    fun update(ctrl: ZController, params: JSONObject): JSONObject? {
        val id = params.optString("automationId")
        if (id.isEmpty()) throw AutomationException("automationId is required")
        val store = AutomationStore(ctrl.appContext())
        val cur = store.getRow(id) ?: return null
        val now = System.currentTimeMillis()
        val cv = ContentValues()
        var newCron = cur.optString("cron_expr")
        var ruleJson = cur.optString("schedule_rule").takeIf { it.isNotEmpty() && it != "null" }
        fun has(k: String): Boolean = params.has(k) && !params.isNull(k)

        if (has("title")) cv.put("title", params.optString("title"))
        if (has("prompt")) {
            val p = params.optString("prompt").trim()
            if (p.isEmpty()) throw AutomationException("prompt 不能为空")
            cv.put("prompt", p)
        }
        if (has("cronExpr")) {
            val c = params.optString("cronExpr").trim()
            if (AutomationSchedule.parseCron(c) == null) throw AutomationException("无效的 cron 表达式: $c")
            newCron = c
            cv.put("cron_expr", c)
        }
        if (has("recurring")) cv.put("recurring", if (params.optBoolean("recurring", true)) 1 else 0)
        if (has("maxRuns")) cv.put("max_runs", params.optInt("maxRuns"))
        val iu = params.optString("intervalUnit").takeIf { it.isNotEmpty() }
        val iv = if (has("interval")) params.optInt("interval") else null
        if ((iu == null) != (iv == null)) throw AutomationException("intervalUnit 与 interval 必须配对")
        if (iu != null && iv != null) {
            if (iv < 1 || iv > 200) throw AutomationException("interval 必须在 1-200 之间")
            ruleJson = AutomationSchedule.buildIntervalRule(iu, iv, newCron, now).toString()
            cv.put("schedule_rule", ruleJson)
        }
        val rule = AutomationSchedule.parseRule(ruleJson)
        val next = AutomationSchedule.computeNext(newCron, rule, now)
        if (next == null) {
            cv.putNull("next_run_at")
            cv.put("lifecycle_status", "completed")
            cv.put("enabled", 0)
        } else {
            cv.put("next_run_at", next)
        }
        cv.putNull("retry_at")
        cv.put("dispatch_attempts", 0)
        cv.putNull("last_error")
        cv.put("updated_at", now)
        store.update(id, cv)
        val row = store.getRow(id) ?: return null
        return toProtocol(row)
    }

    fun delete(ctrl: ZController, params: JSONObject): Boolean {
        val id = params.optString("automationId")
        if (id.isEmpty()) throw AutomationException("automationId is required")
        return AutomationStore(ctrl.appContext()).delete(id)
    }

    fun checkBinding(ctrl: ZController, params: JSONObject): Boolean {
        val taskId = params.optString("targetTaskId")
        if (taskId.isEmpty()) throw AutomationException("targetTaskId is required")
        return AutomationStore(ctrl.appContext()).checkBinding(ctrl.workspacePath(), taskId)
    }

    /** 库行 → 协议对象（zcodeAutomationProtocolSchema：strict，null 字段一律省略）。 */
    fun toProtocol(row: JSONObject): JSONObject {
        val o = JSONObject()
        o.put("automationId", row.optString("automation_id"))
        o.put("title", row.optString("title"))
        o.put("cronExpr", row.optString("cron_expr"))
        o.put("prompt", row.optString("prompt"))
        row.optString("model_selection").takeIf { it.isNotEmpty() && it != "null" }?.let {
            try {
                o.put("modelSelection", JSONObject(it))
            } catch (_: Throwable) {
            }
        }
        row.optString("mode").takeIf { it.isNotEmpty() }?.let { o.put("mode", it) }
        row.optString("target_task_id").takeIf { it.isNotEmpty() }?.let { o.put("targetTaskId", it) }
        o.put("enabled", row.optLong("enabled", 0L) != 0L)
        o.put("lifecycleStatus", row.optString("lifecycle_status").ifEmpty { "active" })
        if (!row.isNull("next_run_at")) o.put("nextRunAt", row.optLong("next_run_at"))
        if (!row.isNull("last_run_at")) o.put("lastRunAt", row.optLong("last_run_at"))
        o.put("runCount", row.optLong("run_count", 0L))
        o.put("recurring", row.optLong("recurring", 1L) != 0L)
        if (!row.isNull("max_runs")) o.put("maxRuns", row.optLong("max_runs"))
        row.optString("schedule_rule").takeIf { it.isNotEmpty() && it != "null" }?.let {
            try {
                o.put("scheduleRule", JSONObject(it))
            } catch (_: Throwable) {
            }
        }
        return o
    }
}

/**
 * 移动端前台调度器：应用核心进程存活时周期性检查到期任务，
 * 派发方式为「新建独立会话并发送 prompt」。后台（进程被杀）不执行，与桌面端的宿主多进程方案不同。
 */
class AutomationScheduler private constructor(private val ctrl: ZController) {

    companion object {
        @Volatile
        private var instance: AutomationScheduler? = null

        fun start(ctrl: ZController) {
            synchronized(this) {
                if (instance == null) instance = AutomationScheduler(ctrl)
            }
            instance?.ensureRunning()
        }
    }

    private val handler = Handler(Looper.getMainLooper())

    @Volatile
    private var running = false

    @Volatile
    private var resetDone = false

    private val tick = object : Runnable {
        override fun run() {
            Thread({
                try {
                    runDue()
                } catch (_: Throwable) {
                }
            }, "coda-automation-tick").start()
            handler.postDelayed(this, 30_000)
        }
    }

    fun ensureRunning() {
        if (running) return
        running = true
        handler.postDelayed(tick, 8_000)
    }

    private fun runDue() {
        if (!ctrl.runtime.isRunning) return
        val store = AutomationStore(ctrl.appContext())
        if (!resetDone) {
            store.resetStaleRunning()
            resetDone = true
        }
        val now = System.currentTimeMillis()
        val due = store.listDue(now)
        for (row in due) {
            val id = row.optString("automation_id")
            if (id.isEmpty()) continue
            if (!store.claim(id, now)) continue
            dispatch(row, store)
        }
    }

    private fun dispatch(row: JSONObject, store: AutomationStore) {
        val id = row.optString("automation_id")
        val wp = ctrl.workspacePath()
        val create = JSONObject()
            .put("workspace", JSONObject().put("workspacePath", wp).put("workspaceKey", wp))
            .put("mode", row.optString("mode").takeIf { it.isNotEmpty() } ?: ctrl.defaultMode())
            .put("titleGenerationEnabled", false)
        parseSel(row)?.let { create.put("model", it) }
        ctrl.runtime.call("session/create", create) { ok, body ->
            if (!ok) {
                failRow(store, id, "会话创建失败: " + errText(body))
                return@call
            }
            val sid = ZParse.parseSessionIdFromSnapshot(body)
            if (sid == null) {
                failRow(store, id, "会话创建失败：响应中没有会话 ID")
                return@call
            }
            val send = JSONObject().put("sessionId", sid).put("content", row.optString("prompt"))
            parseSel(row)?.let { send.put("modelSelection", it) }
            ctrl.runtime.call("session/send", send) { ok2, body2 ->
                if (ok2) {
                    settleRow(row, store, System.currentTimeMillis())
                } else {
                    failRow(store, id, "发送失败: " + errText(body2))
                }
            }
        }
    }

    private fun parseSel(row: JSONObject): JSONObject? {
        val s = row.optString("model_selection").takeIf { it.isNotEmpty() && it != "null" } ?: return null
        return try {
            JSONObject(s)
        } catch (_: Throwable) {
            null
        }
    }

    private fun failRow(store: AutomationStore, id: String, message: String) {
        store.fail(id, message, System.currentTimeMillis())
    }

    private fun settleRow(row: JSONObject, store: AutomationStore, now: Long) {
        val id = row.optString("automation_id")
        val rule = AutomationSchedule.parseRule(row.optString("schedule_rule").takeIf { it != "null" })
        val recurring = row.optLong("recurring", 1L) != 0L
        val maxRuns = if (!row.isNull("max_runs")) row.optLong("max_runs") else null
        val runCountAfter = row.optLong("run_count", 0L) + 1
        val done = !recurring && (maxRuns ?: 1L) <= runCountAfter
        val next = if (done) null else AutomationSchedule.computeNext(row.optString("cron_expr"), rule, now)
        store.settle(id, now, next)
    }

    private fun errText(body: JSONObject): String =
        body.optString("message").ifEmpty { body.toString().take(160) }
}
