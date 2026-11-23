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

    /**
     * 加载与保存的闸门（#739、#741）。
     *
     * 判定逻辑在 [com.coda.mobileui.core.PromptLoadGate] 里单测：
     * 加载期间用户输入、读失败这两条分支都会导致整份提示词被覆盖，
     * 而它们只在真机的时序缝隙里出现。
     */
    private val gate = com.coda.mobileui.core.PromptLoadGate()

    /**
     * 用户是否真的动过输入框。
     *
     * 不能用「输入框非空」代替：旋转或进程重建时 Android 会先恢复 EditText 的内容，
     * 异步读盘还没回来，那份恢复内容会被误判成「加载期间的输入」，
     * 于是磁盘正文不套用、保存闸门永久关闭——用户其实什么都没改。
     */
    private var userEdited = false

    /** 保存进行中：避免连点提交两次写盘，把基线记账弄乱。 */
    private var saving = false

    /** 正在用代码回填正文：这段时间的文本变化不算用户编辑。 */
    private var applyingText = false

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
        setContentView(buildUi())
        updateMeta()
        loadFromDisk()
        onBackPressedDispatcher.addCallback(
            this,
            object : androidx.activity.OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    if (input.text.toString() != gate.initial) confirmDiscard() else finish()
                }
            },
        )
    }

    /**
     * 读文件放到 IO 线程：大提示词或慢存储上，主线程 readText 会卡顿甚至 ANR（#173）。
     *
     * 读失败与加载期间的用户输入都不开保存闸门（#739、#741），并给出重新加载入口。
     */
    private fun loadFromDisk() {
        AgentPrompt.readAsync(file) { read ->
            if (isFinishing || isDestroyed) return@readAsync
            // 只看真实输入标志：旋转/进程重建恢复的正文不算「加载期间输入」。
            val typed = if (userEdited) input.text.toString() else ""
            val d = gate.onLoaded(read, typed = typed)
            d.applyText?.let {
                applyingText = true
                try {
                    input.setText(it)
                    input.setSelection(input.text.length)
                } finally {
                    applyingText = false
                }
                userEdited = false
            }
            // 两种问题合成一条提示：两条 LENGTH_LONG 会互相顶掉，
            // 真正的失败原因反而看不见。
            val msg =
                when {
                    d.error != null && d.needsReload ->
                        "读取失败：${d.error}；你在加载期间的输入已保留，可点「重新加载」取回磁盘正文"
                    d.error != null -> "读取失败：${d.error}；可点「重新加载」重试"
                    d.needsReload -> "加载期间已输入内容，未套用磁盘正文；可点「重新加载」用磁盘内容替换，或先复制你的输入"
                    else -> null
                }
            msg?.let { Toast.makeText(this, it, Toast.LENGTH_LONG).show() }
            updateMeta()
        }
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
        // 重新加载入口：读失败或加载期间输入后闸门会关闭，没有它就只剩「返回重进」
        // 这条死路，而用户刚打的字只能靠放弃修改丢掉——正是本次要防的丢数据。
        bar.addView(
            TextView(this).apply {
                text = "重新加载"
                textSize = 15f
                setTextColor(onSurfaceVariant)
                setPadding(dp(12), dp(10), dp(12), dp(10))
                setOnClickListener { confirmReload() }
            },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT),
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

                override fun afterTextChanged(s: android.text.Editable?) {
                    // 只有真实输入才置位；setText 回填与视图状态恢复都会先把
                    // userEdited 置回 false，避免把它们当成用户编辑。
                    if (!applyingText) userEdited = true
                    updateMeta()
                }
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

    /**
     * 重新加载磁盘正文。
     *
     * 有未保存输入时先确认：重新加载会用磁盘内容替换编辑框，
     * 不能悄悄把用户刚打的字冲掉。
     */
    private fun confirmReload() {
        if (!userEdited || input.text.isEmpty()) {
            userEdited = false
            loadFromDisk()
            return
        }
        CodaSheet(this)
            .compact()
            .title("重新加载")
            .subtitle("将用磁盘上的正文替换当前编辑内容，未保存的输入会丢失")
            .secondaryAction("取消") { it.dismiss() }
            .primaryAction("替换并重载") { sheet ->
                sheet.dismiss()
                applyingText = true
                try {
                    input.setText("")
                } finally {
                    applyingText = false
                }
                userEdited = false
                loadFromDisk()
            }
            .show()
    }

    private fun saveAndFinish() {
        // 连点保护：单线程执行器上排两次写盘会让基线记账竞态。
        if (saving) return
        // 正文还没可信地读进来就保存，会把磁盘上的内容覆盖成几个字或空（#739、#741）。
        if (!gate.canSave) {
            Toast.makeText(this, "正文尚未就绪，暂不能保存；可点「重新加载」重试", Toast.LENGTH_LONG).show()
            return
        }
        saving = true
        val body = input.text.toString()
        // 写盘也走 IO 线程（#173）；失败时保持页面打开，让用户能重试。
        AgentPrompt.saveAsync(file, body) { err ->
            saving = false
            if (isFinishing || isDestroyed) return@saveAsync
            if (err != null) {
                gate.onSaveFailed()
                Toast.makeText(this, "保存失败：$err", Toast.LENGTH_LONG).show()
                return@saveAsync
            }
            gate.onSaved(body)
            userEdited = false
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
