package com.coda.mobileui.core

import java.util.Random
import kotlin.math.roundToInt
import kotlin.math.sin

/** 要画的一个字形：x/y 是它在基框坐标里的左上格（y 向下，地面 = 第 rows 行）。 */
class GlyphDraw(
    val glyph: String,
    val color: String,
    val x: Int,
    val y: Int,
    val alpha: Double,
)

/** 一个 tick 的完整绘制指令；相等的快照画出完全一样的画面，可直接跳过重绘。 */
class ActorSnapshot(
    val visible: Boolean,
    val pose: String,
    /** 帧位移 + 摆动，格。 */
    val bodyX: Int,
    /** 帧位移 + 抬起，格，向上为正；决定地面裁切。 */
    val bodyY: Int,
    val tintDim: Boolean,
    val accessory: GlyphDraw?,
    val badge: GlyphDraw?,
    val particles: List<GlyphDraw>,
    val props: List<GlyphDraw>,
    val shadowWidth: Int,
)

/** 四舍五入到整数格。粒子在类外，所以放成文件级函数。 */
private fun round(v: Double): Int = v.roundToInt()

/** 一个活着的粒子。坐标为基框格，y 向下，地面为第 rows 行；速度单位是格/秒。 */
private class Particle(
    var glyph: String,
    val color: String,
    var x: Double,
    var y: Double,
    var vx: Double,
    var vy: Double,
    val ay: Double,
    val sway: Double,
    val life: Int,
    var age: Int,
    val pop: Boolean,
    val shrinkTo: String?,
) {
    fun step(dt: Double) {
        vy += ay * dt
        x += vx * dt
        y += vy * dt
        age++
    }

    val alive: Boolean get() = age < life

    fun draw(): GlyphDraw {
        val swayShift = round(sin(age * 0.5) * sway)
        var gx = round(x + swayShift)
        var gy = round(y) + if (pop && age < 2) -1 else 0
        var g = glyph
        if (shrinkTo != null && age > life / 2.0) {
            g = shrinkTo
            gx += 1
            gy += 1
        }
        val alpha = if (age > 0.65 * life) 0.5 else 1.0
        return GlyphDraw(g, color, gx, gy, alpha)
    }
}

/**
 * 一个宠物的运动逻辑。
 *
 * 严格照 critter 的 MotionActor 实现（MIT）：
 * 三层播放优先级从高到低是一次性片段（play）、模式循环（setMode，如被拎起）与基础循环（setLoop）。
 * 每 tick 推进一次：先跑"脑子"（小动作、睡眠、不耐烦、变体防抖），时钟加一，粒子前进，
 * 然后推进当前片段的关键帧。输出一个 ActorSnapshot，画面不变就不重绘。
 *
 * 不是线程安全的，只在一个线程（主线程）上使用。
 */
class PetActor(
    var skin: PetSkin,
    seed: Long = System.nanoTime(),
) {
    private val rng = Random(seed)

    /** 减少动态效果：不播进场、不播粒子，姿态直接落到 still。 */
    var reduceMotion: Boolean = false

    /** 隐藏时不画，也不让小动作把时钟弄醒。 */
    var hidden: Boolean = false

    /** 目光方向，由外部根据指针位置设置。 */
    var gaze: GazeZone? = null

    var mood: PetMood = PetMood.IDLE
        private set

    private var clock = 0
    private var baseClip: List<PetFrame> = emptyList()
    private var baseName: String? = null
    private var modeClip: List<PetFrame>? = null
    private var modeName: String? = null
    private var current: List<PetFrame> = emptyList()
    private var currentName: String? = null
    private var currentIndex = 0
    private var keyTicks = 0
    private var onceActive = false

    private var asleep = false
    private var asleepStill: String? = null
    private var idleTicks = 0
    private var waitTicks = 0
    private var fidgetIn = 80
    private var badgeVisible = true
    private var badgeAge = 99

    private var variant = ""
    private var desiredVariant = ""
    private var desiredTicks = 0
    private var variantShownTicks = 0

    private var accessoryName: String? = null
    private var propName: String? = null
    private var particles = ArrayList<Particle>()
    private val effectCycles = HashMap<String, Int>()

    init {
        variant = skin.state(PetMood.RUNNING).defaultVariant ?: ""
        desiredVariant = variant
        accessoryName = skin.state(PetMood.IDLE).accessory
        setLoop(stateLoopName())
    }

    // ------------------------------------------------------------ 播放

    private fun stateLoopName(): String {
        val st = skin.state(mood)
        if (asleep) st.sleep?.let { return it.loop }
        if (mood == PetMood.RUNNING) variantSpec(variant)?.let { return it.loop }
        if (isImpatient) skin.state(PetMood.WAITING).impatient?.let { return it.loop }
        return st.loop ?: ""
    }

    private fun variantSpec(name: String): VariantSpec? = skin.state(PetMood.RUNNING).variants[name]

    private val isImpatient: Boolean
        get() {
            if (mood != PetMood.WAITING) return false
            val imp = skin.state(PetMood.WAITING).impatient ?: return false
            return waitTicks >= imp.after
        }

    private fun setLoop(name: String) {
        baseClip = skin.clip(name)
        baseName = name
        if (!onceActive && modeClip == null) start(baseClip, baseName)
    }

    /** 一次性片段，播完回到循环。 */
    fun play(name: String) {
        val clip = skin.clip(name)
        if (clip.isEmpty()) return
        onceActive = true
        start(clip, name)
    }

    private fun start(
        clip: List<PetFrame>,
        name: String?,
    ) {
        current = clip
        currentName = name
        currentIndex = 0
        keyTicks = 0
        enterKey()
    }

    private fun resumeLoop() {
        val m = modeClip
        if (m != null) start(m, modeName) else start(baseClip, baseName)
    }

    private fun enterKey() {
        current.getOrNull(currentIndex)?.effect?.let { emit(it) }
    }

    /** 触发一次粒子特效。名字以 @ 开头的是引擎内部事件，不是粒子。 */
    private fun emit(effect: String) {
        if (effect.startsWith("@")) return
        if (reduceMotion) return
        val spec = skin.effects[effect] ?: return
        val turn = effectCycles[effect] ?: 0
        effectCycles[effect] = turn + 1
        particles.addAll(spawn(spec, turn))
    }

    private fun spawn(
        spec: EffectSpec,
        cycleIndex: Int,
    ): List<Particle> {
        val group = ArrayList<ParticleSpec>(spec.particles)
        if (spec.cycle.isNotEmpty()) group.addAll(spec.cycle[cycleIndex % spec.cycle.size])
        val out = ArrayList<Particle>()
        for (p in group) {
            for (i in 0 until p.count.coerceAtLeast(1)) {
                val color = p.colors[i % p.colors.size]
                out.add(
                    Particle(
                        glyph = p.glyph,
                        color = color,
                        x = p.x.sample(rng),
                        y = p.y.sample(rng),
                        vx = p.vx?.sample(rng) ?: 0.0,
                        vy = p.vy?.sample(rng) ?: 0.0,
                        ay = p.ay,
                        sway = p.sway,
                        life = p.life.sampleInt(rng).coerceIn(1, 300),
                        age = 0,
                        pop = p.pop,
                        shrinkTo = p.shrinkTo,
                    ),
                )
            }
        }
        return out
    }

    // ------------------------------------------------------------ 状态

    /**
     * 切状态。
     *
     * 与原项目一致：先播旧变体的退出（只有 running → 别的状态才有），再播新状态的进场。
     * 减少动态效果时两端都不播，直接落到 still。
     */
    fun setMood(
        next: PetMood,
        animate: Boolean = true,
    ) {
        if (next == mood && currentName != null) return
        val st = skin.state(next)
        mood = next
        asleep = false
        asleepStill = null
        idleTicks = 0
        waitTicks = 0
        accessoryName = st.accessory
        if (next == PetMood.RUNNING) {
            variant = desiredVariant
            desiredTicks = 0
            variantShownTicks = 0
        }
        propName = if (next == PetMood.RUNNING) variantSpec(variant)?.prop else null
        setLoop(stateLoopName())
        fidgetIn = 40 + rng.nextInt(51)
        badgeVisible = reduceMotion || !animate
        if (!animate) return
        if (reduceMotion) {
            onceActive = false
            return
        }
        val enter =
            if (next == PetMood.RUNNING) {
                variantSpec(variant)?.enter ?: st.enter
            } else {
                st.enter
            }
        if (enter != null) {
            badgeVisible = true
            badgeAge = 0
            play(enter)
        } else {
            onceActive = false
            // 从"做完了"回到空闲时用 seen 收尾，这是原项目的行为
            if (next == PetMood.IDLE && skin.state(PetMood.DONE).seen != null) {
                play(skin.state(PetMood.DONE).seen!!)
            }
        }
    }

    /** 记录会话正在做哪类事；传 null 用默认变体。运行中会做防抖。 */
    fun setActivity(activity: String?) {
        val running = skin.state(PetMood.RUNNING)
        val v =
            if (activity != null && running.variants.containsKey(activity)) {
                activity
            } else {
                running.defaultVariant ?: ""
            }
        if (v == desiredVariant) return
        desiredVariant = v
        desiredTicks = 0
    }

    fun wake() {
        if (!asleep) return
        asleep = false
        asleepStill = null
        idleTicks = 0
        setLoop(stateLoopName())
        skin
            .state(mood)
            .sleep
            ?.wake
            ?.let { play(it) }
    }

    /** 点击逗一下：播一次 pet 片段。 */
    fun pet() {
        wake()
        skin.interactions.pet
            .takeIf { it.isNotEmpty() }
            ?.let { play(it) }
    }

    // ------------------------------------------------------------ 推进

    fun step() {
        brainStep()
        clock++
        badgeAge++
        val dt = skin.tickMs / 1000.0
        for (p in particles) p.step(dt)
        particles.removeAll { !it.alive }
        val spec = accessorySpec
        if (spec?.twinkleEvery != null && spec.twinkle != null && clock % spec.twinkleEvery == 0) {
            emit(spec.twinkle)
        }
        advanceKey()
    }

    private fun brainStep() {
        val st = skin.state(mood)
        if (mood == PetMood.IDLE && !asleep) {
            val sleep = st.sleep
            if (sleep != null) {
                idleTicks++
                if (idleTicks >= sleep.after) {
                    asleep = true
                    setLoop(sleep.loop)
                    play(sleep.doze)
                    return
                }
            }
        }
        if (mood == PetMood.WAITING) {
            val imp = st.impatient
            if (imp != null) {
                waitTicks++
                if (waitTicks == imp.after) setLoop(imp.loop)
            }
        }
        if (mood == PetMood.RUNNING) debounceVariant()
        val fidget = st.fidget
        if (fidget != null && !onceActive && !asleep && !reduceMotion && !hidden) {
            fidgetIn--
            if (fidgetIn <= 0) {
                pickWeighted(fidget.pick)?.let { play(it) }
                fidgetIn = fidget.everyMin + rng.nextInt((fidget.everyMax - fidget.everyMin).coerceAtLeast(1) + 1)
            }
        }
    }

    private fun pickWeighted(list: List<WeightedClip>): String? {
        if (list.isEmpty()) return null
        val total = list.sumOf { it.weight }
        var r = rng.nextDouble() * total
        for (w in list) {
            r -= w.weight
            if (r <= 0) return w.clip
        }
        return list.first().clip
    }

    private fun debounceVariant() {
        variantShownTicks++
        if (desiredVariant == variant) return
        desiredTicks++
        if (desiredTicks >= VARIANT_SETTLE_TICKS && variantShownTicks >= VARIANT_MIN_SHOW_TICKS) {
            switchVariant(desiredVariant)
        }
    }

    private fun switchVariant(v: String) {
        val old = variantSpec(variant)
        val new = variantSpec(v)
        variant = v
        desiredTicks = 0
        variantShownTicks = 0
        propName = new?.prop
        setLoop(stateLoopName())
        if (reduceMotion) return
        val exit = old?.exit
        val enter = new?.enter
        if (exit != null) {
            play(exit)
            // 退出播完再接进场；这里不做回调队列，直接在 advanceKey 结束时判断
            pendingEnter = enter
        } else if (enter != null) {
            play(enter)
        }
    }

    private var pendingEnter: String? = null

    private fun advanceKey() {
        val key =
            current.getOrNull(currentIndex) ?: run {
                if (onceActive) finishOnce()
                return
            }
        keyTicks++
        if (keyTicks < key.ticks) return
        keyTicks = 0
        currentIndex++
        if (currentIndex >= current.size) {
            if (onceActive) {
                finishOnce()
                return
            }
            currentIndex = 0
        }
        enterKey()
    }

    private fun finishOnce() {
        onceActive = false
        val next = pendingEnter
        pendingEnter = null
        if (next != null && skin.clip(next).isNotEmpty()) {
            onceActive = true
            start(skin.clip(next), next)
        } else {
            resumeLoop()
        }
    }

    // ------------------------------------------------------------ 输出

    fun snapshot(): ActorSnapshot {
        val frame = current.getOrNull(currentIndex)
        val pose: String
        val dx: Int
        val dy: Int
        val st = skin.state(mood)
        if (reduceMotion) {
            pose = if (asleep) (st.sleep?.still ?: st.still) else st.still
            dx = 0
            dy = st.stillDy
        } else {
            pose = frame?.pose ?: skin.interactions.lookCenter
            dx = frame?.dx ?: 0
            dy = frame?.dy ?: 0
        }
        val markers = !hidden
        val markerOk = markers && !asleep && dy > -6 && dy < 7
        val particleDraws =
            if (hidden) {
                emptyList()
            } else {
                particles.map { p ->
                    val d = p.draw()
                    GlyphDraw(d.glyph, d.color, d.x + dx, d.y, d.alpha)
                }
            }
        return ActorSnapshot(
            visible = !hidden,
            pose = pose,
            bodyX = dx,
            bodyY = dy,
            tintDim = st.tintDim,
            accessory = if (markerOk) accessoryDraw() else null,
            badge = if (markerOk) badgeDraw() else null,
            particles = particleDraws,
            props = if (hidden) emptyList() else propDraws(dx),
            shadowWidth = shadowWidth(dy),
        )
    }

    private val accessorySpec: AccessorySpec?
        get() = accessoryName?.let { skin.accessories[it] }

    /** 头顶挂件：转圈用 frames + periodMs，静止用 glyph，可上下浮动。 */
    private fun accessoryDraw(): GlyphDraw? {
        val spec = accessorySpec ?: return null
        var bob = 0
        val glyph: String
        val top: Int
        val frames = spec.frames
        if (frames != null && frames.isNotEmpty()) {
            glyph =
                if (reduceMotion) {
                    spec.reduced ?: frames[0]
                } else {
                    frames[spinnerFrame(spec, frames)]
                }
            val h = glyphHeight(glyph)
            val box = spec.box ?: h
            top = -2 - box + ((box - h) / 2.0).toInt()
        } else {
            val g = spec.glyph ?: return null
            glyph = g
            top = -2 - glyphHeight(g)
            val every = spec.bobEvery
            if (every != null && every > 0 && !reduceMotion && (clock / every) % 2 == 1) bob = -1
        }
        return GlyphDraw(glyph, spec.color, centeredX(glyph), top + bob, 1.0)
    }

    /** 等确认时头顶那颗橙色的 !。 */
    private fun badgeDraw(): GlyphDraw? {
        val badge = skin.state(mood).badge ?: return null
        if (!badgeVisible) return null
        val hop = if (!reduceMotion && badgeHops(badge)) -1 else 0
        return GlyphDraw(
            badge.glyph,
            badge.color,
            badge.x,
            -2 - glyphHeight(badge.glyph) + hop,
            1.0,
        )
    }

    private fun badgeHops(badge: BadgeSpec): Boolean {
        val period = if (isImpatient) badge.impatientHopEvery else badge.hopEvery
        return badgeAge < 2 || (period > 0 && clock % period < 2)
    }

    /** 抛接的球，只在对应变体的循环播放时出现。 */
    private fun propDraws(dx: Int): List<GlyphDraw> {
        if (reduceMotion || mood != PetMood.RUNNING) return emptyList()
        val name = propName ?: return emptyList()
        val prop = skin.props[name] ?: return emptyList()
        val loop = variantSpec(variant)?.loop ?: return emptyList()
        if (currentName != loop) return emptyList()
        return prop.colors.mapIndexed { i, color ->
            val ph = ((clock + 6 * i) % 18) / 18.0
            GlyphDraw(
                prop.glyph,
                color,
                3 + round(11 * ph) + dx,
                -2 - round(7 * sin(Math.PI * ph)),
                1.0,
            )
        }
    }

    private fun spinnerFrame(
        spec: AccessorySpec,
        frames: List<String>,
    ): Int {
        val period = spec.periodMs ?: return 0
        if (period <= 0) return 0
        val t = clock.toDouble() * skin.tickMs
        val i = round((1 - kotlin.math.cos(2 * Math.PI * t / period)) / 2 * (frames.size - 1))
        return i.coerceIn(0, frames.size - 1)
    }

    private fun glyphHeight(name: String): Int = skin.glyph(name).size

    private fun centeredX(glyph: String): Int {
        val w = skin.glyph(glyph).firstOrNull()?.length ?: 0
        return round(skin.cols / 2.0 - w / 2.0)
    }

    /** 离地越高影子越小；贴地时没有影子。 */
    private fun shadowWidth(air: Int): Int =
        when {
            air <= 0 -> 0
            air <= 2 -> 10
            air <= 5 -> 6
            else -> 2
        }

    companion object {
        private const val VARIANT_SETTLE_TICKS = 20
        private const val VARIANT_MIN_SHOW_TICKS = 40
    }
}
