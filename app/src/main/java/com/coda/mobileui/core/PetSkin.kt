package com.coda.mobileui.core

import android.graphics.Color
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.roundToInt

//
// 宠物皮肤的数据模型。
//
// 字段与 critter（https://github.com/Brayn-Studio/critter，MIT）的 manifest schema 2 完全对齐，
// 目的就是能把它的五个形象原样拿过来用，不再自造一套。几处必须照抄的语义：
//
// 网格固定 18×10，row height 是地面；部件的 at 可以取负值（抬手、垂腿会画到基框外）。
// 姿态的像素包围盒才是实际绘制范围，所以渲染不能把像素裁在 18×10 里。
// clip key 是 [pose, ticks] 或 [pose, ticks, dx, dy] 或 [pose, ticks, dx, dy, effect]。
// dy 为正表示向上抬起（截图里 y 向下，绘制时要翻过来）。
// Span 既可以是数字，也可以是 [min, max] 表示随机区间。
//

// 部件：字符网格，左上角在基框坐标 [x, y]，可负。
data class PetPart(
    val x: Int,
    val y: Int,
    val rows: List<String>,
)

/** 动画的一帧。effect 在这一帧开始时触发一次。 */
data class PetFrame(
    val pose: String,
    val ticks: Int,
    val dx: Int = 0,
    val dy: Int = 0,
    val effect: String? = null,
)

/** 数字或 [min, max] 区间（min < max 时均匀随机）。 */
data class Span(
    val min: Double,
    val max: Double,
) {
    val isRange: Boolean get() = max > min

    fun sample(rng: java.util.Random): Double = if (!isRange) min else min + rng.nextDouble() * (max - min)

    fun sampleInt(rng: java.util.Random): Int = sample(rng).roundToInt()

    companion object {
        fun of(v: Double) = Span(v, v)

        fun of(
            a: Double,
            b: Double,
        ) = Span(minOf(a, b), maxOf(a, b))
    }
}

/** 一类粒子：count 个实例，位置与速度可在区间内随机。 */
data class ParticleSpec(
    val count: Int,
    val glyph: String,
    val colors: List<String>,
    val x: Span,
    val y: Span,
    val vx: Span?,
    val vy: Span?,
    val ay: Double,
    val life: Span,
    val sway: Double,
    val pop: Boolean,
    val shrinkTo: String?,
)

/** 特效：每次触发时全部 particles，或按次数轮换 cycle 里的某一组。 */
data class EffectSpec(
    val particles: List<ParticleSpec>,
    val cycle: List<List<ParticleSpec>>,
)

/** 头顶挂件：转圈（frames+periodMs）或静止字形（glyph），可带上下浮动与闪烁。 */
data class AccessorySpec(
    val glyph: String?,
    val frames: List<String>?,
    val color: String,
    val periodMs: Int?,
    val box: Int?,
    val reduced: String?,
    val bobEvery: Int?,
    val twinkleEvery: Int?,
    val twinkle: String?,
)

/** 有抽签权重的片段。 */
data class WeightedClip(
    val clip: String,
    val weight: Int,
)

/** 随机小动作：每 every 个 tick 抽一次，权重 pick。 */
data class FidgetSpec(
    val everyMin: Int,
    val everyMax: Int,
    val pick: List<WeightedClip>,
)

/** 睡着：空闲 after 个 tick 后睡着，doze 过渡，loop 循环，wake 醒来。 */
data class SleepSpec(
    val after: Int,
    val doze: String,
    val loop: String,
    val wake: String,
    val still: String,
)

/** 等太久不耐烦：after 个 tick 后换成 loop。 */
data class ImpatientSpec(
    val after: Int,
    val loop: String,
)

/** 头顶常驻标记（等确认时的 !）。x 是基框列，hopEvery 是跳动周期。 */
data class BadgeSpec(
    val glyph: String,
    val color: String,
    val x: Int,
    val hopEvery: Int,
    val impatientHopEvery: Int,
)

/** running 状态的工作变体：一个变体有自己的循环、进出场片段与道具。 */
data class VariantSpec(
    val loop: String,
    val enter: String?,
    val exit: String?,
    val prop: String?,
)

/** 某个状态自己的目光姿态（做完了开花时眼睛仍跟着看）。 */
data class GazePoses(
    val left: String,
    val right: String,
    val up: String,
    val down: String,
    val center: String,
)

/**
 * 一个状态的全部表现。
 *
 * still 在这里是必填的姿态名（不是片段），"减少动态效果"时直接停在它上面。
 */
data class PetStateSpec(
    val enter: String?,
    val loop: String?,
    val tintDim: Boolean,
    val accessory: String?,
    val still: String,
    val stillDy: Int,
    val seen: String?,
    val fidget: FidgetSpec?,
    val sleep: SleepSpec?,
    val impatient: ImpatientSpec?,
    val badge: BadgeSpec?,
    val variants: Map<String, VariantSpec>,
    val defaultVariant: String?,
    val gaze: GazePoses?,
)

/** 交互用到的片段与姿态名。 */
data class PetInteractions(
    val notice: String,
    val pet: String,
    val petEnd: String,
    val press: String,
    val boopEnd: String,
    val held: String,
    val heldStill: String,
    val drop: String,
    val blink: String,
    val join: String,
    val leave: String,
    val appear: String,
    val vanish: String,
    val hop: String,
    val land: String,
    val blinkPose: String,
    val landDust: String,
    val walkLeft: List<String>,
    val walkRight: List<String>,
    val lookLeft: String,
    val lookRight: String,
    val lookUp: String,
    val lookDown: String,
    val lookCenter: String,
) {
    fun lookFor(zone: GazeZone?): String? =
        when (zone) {
            GazeZone.LEFT -> lookLeft
            GazeZone.RIGHT -> lookRight
            GazeZone.UP -> lookUp
            GazeZone.DOWN -> lookDown
            GazeZone.CENTER -> lookCenter
            null -> null
        }
}

/** 道具（抛接的球）。 */
data class PropSpec(
    val glyph: String,
    val colors: List<String>,
)

/** 目光方向。 */
enum class GazeZone { LEFT, RIGHT, UP, DOWN, CENTER }

/** 宠物状态。与原项目一致：idle / running / waiting / done / error。 */
enum class PetMood(
    val key: String,
) {
    IDLE("idle"),
    RUNNING("running"),
    WAITING("waiting"),
    DONE("done"),
    ERROR("error"),
}

/** 一份完整的皮肤。 */
data class PetSkin(
    val schema: Int,
    val id: String,
    val title: String,
    val tickMs: Int,
    val cols: Int,
    val rows: Int,
    val palette: Map<String, String>,
    val parts: Map<String, PetPart>,
    val poses: Map<String, List<String>>,
    val glyphs: Map<String, List<String>>,
    val clips: Map<String, List<PetFrame>>,
    val effects: Map<String, EffectSpec>,
    val accessories: Map<String, AccessorySpec>,
    val states: Map<String, PetStateSpec>,
    val interactions: PetInteractions,
    val props: Map<String, PropSpec>,
    /** manifest 里显式声明的 interactions clip 引用（path -> name），默认名不参与校验。 */
    val declaredInteractionClips: Map<String, String> = emptyMap(),
    /** manifest 里显式声明的 interactions pose 引用（path -> name）。 */
    val declaredInteractionPoses: Map<String, String> = emptyMap(),
) {
    fun clip(name: String?): List<PetFrame> = if (name == null) emptyList() else clips[name] ?: emptyList()
    fun state(mood: PetMood): PetStateSpec = states[mood.key] ?: states.values.firstOrNull() ?: DEFAULT_STATE

    /**
     * 加载期的引用完整性校验。
     *
     * clip()/glyph()/gridOf() 的兜底保留为防御，但不能是唯一的错误处理：
     * 引用错误的皮肤以前能「解析成功」，渲染出来却是空白宠物且无任何诊断。
     * 这里让缺失在 load time 带着路径信息暴露。
     */
    internal fun validateReferences() {
        val problems = ArrayList<String>()
        fun needPose(path: String, name: String?) {
            if (!name.isNullOrEmpty() && name !in poses) problems.add("$path -> pose '$name'")
        }
        fun needClip(path: String, name: String?) {
            if (!name.isNullOrEmpty() && name !in clips) problems.add("$path -> clip '$name'")
        }
        clips.forEach { (cname, frames) ->
            frames.forEachIndexed { i, fr -> needPose("clips.$cname[$i]", fr.pose) }
        }
        states.forEach { (sname, st) ->
            needClip("states.$sname.enter", st.enter)
            needClip("states.$sname.loop", st.loop)
            needClip("states.$sname.seen", st.seen)
            needPose("states.$sname.still", st.still)
            st.variants.forEach { (vname, v) ->
                needClip("states.$sname.variants.$vname.loop", v.loop)
                needClip("states.$sname.variants.$vname.enter", v.enter)
                needClip("states.$sname.variants.$vname.exit", v.exit)
            }
            st.sleep?.let { s ->
                needClip("states.$sname.sleep.doze", s.doze)
                needClip("states.$sname.sleep.loop", s.loop)
                needClip("states.$sname.sleep.wake", s.wake)
                needPose("states.$sname.sleep.still", s.still)
            }
            st.impatient?.let { needClip("states.$sname.impatient.loop", it.loop) }
            st.fidget?.pick?.forEachIndexed { i, w -> needClip("states.$sname.fidget.pick[$i]", w.clip) }
        }
        // interactions 只校验显式声明的引用：解析器给未声明字段填了通用默认名
        // （emerge/goodbye/pop_up/burrow 等），部分内置皮肤本就没有这些片段，
        // 运行期由 clip() 的空列表兜底。把默认名也当硬引用会误杀合法皮肤。
        declaredInteractionClips.forEach { (path, name) -> needClip(path, name) }
        declaredInteractionPoses.forEach { (path, name) -> needPose(path, name) }
        if (problems.isNotEmpty()) {
            throw IllegalArgumentException("皮肤 '$id' 引用缺失：${problems.joinToString("; ")}")
        }
        // error 状态缺失只警告不拒绝：state() 会回退到第一个状态，
        // 但配置缺口必须可见，不能静默把 error 显示成 idle。
        if ("error" !in states) {
            AppLog.w("PetSkin", "皮肤 '$id' 缺少 error 状态，出错时将回退显示其他状态")
        }
    }

    fun glyph(name: String?): List<String> = if (name == null) emptyList() else glyphs[name] ?: emptyList()

    /**
     * 把姿态合成成像素包围盒。
     *
     * 与原项目 SkinRenderer.grid 一致：部件按 poses 顺序叠放，后覆盖先；
     * 结果只保留实际画到的像素，所以抬手、垂腿这些超出 18×10 的像素不会被裁掉。
     * 返回 null 表示这个姿态不存在或全透明。
     */
    fun gridOf(pose: String): PixelGrid? {
        val names = poses[pose] ?: return null
        val pixels = HashMap<Int, HashMap<Int, Char>>()
        for (name in names) {
            val part = parts[name] ?: continue
            // 坐标来自 manifest：损坏或被改过时可能是 Int.MIN_VALUE，
            // 直接参与包围盒计算会溢出成负宽度，进而按负长度分配缓冲区。
            if (!isPlausiblePartOrigin(part.x, part.y)) continue
            part.rows.forEachIndexed { r, line ->
                if (!isPlausiblePartLine(line)) return@forEachIndexed
                line.forEachIndexed { c, ch ->
                    if (ch != '.') {
                        val y = part.y + r
                        val x = part.x + c
                        pixels.getOrPut(y) { HashMap() }[x] = ch
                    }
                }
            }
        }
        if (pixels.isEmpty()) return null
        val minX = pixels.values.minOf { row -> row.keys.min() }
        val maxX = pixels.values.maxOf { row -> row.keys.max() }
        val minY = pixels.keys.min()
        val maxY = pixels.keys.max()
        val rowsOut =
            (minY..maxY).map { y ->
                val row = pixels[y]
                (minX..maxX).joinToString("") { x -> row?.get(x)?.toString() ?: "." }
            }
        return PixelGrid(minX, minY, maxX - minX + 1, maxY - minY + 1, rowsOut)
    }

    /** 调色板解析成 ARGB；dim 为原项目里状态级的淡化（混向中灰并降透明度）。 */
    fun resolveColor(
        hex: String,
        dim: Boolean,
        dimStrength: Double = 1.0,
    ): Int {
        val v = runCatching { Color.parseColor(hex) }.getOrDefault(Color.GRAY)
        if (!dim) return v
        val s = dimStrength.coerceIn(0.0, 1.0)
        val grey = 0.5 * s
        fun mix(c: Int): Int = (c * (1 - grey) + 127.5 * grey).roundToInt().coerceIn(0, 255)
        // 以调色板自身的 alpha 为基数，而不是固定 255：#80FFFFFF 这类有意半透明的
        // 颜色在 dim 后应保持半透明，否则会把宠物背后的内容遮住。
        val a = (Color.alpha(v) * (1 - 0.15 * s)).roundToInt().coerceIn(0, 255)
        return Color.argb(a, mix(Color.red(v)), mix(Color.green(v)), mix(Color.blue(v)))
    }

    companion object {
        private val DEFAULT_STATE =
            PetStateSpec(
                enter = null,
                loop = null,
                tintDim = false,
                accessory = null,
                still = "base",
                stillDy = 0,
                seen = null,
                fidget = null,
                sleep = null,
                impatient = null,
                badge = null,
                variants = emptyMap(),
                defaultVariant = null,
                gaze = null,
            )

        private fun JSONArray?.strList(): List<String> {
            if (this == null) return emptyList()
            val out = ArrayList<String>(length())
            for (i in 0 until length()) out.add(optString(i))
            return out
        }

        private fun JSONObject?.span(key: String): Span? {
            val raw = this?.opt(key) ?: return null
            // optDouble 对缺失/非数值元素返回 NaN，minOf/maxOf 会把 NaN 传播进区间，
            // 粒子最终在 NaN 坐标渲染。先做 isFinite 校验：单值缺失退化为定值，
            // 全部非法返回 null 让调用方落到文档默认值。
            if (raw is JSONArray) {
                val a = raw.optDouble(0)
                val b = raw.optDouble(1)
                return when {
                    a.isFinite() && b.isFinite() -> Span.of(a, b)
                    a.isFinite() -> Span.of(a)
                    b.isFinite() -> Span.of(b)
                    else -> null
                }
            }
            val d = (raw as? Number)?.toDouble() ?: return null
            return if (d.isFinite()) Span.of(d) else null
        }

        /** 读一份 manifest。字段缺失时退回原项目验证器允许的安全默认值。 */
        fun fromJson(raw: String): PetSkin {
            val root = JSONObject(raw)
            val palette = LinkedHashMap<String, String>()
            root.optJSONObject("palette")?.let { o ->
                o.keys().forEach { k -> palette[k] = o.optString(k) }
            }
            val parts = LinkedHashMap<String, PetPart>()
            root.optJSONObject("parts")?.let { o ->
                o.keys().forEach { k ->
                    val item = o.optJSONObject(k) ?: return@forEach
                    val at = item.optJSONArray("at")
                    parts[k] =
                        PetPart(
                            at?.optInt(0) ?: 0,
                            at?.optInt(1) ?: 0,
                            item.optJSONArray("rows").strList(),
                        )
                }
            }
            val poses = LinkedHashMap<String, List<String>>()
            root.optJSONObject("poses")?.let { o ->
                o.keys().forEach { k -> poses[k] = o.optJSONArray(k).strList() }
            }
            val glyphs = LinkedHashMap<String, List<String>>()
            root.optJSONObject("glyphs")?.let { o ->
                o.keys().forEach { k -> glyphs[k] = o.optJSONArray(k).strList() }
            }
            val clips = LinkedHashMap<String, List<PetFrame>>()
            root.optJSONObject("clips")?.let { o ->
                o.keys().forEach { k ->
                    val arr = o.optJSONArray(k) ?: return@forEach
                    val frames = ArrayList<PetFrame>(arr.length())
                    for (i in 0 until arr.length()) {
                        val f = arr.optJSONArray(i) ?: continue
                        val n = f.length()
                        if (n < 2) continue
                        frames.add(
                            PetFrame(
                                pose = f.optString(0),
                                ticks = f.optInt(1, 1).coerceAtLeast(1),
                                dx = if (n >= 4) f.optInt(2) else 0,
                                dy = if (n >= 4) f.optInt(3) else 0,
                                effect = if (n >= 5) f.optString(4).takeIf { it.isNotEmpty() } else null,
                            ),
                        )
                    }
                    clips[k] = frames
                }
            }
            val effects = LinkedHashMap<String, EffectSpec>()
            root.optJSONObject("effects")?.let { o ->
                o.keys().forEach { k ->
                    val item = o.optJSONObject(k) ?: return@forEach
                    effects[k] =
                        EffectSpec(
                            particles = parseParticles(item.optJSONArray("particles")),
                            cycle =
                                (0 until (item.optJSONArray("cycle")?.length() ?: 0)).map { i ->
                                    parseParticles(item.optJSONArray("cycle")?.optJSONArray(i))
                                },
                        )
                }
            }
            val accessories = LinkedHashMap<String, AccessorySpec>()
            root.optJSONObject("accessories")?.let { o ->
                o.keys().forEach { k ->
                    val item = o.optJSONObject(k) ?: return@forEach
                    accessories[k] =
                        AccessorySpec(
                            glyph = item.optString("glyph").takeIf { it.isNotEmpty() },
                            frames = item.optJSONArray("frames")?.strList()?.takeIf { it.isNotEmpty() },
                            color = item.optString("color", "#FFFFFF"),
                            periodMs = item.optInt("periodMs", 0).takeIf { it > 0 },
                            box = item.optInt("box", 0).takeIf { it > 0 },
                            reduced = item.optString("reduced").takeIf { it.isNotEmpty() },
                            bobEvery = item.optInt("bobEvery", 0).takeIf { it > 0 },
                            twinkleEvery = item.optInt("twinkleEvery", 0).takeIf { it > 0 },
                            twinkle = item.optString("twinkle").takeIf { it.isNotEmpty() },
                        )
                }
            }
            val props = LinkedHashMap<String, PropSpec>()
            root.optJSONObject("props")?.let { o ->
                o.keys().forEach { k ->
                    val item = o.optJSONObject(k) ?: return@forEach
                    props[k] =
                        PropSpec(
                            item.optString("glyph", "dot"),
                            item.optJSONArray("colors")?.strList().orEmpty(),
                        )
                }
            }
            val states = LinkedHashMap<String, PetStateSpec>()
            root.optJSONObject("states")?.let { o ->
                o.keys().forEach { k ->
                    val item = o.optJSONObject(k) ?: return@forEach
                    val variants = LinkedHashMap<String, VariantSpec>()
                    item.optJSONObject("variants")?.let { v ->
                        v.keys().forEach { vk ->
                            val vi = v.optJSONObject(vk) ?: return@forEach
                            variants[vk] =
                                VariantSpec(
                                    loop = vi.optString("loop"),
                                    enter = vi.optString("enter").takeIf { it.isNotEmpty() },
                                    exit = vi.optString("exit").takeIf { it.isNotEmpty() },
                                    prop = vi.optString("prop").takeIf { it.isNotEmpty() },
                                )
                        }
                    }
                    var fidget: FidgetSpec? = null
                    item.optJSONObject("fidget")?.let { f ->
                        val every = f.optJSONArray("every")
                        val picks = ArrayList<WeightedClip>()
                        val pickArr = f.optJSONArray("pick")
                        for (i in 0 until (pickArr?.length() ?: 0)) {
                            val p = pickArr?.optJSONArray(i) ?: continue
                            picks.add(WeightedClip(p.optString(0), p.optInt(1, 1).coerceAtLeast(1)))
                        }
                        // 周期必须至少为 1：0 会被消费方拿去做取模（tick % every）直接除零，
                        // 负数则每个 tick 都触发。下限与 periodMs 等字段保持一致。
                        val lo = (every?.optInt(0) ?: 60).coerceAtLeast(1)
                        val hi = (every?.optInt(1, lo) ?: lo).coerceAtLeast(1)
                        fidget = FidgetSpec(minOf(lo, hi), maxOf(lo, hi), picks)
                    }
                    var sleep: SleepSpec? = null
                    item.optJSONObject("sleep")?.let { s ->
                        sleep =
                            SleepSpec(
                                after = s.optInt("after", 2000).coerceAtLeast(1),
                                doze = s.optString("doze", "doze"),
                                loop = s.optString("loop", "sleep"),
                                wake = s.optString("wake", "wake"),
                                still = s.optString("still", "shut"),
                            )
                    }
                    var impatient: ImpatientSpec? = null
                    item.optJSONObject("impatient")?.let { s ->
                        impatient =
                            ImpatientSpec(
                                after = s.optInt("after", 500).coerceAtLeast(1),
                                loop = s.optString("loop"),
                            )
                    }
                    var badge: BadgeSpec? = null
                    item.optJSONObject("badge")?.let { b ->
                        badge =
                            BadgeSpec(
                                glyph = b.optString("glyph", "bang"),
                                color = b.optString("color", "#FF9F0A"),
                                x = b.optInt("x", 16),
                                hopEvery = b.optInt("hopEvery", 20).coerceAtLeast(1),
                                impatientHopEvery = b.optInt("impatientHopEvery", 8).coerceAtLeast(1),
                            )
                    }
                    var gaze: GazePoses? = null
                    item.optJSONObject("gaze")?.let { g ->
                        gaze =
                            GazePoses(
                                left = g.optString("left"),
                                right = g.optString("right"),
                                up = g.optString("up"),
                                down = g.optString("down"),
                                center = g.optString("center"),
                            )
                    }
                    states[k] =
                        PetStateSpec(
                            enter = item.optString("enter").takeIf { it.isNotEmpty() },
                            loop = item.optString("loop").takeIf { it.isNotEmpty() },
                            tintDim = item.optString("tint") == "dim",
                            accessory = item.optString("accessory").takeIf { it.isNotEmpty() },
                            still = item.optString("still", "base"),
                            stillDy = item.optInt("stillDy", 0),
                            seen = item.optString("seen").takeIf { it.isNotEmpty() },
                            fidget = fidget,
                            sleep = sleep,
                            impatient = impatient,
                            badge = badge,
                            variants = variants,
                            defaultVariant = item.optString("defaultVariant").takeIf { it.isNotEmpty() },
                            gaze = gaze,
                        )
                }
            }
            val ix = root.optJSONObject("interactions")
            // 只把显式声明的引用交给校验：未声明字段的默认名是解析器的兜底，
            // 不是皮肤作者的声明，缺了不该在加载期报错。
            val declaredClips = LinkedHashMap<String, String>()
            val declaredPoses = LinkedHashMap<String, String>()
            listOf(
                "notice", "pet", "petEnd", "press", "boopEnd", "held",
                "drop", "blink", "join", "leave", "appear", "vanish",
            ).forEach { k ->
                ix?.optString(k)?.takeIf { it.isNotEmpty() }?.let { declaredClips["interactions.$k"] = it }
            }
            listOf(
                "heldStill", "hop", "land", "blinkPose",
                "lookLeft", "lookRight", "lookUp", "lookDown", "lookCenter",
            ).forEach { k ->
                ix?.optString(k)?.takeIf { it.isNotEmpty() }?.let { declaredPoses["interactions.$k"] = it }
            }
            listOf("walkLeft", "walkRight").forEach { k ->
                ix?.optJSONArray(k)?.strList()?.forEachIndexed { i, p ->
                    if (p.isNotEmpty()) declaredPoses["interactions.$k[$i]"] = p
                }
            }
            val interactions =
                PetInteractions(
                    notice = ix?.optString("notice", "notice") ?: "notice",
                    pet = ix?.optString("pet", "pet") ?: "pet",
                    petEnd = ix?.optString("petEnd", "pet_end") ?: "pet_end",
                    press = ix?.optString("press", "press") ?: "press",
                    boopEnd = ix?.optString("boopEnd", "boop_end") ?: "boop_end",
                    held = ix?.optString("held", "held") ?: "held",
                    heldStill = ix?.optString("heldStill", "held_a") ?: "held_a",
                    drop = ix?.optString("drop", "drop") ?: "drop",
                    blink = ix?.optString("blink", "blink") ?: "blink",
                    join = ix?.optString("join", "emerge") ?: "emerge",
                    leave = ix?.optString("leave", "goodbye") ?: "goodbye",
                    appear = ix?.optString("appear", "pop_up") ?: "pop_up",
                    vanish = ix?.optString("vanish", "burrow") ?: "burrow",
                    hop = ix?.optString("hop", "air") ?: "air",
                    land = ix?.optString("land", "base") ?: "base",
                    blinkPose = ix?.optString("blinkPose", "shut") ?: "shut",
                    landDust = ix?.optString("landDust", "dust_small") ?: "dust_small",
                    walkLeft = ix?.optJSONArray("walkLeft")?.strList() ?: listOf("walk_l_a", "walk_l_b"),
                    walkRight = ix?.optJSONArray("walkRight")?.strList() ?: listOf("walk_r_a", "walk_r_b"),
                    lookLeft = ix?.optString("lookLeft", "look_l") ?: "look_l",
                    lookRight = ix?.optString("lookRight", "look_r") ?: "look_r",
                    lookUp = ix?.optString("lookUp", "look_up") ?: "look_up",
                    lookDown = ix?.optString("lookDown", "look_down") ?: "look_down",
                    lookCenter = ix?.optString("lookCenter", "base") ?: "base",
                )
            val grid = root.optJSONArray("grid")
            if (poses.isEmpty()) throw IllegalArgumentException("poses 为空")
            // schema 与网格在加载期校验：不受支持的版本/非法网格以前被静默接受，
            // 问题拖到渲染期才以空白宠物或除零的形式暴露。
            val schema = root.optInt("schema", 2)
            if (schema !in 2..3) {
                throw IllegalArgumentException("不受支持的皮肤 schema：$schema（支持 2~3）")
            }
            val cols = grid?.optInt(0) ?: 18
            val rows = grid?.optInt(1) ?: 10
            if (cols < 1 || rows < 1) {
                throw IllegalArgumentException("grid 尺寸非法：${cols}x$rows（各维必须 >= 1）")
            }
            val skin = PetSkin(
                schema = schema,
                id = root.optString("name", "skin"),
                title = root.optString("title", root.optString("name", "skin")),
                tickMs = root.optInt("tickMs", 60).coerceIn(16, 1000),
                cols = cols,
                rows = rows,
                palette = palette,
                parts = parts,
                poses = poses,
                glyphs = glyphs,
                clips = clips,
                effects = effects,
                accessories = accessories,
                states = states,
                interactions = interactions,
                props = props,
                declaredInteractionClips = declaredClips,
                declaredInteractionPoses = declaredPoses,
            )
            skin.validateReferences()
            return skin
        }

        private fun parseParticles(arr: JSONArray?): List<ParticleSpec> {
            if (arr == null) return emptyList()
            val out = ArrayList<ParticleSpec>(arr.length())
            for (i in 0 until arr.length()) {
                val p = arr.optJSONObject(i) ?: continue
                val colors = p.optJSONArray("colors")?.strList().orEmpty()
                val single = p.optString("color").takeIf { it.isNotEmpty() }
                out.add(
                    ParticleSpec(
                        count = (p.optInt("count", 1)).coerceIn(1, 64),
                        glyph = p.optString("glyph", "dot"),
                        colors = if (colors.isNotEmpty()) colors else listOfNotNull(single).ifEmpty { listOf("#FFFFFF") },
                        x = p.span("x") ?: Span.of(9.0),
                        y = p.span("y") ?: Span.of(5.0),
                        vx = p.span("vx"),
                        vy = p.span("vy"),
                        ay = p.optDouble("ay", 0.0),
                        life = p.span("life") ?: Span.of(10.0),
                        sway = p.optDouble("sway", 0.0),
                        pop = p.optBoolean("pop", false),
                        shrinkTo = p.optString("shrinkTo").takeIf { it.isNotEmpty() },
                    ),
                )
            }
            return out
        }
    }
}

/** 部件允许画到基框外的最大格数。手绘皮肤会用到负坐标（抬手、垂腿），但不会到几十格。 */
internal const val PET_PART_MAX_ORIGIN = 64

/** 一行像素的最大长度。基框 18×10 用不到这么宽，超了说明 manifest 被改过或损坏。 */
internal const val PET_PART_MAX_LINE = 256

/** 部件原点是可信的：坐标有界，包围盒就不会溢出成负宽度。 */
internal fun isPlausiblePartOrigin(
    x: Int,
    y: Int,
): Boolean =
    x >= -PET_PART_MAX_ORIGIN && x <= PET_PART_MAX_ORIGIN &&
        y >= -PET_PART_MAX_ORIGIN && y <= PET_PART_MAX_ORIGIN

/** 部件的行长度可信：避免为一行超长字符分配巨量内存。 */
internal fun isPlausiblePartLine(line: String): Boolean = line.length <= PET_PART_MAX_LINE

/** 一次栅格化后的姿态：包围盒 + 逐行字符。 */
data class PixelGrid(
    val minX: Int,
    val minY: Int,
    val width: Int,
    val height: Int,
    val rows: List<String>,
) {
    fun charAt(
        x: Int,
        y: Int,
    ): Char? {
        if (x < minX || x >= minX + width || y < minY || y >= minY + height) return null
        val ch = rows[y - minY][x - minX]
        return if (ch == '.') null else ch
    }
}
