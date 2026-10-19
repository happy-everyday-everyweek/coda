package com.coda.mobileui

import android.content.Context
import android.content.SharedPreferences
import androidx.appcompat.app.AppCompatDelegate

/**
 * 用户设置存储。
 *
 * 外观相关的项已经是真实功能：主题模式、自定义主色、自动取色、界面字号都会持久化，
 * 并在页面重建后立即生效。
 */
class SettingsStore private constructor(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** 主题模式：随系统 / 浅色 / 深色。 */
    var themeMode: Int
        get() = prefs.getInt(KEY_THEME_MODE, MODE_SYSTEM)
        set(value) = prefs.edit().putInt(KEY_THEME_MODE, value).apply()

    /** 自动取色：开启且系统支持时跟随壁纸取色，否则使用自定义主色。 */
    var autoColor: Boolean
        get() = prefs.getBoolean(KEY_AUTO_COLOR, true)
        set(value) = prefs.edit().putBoolean(KEY_AUTO_COLOR, value).apply()

    /** 自定义主色（对应 ACCENT_THEMES 的下标）。 */
    var accentIndex: Int
        get() = prefs.getInt(KEY_ACCENT, 0)
        set(value) = prefs.edit().putInt(KEY_ACCENT, value).apply()

    /** 界面字号档位（对应 FONT_SCALES 的下标）。 */
    var fontScaleIndex: Int
        get() = prefs.getInt(KEY_FONT_SCALE, 1)
        set(value) = prefs.edit().putInt(KEY_FONT_SCALE, value).apply()

    /** 代码显示：显示行号。 */
    var showLineNumbers: Boolean
        get() = prefs.getBoolean(KEY_SHOW_LINE_NUMBERS, true)
        set(value) = prefs.edit().putBoolean(KEY_SHOW_LINE_NUMBERS, value).apply()

    /** 代码显示：长行自动换行。 */
    var wrapLongLines: Boolean
        get() = prefs.getBoolean(KEY_WRAP_LONG_LINES, false)
        set(value) = prefs.edit().putBoolean(KEY_WRAP_LONG_LINES, value).apply()

    /** 代码字号档位（对应 CODE_FONT_SIZES 的下标）。 */
    var codeFontSizeIndex: Int
        get() = prefs.getInt(KEY_CODE_FONT_SIZE, 1)
        set(value) = prefs.edit().putInt(KEY_CODE_FONT_SIZE, value).apply()

    /** 浅色代码主题（对应 CODE_THEMES 的下标）。 */
    var codeThemeLightIndex: Int
        get() = prefs.getInt(KEY_CODE_THEME_LIGHT, 0)
        set(value) = prefs.edit().putInt(KEY_CODE_THEME_LIGHT, value).apply()

    /** 深色代码主题（对应 CODE_THEMES 的下标）。 */
    var codeThemeDarkIndex: Int
        get() = prefs.getInt(KEY_CODE_THEME_DARK, 0)
        set(value) = prefs.edit().putInt(KEY_CODE_THEME_DARK, value).apply()
    /** 发送消息后自动滚动到底部。 */
    var chatAutoScroll: Boolean
        get() = prefs.getBoolean(KEY_CHAT_AUTO_SCROLL, true)
        set(value) = prefs.edit().putBoolean(KEY_CHAT_AUTO_SCROLL, value).apply()
    /** 默认发送模式（0=Yolo，1=Build，2=Chat）。 */
    var defaultSendMode: Int
        get() = prefs.getInt(KEY_DEFAULT_SEND_MODE, 1).coerceIn(0, 2)
        set(value) = prefs.edit().putInt(KEY_DEFAULT_SEND_MODE, value.coerceIn(0, 2)).apply()
    /** 斜杠命令最近使用（最多 5 个，逗号分隔存储）。 */
    var slashRecent: List<String>
        get() = (prefs.getString(KEY_SLASH_RECENT, "") ?: "")
            .split(',').map { it.trim() }.filter { it.isNotEmpty() }
        set(value) = prefs.edit().putString(KEY_SLASH_RECENT, value.take(5).joinToString(",")).apply()
    /** 记录一次斜杠命令使用。 */
    fun addSlashRecent(name: String) {
        slashRecent = listOf(name) + slashRecent.filter { it != name }
    }
    /** 工作区根目录；为空表示尚未选定，由默认位置接管。 */
    var workspaceRoot: String
        get() = prefs.getString(KEY_WORKSPACE_ROOT, "") ?: ""
        set(value) = prefs.edit().putString(KEY_WORKSPACE_ROOT, value).apply()

    /** 主题模式对应的 AppCompat 夜间模式。 */
    val nightMode: Int
        get() = when (themeMode) {
            MODE_LIGHT -> AppCompatDelegate.MODE_NIGHT_NO
            MODE_DARK -> AppCompatDelegate.MODE_NIGHT_YES
            else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
        }

    /** 自定义主色的十六进制文本（仅用于回显）。 */
    var customAccentHex: String
        get() = prefs.getString(KEY_ACCENT_HEX, "") ?: ""
        set(value) = prefs.edit().putString(KEY_ACCENT_HEX, value).apply()

    // ---------------------------------------------------------------- 宠物

    /** 宠物形象 id（对应 PetSkins.all 里的一份皮肤）。 */
    var petSkinId: String
        // 默认形象是 Clawd（原项目的默认）。旧版本存过 "coda"，那是我自造的形象、
        // 现在已不存在；读到它时回退到 Clawd，否则会取不到皮肤而整只不显示。
        get() = (prefs.getString(KEY_PET_SKIN, "clawd") ?: "clawd").let {
            if (it == "coda" || it.isEmpty()) "clawd" else it
        }
        set(value) = prefs.edit().putString(KEY_PET_SKIN, value).apply()

    /** 宠物总开关。关掉后输入框上方不再出现，设置页里的装饰也一并隐藏。 */
    var petEnabled: Boolean
        get() = prefs.getBoolean(KEY_PET_ENABLED, true)
        set(value) = prefs.edit().putBoolean(KEY_PET_ENABLED, value).apply()

    /** 输入框上方的提示条：只显示球泡、不显示宠物本体。 */
    var petBubbleOnly: Boolean
        get() = prefs.getBoolean(KEY_PET_BUBBLE_ONLY, false)
        set(value) = prefs.edit().putBoolean(KEY_PET_BUBBLE_ONLY, value).apply()

    /** 减少动态效果；跟随系统无障碍设置时可以单独覆盖。 */
    var petReduceMotion: Boolean
        get() = prefs.getBoolean(KEY_PET_REDUCE_MOTION, false)
        set(value) = prefs.edit().putBoolean(KEY_PET_REDUCE_MOTION, value).apply()

    /**
     * 实际生效的"减少动态效果"。
     *
     * 用户手动打开，或者系统里开了「移除动画」时为真。系统那档是硬性偏好，
     * 不该被应用里的默认值盖掉，所以这里取或。
     */
    fun petReduceMotionEffective(ctx: Context): Boolean {
        if (petReduceMotion) return true
        val scale = runCatching {
            android.provider.Settings.Global.getFloat(
                ctx.contentResolver,
                android.provider.Settings.Global.ANIMATOR_DURATION_SCALE,
                1f,
            )
        }.getOrDefault(1f)
        return scale == 0f
    }

    // ------------------------------------------------- 宠物出现在哪些场景

    /** 输入框上方。这里是主战场，默认开。 */
    var petInComposer: Boolean
        get() = prefs.getBoolean(KEY_PET_IN_COMPOSER, true)
        set(value) = prefs.edit().putBoolean(KEY_PET_IN_COMPOSER, value).apply()

    /** 宠物设置页顶部那只趴着的。默认开，关掉后连设置页里的装饰也不出现。 */
    var petInSettings: Boolean
        get() = prefs.getBoolean(KEY_PET_IN_SETTINGS, true)
        set(value) = prefs.edit().putBoolean(KEY_PET_IN_SETTINGS, value).apply()

    /** 其它设置页（外观、使用统计等）。默认关，避免到处都有它。 */
    var petInOtherSettings: Boolean
        get() = prefs.getBoolean(KEY_PET_IN_OTHER_SETTINGS, false)
        set(value) = prefs.edit().putBoolean(KEY_PET_IN_OTHER_SETTINGS, value).apply()

    /** 按场景判断输入框上方要不要显示（总开关 + 场景开关同时成立才显示）。 */
    fun petComposerVisible(): Boolean = petEnabled && petInComposer

    /** 按场景判断某个设置页要不要那只趴着的。 */
    fun petSettingsVisible(pageKey: String): Boolean {
        if (!petEnabled) return false
        return if (pageKey == "pet") petInSettings else petInOtherSettings
    }

    /** 当前生效的主色名称（自动取色时为系统取色）。 */
    fun accentName(): String = when {
        autoColor -> "自动取色"
        customAccentHex.isNotEmpty() -> "自定义 $customAccentHex"
        else -> ACCENT_NAMES[accentIndex.coerceIn(ACCENT_NAMES.indices)]
    }

    /** 当前应启用的图标套件：0 = 自动取色，其余 = 色板下标 + 1。 */
    val iconSlot: Int
        get() = if (autoColor) 0 else accentIndex.coerceIn(ACCENT_NAMES.indices) + 1

    /** 当前界面字号名称。 */
    fun fontScaleName(): String =
        FONT_SCALE_NAMES[fontScaleIndex.coerceIn(FONT_SCALE_NAMES.indices)]

    /** 会话最近一次被看到的时间（抽屉里的「已完成」标记据此判断未读）。 */
    fun sessionSeenAt(sessionId: String): Long = prefs.getLong(KEY_SESSION_SEEN + sessionId, 0L)

    /** 记录会话已被看到。 */
    fun markSessionSeen(sessionId: String, at: Long = System.currentTimeMillis()) {
        prefs.edit().putLong(KEY_SESSION_SEEN + sessionId, at).apply()
    }

    /** 当前代码字号名称。 */
    fun codeFontSizeName(): String =
        CODE_FONT_SIZE_NAMES[codeFontSizeIndex.coerceIn(CODE_FONT_SIZE_NAMES.indices)]

    companion object {
        private const val PREFS_NAME = "zcode_ui_settings"
        private const val KEY_THEME_MODE = "theme_mode"
        private const val KEY_AUTO_COLOR = "auto_color"
        private const val KEY_ACCENT = "accent"
        private const val KEY_ACCENT_HEX = "accent_hex"
        private const val KEY_FONT_SCALE = "font_scale"
        private const val KEY_SHOW_LINE_NUMBERS = "code_show_line_numbers"
        private const val KEY_WRAP_LONG_LINES = "code_wrap_long_lines"
        private const val KEY_CODE_FONT_SIZE = "code_font_size"
        private const val KEY_CODE_THEME_LIGHT = "code_theme_light"
        private const val KEY_CODE_THEME_DARK = "code_theme_dark"
        private const val KEY_CHAT_AUTO_SCROLL = "chat_auto_scroll"
        private const val KEY_DEFAULT_SEND_MODE = "default_send_mode"
        private const val KEY_SLASH_RECENT = "slash_recent"
        private const val KEY_WORKSPACE_ROOT = "workspace_root"
        private const val KEY_SESSION_SEEN = "session_seen_"
        private const val KEY_PET_SKIN = "pet_skin"
        private const val KEY_PET_ENABLED = "pet_enabled"
        private const val KEY_PET_BUBBLE_ONLY = "pet_bubble_only"
        private const val KEY_PET_REDUCE_MOTION = "pet_reduce_motion"
        private const val KEY_PET_IN_COMPOSER = "pet_in_composer"
        private const val KEY_PET_IN_SETTINGS = "pet_in_settings"
        private const val KEY_PET_IN_OTHER_SETTINGS = "pet_in_other_settings"

        const val MODE_SYSTEM = 0
        const val MODE_LIGHT = 1
        const val MODE_DARK = 2

        /** 主题模式选项：设置键 -> 标题。 */
        val THEME_MODES = arrayOf(
            "theme=$MODE_SYSTEM" to "跟随系统",
            "theme=$MODE_LIGHT" to "浅色",
            "theme=$MODE_DARK" to "深色",
        )

        /** 自定义主色：设置键 -> 标题，下标与 ACCENT_THEMES 对应。 */
        /** 主色色板；顺序与图标套件一一对应。 */
        val ACCENT_NAMES = arrayOf("黑", "蓝", "紫", "绿", "琥珀", "玫红", "青", "橙")

        val ACCENT_COLORS = intArrayOf(
            0xFF1B1B1B.toInt(),
            0xFF1D5FD0.toInt(),
            0xFF6A4CA8.toInt(),
            0xFF2E6B45.toInt(),
            0xFF8A6100.toInt(),
            0xFFB3325C.toInt(),
            0xFF0F7B8A.toInt(),
            0xFFB4551C.toInt(),
        )

        val ACCENT_THEMES = intArrayOf(
            R.style.Theme_Coda,
            R.style.Theme_Coda_Blue,
            R.style.Theme_Coda_Violet,
            R.style.Theme_Coda_Green,
            R.style.Theme_Coda_Amber,
            R.style.Theme_Coda_Rose,
            R.style.Theme_Coda_Teal,
            R.style.Theme_Coda_Orange,
        )

        /**
         * 自定义颜色落到最接近的色板档位。
         * 低版本系统无法由任意种子色生成整套 MD3 色板，因此用最近色近似，
         * 保证主题与图标在任何系统上都能跟着变。
         */
        fun nearestAccentIndex(color: Int): Int {
            var best = 0
            var bestDistance = Long.MAX_VALUE
            ACCENT_COLORS.forEachIndexed { index, candidate ->
                val distance = colorDistance(color, candidate)
                if (distance < bestDistance) {
                    bestDistance = distance
                    best = index
                }
            }
            return best
        }

        /** 带权重的 RGB 距离（人眼对绿色更敏感）。 */
        private fun colorDistance(a: Int, b: Int): Long {
            val dr = ((a shr 16 and 0xFF) - (b shr 16 and 0xFF)).toLong()
            val dg = ((a shr 8 and 0xFF) - (b shr 8 and 0xFF)).toLong()
            val db = ((a and 0xFF) - (b and 0xFF)).toLong()
            return dr * dr * 3 + dg * dg * 4 + db * db * 2
        }

        val FONT_SCALES = floatArrayOf(0.85f, 1.0f, 1.15f, 1.3f)
        val FONT_SCALE_NAMES = arrayOf("小", "默认", "大", "特大")

        val CODE_FONT_SIZES = floatArrayOf(11f, 13f, 15f, 17f)
        val CODE_FONT_SIZE_NAMES = arrayOf("小", "默认", "大", "特大")

        val CODE_THEMES = arrayOf("默认", "GitHub", "Monokai", "Solarized", "One Dark")

        fun codeThemeName(index: Int): String = CODE_THEMES[index.coerceIn(CODE_THEMES.indices)]

        @Volatile
        private var instance: SettingsStore? = null

        fun get(context: Context): SettingsStore =
            instance ?: synchronized(this) {
                instance ?: SettingsStore(context).also { instance = it }
            }
    }
}