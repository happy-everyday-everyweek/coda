package com.coda.mobileui

import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.coda.mobileui.core.OAuthAccounts
import com.coda.mobileui.core.OAuthProviders
import com.google.android.material.color.MaterialColors
import java.util.concurrent.Executors

/**
 * 账号列表。一个平台一张卡，卡里按账号逐行铺开。
 *
 * 点账号进 [OAuthAccountSheet]，那一层才是健康度与签到这些操作。列表这一层只回答两个问题：
 * 这个平台上有几个号、各自还健康吗。右上角给批量动作（全部签到、刷新额度、加新账号）。
 *
 * 数据每次都从宿主现取，不在客户端留副本——账号可能在网关内部被自动禁用或删除，缓存会骗人。
 */
class OAuthAccountsSheet(private val host: BaseActivity) {

    private val sheet = CodaSheet(host)
    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()
    private var column: LinearLayout? = null
    private var busy = false

    private fun dp(v: Float): Int = (v * host.resources.displayMetrics.density + 0.5f).toInt()

    private fun color(attr: Int, fallback: Int): Int =
        MaterialColors.getColor(host.window.decorView, attr, fallback)

    private val onSurface: Int
        get() = color(com.google.android.material.R.attr.colorOnSurface, Color.WHITE)
    private val onSurfaceVariant: Int
        get() = color(com.google.android.material.R.attr.colorOnSurfaceVariant, Color.GRAY)
    private val surfaceHigh: Int
        get() = color(com.google.android.material.R.attr.colorSurfaceContainerHigh, Color.DKGRAY)

    /** 健康度对应的颜色。绿、黄、红三档足够分辨，不再细分。 */
    private fun healthColor(health: OAuthAccounts.Health): Int = when (health) {
        OAuthAccounts.Health.GOOD -> 0xFF2E7D32.toInt()
        OAuthAccounts.Health.LOW -> 0xFFF9A825.toInt()
        OAuthAccounts.Health.COOLING -> 0xFF1565C0.toInt()
        OAuthAccounts.Health.SUSPECT -> 0xFFEF6C00.toInt()
        OAuthAccounts.Health.EXHAUSTED, OAuthAccounts.Health.DISABLED -> 0xFFC62828.toInt()
        OAuthAccounts.Health.UNKNOWN -> 0xFF757575.toInt()
    }

    private fun healthLabel(health: OAuthAccounts.Health): String = when (health) {
        OAuthAccounts.Health.GOOD -> "正常"
        OAuthAccounts.Health.LOW -> "额度偏低"
        OAuthAccounts.Health.COOLING -> "冷却中"
        OAuthAccounts.Health.SUSPECT -> "探活失败"
        OAuthAccounts.Health.EXHAUSTED -> "额度耗尽"
        OAuthAccounts.Health.DISABLED -> "已停用"
        OAuthAccounts.Health.UNKNOWN -> "状态未知"
    }

    fun show() {
        sheet
            .title("OAuth 账号")
            .subtitle("点账号查看健康与签到")
            .headerAction("＋") { openAdd() }
            .content { col ->
                column = col
                render(col)
            }
            .primaryAction("全部签到") { checkinAll() }
            .secondaryAction("关闭") { self -> self.dismiss() }
            .onDismiss { io.shutdown() }
            .show()
    }

    /** 一个平台的账号数，用于列表里展示。 */
    private data class PlatformRow(val provider: OAuthProviders.Provider, val accounts: List<OAuthAccounts.Account>)

    /** 逐个平台取账号。没有账号的平台也画出来，并提示去添加。 */
    private fun render(col: LinearLayout) {
        col.removeAllViews()
        col.addView(hint("正在读取账号…"))

        io.execute {
            val store = com.coda.mobileui.core.ProviderStore.load(host)
            val rows = OAuthProviders.all.map { p ->
                // 只有已经落成 provider 的平台才去问账号，没授权过的平台问了也是空的。
                val authorized = store.any { it.id == p.id }
                PlatformRow(p, if (authorized) OAuthAccounts.list(host, p) else emptyList())
            }
            main.post {
                val target = column ?: return@post
                target.removeAllViews()
                if (rows.all { it.accounts.isEmpty() }) {
                    target.addView(hint("还没有账号。点右上角 ＋ 选一个平台授权，同一个平台可以加多个账号。"))
                }
                rows.forEach { row -> target.addView(platformCard(row.provider, row.accounts)) }
            }
        }
    }

    /** 一个平台一张卡：标题行 + 账号行。 */
    private fun platformCard(p: OAuthProviders.Provider, accounts: List<OAuthAccounts.Account>): View {
        val card = LinearLayout(host).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                cornerRadius = dp(14f).toFloat()
                setColor(surfaceHigh)
            }
            setPadding(dp(14f), dp(12f), dp(14f), dp(6f))
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply {
                bottomMargin = dp(12f)
            }
        }

        // 标题行：平台名 + 账号数，右侧给一个"添加账号"的入口。
        val head = LinearLayout(host).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        head.addView(TextView(host).apply {
            text = p.name
            setTextColor(onSurface)
            textSize = 15f
            setTypeface(typeface, Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f)
        })
        head.addView(TextView(host).apply {
            text = if (accounts.isEmpty()) "未登录" else "${accounts.size} 个账号"
            setTextColor(onSurfaceVariant)
            textSize = 12f
            setPadding(0, 0, dp(10f), 0)
        })
        head.addView(TextView(host).apply {
            text = "添加账号"
            setTextColor(color(androidx.appcompat.R.attr.colorPrimary, onSurface))
            textSize = 12.5f
            setPadding(dp(8f), dp(4f), 0, dp(4f))
            setOnClickListener { openAdd(p) }
        })
        card.addView(head)

        if (accounts.isEmpty()) {
            card.addView(TextView(host).apply {
                text = "尚未授权。点「添加账号」登录一个。"
                setTextColor(onSurfaceVariant)
                textSize = 12.5f
                setPadding(0, dp(8f), 0, dp(10f))
            })
            return card
        }

        card.addView(View(host).apply {
            setBackgroundColor(onSurfaceVariant and 0x33FFFFFF)
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, dp(1f)).apply { topMargin = dp(8f) }
        })

        accounts.forEach { account -> card.addView(accountRow(p, account)) }
        return card
    }

    /** 一行账号：健康点、名称、额度/签到摘要，右侧箭头。 */
    private fun accountRow(p: OAuthProviders.Provider, a: OAuthAccounts.Account): View {
        val row = LinearLayout(host).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(11f), 0, dp(11f))
            isClickable = true
            setOnClickListener {
                // 不再"关列表 → 弹详情"：直接把同一个卡片换成该账号的面板
                OAuthAccountSheet(host, p, a) { refreshList() }.attachTo(sheet)
            }
        }

        // 健康点，用颜色说话，省掉一个文字标签。
        row.addView(View(host).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(healthColor(a.health))
            }
            layoutParams = LinearLayout.LayoutParams(dp(8f), dp(8f)).apply { rightMargin = dp(10f) }
        })

        val text = LinearLayout(host).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f)
        }
        text.addView(TextView(host).apply {
            this.text = a.title
            setTextColor(onSurface)
            textSize = 14f
            maxLines = 1
        })
        val bits = mutableListOf<String>()
        if (a.creditsSize > 0) bits += "余额 ${a.creditsRemain}"
        if (a.todayCheckedIn) bits += "今日已签" else if (a.streakDays > 0) bits += "连签 ${a.streakDays} 天"
        if (a.region == "global") bits += "国际版"
        if (bits.isEmpty()) bits += healthLabel(a.health)
        text.addView(TextView(host).apply {
            this.text = bits.joinToString(" · ")
            setTextColor(onSurfaceVariant)
            textSize = 11.5f
            maxLines = 1
        })
        row.addView(text)

        row.addView(TextView(host).apply {
            setText("›")
            setTextColor(onSurfaceVariant)
            textSize = 16f
        })
        return row
    }

    /** 子面板改动后回到这里重新拉数据。 */
    private fun refreshList() {
        column?.let { render(it) }
    }

    /** 打开某个平台的授权流程；不指定平台就先让用户选。 */
    private fun openAdd(provider: OAuthProviders.Provider? = null) {
        if (provider != null) {
            OAuthSheet(host, provider) { refreshList() }.show()
            return
        }
        // 上游列表用的是单选卡片，这里同样只用文字行，不需要额外组件。
        val pool = OAuthProviders.all
        val picker = CodaSheet(host).title("选择平台")
        picker.content { col ->
            pool.forEach { p ->
                col.addView(TextView(host).apply {
                    text = p.name
                    textSize = 16f
                    setTextColor(onSurface)
                    setPadding(dp(2f), dp(14f), dp(2f), dp(14f))
                    setOnClickListener {
                        // 同一个卡片换成"添加账号"那一页，不要再关掉选择器另开一张
                        OAuthSheet(host, p) { refreshList() }.attachTo(picker)
                    }
                })
            }
        }
        picker.show()
    }

    /**
     * 全部签到。逐平台跑，每个平台一次批量请求，插件那边并发上限 4。
     *
     * 失败与「今天已签」都算正常情况，只有真的报错才提示。
     */
    private fun checkinAll() {
        if (busy) return
        busy = true
        Toast.makeText(host, "正在签到…", Toast.LENGTH_SHORT).show()
        io.execute {
            val store = com.coda.mobileui.core.ProviderStore.load(host)
            val lines = mutableListOf<String>()
            var anyFail = false
            OAuthProviders.all.forEach { p ->
                if (store.none { it.id == p.id }) return@forEach
                val caps = OAuthAccounts.capabilities(host, p)
                if (!caps.checkin) return@forEach
                val r = OAuthAccounts.checkin(host, p, "")
                lines += "${p.name}：${r.message}"
                if (!r.ok) anyFail = true
            }
            main.post {
                busy = false
                val text = if (lines.isEmpty()) "没有可签到的账号" else lines.joinToString("\n")
                Toast.makeText(host, text + if (anyFail) "\n部分失败" else "", Toast.LENGTH_LONG).show()
                render(column ?: return@post)
            }
        }
    }

    private fun hint(text: String): TextView = TextView(host).apply {
        this.text = text
        setTextColor(onSurfaceVariant)
        textSize = 13f
        setLineSpacing(dp(3f).toFloat(), 1f)
        setPadding(0, dp(10f), 0, dp(10f))
    }
}