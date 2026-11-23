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
import com.coda.mobileui.core.OAuthActions
import com.coda.mobileui.core.OAuthProviders
import com.google.android.material.color.MaterialColors
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException

/**
 * 单个账号的控制面板。
 *
 * 上一级是账号列表，点进来这里才做具体动作：看健康、看额度、签到、领成长奖励、刷新额度、
 * 续令牌、删账号。动作都打到宿主的管理接口，客户端不自己算。
 *
 * 删除是唯一不可逆的操作，单独放到底部并要求二次确认。
 */
class OAuthAccountSheet(
    private val host: BaseActivity,
    private val provider: OAuthProviders.Provider,
    initial: OAuthAccounts.Account,
    /** 动作完成后通知上一级刷新。 */
    private val onChanged: () -> Unit,
) {
    /**
     * 当前展示的账号。动作完成后会重新拉一次，所以不能是 val。
     *
     * 主线程写、io 线程读，普通 var 没有可见性保证，后台可能拿到旧快照的 authIndex（#586）。
     */
    @Volatile
    private var account: OAuthAccounts.Account = initial

    /** 账号已在插件侧被删除：列表里找不到它，不能再显示旧快照与操作入口（#583）。 */
    @Volatile
    private var accountRemoved = false

    /**
     * 当前承载本页的卡片。
     *
     * show() 时是新建的那张；attachTo 复用列表那张卡时会指过去，否则页内 dismiss() 会关错卡片。
     * 延迟到真正要用时才创建，避免 attachTo 路径白建一个从不使用的 CodaSheet（#585）。
     */
    private lateinit var hostSheet: CodaSheet
    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()
    private var column: LinearLayout? = null
    /** null 表示能力尚未加载完成，与「确实不支持」必须分开（#582）。 */
    private var caps: OAuthAccounts.Capabilities? = null
    private var working = false

    private fun dp(v: Float): Int = (v * host.resources.displayMetrics.density + 0.5f).toInt()

    private fun color(
        attr: Int,
        fallback: Int,
    ): Int = MaterialColors.getColor(host.window.decorView, attr, fallback)

    private val onSurface: Int
        get() = color(com.google.android.material.R.attr.colorOnSurface, Color.WHITE)
    private val onSurfaceVariant: Int
        get() = color(com.google.android.material.R.attr.colorOnSurfaceVariant, Color.GRAY)
    private val surfaceHigh: Int
        get() = color(com.google.android.material.R.attr.colorSurfaceContainerHigh, Color.DKGRAY)
    private val primary: Int
        get() = color(androidx.appcompat.R.attr.colorPrimary, onSurface)

    private fun healthColor(h: OAuthAccounts.Health): Int =
        when (h) {
            OAuthAccounts.Health.GOOD -> 0xFF2E7D32.toInt()
            OAuthAccounts.Health.LOW -> 0xFFF9A825.toInt()
            OAuthAccounts.Health.COOLING -> 0xFF1565C0.toInt()
            OAuthAccounts.Health.SUSPECT -> 0xFFEF6C00.toInt()
            OAuthAccounts.Health.EXHAUSTED, OAuthAccounts.Health.DISABLED -> 0xFFC62828.toInt()
            OAuthAccounts.Health.UNKNOWN -> 0xFF757575.toInt()
        }

    private fun healthLabel(h: OAuthAccounts.Health): String =
        when (h) {
            OAuthAccounts.Health.GOOD -> "正常"
            OAuthAccounts.Health.LOW -> "额度偏低"
            OAuthAccounts.Health.COOLING -> "冷却中"
            OAuthAccounts.Health.SUSPECT -> "探活失败"
            OAuthAccounts.Health.EXHAUSTED -> "额度耗尽"
            OAuthAccounts.Health.DISABLED -> "已停用"
            OAuthAccounts.Health.UNKNOWN -> "状态未知"
        }

    /**
     * 本页在卡片上的全部配置，两条入口共用一份（#585）。
     *
     * 之前 show() 与 attachTo() 各抄一遍标题、内容、两个按钮和 onDismiss，
     * 改一处漏一处会让两条入口的行为悄悄分叉。
     */
    private fun CodaSheet.bindAccountPage() {
        title(account.title)
        subtitle(provider.name)
        content { col ->
            column = col
            renderLoading()
            reload()
        }
        primaryAction("签到") { checkin() }
        secondaryAction("关闭") { self -> self.dismiss() }
        onDismiss { io.shutdown() }
    }

    fun show() {
        val own = CodaSheet(host)
        hostSheet = own
        own.bindAccountPage()
        own.show()
    }

    /**
     * 把这一页挂到一张**已经打开**的卡片上，不新开卡片。
     *
     * 账号列表点某一行时用它：列表卡片原地换成这个账号的面板，
     * 而不是先关列表再弹一张新卡（那样卡片会先沉下去再升起来）。
     */
    fun attachTo(target: CodaSheet) {
        // 复用列表那张卡：本页的关闭动作必须作用在它上面，而不是自己 new 的那张
        hostSheet = target
        target.navigate { target.bindAccountPage() }
    }

    /** 拉一次最新状态：能力与账号数据一起更新，然后重画。 */
    private fun reload() {
        submit {
            // 读接口在后台线程，异常不接住会直接杀死进程；接住后要在界面上给出结果，
            // 不能把界面留在“正在读取账号状态…”。
            val loaded =
                runCatching {
                    val c = OAuthAccounts.capabilities(host, provider)
                    // 列表里找不到它，说明账号已经在插件侧被删掉了。这时不能拿旧快照当最新，
                    // 否则界面会继续显示健康度、额度并放行签到/删除（#583）。
                    val found =
                        OAuthAccounts
                            .list(host, provider)
                            .firstOrNull { it.authIndex == account.authIndex }
                    Triple(c, found ?: account, found == null)
                }
            main.post {
                loaded
                    .onSuccess { (c, fresh, removed) ->
                        caps = c
                        account = fresh
                        accountRemoved = removed
                        render()
                    }.onFailure { e ->
                        renderLoadFailure(e)
                    }
            }
        }
    }

    /**
     * 提交一个后台任务。
     *
     * 面板关闭时 onDismiss 已经 shutdown 线程池，此后任何延迟提交都会抛
     * RejectedExecutionException——它发生在主线程，等于直接崩溃。关闭后的回调直接丢弃。
     */
    private fun submit(block: () -> Unit) {
        try {
            io.execute(block)
        } catch (e: RuntimeException) {
            if (!OAuthSheetErrors.isPoolClosed(e)) throw e
        }
    }

    /** 读取失败也要有可见结果，否则卡片会一直停在加载态。 */
    private fun renderLoadFailure(e: Throwable) {
        val col = column ?: return
        col.removeAllViews()
        col.addView(hint(OAuthSheetErrors.reloadMessage(e)))
        // 失败不能是死路（OCR #820）：caps 停在 null 时签到只会一直提示
        // 「正在读取平台能力」，必须给重试入口；主按钮也换成重试。
        col.addView(
            android.widget.TextView(host).apply {
                text = "重试"
                textSize = 15f
                setTextColor(primary)
                setPadding(0, dp(12f), 0, dp(12f))
                isClickable = true
                setOnClickListener {
                    renderLoading()
                    reload()
                }
            },
        )
        hostSheet.primaryAction("重试") {
            renderLoading()
            reload()
        }
    }

    private fun renderLoading() {
        column?.apply {
            removeAllViews()
            addView(hint("正在读取账号状态…"))
        }
    }

    private fun render() {
        val col = column ?: return
        col.removeAllViews()
        if (accountRemoved) {
            // 账号已不存在：只说明状态，不再渲染健康度、额度与任何动作入口（#583）。
            // 卡片底部的主按钮是配置在卡片上的，不跟着内容区走，这里一并换成关闭。
            hostSheet.primaryAction("关闭") { self -> self.dismiss() }
            col.addView(hint("该账号已不存在，可能已在插件面板中被删除。"))
            col.addView(hint("关闭这张卡片后重新打开列表即可看到最新账号。"))
            return
        }
        col.addView(healthCard())
        col.addView(creditsCard())
        col.addView(checkinCard())
        col.addView(actionRow())
        col.addView(deleteRow())
    }

    /** 健康度卡：大字状态 + 一句解释，颜色跟着状态走。 */
    private fun healthCard(): View {
        val health = account.health
        val card =
            LinearLayout(host).apply {
                orientation = LinearLayout.VERTICAL
                background =
                    GradientDrawable().apply {
                        cornerRadius = dp(14f).toFloat()
                        setColor(surfaceHigh)
                    }
                setPadding(dp(16f), dp(14f), dp(16f), dp(14f))
                layoutParams =
                    LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply {
                        bottomMargin = dp(12f)
                    }
            }
        val head =
            LinearLayout(host).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
        head.addView(
            View(host).apply {
                background =
                    GradientDrawable().apply {
                        shape = GradientDrawable.OVAL
                        setColor(healthColor(health))
                    }
                layoutParams = LinearLayout.LayoutParams(dp(10f), dp(10f)).apply { rightMargin = dp(9f) }
            },
        )
        head.addView(
            TextView(host).apply {
                text = healthLabel(health)
                setTextColor(onSurface)
                textSize = 16f
                setTypeface(typeface, Typeface.BOLD)
            },
        )
        if (account.selected) {
            head.addView(
                TextView(host).apply {
                    text = "当前路由"
                    setTextColor(primary)
                    textSize = 11.5f
                    setPadding(dp(10f), 0, 0, 0)
                },
            )
        }
        card.addView(head)

        // 解释只在该说的话上说，正常账号不啰嗦。
        val note =
            when (health) {
                OAuthAccounts.Health.COOLING -> {
                    val left = (account.coolUntil * 1000 - System.currentTimeMillis()) / 60_000
                    if (left > 0) "冷却中，约 $left 分钟后恢复" else "冷却即将结束"
                }

                OAuthAccounts.Health.EXHAUSTED -> {
                    "额度已耗尽，签到或等周期刷新后可恢复"
                }

                OAuthAccounts.Health.DISABLED -> {
                    "已被停用，插件不会再给它派请求"
                }

                OAuthAccounts.Health.SUSPECT -> {
                    "定时探活失败，但仍有额度"
                }

                OAuthAccounts.Health.LOW -> {
                    "余额低于调度红线，请求会被优先绕开"
                }

                OAuthAccounts.Health.UNKNOWN -> {
                    account.error.ifEmpty { "暂时读不到状态" }
                }

                OAuthAccounts.Health.GOOD -> {
                    ""
                }
            }
        if (note.isNotEmpty()) {
            card.addView(
                TextView(host).apply {
                    text = note
                    setTextColor(onSurfaceVariant)
                    textSize = 12.5f
                    setPadding(0, dp(6f), 0, 0)
                },
            )
        }

        // 请求成功率，只有真有请求时才显示。
        val total = account.success + account.failed
        if (total > 0) {
            val rate = account.success * 100 / total
            card.addView(
                TextView(host).apply {
                    text = "累计请求 $total 次，成功 $rate%"
                    setTextColor(onSurfaceVariant)
                    textSize = 12f
                    setPadding(0, dp(6f), 0, 0)
                },
            )
        }
        return card
    }

    /** 额度卡：有数据就画一条进度，没拉到数据就只提示。 */
    private fun creditsCard(): View {
        val card =
            LinearLayout(host).apply {
                orientation = LinearLayout.VERTICAL
                background =
                    GradientDrawable().apply {
                        cornerRadius = dp(14f).toFloat()
                        setColor(surfaceHigh)
                    }
                setPadding(dp(16f), dp(14f), dp(16f), dp(14f))
                layoutParams =
                    LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply {
                        bottomMargin = dp(12f)
                    }
            }
        card.addView(
            TextView(host).apply {
                text = "额度"
                setTextColor(onSurfaceVariant)
                textSize = 11.5f
            },
        )
        if (account.creditsSize <= 0) {
            card.addView(
                TextView(host).apply {
                    text = "尚未拉到额度。点下方「刷新额度」向服务端取一次。"
                    setTextColor(onSurfaceVariant)
                    textSize = 12.5f
                    setPadding(0, dp(5f), 0, 0)
                },
            )
            return card
        }
        card.addView(
            TextView(host).apply {
                text = "${account.creditsRemain} / ${account.creditsSize}"
                setTextColor(onSurface)
                textSize = 19f
                setTypeface(typeface, Typeface.BOLD)
                setPadding(0, dp(3f), 0, 0)
            },
        )
        // 一条细进度：剩余部分用状态色，已用部分留底色。用权重分配宽度。
        val ratio = account.creditsRatio
        if (ratio >= 0) {
            val track =
                LinearLayout(host).apply {
                    orientation = LinearLayout.HORIZONTAL
                    background =
                        GradientDrawable().apply {
                            cornerRadius = dp(3f).toFloat()
                            setColor(onSurfaceVariant and 0x33FFFFFF)
                        }
                    layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, dp(6f)).apply { topMargin = dp(9f) }
                }
            if (ratio > 0f) {
                track.addView(
                    View(host).apply {
                        background =
                            GradientDrawable().apply {
                                cornerRadius = dp(3f).toFloat()
                                setColor(healthColor(account.health))
                            }
                        layoutParams = LinearLayout.LayoutParams(0, MATCH_PARENT, ratio)
                    },
                )
            }
            if (ratio < 1f) {
                track.addView(
                    View(host).apply {
                        layoutParams = LinearLayout.LayoutParams(0, MATCH_PARENT, 1f - ratio)
                    },
                )
            }
            card.addView(track)
        }
        if (account.creditsUsed > 0) {
            card.addView(
                TextView(host).apply {
                    text = "本期已用 ${account.creditsUsed}"
                    setTextColor(onSurfaceVariant)
                    textSize = 12f
                    setPadding(0, dp(8f), 0, 0)
                },
            )
        }
        return card
    }

    /** 签到卡：今天签没签、连签几天，右侧一个签到按钮。 */
    private fun checkinCard(): View {
        val card =
            LinearLayout(host).apply {
                orientation = LinearLayout.VERTICAL
                background =
                    GradientDrawable().apply {
                        cornerRadius = dp(14f).toFloat()
                        setColor(surfaceHigh)
                    }
                setPadding(dp(16f), dp(14f), dp(16f), dp(14f))
                layoutParams =
                    LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply {
                        bottomMargin = dp(12f)
                    }
            }
        val head =
            LinearLayout(host).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
        val text =
            LinearLayout(host).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f)
            }
        text.addView(
            TextView(host).apply {
                this.text = "签到"
                setTextColor(onSurface)
                textSize = 14.5f
                setTypeface(typeface, Typeface.BOLD)
            },
        )
        text.addView(
            TextView(host).apply {
                val bits = mutableListOf<String>()
                bits += if (account.todayCheckedIn) "今天已签" else "今天未签"
                if (account.streakDays > 0) bits += "连签 ${account.streakDays} 天"
                if (account.weekCheckinDays > 0) bits += "本周 ${account.weekCheckinDays} 天"
                if (account.todayCredit > 0) bits += "今日 +${account.todayCredit}"
                this.text = bits.joinToString(" · ")
                setTextColor(onSurfaceVariant)
                textSize = 12f
                setPadding(0, dp(3f), 0, 0)
            },
        )
        head.addView(text)

        // 三态：未加载 / 不支持 / 支持。把未加载当成不支持会在弱网下误报（#582）。
        val checkinLabel =
            when (val c = caps) {
                null -> "正在读取…"
                else -> if (c.checkin) null else "该平台不支持"
            }
        if (checkinLabel != null) {
            head.addView(
                TextView(host).apply {
                    this.text = checkinLabel
                    setTextColor(onSurfaceVariant)
                    textSize = 12f
                },
            )
        } else {
            head.addView(actionButton(if (account.todayCheckedIn) "再签一次" else "立即签到", primary) { checkin() })
        }
        card.addView(head)

        // 国际版账号走加油包，没有签到。
        if (account.region == "global") {
            card.addView(
                TextView(host).apply {
                    setText("国际版账号没有每日签到，额度靠专家加油包。")
                    setTextColor(onSurfaceVariant)
                    textSize = 12f
                    setPadding(0, dp(7f), 0, 0)
                },
            )
        }
        return card
    }

    /** 已知平台的动作清单按插件的真实路由逐条试探，界面只照结果渲染。 */
    private fun actionRow(): View {
        val row =
            LinearLayout(host).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams =
                    LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply {
                        bottomMargin = dp(12f)
                    }
            }
        // 签到有专门的卡片，这里跳过，避免同一个动作出现两次。
        // 判空必须用过滤后的集合：否则只有 delete 的平台会渲染出一行空白（#587）。
        val actions = OAuthActions.available(caps ?: return row).filter { it.id != "checkin" && it.id != "delete" }
        if (actions.isEmpty()) {
            row.addView(hint("该平台插件没有提供额外的账号操作。"))
            return row
        }
        actions.forEach { action ->
            row.addView(
                actionItem(
                    title = action.title,
                    note = action.note,
                    tint = if (action.danger) 0xFFC62828.toInt() else onSurface,
                ) { perform(action) },
            )
        }
        return row
    }

    /** 执行表里的动作。需要确认的先弹二次确认，否则直接跑。 */
    private fun perform(action: OAuthActions.Action) {
        if (accountRemoved) {
            Toast.makeText(host, "该账号已不存在", Toast.LENGTH_SHORT).show()
            return
        }
        if (!action.confirm) {
            val target = account.authIndex
            runAction { OAuthActions.run(host, provider, action, target) }
            return
        }
        CodaSheet(host)
            .compact()
            .title(action.title)
            .subtitle(action.note)
            .content { col ->
                col.addView(
                    TextView(host).apply {
                        setText("确认要对「${account.title}」执行吗？")
                        setTextColor(onSurfaceVariant)
                        textSize = 13f
                    },
                )
            }.primaryAction("确认") { self ->
                self.dismiss()
                val target = account.authIndex
                runAction { OAuthActions.run(host, provider, action, target) }
            }.secondaryAction("取消") { self -> self.dismiss() }
            .show()
    }

    /** 删除单独一节，颜色用错误的红，明确它是不可逆的。 */
    private fun deleteRow(): View {
        val box = LinearLayout(host).apply { orientation = LinearLayout.VERTICAL }
        // 与 OAuthActions.available 同一判据（OCR #820）：只看 panel 会让
        // 没实现 /delete 的插件也显示不可逆的「删除」，点了必然 404。
        if (caps?.routes?.contains("/delete") == true) {
            box.addView(actionItem("删除账号", "同时删掉本地凭据，需要重新授权", 0xFFC62828.toInt()) { confirmDelete() })
        } else {
            box.addView(hint("该平台的账号需要在插件的管理面板里删除。"))
        }
        return box
    }

    /** 一行可点动作：标题 + 说明 + 箭头。 */
    private fun actionItem(
        title: String,
        note: String,
        tint: Int = onSurface,
        block: () -> Unit,
    ): View =
        LinearLayout(host).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(13f), 0, dp(13f))
            isClickable = true
            setOnClickListener { block() }

            val col =
                LinearLayout(host).apply {
                    orientation = LinearLayout.VERTICAL
                    layoutParams = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f)
                }
            col.addView(
                TextView(host).apply {
                    text = title
                    setTextColor(tint)
                    textSize = 14.5f
                },
            )
            col.addView(
                TextView(host).apply {
                    text = note
                    setTextColor(onSurfaceVariant)
                    textSize = 11.5f
                    setPadding(0, dp(2f), 0, 0)
                },
            )
            addView(col)
            addView(
                TextView(host).apply {
                    text = "›"
                    setTextColor(onSurfaceVariant)
                    textSize = 16f
                },
            )
        }

    private fun actionButton(
        text: String,
        tint: Int,
        block: () -> Unit,
    ): TextView =
        TextView(host).apply {
            this.text = text
            setTextColor(tint)
            textSize = 13f
            setPadding(dp(14f), dp(7f), dp(14f), dp(7f))
            background =
                GradientDrawable().apply {
                    cornerRadius = dp(16f).toFloat()
                    setStroke(dp(1f), tint and 0x66FFFFFF)
                    setColor(Color.TRANSPARENT)
                }
            isClickable = true
            setOnClickListener { block() }
        }

    // ---------------------------------------------------------------- 动作

    /**
     * 所有动作统一走这里：跑在后台、防重入、结果提示、完成后刷新。
     *
     * [onSuccess] 只在动作真的成功时执行。删除这类不可逆操作要靠它关面板——
     * 之前不看结果就关，失败时用户会以为已经删掉了。
     */
    private fun runAction(
        onSuccess: (() -> Unit)? = null,
        block: () -> OAuthAccounts.Outcome,
    ) {
        if (working) return
        working = true
        submit {
            val result =
                runCatching(block).getOrElse {
                    OAuthAccounts.Outcome(false, it.message ?: "操作失败")
                }
            main.post {
                working = false
                Toast.makeText(host, result.message, Toast.LENGTH_LONG).show()
                if (result.ok) {
                    onChanged()
                    if (onSuccess != null) {
                        onSuccess()
                        // 页面已经关掉或换走：不再刷新，避免操作已移除的视图
                        return@post
                    }
                }
                // 无论成败都重读一次，避免界面停在旧状态。
                reload()
            }
        }
    }

    private fun checkin() {
        val c = caps
        // 尚未加载完成不等于不支持：弱网下首次进页面就点签到会收到错误结论（#582）。
        if (c == null) {
            Toast.makeText(host, "正在读取平台能力，请稍候", Toast.LENGTH_SHORT).show()
            return
        }
        if (!c.checkin) {
            Toast.makeText(host, "该平台不支持签到", Toast.LENGTH_SHORT).show()
            return
        }
        if (accountRemoved) {
            Toast.makeText(host, "该账号已不存在", Toast.LENGTH_SHORT).show()
            return
        }
        val target = account.authIndex
        runAction { OAuthAccounts.checkin(host, provider, target) }
    }

    /** 删除要二次确认：这一步连本地凭据一起删，删完只能重新授权。 */
    private fun confirmDelete() {
        CodaSheet(host)
            .compact()
            .title("删除账号")
            .subtitle(account.title)
            .content { col ->
                col.addView(
                    TextView(host).apply {
                        setText("会连同本地凭据一起删除，之后需要重新授权才能使用。已经产生的额度不会退回。")
                        setTextColor(onSurfaceVariant)
                        textSize = 13f
                        setLineSpacing(dp(3f).toFloat(), 1f)
                    },
                )
            }.primaryAction("删除") { self ->
                self.dismiss()
                val action = OAuthActions.byId("delete")
                if (action == null) {
                    Toast.makeText(host, "删除动作不可用", Toast.LENGTH_LONG).show()
                    return@primaryAction
                }
                // 只有真的删成功才关面板：失败时面板要留着，错误提示可见、可以重试
                val target = account.authIndex
                runAction(onSuccess = { hostSheet.dismiss() }) {
                    OAuthActions.run(host, provider, action, target)
                }
            }.secondaryAction("取消") { self -> self.dismiss() }
            .show()
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

/**
 * 账号面板错误处理的判定。
 *
 * 这两条都是“边界”，不是格式偏好：既要只吞关闭后预期的拒绝执行异常（不能连 OOM
 * 一起吞），也要保证读取失败时界面上有一句非空提示，而不是停在加载态。
 */
internal object OAuthSheetErrors {
    /** 线程池已关闭导致的提交失败：面板关掉之后才会出现，可以丢弃。 */
    fun isPoolClosed(e: Throwable): Boolean = e is RejectedExecutionException

    /** 读取失败的提示文本；异常没有 message 时退回类名，保证不是空串。 */
    fun reloadMessage(e: Throwable): String = e.message?.takeIf { it.isNotBlank() } ?: e.javaClass.simpleName
}
