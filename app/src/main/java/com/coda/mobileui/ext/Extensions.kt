package com.coda.mobileui.ext

import android.content.Context

/**
 * 拓展系统入口。
 *
 * 分类在这里登记，引擎在这里完成首次装载。宿主（启动内核、设置页）统一走这里拿引擎，
 * 避免各处重复注册分类导致行为不一致。以后开放工作区分类时，只需在 [categories] 里加一项。
 */
object Extensions {

    /** 当前开放的分类。顺序即装载与展示顺序。 */
    private val categories: List<ExtensionCategory> = listOf(TerminalCategory)

    /** 取引擎并确保分类已注册、拓展已装载。 */
    fun engine(context: Context): ExtensionEngine {
        val engine = ExtensionEngine.get(context)
        for (category in categories) engine.registerCategory(category)
        engine.ensureLoaded()
        return engine
    }

    /** 终端分类的对外能力；没有可用终端拓展时为 null。 */
    fun terminal(context: Context): TerminalCapability? = engine(context).terminalCapability()
}