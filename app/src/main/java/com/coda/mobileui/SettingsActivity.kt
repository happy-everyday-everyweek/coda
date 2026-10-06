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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setupEdgeToEdge()
        setContentView(R.layout.activity_settings)

        findViewById<View>(R.id.settings_root).applySystemBarsInset()

        findViewById<View>(R.id.btn_back).setOnClickListener { backToDrawer() }
        attachRightSwipe()
        findViewById<View>(R.id.btn_more).setOnClickListener { view ->
            Snackbar.make(view, R.string.settings_more_placeholder, Snackbar.LENGTH_SHORT).show()
        }

        SettingsViewBuilder.render(
            this,
            findViewById<LinearLayout>(R.id.settings_container),
            SettingsData.home,
        ) { page ->
            startActivity(
                Intent(this, SettingsDetailActivity::class.java)
                    .putExtra(SettingsDetailActivity.EXTRA_PAGE, page),
            )
        }
    }

    /** 设置首页与对话页同级：右滑回到抽屉。 */
    private fun attachRightSwipe() {
        var startX = 0f
        var startY = 0f
        findViewById<View>(R.id.settings_root).setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    startX = event.x
                    startY = event.y
                    false
                }

                MotionEvent.ACTION_UP -> {
                    val dx = event.x - startX
                    val dy = kotlin.math.abs(event.y - startY)
                    if (dx > dp(36) && dx > dy) {
                        backToDrawer()
                        true
                    } else {
                        false
                    }
                }

                else -> false
            }
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    /** 回到主界面并展开抽屉。 */
    private fun backToDrawer() {
        MainActivity.pendingOpenDrawer = true
        finish()
    }
}