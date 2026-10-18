package com.coda.mobileui.core

import android.graphics.Color
import org.json.JSONArray
import org.json.JSONObject

/**
 * 宠物皮肤的数据模型。
 *
 * 这里只描述"长什么样、怎么动"，不含任何绘制逻辑：渲染器（PetView）只做三件事——
 * 把姿态里的部件拼成字符网格、按片段逐格播放、按状态机切换片段。因此换形象、
 * 加状态、加动作都只改数据，不改代码。这套分工照搬 Critter
 * （https://github.com/Brayn-Studio/critter，MIT）的做法：
 * parts 是字符图，poses 是部件组合，clips 是「姿态 + 停留格数」的序列。
 */

/** 一个图形部件：字符网格（'.' 为透明），左上角落在网格的 [x, y]。 */
class PetPart(val x: Int, val y: Int, val rows: List<String>)

/** 动画的一帧：显示哪个姿态、停多少格、以及整体位移（格）。 */
class PetFrame(val pose: String, val hold: Int, val dx: Int = 0, val dy: Int = 0)

/**
 * 一个状态怎么表现。
 *
 * enter 是进场片段，loop 是循环片段，still 是"减少动态效果"时停住的姿态。
 * dwellMs 大于 0 时表示这个状态只停留这么久，之后自动回 idle（做完了、出错了都是这样）。
 * fidgetEveryMs 与 fidget 描述空闲时的随机小动作，不让它看起来像死物。
 */
class PetStateSpec(
    val enter: String? = null,
    val loop: String? = null,
    val still: String? = null,
    val badge: String = "",
    val badgeColor: String = "",
    val dwellMs: Long = 0L,
    val fidgetEveryMs: Long = 0L,
    val fidget: List<String> = emptyList(),
)

/** 一份完整的皮肤。 */
class PetSkin(
    val id: String,
    val title: String,
    /** 一格对应多少毫秒。60 是 Critter 的默认值。 */
    val tickMs: Int,
    val cols: Int,
    val rows: Int,
    /** 颜色字符 → 颜色。值可以是 "#RRGGBB"，也可以是 @accent 这类语义色。 */
    val palette: Map<Char, String>,
    val parts: Map<String, PetPart>,
    val poses: Map<String, List<String>>,
    val glyphs: Map<String, List<String>>,
    val clips: Map<String, List<PetFrame>>,
    val states: Map<String, PetStateSpec>,
) {
    fun clip(name: String?): List<PetFrame> = if (name == null) emptyList() else clips[name] ?: emptyList()

    fun state(name: String): PetStateSpec? = states[name]

    /**
     * 把姿态合成成一张字符网格。
     *
     * 部件按 poses 里给的顺序叠放，后出现的覆盖先出现的；越界的字符直接跳过，
     * 这样头顶的呆毛可以用负 y 坐标画到网格外面去。
     */
    fun gridOf(pose: String): Array<CharArray> {
        val grid = Array(rows) { CharArray(cols) { '.' } }
        poses[pose]?.forEach { partName ->
            val part = parts[partName] ?: return@forEach
            part.rows.forEachIndexed { ry, line ->
                val y = part.y + ry
                if (y < 0 || y >= rows) return@forEachIndexed
                line.forEachIndexed { rx, ch ->
                    val x = part.x + rx
                    if (ch != '.' && x >= 0 && x < cols) grid[y][x] = ch
                }
            }
        }
        return grid
    }

    /** 解析调色板；语义色由调用方注入当前主题色，其余按十六进制读。 */
    fun resolvePalette(accent: Int, accentDark: Int, accentSoft: Int): Map<Char, Int> =
        palette.mapValues { (_, token) -> resolveColor(token, accent, accentDark, accentSoft) }

    companion object {
        const val TOKEN_ACCENT_LIGHT = "@accentLight"
        const val TOKEN_ACCENT = "@accent"
        const val TOKEN_ACCENT_SOFT = "@accentSoft"

        internal fun resolveColor(token: String, accent: Int, accentDark: Int, accentSoft: Int): Int =
            when (token) {
                TOKEN_ACCENT_LIGHT -> accent
                TOKEN_ACCENT -> accentDark
                TOKEN_ACCENT_SOFT -> accentSoft
                else -> runCatching { Color.parseColor(token) }.getOrDefault(Color.GRAY)
            }

        /**
         * 从 JSON 读一份皮肤。字段与 Critter 的 manifest 对齐，只是裁掉了粒子与位移之外的特效。
         *
         * 自定义皮肤放进 filesDir/pet/<名字>.json 就能出现在皮肤列表里；读不出来时
         * 调用方退回内置皮肤，不会让界面空掉。
         */
        fun fromJson(raw: String): PetSkin {
            val root = JSONObject(raw)
            val paletteJson = root.optJSONObject("palette") ?: JSONObject()
            val palette = LinkedHashMap<Char, String>()
            paletteJson.keys().forEach { key ->
                if (key.isNotEmpty()) palette[key[0]] = paletteJson.optString(key)
            }
            val parts = LinkedHashMap<String, PetPart>()
            root.optJSONObject("parts")?.let { obj ->
                obj.keys().forEach { key ->
                    val item = obj.optJSONObject(key) ?: return@forEach
                    val at = item.optJSONArray("at")
                    parts[key] = PetPart(
                        x = at?.optInt(0) ?: 0,
                        y = at?.optInt(1) ?: 0,
                        rows = item.optJSONArray("rows").toStringList(),
                    )
                }
            }
            val poses = LinkedHashMap<String, List<String>>()
            root.optJSONObject("poses")?.let { obj ->
                obj.keys().forEach { key -> poses[key] = obj.optJSONArray(key).toStringList() }
            }
            val glyphs = LinkedHashMap<String, List<String>>()
            root.optJSONObject("glyphs")?.let { obj ->
                obj.keys().forEach { key -> glyphs[key] = obj.optJSONArray(key).toStringList() }
            }
            val clips = LinkedHashMap<String, List<PetFrame>>()
            root.optJSONObject("clips")?.let { obj ->
                obj.keys().forEach { key ->
                    val frames = mutableListOf<PetFrame>()
                    val arr = obj.optJSONArray(key) ?: return@forEach
                    for (i in 0 until arr.length()) {
                        val frame = arr.optJSONArray(i) ?: continue
                        frames += PetFrame(
                            pose = frame.optString(0),
                            hold = frame.optInt(1, 1).coerceAtLeast(1),
                            dx = frame.optInt(2, 0),
                            dy = frame.optInt(3, 0),
                        )
                    }
                    clips[key] = frames
                }
            }
            val states = LinkedHashMap<String, PetStateSpec>()
            root.optJSONObject("states")?.let { obj ->
                obj.keys().forEach { key ->
                    val item = obj.optJSONObject(key) ?: return@forEach
                    states[key] = PetStateSpec(
                        enter = item.optString("enter").takeIf { it.isNotEmpty() && it != "null" },
                        loop = item.optString("loop").takeIf { it.isNotEmpty() && it != "null" },
                        still = item.optString("still").takeIf { it.isNotEmpty() && it != "null" },
                        badge = item.optString("badge"),
                        badgeColor = item.optString("badgeColor"),
                        dwellMs = item.optLong("dwellMs"),
                        fidgetEveryMs = item.optLong("fidgetEveryMs"),
                        fidget = item.optJSONArray("fidget").toStringList(),
                    )
                }
            }
            val grid = root.optJSONArray("grid")
            val pose0 = poses.values.firstOrNull().orEmpty()
            return PetSkin(
                id = root.optString("name", "custom"),
                title = root.optString("title", root.optString("name", "自定义")),
                tickMs = root.optInt("tickMs", 60).coerceIn(16, 1000),
                cols = grid?.optInt(0) ?: 18,
                rows = grid?.optInt(1) ?: 16,
                palette = palette,
                parts = parts,
                poses = poses,
                glyphs = glyphs,
                clips = clips,
                states = states,
            ).also { if (pose0.isEmpty()) throw IllegalArgumentException("poses 为空") }
        }

        private fun JSONArray?.toStringList(): List<String> {
            if (this == null) return emptyList()
            val out = ArrayList<String>(length())
            for (i in 0 until length()) out += optString(i)
            return out
        }
    }
}

/** 宠物状态。界面只认这几个，皮肤里对应同名 key。 */
enum class PetMood(val key: String) {
    IDLE("idle"),
    RUNNING("running"),
    WAITING("waiting"),
    DONE("done"),
    ERROR("error"),
}
