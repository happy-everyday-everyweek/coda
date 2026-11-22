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
 *
 * @param onlyProviderId 非空时只看这一个平台。从「模型设置 → 某个已添加的供应商」
 *   点进来就是这种情形：用户点的是某一条 provider，想看的是它下面的账号，
 *   不该把所有平台的账号都铺一遍。从旧的「OAuth 账号」总入口进来时为空，保持全量。
 */
class OAuthAccountsSheet(
    private val host: BaseActivity,
    private val onlyProviderId: String? = null,
) {
    private val sheet = CodaSheet(host)
    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()
    private var column: LinearLayout? = null
    private var busy = false
    @Volatile
    private var closed = false

    private fun dp(v: Float): Int = (v * host.resources.displayMetrics.density + 0.5f).toInt()

    private fun color(
        attr: Int,
        fallback: Int,
    ): Int = MaterialColors.getColor(host.window.decorView, attr, fallback)

    /**
     * 主题色缓存（#594）。
     *
     * 之前是 get() 每次重解析，platformCard/accountRow 循环里每行读好几次，
     * 列表重建一次就是几十次重复的主题查询。本对象随卡片创建、随卡片作废，
     * 所以 lazy 就够了，不需要额外的失效钩子。
     */
    private val onSurface: Int by lazy { color(com.google.android.material.R.attr.colorOnSurface, Color.WHITE) }
    private val onSurfaceVariant: Int by lazy { color(com.google.android.material.R.attr.colorOnSurfaceVariant, Color.GRAY) }
    private val surfaceHigh: Int by lazy { color(com.google.android.material.R.attr.colorSurfaceContainerHigh, Color.DKGRAY) }

    /**
     * 本次作用域内的平台（#591 #593）。
     *
     * render 与「全部签到」共用同一份，避免两处各算一遍导致
     * 「界面只显示一个平台、批量操作却打到所有平台」。
     * 指定了 id 但注册表里没有时返回空列表——不能回退到全量，
     * 那会把「平台不存在」伪装成「平台列表正常」。
     */
    private fun scopedPlatforms(): List<OAuthProviders.Provider> =
        onlyProviderId?.let { id -> OAuthProviders.all.filter { it.id == id } } ?: OAuthProviders.all

    /** 健康度对应的颜色。绿、黄、红三档足够分辨，不再细分。 */
    private fun healthColor(health: OAuthAccounts.Health): Int =
        when (health) {
            OAuthAccounts.Health.GOOD -> 0xFF2E7D32.toInt()
            OAuthAccounts.Health.LOW -> 0xFFF9A825.toInt()
            OAuthAccounts.Health.COOLING -> 0xFF1565C0.toInt()
            OAuthAccounts.Health.SUSPECT -> 0xFFEF6C00.toInt()
            OAuthAccounts.Health.EXHAUSTED, OAuthAccounts.Health.DISABLED -> 0xFFC62828.toInt()
            OAuthAccounts.Health.UNKNOWN -> 0xFF757575.toInt()
        }

    private fun healthLabel(health: OAuthAccounts.Health): String =
        when (health) {
            OAuthAccounts.Health.GOOD -> "正常"
            OAuthAccounts.Health.LOW -> "额度偏低"
            OAuthAccounts.Health.COOLING -> "冷却中"
            OAuthAccounts.Health.SUSPECT -> "探活失败"
            OAuthAccounts.Health.EXHAUSTED -> "额度耗尽"
            OAuthAccounts.Health.DISABLED -> "已停用"
            OAuthAccounts.Health.UNKNOWN -> "状态未知"
        }

    fun show() {
        val one = onlyProviderId?.let { OAuthProviders.byId(it) }
        sheet
            .title(one?.let { "${it.name} 账号" } ?: "OAuth 账号")
            .subtitle("点账号查看健康与签到")
            .headerAction("＋") { openAdd() }
            .content { col ->
                column = col
                render(col)
            }.primaryAction("全部签到") { checkinAll() }
            .secondaryAction("关闭") { self -> self.dismiss() }
            .onDismiss {
                closed = true
                // 关掉后不再持有已移除的视图树（#595）。
                column = null
                io.shutdownNow()
            }
            .show()
    }

    /** 关闭后不再接收任务，也不把结果写回已分离的视图。 */
    private fun submit(work: () -> Unit) {
        if (closed) return
        try {
            io.execute {
                if (closed) return@execute
                work()
            }
        } catch (_: java.util.concurrent.RejectedExecutionException) {
            // 已关闭，忽略。
        }
    }

    /** 一个平台的账号数，用于列表里展示。 */
    private data class PlatformRow(
        val provider: OAuthProviders.Provider,
        val accounts: List<OAuthAccounts.Account>,
    )

    /** 逐个平台取账号。没有账号的平台也画出来，并提示去添加。 */
    private fun render(col: LinearLayout) {
        col.removeAllViews()
        col.addView(hint("正在读取账号…"))
        // 捕获本次要写入的容器，不要把结果写回可能已经换过的字段。
        val target = col
        submit {
            try {
                val store =
                    com.coda.mobileui.core.ProviderStore
                        .load(host)
                val platforms = scopedPlatforms()
                val rows =
                    platforms.map { p ->
                        val authorized = store.any { it.id == p.id }
                        PlatformRow(p, if (authorized) OAuthAccounts.list(host, p) else emptyList())
                    }
                main.post {
                    if (closed) return@post
                    target.removeAllViews()
                    // 指定了平台却一个都没匹配上：这是「平台不存在」，不是「没有账号」。
                    // 空列表上 rows.all { ... } 是空真，会误报成后者（#593）。
                    if (platforms.isEmpty()) {
                        target.addView(hint("未找到该平台，可能已被移除或不再支持。"))
                        target.addView(hint("返回供应商列表重新选择一个平台即可。"))
                        return@post
                    }
                    if (rows.all { it.accounts.isEmpty() }) {
                        target.addView(hint("还没有账号。点右上角 ＋ 选一个平台授权，同一个平台可以加多个账号。"))
                    }
                    rows.forEach { row -> target.addView(platformCard(row.provider, row.accounts)) }
                }
            } catch (e: Throwable) {
                main.post {
                    if (closed) return@post
                    target.removeAllViews()
                    target.addView(hint("读取账号失败：${e.message ?: "网络或网关异常"}"))
                    target.addView(
                        TextView(host).apply {
                            text = "重试"
                            setTextColor(color(androidx.appcompat.R.attr.colorPrimary, onSurface))
                            textSize = 13f
                            setPadding(0, dp(4f), 0, dp(10f))
                            setOnClickListener { render(target) }
                        },
                    )
                }
            }
        }
    }

    /** 一个平台一张卡：标题行 + 账号行。 */
    private fun platformCard(
        p: OAuthProviders.Provider,
        accounts: List<OAuthAccounts.Account>,
    ): View {
        val card =
            LinearLayout(host).apply {
                orientation = LinearLayout.VERTICAL
                background =
                    GradientDrawable().apply {
                        cornerRadius = dp(14f).toFloat()
                        setColor(surfaceHigh)
                    }
                setPadding(dp(14f), dp(12f), dp(14f), dp(6f))
                layoutParams =
                    LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply {
                        bottomMargin = dp(12f)
                    }
            }

        // 标题行：平台名 + 账号数，右侧给一个"添加账号"的入口。
        val head =
            LinearLayout(host).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
        head.addView(
            TextView(host).apply {
                text = p.name
                setTextColor(onSurface)
                textSize = 15f
                setTypeface(typeface, Typeface.BOLD)
                layoutParams = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f)
            },
        )
        head.addView(
            TextView(host).apply {
                text = if (accounts.isEmpty()) "未登录" else "${accounts.size} 个账号"
                setTextColor(onSurfaceVariant)
                textSize = 12f
                setPadding(0, 0, dp(10f), 0)
            },
        )
        head.addView(
            TextView(host).apply {
                text = "添加账号"
                setTextColor(color(androidx.appcompat.R.attr.colorPrimary, onSurface))
                textSize = 12.5f
                setPadding(dp(8f), dp(4f), 0, dp(4f))
                setOnClickListener { openAdd(p) }
            },
        )
        card.addView(head)

        if (accounts.isEmpty()) {
            card.addView(
                TextView(host).apply {
                    text = "尚未授权。点「添加账号」登录一个。"
                    setTextColor(onSurfaceVariant)
                    textSize = 12.5f
                    setPadding(0, dp(8f), 0, dp(10f))
                },
            )
            return card
        }

        card.addView(
            View(host).apply {
                setBackgroundColor(onSurfaceVariant and 0x33FFFFFF)
                layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, dp(1f)).apply { topMargin = dp(8f) }
            },
        )

        accounts.forEach { account -> card.addView(accountRow(p, account)) }
        return card
    }

    /** 一行账号：健康点、名称、额度/签到摘要，右侧箭头。 */
    private fun accountRow(
        p: OAuthProviders.Provider,
        a: OAuthAccounts.Account,
    ): View {
        val row =
            LinearLayout(host).apply {
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
        row.addView(
            View(host).apply {
                background =
                    GradientDrawable().apply {
                        shape = GradientDrawable.OVAL
                        setColor(healthColor(a.health))
                    }
                layoutParams = LinearLayout.LayoutParams(dp(8f), dp(8f)).apply { rightMargin = dp(10f) }
            },
        )

        val text =
            LinearLayout(host).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f)
            }
        text.addView(
            TextView(host).apply {
                this.text = a.title
                setTextColor(onSurface)
                textSize = 14f
                maxLines = 1
            },
        )
        val bits = mutableListOf<String>()
        if (a.creditsSize > 0) bits += "余额 ${a.creditsRemain}"
        if (a.todayCheckedIn) {
            bits += "今日已签"
        } else if (a.streakDays > 0) {
            bits += "连签 ${a.streakDays} 天"
        }
        if (a.region == "global") bits += "国际版"
        if (bits.isEmpty()) bits += healthLabel(a.health)
        text.addView(
            TextView(host).apply {
                this.text = bits.joinToString(" · ")
                setTextColor(onSurfaceVariant)
                textSize = 11.5f
                maxLines = 1
            },
        )
        row.addView(text)

        row.addView(
            TextView(host).apply {
                setText("›")
                setTextColor(onSurfaceVariant)
                textSize = 16f
            },
        )
        return row
    }

    /** 子面板改动后回到这里重新拉数据。 */
    private fun refreshList() {
        // 卡片已关闭、或被子页 attachTo 接管后，这个列已经脱离视图树：
        // 再走一遍完整 IO 往返只是白跑，还可能往 detached hierarchy 里塞内容（#595）。
        val col = column ?: return
        if (closed || col.parent == null) return
        render(col)
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
                col.addView(
                    TextView(host).apply {
                        text = p.name
                        textSize = 16f
                        setTextColor(onSurface)
                        setPadding(dp(2f), dp(14f), dp(2f), dp(14f))
                        setOnClickListener {
                            // 同一个卡片换成"添加账号"那一页，不要再关掉选择器另开一张
                            OAuthSheet(host, p) { refreshList() }.attachTo(picker)
                        }
                    },
                )
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
        if (busy || closed) return
        busy = true
        Toast.makeText(host, "正在签到…", Toast.LENGTH_SHORT).show()
        submit {
            try {
                val store =
                    com.coda.mobileui.core.ProviderStore
                        .load(host)
                val lines = mutableListOf<String>()
                var anyFail = false
                // 与界面同一份作用域：单平台模式下不能打到其它平台（#591）。
                scopedPlatforms().forEach { p ->
                    if (store.none { it.id == p.id }) return@forEach
                    val caps = OAuthAccounts.capabilities(host, p)
                    if (!caps.checkin) return@forEach
                    val r = OAuthAccounts.checkin(host, p, "")
                    lines += "${p.name}：${r.message}"
                    if (!r.ok) anyFail = true
                }
                main.post {
                    if (closed) return@post
                    val text = if (lines.isEmpty()) "没有可签到的账号" else lines.joinToString("\n")
                    Toast.makeText(host, text + if (anyFail) "\n部分失败" else "", Toast.LENGTH_LONG).show()
                    column?.let { render(it) }
                }
            } catch (e: Throwable) {
                main.post {
                    if (!closed) Toast.makeText(host, "签到失败：${e.message ?: "网络异常"}", Toast.LENGTH_LONG).show()
                }
            } finally {
                // 无论成功、失败还是异常，都要复位；否则批量签到会永久失效。
                main.post { busy = false }
            }
        }
    }

    private fun hint(text: String): TextView =
        TextView(host).apply {
            this.text = text
            setTextColor(onSurfaceVariant)
            textSize = 13f
            setLineSpacing(dp(3f).toFloat(), 1f)
            setPadding(0, dp(10f), 0, dp(10f))
        }
}
