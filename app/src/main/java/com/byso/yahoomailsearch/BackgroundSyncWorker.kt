package com.byso.yahoomailsearch

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters

class BackgroundSyncWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val prefs = applicationContext.getSharedPreferences(
            AppKeys.PREFS_APP,
            Context.MODE_PRIVATE
        )
        if (!prefs.getBoolean(AppKeys.KEY_BACKGROUND_SYNC, true)) {
            return Result.success()
        }
        if (!prefs.getBoolean(AppKeys.KEY_INITIAL_SYNC_COMPLETE, false)) {
            return Result.success()
        }

        val secure = SecurePrefs(applicationContext)
        val email = secure.get(AppKeys.KEY_EMAIL)
        val yahooPassword = secure.get(AppKeys.KEY_YAHOO_PASSWORD)
        val githubToken = secure.get(AppKeys.KEY_GITHUB_TOKEN)
        val passphrase = secure.get(AppKeys.KEY_ARCHIVE_PASSPHRASE)

        if (
            email.isBlank() ||
            yahooPassword.isBlank() ||
            githubToken.isBlank() ||
            passphrase.length < 8
        ) return Result.success()

        return try {
            val cache = CacheDb(applicationContext)
            ArchiveSyncManager(
                email = email,
                yahooAppPassword = yahooPassword,
                githubToken = githubToken,
                archivePassphrase = passphrase,
                cache = cache
            ).sync { progress ->
                if (progress.complete) {
                    prefs.edit()
                        .putInt(AppKeys.KEY_LAST_TOTAL, progress.totalMessages)
                        .putInt(AppKeys.KEY_LAST_ARCHIVED, cache.size())
                        .putInt(AppKeys.KEY_LAST_NEW, progress.newArchived)
                        .putLong(AppKeys.KEY_LAST_SYNC_MS, System.currentTimeMillis())
                        .apply()
                }
            }
            Result.success()
        } catch (_: Throwable) {
            Result.retry()
        }
    }
}
