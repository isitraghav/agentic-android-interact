package com.agent.nemotron

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import androidx.core.app.NotificationCompat

object NotificationHelper {
    private const val CHANNEL_ID = "agent_status"
    private const val TOAST_ID = 1001

    fun createChannel(ctx: Context) {
        val mgr = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (mgr.getNotificationChannel(CHANNEL_ID) != null) return
        val ch = NotificationChannel(CHANNEL_ID, "Agent Status", NotificationManager.IMPORTANCE_HIGH).apply {
            description = "Live agent action updates"
            enableVibration(false)
            setSound(null, null)
        }
        mgr.createNotificationChannel(ch)
    }

    fun showToast(ctx: Context, text: String) {
        val mgr = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val n = NotificationCompat.Builder(ctx, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("Agent")
            .setContentText(text)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setTimeoutAfter(4000)
            .setAutoCancel(true)
            .build()
        mgr.notify(TOAST_ID, n)
    }
}
