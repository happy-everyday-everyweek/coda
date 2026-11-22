package com.coda.mobileui

import android.content.Context
import android.content.res.Configuration
import android.os.Bundle
import androidx.appcompat.app.AppCompatDelegate
import com.google.android.material.color.DynamicColors

/**
 * 全部页面的公共基类，负责把用户设置真正作用到界面上：
 * 界面字号、自定义主色、自动取色、深浅主题。
 */
open class BaseActivity : androidx.appcompat.app.AppCompatActivity() {
    override fun attachBaseContext(newBase: Context) {
        val store = SettingsStore.get(newBase)
        val config = Configuration(newBase.resources.configuration)
        // 应用档位是相对倍率，乘在系统字号之上：绝对赋值会把无障碍大字号
        // 还原成应用预设，依赖大字号阅读的用户进应用后字反而变小。
        config.fontScale =
            newBase.resources.configuration.fontScale *
                SettingsStore.FONT_SCALES[
                    store.fontScaleIndex.coerceIn(SettingsStore.FONT_SCALES.indices),
                ]
        super.attachBaseContext(newBase.createConfigurationContext(config))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        val store = SettingsStore.get(this)
        AppCompatDelegate.setDefaultNightMode(store.nightMode)
        // 自动取色只在系统真的支持动态取色时生效；Android 8–11 与没有 Material You
        // 取色的设备保持静态色板，此时必须回落到用户已选的主色，否则选了也不生效。
        val dynamicColor =
            store.autoColor &&
                android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S &&
                DynamicColors.isDynamicColorAvailable()
        if (!dynamicColor) {
            setTheme(SettingsStore.ACCENT_THEMES[store.accentIndex.coerceIn(SettingsStore.ACCENT_THEMES.indices)])
        }
        super.onCreate(savedInstanceState)
        if (dynamicColor) {
            DynamicColors.applyToActivityIfAvailable(this)
        }
        // 图标颜色跟随主色，每次进入页面同步一次，保证与设置一致。
        AppIconManager.apply(this, store.iconSlot)
    }
}
