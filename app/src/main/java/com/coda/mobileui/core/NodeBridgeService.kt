package com.coda.mobileui.core

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import com.coda.mobileui.MainActivity
import com.coda.mobileui.R

/**
 * 宿主桥前台服务：让节点在 App 退到后台后仍可被桌面端连接。
 *
 * Android 仍可能在内存紧张时杀进程；杀进程后连接会断，桌面端必须显示
 * 「节点离线」而不是假装任务还在跑。这里只保证正常后台期间存活与可见通知。
 */
class NodeBridgeService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
        // 这时端口还没绑定，先给个中性文案，等 onStartCommand 成功后再更新成真实端口。
        startForeground(NOTIFICATION_ID, buildNotification("正在启动…"))
    }

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int,
    ): Int {
        val error = NodeBridge.start(this)
        if (error != null) {
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.notify(NOTIFICATION_ID, buildNotification("启动失败：$error"))
            stopSelf()
            return START_NOT_STICKY
        }
        // 端口绑定成功后再刷新一次：否则通知会一直显示启动前的 0。
        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
            .notify(NOTIFICATION_ID, buildNotification())
        return START_STICKY
    }

    override fun onDestroy() {
        NodeBridge.stop()
        super.onDestroy()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "桌面节点桥", NotificationManager.IMPORTANCE_LOW).apply {
                description = "允许已配对的 Coda 桌面端连接本机"
            },
        )
    }

    private fun buildNotification(text: String = "已监听 ${NodeBridge.port()}，等待桌面端连接"): Notification {
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val builder =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                Notification.Builder(this, CHANNEL_ID)
            } else {
                @Suppress("DEPRECATION")
                Notification.Builder(this)
            }
        return builder
            .setContentTitle("Coda 桌面节点")
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_server)
            .setContentIntent(open)
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val CHANNEL_ID = "coda_node_bridge"
        private const val NOTIFICATION_ID = 3107

        fun start(ctx: Context) {
            val intent = Intent(ctx, NodeBridgeService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) ctx.startForegroundService(intent) else ctx.startService(intent)
        }

        fun stop(ctx: Context) {
            ctx.stopService(Intent(ctx, NodeBridgeService::class.java))
        }
    }
}
