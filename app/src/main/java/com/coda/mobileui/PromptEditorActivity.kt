package com.coda.mobileui

import android.graphics.Typeface
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.coda.mobileui.core.AgentPrompt
import com.google.android.material.color.MaterialColors
import java.io.File

/**
 * 纯文本模板编辑器。
 *
 * 系统提示词模板与 AGENTS.md 都用它：给一个文件路径就能编辑，保存即写回，
 * 不做结构化限制。清空等于删除文件，调用方据此决定是恢复默认还是表示未设置。
 */
class PromptEditorActivity : BaseActivity() {
    private lateinit var file: File
    private lateinit var input: EditText
    private lateinit var meta: TextView
    private var title = ""
    private var hint = ""

    /** 进入页面时的内容，用于判断有没有未保存的修改。 */
    private var initial = ""

    /** 正文是否已从磁盘读到：没读到就保存会把还没加载出来的内容覆盖成空。 */
    private var loaded = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val scope = intent.getStringExtra(EXTRA_SCOPE) ?: SCOPE_GLOBAL
        val path = intent.getStringExtra(EXTRA_PATH)
        file =
            if (!path.isNullOrEmpty()) {
                File(path)
            } else if (scope == SCOPE_WORKSPACE) {
                AgentPrompt.workspaceFile(this)
            } else {
                AgentPrompt.globalFile(this)
            }
        title =
            intent.getStringExtra(EXTRA_TITLE)
                ?: if (scope == SCOPE_WORKSPACE) "工作区提示词" else "用户级提示词"
        hint = intent.getStringExtra(EXTRA_HINT) ?: ""
        initial = ""
        setContentView(buildUi())
        updateMeta()
        // 读文件放到 IO 线程：大提示词或慢存储上，主线程 readText 会卡顿甚至 ANR（#173）。
        AgentPrompt.readAsync(file) { text ->
            if (isFinishing || isDestroyed) return@readAsync
            // 基线无论如何都要记下来：否则「放弃修改」判断会拿空串比对，
            // 用户加载期间打的字会被当成未修改而静默丢掉。
            initial = text
            loaded = true
            // 加载期间用户已经动过输入框：不能把磁盘内容盖上去，否则刚打的字就没了。
            if (input.text.isNotEmpty()) return@readAsync
            input.setText(text)
            input.setSelection(input.text.length)
            updateMeta()
        }
        onBackPressedDispatcher.addCallback(
            this,
            object : androidx.activity.OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    if (input.text.toString() != initial) confirmDiscard() else finish()
                }
            },
        )
    }

    private fun buildUi(): View {
        val root =
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
            }
        val onSurface = MaterialColors.getColor(root, com.google.android.material.R.attr.colorOnSurface)
        val onSurfaceVariant = MaterialColors.getColor(root, com.google.android.material.R.attr.colorOnSurfaceVariant)
        val primary = MaterialColors.getColor(root, androidx.appcompat.R.attr.colorPrimary)
        root.setBackgroundColor(MaterialColors.getColor(root, com.google.android.material.R.attr.colorSurface))
        root.applySystemBarsInset()

        val bar =
            LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(6), dp(6), dp(6), dp(6))
            }
        bar.addView(
            ImageButton(this).apply {
                setImageResource(R.drawable.ic_arrow_back)
                background = null
                imageTintList = android.content.res.ColorStateList.valueOf(onSurfaceVariant)
                contentDescription = "返回"
                setOnClickListener { onBackPressedDispatcher.onBackPressed() }
            },
            LinearLayout.LayoutParams(dp(44), dp(44)),
        )
        bar.addView(
            TextView(this).apply {
                text = title
                textSize = 18f
                setTextColor(onSurface)
                setPadding(dp(6), 0, 0, 0)
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
            },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
        )
        bar.addView(
            TextView(this).apply {
                text = "恢复默认"
                textSize = 15f
                setTextColor(onSurfaceVariant)
                setPadding(dp(12), dp(10), dp(12), dp(10))
                setOnClickListener { confirmClear() }
            },
        )
        bar.addView(
            TextView(this).apply {
                text = "保存"
                textSize = 15f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(primary)
                setPadding(dp(12), dp(10), dp(12), dp(10))
                setOnClickListener { saveAndFinish() }
            },
        )
        root.addView(bar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        root.addView(
            View(this).apply {
                setBackgroundColor(MaterialColors.getColor(root, com.google.android.material.R.attr.colorOutlineVariant))
            },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)),
        )

        input =
            EditText(this).apply {
                background = null
                gravity = Gravity.TOP or Gravity.START
                inputType =
                    InputType.TYPE_CLASS_TEXT or
                        InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                        InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
                typeface = Typeface.MONOSPACE
                textSize = 13.5f
                isSingleLine = false
                setHorizontallyScrolling(false)
                setTextColor(onSurface)
                setHintTextColor(onSurfaceVariant)
                hint = this@PromptEditorActivity.hint
                setPadding(dp(16), dp(12), dp(16), dp(12))
            }
        input.addTextChangedListener(
            object : android.text.TextWatcher {
                override fun beforeTextChanged(
                    s: CharSequence?,
                    start: Int,
                    count: Int,
                    after: Int,
                ) = Unit

                override fun onTextChanged(
                    s: CharSequence?,
                    start: Int,
                    before: Int,
                    count: Int,
                ) = Unit

                override fun afterTextChanged(s: android.text.Editable?) = updateMeta()
            },
        )
        root.addView(input, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        meta =
            TextView(this).apply {
                textSize = 12f
                setTextColor(onSurfaceVariant)
                setPadding(dp(16), dp(8), dp(16), dp(12))
            }
        root.addView(meta, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        return root
    }

    private fun updateMeta() {
        if (!::meta.isInitialized) return
        meta.text = "${input.text.length} 字"
    }

    private fun saveAndFinish() {
        // 正文还没读进来就保存，会把磁盘上的内容覆盖成空（异步加载引入的新风险）。
        if (!loaded) {
            Toast.makeText(this, "正在读取文件，请稍候再保存", Toast.LENGTH_SHORT).show()
            return
        }
        val body = input.text.toString()
        // 写盘也走 IO 线程（#173）；失败时保持页面打开，让用户能重试。
        AgentPrompt.saveAsync(file, body) { err ->
            if (isFinishing || isDestroyed) return@saveAsync
            if (err != null) {
                Toast.makeText(this, "保存失败：$err", Toast.LENGTH_LONG).show()
                return@saveAsync
            }
            Toast.makeText(this, "已保存", Toast.LENGTH_SHORT).show()
            setResult(RESULT_OK)
            finish()
        }
    }

    private fun confirmClear() {
        CodaSheet(this)
            .compact()
            .title("恢复默认")
            .subtitle("删除后恢复默认")
            .secondaryAction("取消") { it.dismiss() }
            .primaryAction("恢复") { sheet ->
                sheet.dismiss()
                // 删除失败必须告知并保持页面：旧提示词还在，报成功会让用户以为已重置（#169）。
                AgentPrompt.saveAsync(file, "") { err ->
                    if (isFinishing || isDestroyed) return@saveAsync
                    if (err != null) {
                        Toast.makeText(this, "恢复失败：$err", Toast.LENGTH_LONG).show()
                        return@saveAsync
                    }
                    // 「恢复默认」是清空文件让内核回落内置模板。清完后必须把基线一起重置，
                    // 否则设置页会把它显示成「已清空」而不是「默认」（#403 的配套消费方）。
                    com.coda.mobileui.core.SystemPrompts.itemOf(file.name)?.let {
                        com.coda.mobileui.core.SystemPrompts.resetBaseline(this, it)
                    }
                    setResult(RESULT_OK)
                    finish()
                }
            }
            .show()
    }

    private fun confirmDiscard() {
        CodaSheet(this)
            .compact()
            .title("放弃修改")
            .subtitle("修改尚未保存")
            .secondaryAction("继续编辑") { it.dismiss() }
            .primaryAction("放弃") { sheet ->
                sheet.dismiss()
                finish()
            }
            .show()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    companion object {
        const val EXTRA_SCOPE = "scope"
        const val SCOPE_GLOBAL = "global"
        const val SCOPE_WORKSPACE = "workspace"
        const val EXTRA_PATH = "path"
        const val EXTRA_TITLE = "title"
        const val EXTRA_HINT = "hint"
    }
}
