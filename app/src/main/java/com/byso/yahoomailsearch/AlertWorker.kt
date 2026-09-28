package com.byso.yahoomailsearch

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters

class AlertWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val secure = SecurePrefs(applicationContext)
        val email = secure.get(AppKeys.KEY_EMAIL)
        val password = secure.get(AppKeys.KEY_YAHOO_PASSWORD)
        if (email.isBlank() || password.isBlank()) return Result.success()

        val rules = AlertRuleStore(applicationContext).all().filter { it.enabled }
        if (rules.isEmpty()) return Result.success()

        val prefs = applicationContext.getSharedPreferences(
            AppKeys.PREFS_APP,
            Context.MODE_PRIVATE
        )
        val now = System.currentTimeMillis()
        val after = prefs.getLong(
            AppKeys.KEY_LAST_ALERT_CHECK_MS,
            now - 20L * 60L * 1000L
        )
        val hideSensitive = prefs.getBoolean(
            AppKeys.KEY_HIDE_NOTIFICATION_CONTENT,
            true
        )

        return try {
            val recent = YahooImapClient(email, password)
                .fetchRecentSince(afterMs = after, maxPerFolder = 150)

            recent.forEach { mail ->
                rules.filter { it.matches(mail) }.forEach { rule ->
                    NotificationHelper.notifyRuleMatch(
                        applicationContext,
                        rule,
                        mail,
                        hideSensitive
                    )
                }
            }

            prefs.edit()
                .putLong(AppKeys.KEY_LAST_ALERT_CHECK_MS, now)
                .apply()
            Result.success()
        } catch (_: Throwable) {
            Result.retry()
        }
    }
}
