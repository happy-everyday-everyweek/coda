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

    /** 当前展示的账号。动作完成后会重新拉一次，所以不能是 val。 */
    private var account: OAuthAccounts.Account = initial

    private val sheet = CodaSheet(host)
    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()
    private var column: LinearLayout? = null
    private var caps = OAuthAccounts.Capabilities(false, false, false, false, false)
    private var working = false

    private fun dp(v: Float): Int = (v * host.resources.displayMetrics.density + 0.5f).toInt()

    private fun color(attr: Int, fallback: Int): Int =
        MaterialColors.getColor(host.window.decorView, attr, fallback)

    private val onSurface: Int
        get() = color(com.google.android.material.R.attr.colorOnSurface, Color.WHITE)
    private val onSurfaceVariant: Int
        get() = color(com.google.android.material.R.attr.colorOnSurfaceVariant, Color.GRAY)
    private val surfaceHigh: Int
        get() = color(com.google.android.material.R.attr.colorSurfaceContainerHigh, Color.DKGRAY)
    private val primary: Int
        get() = color(androidx.appcompat.R.attr.colorPrimary, onSurface)

    private fun healthColor(h: OAuthAccounts.Health): Int = when (h) {
        OAuthAccounts.Health.GOOD -> 0xFF2E7D32.toInt()
        OAuthAccounts.Health.LOW -> 0xFFF9A825.toInt()
        OAuthAccounts.Health.COOLING -> 0xFF1565C0.toInt()
        OAuthAccounts.Health.SUSPECT -> 0xFFEF6C00.toInt()
        OAuthAccounts.Health.EXHAUSTED, OAuthAccounts.Health.DISABLED -> 0xFFC62828.toInt()
        OAuthAccounts.Health.UNKNOWN -> 0xFF757575.toInt()
    }

    private fun healthLabel(h: OAuthAccounts.Health): String = when (h) {
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
            .title(account.title)
            .subtitle(provider.name)
            .content { col ->
                column = col
                renderLoading()
                reload()
            }
            .primaryAction("签到") { checkin() }
            .secondaryAction("关闭") { self -> self.dismiss() }
            .onDismiss { io.shutdown() }
            .show()
    }

/** 拉一次最新状态：能力与账号数据一起更新，然后重画。 */
    private fun reload() {
        io.execute {
            val c = OAuthAccounts.capabilities(host, provider)
            // 账号可能已经被删掉，这时列表里找不到，保留原值让界面不至于空白。
            val fresh = OAuthAccounts.list(host, provider).firstOrNull { it.authIndex == account.authIndex }
                ?: account
            main.post {
                caps = c
                account = fresh
                render()
            }
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

        col.addView(healthCard())
        col.addView(creditsCard())
        col.addView(checkinCard())
        col.addView(actionRow())
        col.addView(deleteRow())
    }

    /** 健康度卡：大字状态 + 一句解释，颜色跟着状态走。 */
    private fun healthCard(): View {
        val health = account.health
        val card = LinearLayout(host).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                cornerRadius = dp(14f).toFloat()
                setColor(surfaceHigh)
            }
            setPadding(dp(16f), dp(14f), dp(16f), dp(14f))
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply {
                bottomMargin = dp(12f)
            }
        }
        val head = LinearLayout(host).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        head.addView(View(host).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(healthColor(health))
            }
            layoutParams = LinearLayout.LayoutParams(dp(10f), dp(10f)).apply { rightMargin = dp(9f) }
        })
        head.addView(TextView(host).apply {
            text = healthLabel(health)
            setTextColor(onSurface)
            textSize = 16f
            setTypeface(typeface, Typeface.BOLD)
        })
        if (account.selected) {
            head.addView(TextView(host).apply {
                text = "当前路由"
                setTextColor(primary)
                textSize = 11.5f
                setPadding(dp(10f), 0, 0, 0)
            })
        }
        card.addView(head)

        // 解释只在该说的话上说，正常账号不啰嗦。
        val note = when (health) {
            OAuthAccounts.Health.COOLING -> {
                val left = (account.coolUntil * 1000 - System.currentTimeMillis()) / 60_000
                if (left > 0) "冷却中，约 $left 分钟后恢复" else "冷却即将结束"
            }
            OAuthAccounts.Health.EXHAUSTED -> "额度已耗尽，签到或等周期刷新后可恢复"
            OAuthAccounts.Health.DISABLED -> "已被停用，插件不会再给它派请求"
            OAuthAccounts.Health.SUSPECT -> "定时探活失败，但仍有额度"
            OAuthAccounts.Health.LOW -> "余额低于调度红线，请求会被优先绕开"
            OAuthAccounts.Health.UNKNOWN -> account.error.ifEmpty { "暂时读不到状态" }
            OAuthAccounts.Health.GOOD -> ""
        }
        if (note.isNotEmpty()) {
            card.addView(TextView(host).apply {
                text = note
                setTextColor(onSurfaceVariant)
                textSize = 12.5f
                setPadding(0, dp(6f), 0, 0)
            })
        }

        // 请求成功率，只有真有请求时才显示。
        val total = account.success + account.failed
        if (total > 0) {
            val rate = account.success * 100 / total
            card.addView(TextView(host).apply {
                text = "累计请求 $total 次，成功 $rate%"
                setTextColor(onSurfaceVariant)
                textSize = 12f
                setPadding(0, dp(6f), 0, 0)
            })
        }
        return card
    }

    /** 额度卡：有数据就画一条进度，没拉到数据就只提示。 */
    private fun creditsCard(): View {
        val card = LinearLayout(host).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                cornerRadius = dp(14f).toFloat()
                setColor(surfaceHigh)
            }
            setPadding(dp(16f), dp(14f), dp(16f), dp(14f))
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply {
                bottomMargin = dp(12f)
            }
        }
        card.addView(TextView(host).apply {
            text = "额度"
            setTextColor(onSurfaceVariant)
            textSize = 11.5f
        })
        if (account.creditsSize <= 0) {
            card.addView(TextView(host).apply {
                text = "尚未拉到额度。点下方「刷新额度」向服务端取一次。"
                setTextColor(onSurfaceVariant)
                textSize = 12.5f
                setPadding(0, dp(5f), 0, 0)
            })
            return card
        }
        card.addView(TextView(host).apply {
            text = "${account.creditsRemain} / ${account.creditsSize}"
            setTextColor(onSurface)
            textSize = 19f
            setTypeface(typeface, Typeface.BOLD)
            setPadding(0, dp(3f), 0, 0)
        })
        // 一条细进度：剩余部分用状态色，已用部分留底色。用权重分配宽度。
        val ratio = account.creditsRatio
        if (ratio >= 0) {
            val track = LinearLayout(host).apply {
                orientation = LinearLayout.HORIZONTAL
                background = GradientDrawable().apply {
                    cornerRadius = dp(3f).toFloat()
                    setColor(onSurfaceVariant and 0x33FFFFFF)
                }
                layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, dp(6f)).apply { topMargin = dp(9f) }
            }
            if (ratio > 0f) {
                track.addView(View(host).apply {
                    background = GradientDrawable().apply {
                        cornerRadius = dp(3f).toFloat()
                        setColor(healthColor(account.health))
                    }
                    layoutParams = LinearLayout.LayoutParams(0, MATCH_PARENT, ratio)
                })
            }
            if (ratio < 1f) {
                track.addView(View(host).apply {
                    layoutParams = LinearLayout.LayoutParams(0, MATCH_PARENT, 1f - ratio)
                })
            }
            card.addView(track)
        }
        if (account.creditsUsed > 0) {
            card.addView(TextView(host).apply {
                text = "本期已用 ${account.creditsUsed}"
                setTextColor(onSurfaceVariant)
                textSize = 12f
                setPadding(0, dp(8f), 0, 0)
            })
        }
        return card
    }

    /** 签到卡：今天签没签、连签几天，右侧一个签到按钮。 */
    private fun checkinCard(): View {
        val card = LinearLayout(host).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                cornerRadius = dp(14f).toFloat()
                setColor(surfaceHigh)
            }
            setPadding(dp(16f), dp(14f), dp(16f), dp(14f))
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply {
                bottomMargin = dp(12f)
            }
        }
        val head = LinearLayout(host).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val text = LinearLayout(host).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f)
        }
        text.addView(TextView(host).apply {
            this.text = "签到"
            setTextColor(onSurface)
            textSize = 14.5f
            setTypeface(typeface, Typeface.BOLD)
        })
        text.addView(TextView(host).apply {
            val bits = mutableListOf<String>()
            bits += if (account.todayCheckedIn) "今天已签" else "今天未签"
            if (account.streakDays > 0) bits += "连签 ${account.streakDays} 天"
            if (account.weekCheckinDays > 0) bits += "本周 ${account.weekCheckinDays} 天"
            if (account.todayCredit > 0) bits += "今日 +${account.todayCredit}"
            this.text = bits.joinToString(" · ")
            setTextColor(onSurfaceVariant)
            textSize = 12f
            setPadding(0, dp(3f), 0, 0)
        })
        head.addView(text)

        if (!caps.checkin) {
            head.addView(TextView(host).apply {
                this.text = "该平台不支持"
                setTextColor(onSurfaceVariant)
                textSize = 12f
            })
        } else {
            head.addView(actionButton(if (account.todayCheckedIn) "再签一次" else "立即签到", primary) { checkin() })
        }
        card.addView(head)

        // 国际版账号走加油包，没有签到。
        if (account.region == "global") {
            card.addView(TextView(host).apply {
                setText("国际版账号没有每日签到，额度靠专家加油包。")
                setTextColor(onSurfaceVariant)
                textSize = 12f
                setPadding(0, dp(7f), 0, 0)
            })
        }
        return card
    }

    /** 已知平台的动作清单按插件的真实路由逐条试探，界面只照结果渲染。 */
    private fun actionRow(): View {
        val row = LinearLayout(host).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply {
                bottomMargin = dp(12f)
            }
        }
        // 签到有专门的卡片，这里跳过，避免同一个动作出现两次。
        val actions = OAuthActions.available(caps).filter { it.id != "checkin" }
        if (actions.isEmpty()) {
            row.addView(hint("该平台插件没有提供额外的账号操作。"))
            return row
        }
        actions.forEach { action ->
            // 删除放在最底部单独处理，这里不重复。
            if (action.id == "delete") return@forEach
            row.addView(
                actionItem(
                    title = action.title,
                    note = action.note,
                    tint = if (action.danger) 0xFFC62828.toInt() else onSurface,
                ) { perform(action) }
            )
        }
        return row
    }

    /** 执行表里的动作。需要确认的先弹二次确认，否则直接跑。 */
    private fun perform(action: OAuthActions.Action) {
        if (!action.confirm) {
            run { OAuthActions.run(host, provider, action, account.authIndex) }
            return
        }
        CodaSheet(host)
            .compact()
            .title(action.title)
            .subtitle(action.note)
            .content { col ->
                col.addView(TextView(host).apply {
                    setText("确认要对「${account.title}」执行吗？")
                    setTextColor(onSurfaceVariant)
                    textSize = 13f
                })
            }
            .primaryAction("确认") { self ->
                self.dismiss()
                run { OAuthActions.run(host, provider, action, account.authIndex) }
            }
            .secondaryAction("取消") { self -> self.dismiss() }
            .show()
    }

    /** 删除单独一节，颜色用错误的红，明确它是不可逆的。 */
    private fun deleteRow(): View {
        val box = LinearLayout(host).apply { orientation = LinearLayout.VERTICAL }
        if (caps.panel) {
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
    ): View = LinearLayout(host).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(0, dp(13f), 0, dp(13f))
        isClickable = true
        setOnClickListener { block() }

        val col = LinearLayout(host).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f)
        }
        col.addView(TextView(host).apply {
            text = title
            setTextColor(tint)
            textSize = 14.5f
        })
        col.addView(TextView(host).apply {
            text = note
            setTextColor(onSurfaceVariant)
            textSize = 11.5f
            setPadding(0, dp(2f), 0, 0)
        })
        addView(col)
        addView(TextView(host).apply {
            text = "›"
            setTextColor(onSurfaceVariant)
            textSize = 16f
        })
    }

    private fun actionButton(text: String, tint: Int, block: () -> Unit): TextView =
        TextView(host).apply {
            this.text = text
            setTextColor(tint)
            textSize = 13f
            setPadding(dp(14f), dp(7f), dp(14f), dp(7f))
            background = GradientDrawable().apply {
                cornerRadius = dp(16f).toFloat()
                setStroke(dp(1f), tint and 0x66FFFFFF)
                setColor(Color.TRANSPARENT)
            }
            isClickable = true
            setOnClickListener { block() }
        }

    // ---------------------------------------------------------------- 动作

    /** 所有动作统一走这里：跑在后台、防重入、结果提示、完成后刷新。 */
    private fun run(block: () -> OAuthAccounts.Outcome) {
        if (working) return
        working = true
        io.execute {
            val result = runCatching(block).getOrElse { OAuthAccounts.Outcome(false, it.message ?: "操作失败") }
            main.post {
                working = false
                Toast.makeText(host, result.message, Toast.LENGTH_LONG).show()
                if (result.ok) onChanged()
                // 无论成败都重读一次，避免界面停在旧状态。
                reload()
            }
        }
    }

    private fun checkin() {
        if (!caps.checkin) {
            Toast.makeText(host, "该平台不支持签到", Toast.LENGTH_SHORT).show()
            return
        }
        run { OAuthAccounts.checkin(host, provider, account.authIndex) }
    }

    /** 删除要二次确认：这一步连本地凭据一起删，删完只能重新授权。 */
    private fun confirmDelete() {
        CodaSheet(host)
            .compact()
            .title("删除账号")
            .subtitle(account.title)
            .content { col ->
                col.addView(TextView(host).apply {
                    setText("会连同本地凭据一起删除，之后需要重新授权才能使用。已经产生的额度不会退回。")
                    setTextColor(onSurfaceVariant)
                    textSize = 13f
                    setLineSpacing(dp(3f).toFloat(), 1f)
                })
            }
            .primaryAction("删除") { self ->
                self.dismiss()
                val action = OAuthActions.byId("delete")
                if (action != null) {
                    run { OAuthActions.run(host, provider, action, account.authIndex) }
                }
                // 删掉后这个账号已不存在，面板本身也该关掉。
                sheet.dismiss()
            }
            .secondaryAction("取消") { self -> self.dismiss() }
            .show()
    }

    private fun hint(text: String): TextView = TextView(host).apply {
        this.text = text
        setTextColor(onSurfaceVariant)
        textSize = 13f
        setLineSpacing(dp(3f).toFloat(), 1f)
        setPadding(0, dp(10f), 0, dp(10f))
    }
}