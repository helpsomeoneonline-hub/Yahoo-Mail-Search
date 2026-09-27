package com.byso.yahoomailsearch

import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.byso.yahoomailsearch.databinding.ActivityMainBinding
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private lateinit var securePrefs: SecurePrefs
    private lateinit var cache: CacheDb
    private val executor = Executors.newSingleThreadExecutor()
    private var currentSearchQuery: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        securePrefs = SecurePrefs(this)
        cache = CacheDb(this)
        restoreSavedSettings()

        binding.statusText.text =
            "Ready. Temporary search cache: ${cache.size()} messages."

        binding.testButton.setOnClickListener { testAndSave() }
        binding.syncButton.setOnClickListener { startSync() }
        binding.rebuildButton.setOnClickListener { rebuildCache() }
        binding.clearCacheButton.setOnClickListener { confirmClearCache() }
        binding.searchButton.setOnClickListener { runSearch() }
        binding.trashMatchesButton.setOnClickListener { prepareBulkTrash() }
    }

    private fun restoreSavedSettings() {
        binding.yahooEmail.setText(securePrefs.get(KEY_EMAIL))
        binding.yahooAppPassword.setText(securePrefs.get(KEY_YAHOO_PASSWORD))
        binding.githubToken.setText(securePrefs.get(KEY_GITHUB_TOKEN))
        binding.archivePassphrase.setText(securePrefs.get(KEY_ARCHIVE_PASSPHRASE))
    }

    private fun credentials(): Credentials? {
        val value = Credentials(
            email = binding.yahooEmail.text.toString().trim(),
            yahooPassword = binding.yahooAppPassword.text.toString().trim(),
            githubToken = binding.githubToken.text.toString().trim(),
            archivePassphrase = binding.archivePassphrase.text.toString()
        )

        val missing = when {
            value.email.isBlank() -> "Enter your Yahoo email address."
            value.yahooPassword.isBlank() -> "Enter your Yahoo app password."
            value.githubToken.isBlank() -> "Enter a GitHub token with access to Yahoo-Mail-Data."
            value.archivePassphrase.length < 8 -> "Use an archive passphrase of at least 8 characters."
            else -> null
        }
        if (missing != null) {
            binding.statusText.text = missing
            return null
        }
        return value
    }

    private fun manager(creds: Credentials): ArchiveSyncManager =
        ArchiveSyncManager(
            email = creds.email,
            yahooAppPassword = creds.yahooPassword,
            githubToken = creds.githubToken,
            archivePassphrase = creds.archivePassphrase,
            cache = cache
        )

    private fun testAndSave() {
        val creds = credentials() ?: return
        runTask("Testing Yahoo and GitHub connections...") {
            manager(creds).testConnections()
            securePrefs.put(KEY_EMAIL, creds.email)
            securePrefs.put(KEY_YAHOO_PASSWORD, creds.yahooPassword)
            securePrefs.put(KEY_GITHUB_TOKEN, creds.githubToken)
            securePrefs.put(KEY_ARCHIVE_PASSPHRASE, creds.archivePassphrase)
            postStatus("Connected. Credentials are encrypted with Android Keystore.")
        }
    }

    private fun startSync() {
        val creds = credentials() ?: return
        runTask("Starting Yahoo → encrypted GitHub archive sync...") {
            manager(creds).sync(::postStatus)
        }
    }

    private fun rebuildCache() {
        val creds = credentials() ?: return
        runTask("Rebuilding temporary search cache from encrypted GitHub archive...") {
            manager(creds).rebuildCache(::postStatus)
        }
    }

    private fun runSearch() {
        val query = binding.searchBox.text.toString().trim()
        currentSearchQuery = query
        runTask("Searching temporary index...") {
            val result = cache.search(query)
            runOnUiThread {
                binding.resultCount.text = "${result.total} matches"
                binding.resultsText.text = formatResults(result)
                binding.statusText.text =
                    if (result.total == 0) "No matches found."
                    else "Search complete. Showing up to ${result.preview.size} messages."
            }
        }
    }

    private fun formatResults(result: SearchSummary): String {
        if (result.preview.isEmpty()) return "No matching messages."
        val formatter = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
        return result.preview.joinToString("\n\n") { mail ->
            val date = if (mail.dateMs > 0) formatter.format(Date(mail.dateMs)) else "Unknown date"
            buildString {
                append(date)
                append(" • ")
                append(mail.folder)
                append('\n')
                append("From: ")
                append(mail.sender.ifBlank { "(unknown)" })
                append('\n')
                append(mail.subject.ifBlank { "(no subject)" })
                if (mail.hasAttachments) {
                    append('\n')
                    append("Attachments: ")
                    append(mail.attachmentNames.ifBlank { "Yes" })
                }
            }
        }
    }

    private fun prepareBulkTrash() {
        val query = binding.searchBox.text.toString().trim()
        if (query.isBlank()) {
            binding.statusText.text =
                "For safety, enter a search first. Blank-search bulk deletion is disabled."
            return
        }
        currentSearchQuery = query

        runTask("Counting messages that would be moved to Yahoo Trash...") {
            val keys = cache.matchingKeys(query)
            runOnUiThread {
                if (keys.isEmpty()) {
                    binding.statusText.text = "No matching messages to move."
                    return@runOnUiThread
                }

                AlertDialog.Builder(this)
                    .setTitle("Move ${keys.size} messages to Yahoo Trash?")
                    .setMessage(
                        "Search: \"$query\"\n\n" +
                            "The matching messages will be moved to Yahoo Trash. " +
                            "Their encrypted GitHub archive copies will be kept for recovery."
                    )
                    .setNegativeButton("Cancel", null)
                    .setPositiveButton("Move to Trash") { _, _ ->
                        moveKeysToTrash(keys)
                    }
                    .show()
            }
        }
    }

    private fun moveKeysToTrash(keys: List<MailKey>) {
        val creds = credentials() ?: return
        runTask("Moving ${keys.size} messages to Yahoo Trash...") {
            YahooImapClient(creds.email, creds.yahooPassword)
                .moveToTrash(keys, ::postStatus)
            cache.deleteKeys(keys)
            postStatus(
                "Moved ${keys.size} messages to Yahoo Trash. " +
                    "Encrypted GitHub archive copies were kept."
            )
            runOnUiThread { runSearch() }
        }
    }

    private fun confirmClearCache() {
        AlertDialog.Builder(this)
            .setTitle("Clear temporary phone cache?")
            .setMessage(
                "This removes the searchable phone cache only. " +
                    "It does not delete the encrypted GitHub archive."
            )
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Clear Cache") { _, _ ->
                runTask("Clearing temporary cache...") {
                    cache.clear()
                    postStatus("Temporary phone cache cleared. GitHub archive was not changed.")
                    runOnUiThread {
                        binding.resultCount.text = "0 matches"
                        binding.resultsText.text = "Search cache is empty."
                    }
                }
            }
            .show()
    }

    private fun runTask(startMessage: String, task: () -> Unit) {
        binding.progressBar.visibility = View.VISIBLE
        binding.statusText.text = startMessage
        setBusy(true)
        executor.execute {
            try {
                task()
            } catch (error: Throwable) {
                postStatus("Error: ${error.message ?: error.javaClass.simpleName}")
            } finally {
                runOnUiThread {
                    binding.progressBar.visibility = View.GONE
                    setBusy(false)
                }
            }
        }
    }

    private fun setBusy(busy: Boolean) {
        binding.testButton.isEnabled = !busy
        binding.syncButton.isEnabled = !busy
        binding.rebuildButton.isEnabled = !busy
        binding.clearCacheButton.isEnabled = !busy
        binding.searchButton.isEnabled = !busy
        binding.trashMatchesButton.isEnabled = !busy
    }

    private fun postStatus(message: String) {
        runOnUiThread { binding.statusText.text = message }
    }

    override fun onDestroy() {
        executor.shutdown()
        super.onDestroy()
    }

    data class Credentials(
        val email: String,
        val yahooPassword: String,
        val githubToken: String,
        val archivePassphrase: String
    )

    companion object {
        private const val KEY_EMAIL = "yahoo_email"
        private const val KEY_YAHOO_PASSWORD = "yahoo_app_password"
        private const val KEY_GITHUB_TOKEN = "github_token"
        private const val KEY_ARCHIVE_PASSPHRASE = "archive_passphrase"
    }
}
