package com.coda.mobileui

import android.content.Context
import io.noties.markwon.Markwon
import io.noties.markwon.ext.strikethrough.StrikethroughPlugin

/**
 * 全应用统一的 Markdown 渲染器。
 *
 * 只注册 CorePlugin 时删除线会原样显示成符号；三处渲染点（主聊天、Coda Bot、
 * 文件预览）必须用同一套插件，否则同一段文本在不同页面长得不一样。
 */
object MarkdownRender {
    fun create(ctx: Context): Markwon =
        Markwon
            .builder(ctx)
            .usePlugin(StrikethroughPlugin.create())
            .build()
}