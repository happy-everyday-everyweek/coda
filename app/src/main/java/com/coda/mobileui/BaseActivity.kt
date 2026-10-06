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
        config.fontScale = SettingsStore.FONT_SCALES[
            store.fontScaleIndex.coerceIn(SettingsStore.FONT_SCALES.indices)
        ]
        super.attachBaseContext(newBase.createConfigurationContext(config))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        val store = SettingsStore.get(this)
        AppCompatDelegate.setDefaultNightMode(store.nightMode)
        if (!store.autoColor) {
            setTheme(SettingsStore.ACCENT_THEMES[store.accentIndex.coerceIn(SettingsStore.ACCENT_THEMES.indices)])
        }
        super.onCreate(savedInstanceState)
        if (store.autoColor) {
            // 系统支持动态取色时跟随壁纸；不支持的设备会静默回退到静态色板。
            DynamicColors.applyToActivityIfAvailable(this)
        }
        // 图标颜色跟随主色，每次进入页面同步一次，保证与设置一致。
        AppIconManager.apply(this, store.iconSlot)
    }
}