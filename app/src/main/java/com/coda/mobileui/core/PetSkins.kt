package com.coda.mobileui.core

import android.content.Context
import java.io.File

/**
 * 内置形象与皮肤加载。
 *
 * 内置形象叫 Coda：一枚圆滚滚的小家伙，头顶两片嫩叶，会随状态竖起、耷拉、合拢。
 * 它的所有动作都写在下面这份数据里，渲染器不认识任何具体姿态名，所以以后加形象
 * 只要再写一份同样结构的数据。
 */
object PetSkins {

    /** 内置形象。 */
    val builtin: PetSkin by lazy { buildCoda() }

    /**
     * 读取全部可用皮肤：内置一份 + filesDir/pet 下的自定义 JSON。
     *
     * 自定义文件解析失败的会被跳过（不会让设置页炸掉），文件名即皮肤 id，
     * 与内置同名时优先用自定义那份，方便就地改造默认形象。
     */
    fun all(ctx: Context): List<PetSkin> {
        val out = mutableListOf<PetSkin>()
        val dir = File(ctx.filesDir, "pet")
        val custom = ArrayList<PetSkin>()
        if (dir.isDirectory) {
            dir.listFiles { f -> f.isFile && f.name.endsWith(".json", true) }
                ?.sortedBy { it.name }
                ?.forEach { file ->
                    runCatching { PetSkin.fromJson(file.readText()) }
                        .onSuccess { parsed -> custom.add(parsed) }
                }
        }
        val replacing = custom.firstOrNull { it.id == builtin.id }
        out.add(replacing ?: builtin)
        custom.filter { it.id != builtin.id }.forEach { out.add(it) }
        return out
    }

    /** 按 id 取皮肤，找不到退回内置。 */
    fun resolve(ctx: Context, id: String): PetSkin =
        all(ctx).firstOrNull { it.id == id } ?: builtin

    // ------------------------------------------------------------------ 数据

    private fun part(x: Int, y: Int, vararg rows: String) = PetPart(x, y, rows.toList())

    private fun clip(vararg frames: PetFrame) = frames.toList()

    private fun f(pose: String, hold: Int, dx: Int = 0, dy: Int = 0) = PetFrame(pose, hold, dx, dy)

    private fun buildCoda(): PetSkin {
        val palette = mapOf(
            // 身体用主题色，随 Material You 自动跟随壁纸
            'B' to PetSkin.TOKEN_ACCENT,
            'h' to PetSkin.TOKEN_ACCENT_LIGHT,
            'b' to PetSkin.TOKEN_ACCENT_SOFT,
            // 头顶嫩叶
            'G' to "#6BCB5F",
            'g' to "#3F9442",
            'y' to "#FFD24A",
            'f' to "#FF8FB8",
            // 五官
            'o' to "#2A2140",
            'w' to "#FFFFFF",
            'p' to "#F7A1C0",
            'm' to "#5B3A8E",
            'd' to "#7D848E",
        )

        val parts = linkedMapOf(
            // 身体：三档高度，用于压扁、正常、拉长。全部从 y=4 起（squash 从 5、tall 从 3），
            // 上面 y=0..3 留给头顶嫩叶，这样蹲下、跳起、开花都不会压到叶子。
            "body" to part(
                3, 4,
                "..BBBBBBBB..",
                ".BhBBBBBBBBb",
                "BhBBBBBBBBBB",
                "BBBBBBBBBBBB",
                "BBBBBBBBBBBB",
                "BBBBBBBBBBBB",
                "BBBBBBBBBBBB",
                ".bBBBBBBBBb.",
                "..bbbbbbbb..",
            ),
            "body_squash" to part(
                2, 5,
                "...BBBBBBBB...",
                "..BhBBBBBBBBb.",
                ".BhBBBBBBBBBBb",
                ".BBBBBBBBBBBB.",
                "BBBBBBBBBBBBBB",
                "BBBBBBBBBBBBBB",
                ".bBBBBBBBBBBb.",
                "..bbbbbbbbbb..",
            ),
            "body_tall" to part(
                4, 3,
                "..BBBBBB..",
                ".BhBBBBBBb",
                "BhBBBBBBBB",
                "BBBBBBBBBB",
                "BBBBBBBBBB",
                "BBBBBBBBBB",
                "BBBBBBBBBB",
                "BBBBBBBBBB",
                ".bBBBBBBb.",
                "..bbbbbb..",
            ),
            // 头顶嫩叶：立起、下垂、歪向一侧、合拢成芽、开花
            "top" to part(7, 1, "GG..GG", ".GggG."),
            "top_low" to part(7, 2, "GG..GG", ".GggG."),
            "top_up" to part(8, 0, "GG..GG", ".GggG."),
            "top_droop" to part(7, 2, "..gg..", "GGggGG"),
            "top_sway_l" to part(6, 1, "GG..GG.", "..GggG."),
            "top_sway_r" to part(8, 1, ".GG..GG", ".GggG.."),
            "top_flat" to part(5, 3, "GGGGggGGGG"),
            "top_bud" to part(8, 2, ".GG.", "GggG"),
            "top_bloom" to part(6, 1, "GG.ff.GG", "..GyyG.."),
            // 眼睛：对齐身体中部（body 第 5 行 = y9）
            "eyes_open" to part(5, 9, "ow....wo", "oo....oo"),
            "eyes_l" to part(4, 9, "ow....wo", "oo....oo"),
            "eyes_r" to part(6, 9, "ow....wo", "oo....oo"),
            "eyes_up" to part(5, 8, "ow....wo", "oo....oo"),
            "eyes_down" to part(5, 10, "ow....wo", "oo....oo"),
            "eyes_half" to part(5, 10, "oo....oo"),
            "eyes_shut" to part(5, 10, "ooo..ooo"),
            "eyes_happy" to part(5, 9, ".o....o.", "o.o..o.o"),
            "eyes_wide" to part(5, 8, "ow....wo", "ow....wo", "oo....oo"),
            "eyes_wink" to part(5, 9, "ow....o.", "oo...o.o"),
            "eyes_squint" to part(5, 8, "o......o", ".o....o.", "o......o"),
            "eyes_dizzy" to part(5, 8, "o.o....o.o", ".o......o.", "o.o....o.o"),
            // 腮红与嘴：在眼睛下方一行
            "blush" to part(4, 10, "pp......pp"),
            "mouth_dot" to part(8, 11, "mm"),
            "mouth_o" to part(8, 10, "mm"),
            "mouth_open" to part(8, 10, "mm", "pp"),
            "mouth_smile" to part(7, 10, ".mm.", "m..m"),
            "mouth_frown" to part(7, 10, "m..m", ".mm."),
            // 脚：贴在身体下缘
            "feet" to part(4, 13, "bbb....bbb"),
            "feet_a" to part(4, 13, ".......bbb"),
            "feet_b" to part(4, 13, "bbb......."),
            "feet_tuck" to part(5, 13, "bb....bb"),
            "feet_wide" to part(3, 13, "bbb......bbb"),
            // 手：从身体两侧伸出
            "arm_l" to part(2, 8, "B", "B"),
            "arm_r" to part(15, 8, "B", "B"),
            "arm_l_up" to part(1, 7, "B.", ".B"),
            "arm_r_up" to part(15, 7, ".B", "B."),
            "arm_l_droop" to part(2, 9, "B.", ".B"),
            "arm_r_droop" to part(15, 9, ".B", "B."),
        )

        val poses = linkedMapOf(
            "base" to listOf("body", "feet", "arm_l", "arm_r", "eyes_open", "top"),
            "half" to listOf("body", "feet", "arm_l", "arm_r", "eyes_half", "top"),
            "shut" to listOf("body", "feet", "arm_l", "arm_r", "eyes_shut", "top"),
            "look_l" to listOf("body", "feet", "arm_l", "arm_r", "eyes_l", "top"),
            "look_r" to listOf("body", "feet", "arm_l", "arm_r", "eyes_r", "top"),
            "look_up" to listOf("body", "feet", "arm_l", "arm_r", "eyes_up", "top"),
            "look_down" to listOf("body", "feet", "arm_l", "arm_r", "eyes_down", "top"),
            "breathe" to listOf("body_squash", "feet_wide", "eyes_open", "top_low"),
            "sway_l" to listOf("body", "feet", "arm_l", "arm_r", "eyes_l", "top_sway_l"),
            "sway_r" to listOf("body", "feet", "arm_l", "arm_r", "eyes_r", "top_sway_r"),
            "wink" to listOf("body", "feet", "arm_l", "arm_r", "eyes_wink", "blush", "top_sway_r"),
            "happy" to listOf("body", "feet", "arm_l_up", "arm_r_up", "eyes_happy", "blush", "mouth_smile", "top"),
            "happy_l" to listOf("body", "feet", "arm_l_up", "arm_r_up", "eyes_happy", "blush", "mouth_smile", "top_sway_l"),
            "happy_r" to listOf("body", "feet", "arm_l_up", "arm_r_up", "eyes_happy", "blush", "mouth_smile", "top_sway_r"),
            "air" to listOf("body_tall", "feet_tuck", "arm_l_up", "arm_r_up", "eyes_open", "top_up"),
            "squash" to listOf("body_squash", "feet_wide", "eyes_open", "top_flat"),
            "squash_happy" to listOf("body_squash", "feet_wide", "eyes_happy", "blush", "top_flat"),
            "cheer" to listOf("body_tall", "feet_tuck", "arm_l_up", "arm_r_up", "eyes_happy", "blush", "mouth_open", "top_bloom"),
            "alert" to listOf("body_tall", "feet_tuck", "arm_l_up", "arm_r_up", "eyes_wide", "mouth_o", "top_up"),
            "lift" to listOf("body", "feet", "arm_l", "arm_r_up", "eyes_open", "top_up"),
            "ra_lift" to listOf("body", "feet", "arm_l_up", "arm_r", "eyes_open", "top_up"),
            "think" to listOf("body", "feet", "arm_l", "arm_r", "eyes_up", "top"),
            "read" to listOf("body", "feet", "arm_l", "arm_r_droop", "eyes_down", "top"),
            "walk_a" to listOf("body", "feet_a", "arm_l", "arm_r", "eyes_l", "top_sway_r"),
            "walk_b" to listOf("body", "feet_b", "arm_l", "arm_r", "eyes_r", "top_sway_l"),
            "juggle_a" to listOf("body", "feet", "arm_l_up", "arm_r", "eyes_up", "top_sway_l"),
            "juggle_b" to listOf("body", "feet", "arm_l", "arm_r_up", "eyes_up", "top_sway_r"),
            "oops" to listOf("body", "feet", "arm_l_up", "arm_r_up", "eyes_dizzy", "mouth_frown", "top_up"),
            "slump" to listOf("body_squash", "feet", "arm_l_droop", "arm_r_droop", "eyes_down", "mouth_frown", "top_droop"),
            "slump_shut" to listOf("body_squash", "feet", "arm_l_droop", "arm_r_droop", "eyes_shut", "mouth_frown", "top_droop"),
            "doze" to listOf("body_squash", "feet_wide", "eyes_half", "top_droop"),
            "sleep" to listOf("body_squash", "feet_wide", "eyes_shut", "top_bud"),
            "bloom" to listOf("body", "feet", "arm_l_up", "arm_r_up", "eyes_happy", "blush", "top_bloom"),
        )

        val clips = linkedMapOf(
            // 空闲：长时间待机 + 呼吸 + 偶尔左右看，节奏放慢，避免抢注意力
            "idle" to clip(
                f("base", 60), f("breathe", 8), f("base", 52),
                f("sway_l", 4), f("base", 3), f("sway_r", 4), f("base", 3),
            ),
            "blink" to clip(f("shut", 2)),
            "double_blink" to clip(f("shut", 2), f("base", 3), f("shut", 2)),
            "look" to clip(f("look_l", 5), f("base", 2), f("look_r", 5), f("base", 2), f("shut", 1)),
            "wink" to clip(f("wink", 6), f("base", 1)),
            "tap" to clip(f("walk_a", 3), f("base", 3), f("walk_b", 3), f("base", 4)),
            // 运行中：先蹲一下起跳，再按在做什么换循环
            "work_enter" to clip(f("squash", 2), f("air", 1), f("base", 2)),
            "laptop" to clip(
                f("think", 10), f("look_down", 8), f("think", 6),
                f("sway_l", 3), f("think", 8), f("look_down", 6), f("base", 3),
            ),
            "read" to clip(f("read", 8), f("look_down", 5), f("look_l", 4), f("look_down", 5), f("look_r", 4)),
            "bash" to clip(f("juggle_a", 3), f("juggle_b", 3)),
            "juggle" to clip(f("juggle_a", 3), f("juggle_b", 3), f("look_up", 3)),
            // 等你确认：先惊一下蹦起来，然后举手挥
            "alert" to clip(f("squash", 1), f("alert", 2), f("air", 1), f("alert", 3), f("base", 2)),
            "ask" to clip(f("alert", 5), f("lift", 3), f("alert", 5), f("ra_lift", 3)),
            "impatient" to clip(f("walk_a", 3), f("base", 3), f("walk_b", 3), f("base", 3), f("squash", 1), f("alert", 3)),
            // 做完了：连跳两下再开花
            "celebrate" to clip(
                f("squash", 2), f("air", 2), f("cheer", 2), f("air", 2),
                f("cheer", 3), f("bloom", 8), f("happy", 4),
            ),
            "content" to clip(
                f("bloom", 14), f("happy", 6), f("bloom", 3),
                f("happy_l", 3), f("bloom", 2), f("happy_r", 3), f("bloom", 10),
            ),
            // 出错：抖一下然后垂头
            "oops" to clip(f("oops", 4), f("squash", 1), f("oops", 3), f("slump", 6)),
            "slump_loop" to clip(f("slump", 40), f("slump_shut", 2), f("slump", 30), f("doze", 12)),
            "seen" to clip(f("happy", 3), f("base", 4)),
        )

        val states = mapOf(
            PetMood.IDLE.key to PetStateSpec(
                loop = "idle",
                still = "base",
                fidgetEveryMs = 5200,
                fidget = listOf("blink", "double_blink", "look", "wink", "tap"),
            ),
            PetMood.RUNNING.key to PetStateSpec(
                enter = "work_enter",
                loop = "laptop",
                still = "base",
            ),
            PetMood.WAITING.key to PetStateSpec(
                enter = "alert",
                loop = "ask",
                still = "alert",
                badge = "?",
                badgeColor = "#FF9F0A",
                fidgetEveryMs = 20000,
                fidget = listOf("impatient"),
            ),
            PetMood.DONE.key to PetStateSpec(
                enter = "celebrate",
                loop = "content",
                still = "bloom",
                badge = "✓",
                badgeColor = "#34C759",
                dwellMs = 8000,
            ),
            PetMood.ERROR.key to PetStateSpec(
                enter = "oops",
                loop = "slump_loop",
                still = "slump",
                badge = "!",
                badgeColor = "#FF3B30",
                dwellMs = 12000,
            ),
        )

        return PetSkin(
            id = "coda",
            title = "Coda",
            tickMs = 60,
            cols = 18,
            rows = 16,
            palette = palette,
            parts = parts,
            poses = poses,
            glyphs = emptyMap(),
            clips = clips,
            states = states,
        )
    }
}