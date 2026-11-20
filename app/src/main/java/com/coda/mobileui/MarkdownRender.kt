package com.coda.mobileui

import android.content.Context
import io.noties.markwon.Markwon
import io.noties.markwon.ext.strikethrough.StrikethroughPlugin
import io.noties.markwon.ext.tables.TablesPlugin
import io.noties.markwon.ext.tasklist.TaskListPlugin

/**
 * 全应用统一的 Markdown 渲染器。
 *
 * 只注册 CorePlugin 时表格、删除线、任务列表会原样显示成符号；三处渲染点
 * （主聊天、Coda Bot、文件预览）必须用同一套插件，否则同一段文本在不同页面长得不一样。
 */
object MarkdownRender {
    fun create(ctx: Context): Markwon =
        Markwon
            .builder(ctx)
            .usePlugin(StrikethroughPlugin.create())
            .usePlugin(TablesPlugin.create())
            .usePlugin(TaskListPlugin.create())
            .build()
}