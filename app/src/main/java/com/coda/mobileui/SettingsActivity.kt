package com.coda.mobileui

import android.content.Intent
import android.os.Bundle
import android.view.MotionEvent
import android.view.View
import android.widget.LinearLayout
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.snackbar.Snackbar

/**
 * 设置（一级）：分类列表，对齐桌面端的全部设置分区。
 */
class SettingsActivity : BaseActivity() {
    private var swipeStartX = 0f
    private var swipeStartY = 0f

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setupEdgeToEdge()
        setContentView(R.layout.activity_settings)

        findViewById<View>(R.id.settings_root).applySystemBarsInset()

        findViewById<View>(R.id.btn_back).setOnClickListener { backToDrawer() }
        // 系统返回键/返回手势也要走同一条路径：之前它只 finish()，抽屉不展开，
        // 与返回按钮、右滑手势的行为不一致（#598）。
        onBackPressedDispatcher.addCallback(
            this,
            object : androidx.activity.OnBackPressedCallback(true) {
                override fun handleOnBackPressed() = backToDrawer()
            },
        )
        findViewById<View>(R.id.btn_more).setOnClickListener { view ->
            Snackbar.make(view, R.string.settings_more_placeholder, Snackbar.LENGTH_SHORT).show()
        }

        SettingsViewBuilder.render(
            this,
            findViewById<LinearLayout>(R.id.settings_container),
            SettingsData.home(this),
        ) { page ->
            startActivity(
                Intent(this, SettingsDetailActivity::class.java)
                    .putExtra(SettingsDetailActivity.EXTRA_PAGE, page),
            )
        }
    }

    /**
     * 右滑返回：只观察事件，不抢着消费。
     *
     * 之前挂在根布局的 OnTouchListener 上，且 ACTION_DOWN 返回 false——列表项、
     * 按钮这些可交互子控件先把事件吃掉，根布局根本收不到 ACTION_UP，手势在这些
     * 区域自然失效。放在 Activity 的事件入口就绕开了这一层：子控件照常收到事件，
     * 只有确认是右滑的那一次抬手才被消费。
     */
    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                swipeStartX = ev.x
                swipeStartY = ev.y
            }

            MotionEvent.ACTION_UP -> {
                val dx = ev.x - swipeStartX
                val dy = kotlin.math.abs(ev.y - swipeStartY)
                if (isRightBackSwipe(dx, dy, dp(36).toFloat())) {
                    backToDrawer()
                    return true
                }
            }
        }
        return super.dispatchTouchEvent(ev)
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    /** 回到主界面并展开抽屉。 */
    private fun backToDrawer() {
        // 走一次性登记 API，不再直接写别处的公开静态字段（#599）。
        MainActivity.requestOpenDrawer()
        finish()
    }
}

/** 右滑返回判定：横向位移过阈值，且明显大于纵向（避免把竖滑当成返回）。 */
internal fun isRightBackSwipe(
    dx: Float,
    dy: Float,
    thresholdPx: Float,
): Boolean = dx > thresholdPx && dx > dy
