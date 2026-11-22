package com.coda.mobileui

import android.graphics.Typeface
import android.text.Editable
import android.text.SpannableString
import android.text.TextWatcher
import android.text.style.StyleSpan
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.coda.mobileui.core.ZSessionInfo
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 会话搜索页。
 *
 * 形态取自 Claude 安卓端：搜索是独立的一页（其内部屏名 SearchChats），入口挂在侧栏
 * 与首页（其埋点 ConversationSearchEntryPoint 取值含 home/drawer/title_bar），
 * 结果行给「标题命中 + 一行上下文」（对应其接口返回的 title_matches 与 matched_snippet）。
 */
class SessionSearchPage(
    private val host: MainActivity,
    private val sessions: () -> List<ZSessionInfo>,
    private val extras: (String) -> List<Extra>,
    private val onClose: () -> Unit,
    private val onPick: (ZSessionInfo) -> Unit,
) {
    /** 会话以外的可搜索对象：设置分类、Coda Bot、图片等。 */
    data class Extra(
        val section: String,
        val title: String,
        val subtitle: String,
        val run: () -> Unit,
        /** 真正命中的原文；provider 按全文匹配时填这里，避免页面二次过滤误删（#659）。 */
        val matched: String? = null,
    )

    private val dp: (Float) -> Int = { (it * host.resources.displayMetrics.density).toInt() }
    // 每个按键都同步跑 extras() 会在大历史上卡住 UI 线程（#658）：
    // 去抖 180ms，取数放后台，序号丢弃过期结果。
    private val searchHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var pendingSearch: Runnable? = null
    private var lastQuery: String? = null
    private var searchSeq = 0
    private var appliedSeq = 0
    private val searchPool = java.util.concurrent.Executors.newSingleThreadExecutor { r ->
        Thread(r, "session-search").apply { isDaemon = true }
    }
    private val timeFormat = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())

    private lateinit var input: EditText
    private lateinit var results: LinearLayout
    private lateinit var hint: TextView

    val view: View by lazy { build() }

    private fun build(): View {
        val root =
            LinearLayout(host).apply {
                orientation = LinearLayout.VERTICAL
                setBackgroundColor(color(com.google.android.material.R.attr.colorSurface))
            }
        val bar =
            LinearLayout(host).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(4f), dp(8f), dp(12f), dp(8f))
            }
        bar.addView(
            ImageButton(host).apply {
                setImageResource(R.drawable.ic_arrow_back)
                background = null
                setColorFilter(color(com.google.android.material.R.attr.colorOnSurface))
                val size = dp(40f)
                layoutParams = LinearLayout.LayoutParams(size, size)
                setPadding(dp(8f), dp(8f), dp(8f), dp(8f))
                setOnClickListener { onClose() }
            },
        )
        input =
            EditText(host).apply {
                hint = host.getString(R.string.drawer_search_hint)
                setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 16f)
                setTextColor(color(com.google.android.material.R.attr.colorOnSurface))
                setHintTextColor(color(com.google.android.material.R.attr.colorOnSurfaceVariant))
                background = null
                isSingleLine = true
                layoutParams = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f)
                addTextChangedListener(
                    object : TextWatcher {
                        override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit

                        override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {
                            scheduleSearch(s?.toString().orEmpty())
                        }

                        override fun afterTextChanged(s: Editable?) = Unit
                    },
                )
            }
        bar.addView(input)
        root.addView(bar, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))

        val scroll =
            ScrollView(host).apply {
                layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f)
            }
        results =
            LinearLayout(host).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(8f), dp(4f), dp(8f), dp(24f))
            }
        scroll.addView(results, android.view.ViewGroup.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        root.addView(scroll)
        hint =
            TextView(host).apply {
                text = "没有匹配的会话"
                setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 13f)
                setTextColor(color(com.google.android.material.R.attr.colorOnSurfaceVariant))
                setPadding(dp(16f), dp(16f), dp(16f), dp(16f))
                visibility = View.GONE
            }
        root.addView(hint, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        render("")
        return root
    }

    fun open() {
        input.setText("")
        input.requestFocus()
        input.post {
            val imm = host.getSystemService(InputMethodManager::class.java)
            imm?.showSoftInput(input, InputMethodManager.SHOW_IMPLICIT)
        }
    }

    fun close() {
        val imm = host.getSystemService(InputMethodManager::class.java)
        imm?.hideSoftInputFromWindow(input.windowToken, 0)
    }

    /** 去抖调度：相同查询直接跳过，取数放后台线程（#658）。 */
    private fun scheduleSearch(raw: String) {
        if (!::results.isInitialized) return
        val q = raw.trim()
        lastQuery?.let { if (com.coda.mobileui.ui.SearchLogic.shouldSkipRender(it, q)) return }
        lastQuery = q
        pendingSearch?.let { searchHandler.removeCallbacks(it) }
        val mine = ++searchSeq
        val task =
            Runnable {
                searchPool.execute {
                    // provider 可能读全部会话索引文件，绝不能在主线程跑。
                    val all = runCatching { sessions() }.getOrDefault(emptyList())
                    val others = if (q.isEmpty()) emptyList() else runCatching { extras(q) }.getOrDefault(emptyList())
                    searchHandler.post {
                        // 迟到的旧结果不能覆盖新查询的结果。
                        if (mine >= appliedSeq) {
                            appliedSeq = mine
                            paint(q, all, others)
                        }
                    }
                }
            }
        pendingSearch = task
        searchHandler.postDelayed(task, 180L)
    }

    /** 页面销毁时停掉在途任务，避免回调持有已销毁的 Activity。 */
    fun release() {
        pendingSearch?.let { searchHandler.removeCallbacks(it) }
        searchHandler.removeCallbacksAndMessages(null)
        searchPool.shutdownNow()
    }

    private fun render(raw: String) {
        if (!::results.isInitialized) return
        val q = raw.trim()
        lastQuery = q
        paint(q, sessions(), if (q.isEmpty()) emptyList() else extras(q))
    }

    private fun paint(
        q: String,
        all: List<ZSessionInfo>,
        rawExtras: List<Extra>,
    ) {
        if (!::results.isInitialized) return
        results.removeAllViews()
        val matched =
            if (q.isEmpty()) {
                all.sortedByDescending { it.updatedAt }
            } else {
                all
                    .filter { it.title.contains(q, ignoreCase = true) || (it.workspacePath ?: "").contains(q, true) }
                    .sortedByDescending { it.updatedAt }
            }
        hint.visibility = if (matched.isEmpty() && q.isNotEmpty()) View.VISIBLE else View.GONE
        matched.forEach { session ->
            results.addView(row(session, q))
        }
        // provider 已按全文匹配，这里只按 matched 原文判定，不再用标题/摘要二次过滤（#659）。
        val others =
            rawExtras.filter {
                com.coda.mobileui.ui.SearchLogic.keeps(
                    com.coda.mobileui.ui.SearchLogic.ExtraHit(it.section, it.title, it.subtitle, it.matched),
                    q,
                )
            }
        // 按 section 聚拢后再渲染：provider 结果按文件/行交错，逐个比较会让分组头重复（#660）。
        com.coda.mobileui.ui.SearchLogic.groupBySection(others) { it.section }.forEach { (section, group) ->
            results.addView(
                TextView(host).apply {
                    text = section
                    setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 12f)
                    setTextColor(color(com.google.android.material.R.attr.colorOnSurfaceVariant))
                    setPadding(dp(12f), dp(14f), dp(12f), dp(4f))
                },
            )
            group.forEach { extra ->
            results.addView(
                LinearLayout(host).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(dp(12f), dp(10f), dp(12f), dp(10f))
                    isClickable = true
                    setOnClickListener { extra.run() }
                    addView(
                        TextView(host).apply {
                            text = highlight(extra.title, q)
                            setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 15f)
                            setTextColor(color(com.google.android.material.R.attr.colorOnSurface))
                            maxLines = 1
                            ellipsize = android.text.TextUtils.TruncateAt.END
                        },
                    )
                    if (extra.subtitle.isNotEmpty()) {
                        addView(
                            TextView(host).apply {
                                text = extra.subtitle
                                setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 12f)
                                setTextColor(color(com.google.android.material.R.attr.colorOnSurfaceVariant))
                                maxLines = 1
                                ellipsize = android.text.TextUtils.TruncateAt.END
                                setPadding(0, dp(3f), 0, 0)
                            },
                        )
                    }
                },
            )
            }
        }
        if (others.isNotEmpty()) hint.visibility = View.GONE
    }

    private fun row(
        session: ZSessionInfo,
        q: String,
    ): View {
        val row =
            LinearLayout(host).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(12f), dp(12f), dp(12f), dp(12f))
                isClickable = true
                setOnClickListener { onPick(session) }
            }
        val title = session.title.ifEmpty { "未命名会话" }
        row.addView(
            TextView(host).apply {
                text = highlight(title, q)
                setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 15f)
                setTextColor(color(com.google.android.material.R.attr.colorOnSurface))
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
            },
        )
        // 次行对应 Claude 结果里的 matched_snippet：本地没有服务端片段，用工作区与时间代替。
        // basename 为空（workspacePath="/"）时不能拼出前导 " · "（#661）。
        val where = session.workspacePath?.takeIf { it.isNotEmpty() }?.let { File(it).name }
        val time = timeFormat.format(Date(session.updatedAt))
        // 查询命中父目录时，光显示 basename 看不出匹配理由，补一行命中的路径前缀。
        val ctx = com.coda.mobileui.ui.SearchLogic.pathContext(session.workspacePath, q)
        val sub = com.coda.mobileui.ui.SearchLogic.subtitle(where, time)
        row.addView(
            TextView(host).apply {
                text = sub
                setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 12f)
                setTextColor(color(com.google.android.material.R.attr.colorOnSurfaceVariant))
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
                setPadding(0, dp(3f), 0, 0)
            },
        )
        if (ctx != null) {
            row.addView(
                TextView(host).apply {
                    text = highlight(ctx, q)
                    setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 11f)
                    setTextColor(color(com.google.android.material.R.attr.colorOnSurfaceVariant))
                    maxLines = 1
                    ellipsize = android.text.TextUtils.TruncateAt.START
                    setPadding(0, dp(2f), 0, 0)
                },
            )
        }
        return row
    }

    private fun highlight(
        text: String,
        q: String,
    ): CharSequence {
        if (q.isEmpty()) return text
        val start = text.indexOf(q, ignoreCase = true)
        if (start < 0) return text
        return SpannableString(text).apply {
            setSpan(StyleSpan(Typeface.BOLD), start, start + q.length, 0)
        }
    }

    private fun color(attr: Int): Int {
        val value = android.util.TypedValue()
        host.theme.resolveAttribute(attr, value, true)
        return if (value.resourceId != 0) {
            androidx.core.content.ContextCompat.getColor(host, value.resourceId)
        } else {
            value.data
        }
    }
}