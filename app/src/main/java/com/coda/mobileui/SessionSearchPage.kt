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
    private val onClose: () -> Unit,
    private val onPick: (ZSessionInfo) -> Unit,
) {
    private val dp: (Float) -> Int = { (it * host.resources.displayMetrics.density).toInt() }
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
                            render(s?.toString().orEmpty())
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

    private fun render(raw: String) {
        if (!::results.isInitialized) return
        val q = raw.trim()
        results.removeAllViews()
        val all = sessions()
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
        val where = session.workspacePath?.takeIf { it.isNotEmpty() }?.let { File(it).name }
        val sub = listOfNotNull(where, timeFormat.format(Date(session.updatedAt))).joinToString(" · ")
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