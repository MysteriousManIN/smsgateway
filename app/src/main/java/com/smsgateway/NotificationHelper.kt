package com.smsgateway

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat

object NotificationHelper {
    const val CHANNEL_SERVICE = "sandesh_fg_service"
    const val CHANNEL_ALERTS = "sandesh_alerts"

    const val NOTIF_ID_QUOTA_ALERT = 2001
    const val NOTIF_ID_BACKEND_ALERT_BASE = 3000

    fun createChannels(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = context.getSystemService(NotificationManager::class.java) ?: return

            // Ongoing service channel (silent/low priority)
            val serviceChannel = NotificationChannel(
                CHANNEL_SERVICE,
                context.getString(R.string.channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = context.getString(R.string.channel_desc)
                setShowBadge(false)
            }
            nm.createNotificationChannel(serviceChannel)

            // High-priority alert channel (sound, vibration, heads-up notification)
            val alertChannel = NotificationChannel(
                CHANNEL_ALERTS,
                "Gateway Alerts & Warnings",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Critical alerts: Gateway down >10min, SMS quota exceeded, security warnings"
                enableVibration(true)
                setShowBadge(true)
            }
            nm.createNotificationChannel(alertChannel)
        }
    }

    fun postLoudAlert(
        context: Context,
        notificationId: Int,
        title: String,
        message: String
    ) {
        createChannels(context)
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return

        val openIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
        val openPendingIntent = PendingIntent.getActivity(context, notificationId, openIntent, flags)

        val notification = NotificationCompat.Builder(context, CHANNEL_ALERTS)
            .setContentTitle(title)
            .setContentText(message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setSmallIcon(android.R.drawable.stat_notify_error)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setDefaults(NotificationCompat.DEFAULT_ALL)
            .setAutoCancel(true)
            .setContentIntent(openPendingIntent)
            .build()

        nm.notify(notificationId, notification)
    }

    fun cancelAlert(context: Context, notificationId: Int) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
        nm.cancel(notificationId)
    }
}

