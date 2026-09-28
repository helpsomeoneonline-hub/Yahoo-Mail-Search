package com.byso.yahoomailsearch

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat

object NotificationHelper {
    const val CHANNEL_ALERTS = "mail_alerts"
    const val CHANNEL_SYNC = "mail_sync"

    fun ensureChannels(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannels(
            listOf(
                NotificationChannel(
                    CHANNEL_ALERTS,
                    "Email alerts",
                    NotificationManager.IMPORTANCE_DEFAULT
                ).apply {
                    description = "Notifications for saved email watch rules"
                },
                NotificationChannel(
                    CHANNEL_SYNC,
                    "Archive sync",
                    NotificationManager.IMPORTANCE_LOW
                ).apply {
                    description = "Background archive and sync status"
                }
            )
        )
    }

    fun notifyRuleMatch(
        context: Context,
        rule: AlertRule,
        mail: MailRecord,
        hideSensitive: Boolean
    ) {
        ensureChannels(context)
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("open_tab", "alerts")
        }
        val pending = PendingIntent.getActivity(
            context,
            rule.id.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val title = if (hideSensitive) {
            "Email alert matched"
        } else {
            rule.name.ifBlank { "Email alert matched" }
        }
        val body = if (hideSensitive) {
            "Open Mail Search to view the matching message."
        } else {
            val sender = mail.sender.ifBlank { "Unknown sender" }
            val subject = mail.subject.ifBlank { "(no subject)" }
            "$sender — $subject"
        }

        val notification = NotificationCompat.Builder(context, CHANNEL_ALERTS)
            .setSmallIcon(android.R.drawable.ic_dialog_email)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setContentIntent(pending)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()

        runCatching {
            NotificationManagerCompat.from(context)
                .notify((rule.id + mail.folder + mail.uid).hashCode(), notification)
        }
    }
}
