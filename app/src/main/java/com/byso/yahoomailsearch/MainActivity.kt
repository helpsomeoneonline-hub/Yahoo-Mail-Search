package com.byso.yahoomailsearch

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.byso.yahoomailsearch.databinding.ActivityMainBinding
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private lateinit var securePrefs: SecurePrefs
    private lateinit var cache: CacheDb
    private lateinit var alertStore: AlertRuleStore
    private lateinit var billingManager: BillingManager
    private val executor = Executors.newSingleThreadExecutor()
    private var sessionUnlocked = false
    private var busy = false

    override fun onCreate(savedInstanceState: Bundle?) {
        applySavedTheme()
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        applySafeAreaInsets()

        securePrefs = SecurePrefs(this)
        cache = CacheDb(this)
        alertStore = AlertRuleStore(this)
        billingManager = BillingManager(this) { runOnUiThread { updatePremiumStatus() } }

        NotificationHelper.ensureChannels(this)
        configureSpinners()
        restoreSavedSettings()
        configureNavigation()
        configureActions()
        refreshHomeStats()
        renderAlerts()
        updatePremiumStatus()

        val appPrefs = getSharedPreferences(AppKeys.PREFS_APP, Context.MODE_PRIVATE)
        WorkScheduler.apply(
            this,
            appPrefs.getBoolean(AppKeys.KEY_BACKGROUND_SYNC, true)
        )
        billingManager.connect()

        val requestedTab = intent.getStringExtra("open_tab")
        if (requestedTab != null) {
            showTab(requestedTab)
        } else if (securePrefs.get(AppKeys.KEY_EMAIL).isBlank()) {
            showTab("settings")
            showFirstRunWelcome()
        } else {
            showTab("home")
        }

        maybeRequestNotificationPermission()
        maybePromptBiometric()
    }

    private fun applySafeAreaInsets() {
        val root = binding.rootLayout
        val baseLeft = root.paddingLeft
        val baseTop = root.paddingTop
        val baseRight = root.paddingRight
        val baseBottom = root.paddingBottom

        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or
                    WindowInsetsCompat.Type.displayCutout()
            )
            view.setPadding(
                baseLeft + bars.left,
                baseTop + bars.top,
                baseRight + bars.right,
                baseBottom + bars.bottom
            )
            insets
        }
        ViewCompat.requestApplyInsets(root)
    }

    private fun applySavedTheme() {
        val prefs = getSharedPreferences(AppKeys.PREFS_APP, Context.MODE_PRIVATE)
        when (prefs.getString(AppKeys.KEY_THEME, AppKeys.THEME_AUTO)) {
            AppKeys.THEME_LIGHT ->
                AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO)
            AppKeys.THEME_DARK ->
                AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)
            else ->
                AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
        }
    }

    private fun configureSpinners() {
        binding.searchTargetSpinner.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            listOf("Anywhere", "Sender", "Subject", "Body", "Attachment filename")
        )
        binding.themeSpinner.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            listOf("Use device setting", "Light", "Dark")
        )
    }

    private fun restoreSavedSettings() {
        binding.yahooEmail.setText(securePrefs.get(AppKeys.KEY_EMAIL))
        binding.yahooAppPassword.setText(securePrefs.get(AppKeys.KEY_YAHOO_PASSWORD))
        binding.githubToken.setText(securePrefs.get(AppKeys.KEY_GITHUB_TOKEN))
        binding.archivePassphrase.setText(securePrefs.get(AppKeys.KEY_ARCHIVE_PASSPHRASE))

        val prefs = getSharedPreferences(AppKeys.PREFS_APP, Context.MODE_PRIVATE)
        val theme = prefs.getString(AppKeys.KEY_THEME, AppKeys.THEME_AUTO)
        binding.themeSpinner.setSelection(
            when (theme) {
                AppKeys.THEME_LIGHT -> 1
                AppKeys.THEME_DARK -> 2
                else -> 0
            }
        )
        binding.biometricSwitch.isChecked =
            prefs.getBoolean(AppKeys.KEY_BIOMETRIC, false)
        binding.hideNotificationContentSwitch.isChecked =
            prefs.getBoolean(AppKeys.KEY_HIDE_NOTIFICATION_CONTENT, true)
        binding.backgroundSyncSwitch.isChecked =
            prefs.getBoolean(AppKeys.KEY_BACKGROUND_SYNC, true)

        binding.accountStatus.text =
            if (binding.yahooEmail.text.isNullOrBlank()) "Not connected"
            else "Account saved — tap Test & Save Connection to verify"
    }

    private fun configureNavigation() {
        binding.navHome.setOnClickListener { showTab("home") }
        binding.navSearch.setOnClickListener { showTab("search") }
        binding.navAlerts.setOnClickListener { showTab("alerts") }
        binding.navSettings.setOnClickListener { showTab("settings") }
    }

    private fun showTab(tab: String) {
        binding.homePanel.visibility = if (tab == "home") View.VISIBLE else View.GONE
        binding.searchPanel.visibility = if (tab == "search") View.VISIBLE else View.GONE
        binding.alertsPanel.visibility = if (tab == "alerts") View.VISIBLE else View.GONE
        binding.settingsPanel.visibility = if (tab == "settings") View.VISIBLE else View.GONE

        val primary = ContextCompat.getColor(this, R.color.purple_primary)
        val secondary = ContextCompat.getColor(this, R.color.text_secondary)
        binding.navHome.setTextColor(if (tab == "home") primary else secondary)
        binding.navSearch.setTextColor(if (tab == "search") primary else secondary)
        binding.navAlerts.setTextColor(if (tab == "alerts") primary else secondary)
        binding.navSettings.setTextColor(if (tab == "settings") primary else secondary)

        binding.appSubtitle.text = when (tab) {
            "search" -> "Find exactly what you need."
            "alerts" -> "Get notified when important emails arrive."
            "settings" -> "Account, security, appearance, and storage."
            else -> "Search. Archive. Keep what matters."
        }
    }

    private fun configureActions() {
        binding.testButton.setOnClickListener { testAndSave() }
        binding.syncButton.setOnClickListener { startSync() }
        binding.searchButton.setOnClickListener { runSearch() }
        binding.createAlertFromSearchButton.setOnClickListener {
            val query = binding.searchBox.text.toString().trim()
            if (query.isBlank()) {
                toast("Enter a search first.")
            } else {
                showAlertEditor(prefillQuery = query, prefillTarget = selectedSearchTarget())
            }
        }
        binding.trashMatchesButton.setOnClickListener { prepareBulkTrash() }
        binding.addAlertButton.setOnClickListener { showAlertEditor() }
        binding.checkAlertsNowButton.setOnClickListener { checkAlertsNow() }
        binding.rebuildButton.setOnClickListener { rebuildCache() }
        binding.clearCacheButton.setOnClickListener { confirmClearCache() }
        binding.privacyButton.setOnClickListener { showPrivacyDialog() }

        binding.themeSpinner.setOnItemSelectedListener(
            SimpleItemSelectedListener { position ->
                val value = when (position) {
                    1 -> AppKeys.THEME_LIGHT
                    2 -> AppKeys.THEME_DARK
                    else -> AppKeys.THEME_AUTO
                }
                val prefs = getSharedPreferences(AppKeys.PREFS_APP, Context.MODE_PRIVATE)
                val current = prefs.getString(AppKeys.KEY_THEME, AppKeys.THEME_AUTO)
                if (current != value) {
                    prefs.edit().putString(AppKeys.KEY_THEME, value).apply()
                    applySavedTheme()
                }
            }
        )

        binding.biometricSwitch.setOnCheckedChangeListener { _, checked ->
            val prefs = getSharedPreferences(AppKeys.PREFS_APP, Context.MODE_PRIVATE)
            if (checked) {
                val manager = BiometricManager.from(this)
                val authenticators =
                    BiometricManager.Authenticators.BIOMETRIC_STRONG or
                        BiometricManager.Authenticators.DEVICE_CREDENTIAL
                if (manager.canAuthenticate(authenticators) ==
                    BiometricManager.BIOMETRIC_SUCCESS
                ) {
                    prefs.edit().putBoolean(AppKeys.KEY_BIOMETRIC, true).apply()
                    sessionUnlocked = false
                    maybePromptBiometric()
                } else {
                    binding.biometricSwitch.isChecked = false
                    toast("Biometric or device credential authentication is not available.")
                }
            } else {
                prefs.edit().putBoolean(AppKeys.KEY_BIOMETRIC, false).apply()
                sessionUnlocked = true
            }
        }

        binding.hideNotificationContentSwitch.setOnCheckedChangeListener { _, checked ->
            getSharedPreferences(AppKeys.PREFS_APP, Context.MODE_PRIVATE)
                .edit()
                .putBoolean(AppKeys.KEY_HIDE_NOTIFICATION_CONTENT, checked)
                .apply()
        }

        binding.backgroundSyncSwitch.setOnCheckedChangeListener { _, checked ->
            val prefs = getSharedPreferences(AppKeys.PREFS_APP, Context.MODE_PRIVATE)
            prefs.edit().putBoolean(AppKeys.KEY_BACKGROUND_SYNC, checked).apply()
            WorkScheduler.apply(this, checked)
        }

        binding.upgradeButton.setOnClickListener {
            billingManager.upgrade(this) { message ->
                runOnUiThread { toast(message) }
            }
        }
        binding.restorePurchasesButton.setOnClickListener {
            billingManager.restore { restored ->
                runOnUiThread {
                    updatePremiumStatus()
                    toast(if (restored) "Pro purchase restored." else "No active Pro subscription found.")
                }
            }
        }
    }

    private fun credentials(): Credentials? {
        val value = Credentials(
            email = binding.yahooEmail.text.toString().trim(),
            yahooPassword = binding.yahooAppPassword.text.toString().replace(" ", "").trim(),
            githubToken = binding.githubToken.text.toString().trim(),
            archivePassphrase = binding.archivePassphrase.text.toString()
        )
        val missing = when {
            value.email.isBlank() -> "Enter your Yahoo email address."
            value.yahooPassword.isBlank() -> "Enter your Yahoo app password."
            value.githubToken.isBlank() ->
                "Enter a GitHub token with access to Yahoo-Mail-Data."
            value.archivePassphrase.length < 8 ->
                "Use an archive passphrase of at least 8 characters."
            else -> null
        }
        if (missing != null) {
            toast(missing)
            showTab("settings")
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
            securePrefs.put(AppKeys.KEY_EMAIL, creds.email)
            securePrefs.put(AppKeys.KEY_YAHOO_PASSWORD, creds.yahooPassword)
            securePrefs.put(AppKeys.KEY_GITHUB_TOKEN, creds.githubToken)
            securePrefs.put(AppKeys.KEY_ARCHIVE_PASSPHRASE, creds.archivePassphrase)
            postStatus("Connected. Credentials are protected by Android Keystore.")
            runOnUiThread {
                binding.accountStatus.text = "✓ Yahoo + GitHub connected"
                toast("Connection successful.")
            }
        }
    }

    private fun startSync() {
        val creds = credentials() ?: return
        showTab("home")
        binding.progressBar.visibility = View.VISIBLE
        binding.progressBar.isIndeterminate = true
        binding.syncStatsText.text = "Counting your Yahoo folders..."
        runTask("Starting encrypted Yahoo → GitHub sync...") {
            manager(creds).sync(::postSyncProgress)
        }
    }

    private fun postSyncProgress(progress: SyncProgress) {
        runOnUiThread {
            binding.progressBar.visibility = View.VISIBLE
            if (progress.totalMessages > 0 || progress.complete) {
                binding.progressBar.isIndeterminate = false
                binding.progressBar.max = 100
                binding.progressBar.progress = progress.percent
            } else {
                binding.progressBar.isIndeterminate = true
            }

            val percentText =
                if (progress.totalMessages > 0) {
                    String.format(
                        Locale.getDefault(),
                        "%.1f%%",
                        progress.processedMessages.toDouble() * 100.0 /
                            progress.totalMessages.toDouble()
                    )
                } else if (progress.complete) "100.0%" else "Counting..."

            binding.statusText.text =
                if (progress.complete) {
                    "✓ SYNC COMPLETE — ${progress.processedMessages} / ${progress.totalMessages}"
                } else {
                    progress.stage
                }

            binding.syncStatsText.text = buildString {
                append("Total emails found: ")
                append(progress.totalMessages)
                append('\n')
                append("Processed: ")
                append(progress.processedMessages)
                append(" / ")
                append(progress.totalMessages)
                append("  (")
                append(percentText)
                append(")")
                append('\n')
                append("Already archived: ")
                append(progress.alreadyArchived)
                append('\n')
                append("New archived this session: ")
                append(progress.newArchived)
                append('\n')
                append("GitHub upload batches: ")
                append(progress.uploadedBatches)
                if (progress.folderName.isNotBlank()) {
                    append('\n')
                    append("Current folder: ")
                    append(progress.folderName)
                    append("  [")
                    append(progress.folderIndex)
                    append("/")
                    append(progress.folderCount)
                    append("]")
                    if (progress.folderTotal > 0) {
                        append('\n')
                        append("Folder: ")
                        append(progress.folderProcessed)
                        append(" / ")
                        append(progress.folderTotal)
                    }
                }
            }

            if (progress.complete) {
                val prefs = getSharedPreferences(AppKeys.PREFS_APP, Context.MODE_PRIVATE)
                prefs.edit()
                    .putBoolean(AppKeys.KEY_INITIAL_SYNC_COMPLETE, true)
                    .putInt(AppKeys.KEY_LAST_TOTAL, progress.totalMessages)
                    .putInt(AppKeys.KEY_LAST_ARCHIVED, cache.size())
                    .putInt(AppKeys.KEY_LAST_NEW, progress.newArchived)
                    .putLong(AppKeys.KEY_LAST_SYNC_MS, System.currentTimeMillis())
                    .apply()
                refreshHomeStats()
                WorkScheduler.apply(
                    this,
                    prefs.getBoolean(AppKeys.KEY_BACKGROUND_SYNC, true)
                )
            }
        }
    }

    private fun refreshHomeStats() {
        val prefs = getSharedPreferences(AppKeys.PREFS_APP, Context.MODE_PRIVATE)
        val total = prefs.getInt(AppKeys.KEY_LAST_TOTAL, cache.size())
        val archived = prefs.getInt(AppKeys.KEY_LAST_ARCHIVED, cache.size())
        val newCount = prefs.getInt(AppKeys.KEY_LAST_NEW, 0)
        val last = prefs.getLong(AppKeys.KEY_LAST_SYNC_MS, 0L)

        binding.statTotalEmails.text = total.toString()
        binding.statArchived.text = archived.toString()
        binding.statNew.text = newCount.toString()
        binding.statLastSync.text =
            if (last <= 0L) "Never"
            else SimpleDateFormat("MMM d, h:mm a", Locale.getDefault()).format(Date(last))
    }

    private fun runSearch() {
        val query = binding.searchBox.text.toString().trim()
        val target = selectedSearchTarget()
        val attachments = binding.attachmentOnly.isChecked

        runTask("Searching your encrypted archive index...") {
            val result = cache.search(query, target, attachments)
            runOnUiThread {
                binding.resultCount.text = "${result.total} matches"
                renderSearchResults(result)
                if (result.total == 0) toast("No matching emails found.")
            }
        }
    }

    private fun selectedSearchTarget(): String =
        when (binding.searchTargetSpinner.selectedItemPosition) {
            1 -> AlertRule.TARGET_SENDER
            2 -> AlertRule.TARGET_SUBJECT
            3 -> AlertRule.TARGET_BODY
            4 -> AlertRule.TARGET_ATTACHMENT
            else -> AlertRule.TARGET_ANY
        }

    private fun renderSearchResults(result: SearchSummary) {
        binding.resultsContainer.removeAllViews()
        if (result.preview.isEmpty()) {
            binding.resultsContainer.addView(simpleMessage("No matching messages."))
            return
        }

        val formatter = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
        result.preview.forEach { mail ->
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(14), dp(12), dp(14), dp(12))
                setBackgroundResource(R.drawable.bg_glass_card)
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = dp(9) }
            }
            val sender = TextView(this).apply {
                text = mail.sender.ifBlank { "(unknown sender)" }
                setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_primary))
                textSize = 15f
                setTypeface(typeface, android.graphics.Typeface.BOLD)
            }
            val subject = TextView(this).apply {
                text = mail.subject.ifBlank { "(no subject)" }
                setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_primary))
                textSize = 14f
            }
            val meta = TextView(this).apply {
                val date =
                    if (mail.dateMs > 0) formatter.format(Date(mail.dateMs)) else "Unknown date"
                val attach =
                    if (mail.hasAttachments) "  •  📎 ${mail.attachmentNames.ifBlank { "attachment" }}" else ""
                text = "$date  •  ${mail.folder}$attach"
                setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_secondary))
                textSize = 12f
            }
            card.addView(sender)
            card.addView(subject)
            card.addView(meta)
            binding.resultsContainer.addView(card)
        }
    }

    private fun prepareBulkTrash() {
        val query = binding.searchBox.text.toString().trim()
        if (query.isBlank()) {
            toast("For safety, enter a search before bulk cleanup.")
            return
        }
        val target = selectedSearchTarget()
        val attachments = binding.attachmentOnly.isChecked

        runTask("Counting matching messages...") {
            val keys = cache.matchingKeys(query, target, attachments)
            runOnUiThread {
                if (keys.isEmpty()) {
                    toast("No matching messages to move.")
                    return@runOnUiThread
                }

                val previewCount = minOf(keys.size, 20)
                AlertDialog.Builder(this)
                    .setTitle("Move ${keys.size} emails to Yahoo Trash?")
                    .setMessage(
                        "Search: “$query”\n\n" +
                            "You reviewed a search matching ${keys.size} emails. " +
                            "The first $previewCount are shown in the search preview when available.\n\n" +
                            "Encrypted GitHub archive copies are kept for recovery."
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
                    "Encrypted archive copies were kept."
            )
            runOnUiThread {
                refreshHomeStats()
                runSearch()
            }
        }
    }

    private fun showAlertEditor(
        existing: AlertRule? = null,
        prefillQuery: String = "",
        prefillTarget: String = AlertRule.TARGET_ANY
    ) {
        if (existing == null && !billingManager.isPro() && alertStore.all().size >= 1) {
            AlertDialog.Builder(this)
                .setTitle("Unlock unlimited alerts")
                .setMessage(
                    "The free plan includes one active alert rule. " +
                        "Pro is designed for unlimited alert rules and premium automation."
                )
                .setNegativeButton("Not now", null)
                .setPositiveButton("Upgrade") { _, _ ->
                    billingManager.upgrade(this) { message ->
                        runOnUiThread { toast(message) }
                    }
                }
                .show()
            return
        }

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(8), dp(22), 0)
        }
        val name = EditText(this).apply {
            hint = "Alert name, e.g. PayPal payment"
            setText(existing?.name.orEmpty())
        }
        val query = EditText(this).apply {
            hint = "Word or phrase to watch for"
            setText(existing?.query ?: prefillQuery)
        }
        val spinner = Spinner(this)
        val labels = listOf("Anywhere", "Sender", "Subject", "Body", "Attachment filename")
        spinner.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            labels
        )
        val chosenTarget = existing?.target ?: prefillTarget
        spinner.setSelection(targetToPosition(chosenTarget))
        layout.addView(name)
        layout.addView(query)
        layout.addView(spinner)

        AlertDialog.Builder(this)
            .setTitle(if (existing == null) "Create email alert" else "Edit email alert")
            .setView(layout)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Save") { _, _ ->
                val q = query.text.toString().trim()
                if (q.isBlank()) {
                    toast("Enter a word or phrase for the alert.")
                    return@setPositiveButton
                }
                val rule = AlertRule(
                    id = existing?.id ?: java.util.UUID.randomUUID().toString(),
                    name = name.text.toString().trim().ifBlank { q },
                    query = q,
                    target = positionToTarget(spinner.selectedItemPosition),
                    enabled = existing?.enabled ?: true
                )
                alertStore.save(rule)
                renderAlerts()
                maybeRequestNotificationPermission()
                toast("Alert saved.")
            }
            .show()
    }

    private fun renderAlerts() {
        val rules = alertStore.all()
        binding.alertRulesContainer.removeAllViews()
        val active = rules.count { it.enabled }
        binding.alertsSummaryText.text =
            if (rules.isEmpty()) {
                "No alerts yet. Create one to watch for a sender, subject, body phrase, or attachment filename."
            } else {
                "$active of ${rules.size} alert rules active. New mail is checked in the background."
            }

        rules.forEach { rule ->
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(14), dp(12), dp(14), dp(12))
                setBackgroundResource(R.drawable.bg_glass_card)
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = dp(10) }
            }

            val titleRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
            }
            val title = TextView(this).apply {
                text = rule.name
                textSize = 16f
                setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_primary))
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }
            val toggle = Switch(this).apply {
                isChecked = rule.enabled
                setOnCheckedChangeListener { _, checked ->
                    alertStore.setEnabled(rule.id, checked)
                    renderAlerts()
                }
            }
            titleRow.addView(title)
            titleRow.addView(toggle)

            val details = TextView(this).apply {
                text = "${targetLabel(rule.target)} contains “${rule.query}”"
                setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_secondary))
                textSize = 13f
            }

            val actions = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
            }
            val test = Button(this).apply {
                text = "Test Rule"
                isAllCaps = false
                layoutParams = LinearLayout.LayoutParams(0, dp(48), 1f)
                setOnClickListener {
                    val count = cache.countMatches(rule.query, rule.target)
                    toast("$count existing indexed emails match this rule.")
                }
            }
            val edit = Button(this).apply {
                text = "Edit"
                isAllCaps = false
                layoutParams = LinearLayout.LayoutParams(0, dp(48), 1f)
                setOnClickListener { showAlertEditor(existing = rule) }
            }
            val delete = Button(this).apply {
                text = "Delete"
                isAllCaps = false
                layoutParams = LinearLayout.LayoutParams(0, dp(48), 1f)
                setOnClickListener {
                    AlertDialog.Builder(this@MainActivity)
                        .setTitle("Delete alert?")
                        .setMessage(rule.name)
                        .setNegativeButton("Cancel", null)
                        .setPositiveButton("Delete") { _, _ ->
                            alertStore.delete(rule.id)
                            renderAlerts()
                        }
                        .show()
                }
            }
            actions.addView(test)
            actions.addView(edit)
            actions.addView(delete)
            card.addView(titleRow)
            card.addView(details)
            card.addView(actions)
            binding.alertRulesContainer.addView(card)
        }
    }

    private fun checkAlertsNow() {
        val creds = credentials() ?: return
        val rules = alertStore.all().filter { it.enabled }
        if (rules.isEmpty()) {
            toast("Create an alert rule first.")
            return
        }

        runTask("Checking recent Yahoo mail against your alert rules...") {
            val recent = YahooImapClient(creds.email, creds.yahooPassword)
                .fetchRecentSince(
                    System.currentTimeMillis() - 24L * 60L * 60L * 1000L,
                    250
                )
            var matched = 0
            val hideSensitive = getSharedPreferences(
                AppKeys.PREFS_APP,
                Context.MODE_PRIVATE
            ).getBoolean(AppKeys.KEY_HIDE_NOTIFICATION_CONTENT, true)

            recent.forEach { mail ->
                rules.filter { it.matches(mail) }.forEach { rule ->
                    matched++
                    NotificationHelper.notifyRuleMatch(
                        this,
                        rule,
                        mail,
                        hideSensitive
                    )
                }
            }
            runOnUiThread {
                toast("Alert check complete: $matched matches in recent mail.")
            }
        }
    }

    private fun rebuildCache() {
        val creds = credentials() ?: return
        runTask("Rebuilding the temporary search index from encrypted GitHub archive...") {
            manager(creds).rebuildCache(::postStatus)
            runOnUiThread {
                refreshHomeStats()
                toast("Search cache rebuilt.")
            }
        }
    }

    private fun confirmClearCache() {
        AlertDialog.Builder(this)
            .setTitle("Clear temporary phone cache?")
            .setMessage(
                "This removes the searchable cache on this device only. " +
                    "Your encrypted GitHub archive is not deleted."
            )
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Clear Cache") { _, _ ->
                runTask("Clearing temporary cache...") {
                    cache.clear()
                    postStatus("Temporary phone cache cleared. GitHub archive was not changed.")
                    runOnUiThread {
                        binding.resultCount.text = "0 matches"
                        binding.resultsContainer.removeAllViews()
                        refreshHomeStats()
                    }
                }
            }
            .show()
    }

    private fun showPrivacyDialog() {
        AlertDialog.Builder(this)
            .setTitle("Privacy & monetization")
            .setMessage(
                "Mail Search connects directly to Yahoo using the app password you provide. " +
                    "Archive content is encrypted before being stored in your own GitHub repository. " +
                    "Credentials are stored on-device using Android Keystore.\n\n" +
                    "The app does not need advertising trackers to monetize. The planned model is " +
                    "a free tier plus an optional Google Play Pro subscription for advanced automation.\n\n" +
                    "Before public Play Store release, the published listing will include a public privacy-policy URL " +
                    "and an accurate Data Safety declaration."
            )
            .setPositiveButton("OK", null)
            .show()
    }

    private fun updatePremiumStatus() {
        if (BuildConfig.DEBUG) {
            binding.premiumStatusText.text = "✓ Test Build — All Features Unlocked"
            binding.upgradeButton.visibility = View.GONE
            binding.restorePurchasesButton.visibility = View.GONE
        } else {
            binding.upgradeButton.visibility = View.VISIBLE
            binding.restorePurchasesButton.visibility = View.VISIBLE
            binding.premiumStatusText.text =
                if (billingManager.isPro()) {
                    "✓ Mail Search Pro"
                } else {
                    "Free plan — 1 alert rule"
                }
        }
    }

    private fun showFirstRunWelcome() {
        AlertDialog.Builder(this)
            .setTitle("Welcome to Yahoo Mail Search")
            .setMessage(
                "Set up four things once: your Yahoo address, Yahoo app password, " +
                    "GitHub archive token, and archive passphrase. Then tap Test & Save Connection.\n\n" +
                    "Your normal Yahoo password is not used."
            )
            .setPositiveButton("Set up") { _, _ ->
                binding.yahooEmail.requestFocus()
            }
            .show()
    }

    private fun maybeRequestNotificationPermission() {
        if (
            Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 3001)
        }
    }

    private fun maybePromptBiometric() {
        val enabled = getSharedPreferences(AppKeys.PREFS_APP, Context.MODE_PRIVATE)
            .getBoolean(AppKeys.KEY_BIOMETRIC, false)
        if (!enabled || sessionUnlocked) return

        val executor = ContextCompat.getMainExecutor(this)
        val prompt = BiometricPrompt(
            this,
            executor,
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(
                    result: BiometricPrompt.AuthenticationResult
                ) {
                    super.onAuthenticationSucceeded(result)
                    sessionUnlocked = true
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    super.onAuthenticationError(errorCode, errString)
                    if (!sessionUnlocked) {
                        toast("Authentication is required to open Mail Search.")
                    }
                }
            }
        )

        val authenticators =
            BiometricManager.Authenticators.BIOMETRIC_STRONG or
                BiometricManager.Authenticators.DEVICE_CREDENTIAL

        val info = BiometricPrompt.PromptInfo.Builder()
            .setTitle("Unlock Yahoo Mail Search")
            .setSubtitle("Protect your email archive and search history")
            .setAllowedAuthenticators(authenticators)
            .build()
        prompt.authenticate(info)
    }

    private fun runTask(startMessage: String, task: () -> Unit) {
        if (busy) {
            toast("Another task is already running.")
            return
        }
        busy = true
        postStatus(startMessage)
        setBusy(true)
        executor.execute {
            try {
                task()
            } catch (error: Throwable) {
                postStatus("Error: ${friendlyError(error)}")
                runOnUiThread { toast(friendlyError(error)) }
            } finally {
                runOnUiThread {
                    busy = false
                    setBusy(false)
                }
            }
        }
    }

    private fun friendlyError(error: Throwable): String {
        val message = error.message.orEmpty()
        return when {
            message.contains("AUTHENTICATIONFAILED", ignoreCase = true) ->
                "Yahoo rejected the sign-in. Create a fresh Yahoo app password and try again."
            message.contains("401") || message.contains("Bad credentials", true) ->
                "GitHub rejected the token. Check that it can access Yahoo-Mail-Data with Contents read/write."
            message.contains("timeout", true) ->
                "The connection timed out. Check your internet and try again."
            else -> message.ifBlank { error.javaClass.simpleName }
        }
    }

    private fun setBusy(value: Boolean) {
        binding.testButton.isEnabled = !value
        binding.syncButton.isEnabled = !value
        binding.rebuildButton.isEnabled = !value
        binding.clearCacheButton.isEnabled = !value
        binding.searchButton.isEnabled = !value
        binding.trashMatchesButton.isEnabled = !value
        binding.checkAlertsNowButton.isEnabled = !value
    }

    private fun postStatus(message: String) {
        runOnUiThread { binding.statusText.text = message }
    }

    private fun targetToPosition(target: String): Int =
        when (target) {
            AlertRule.TARGET_SENDER -> 1
            AlertRule.TARGET_SUBJECT -> 2
            AlertRule.TARGET_BODY -> 3
            AlertRule.TARGET_ATTACHMENT -> 4
            else -> 0
        }

    private fun positionToTarget(position: Int): String =
        when (position) {
            1 -> AlertRule.TARGET_SENDER
            2 -> AlertRule.TARGET_SUBJECT
            3 -> AlertRule.TARGET_BODY
            4 -> AlertRule.TARGET_ATTACHMENT
            else -> AlertRule.TARGET_ANY
        }

    private fun targetLabel(target: String): String =
        when (target) {
            AlertRule.TARGET_SENDER -> "Sender"
            AlertRule.TARGET_SUBJECT -> "Subject"
            AlertRule.TARGET_BODY -> "Body"
            AlertRule.TARGET_ATTACHMENT -> "Attachment filename"
            else -> "Anywhere"
        }

    private fun simpleMessage(text: String): TextView =
        TextView(this).apply {
            this.text = text
            setPadding(dp(14), dp(14), dp(14), dp(14))
            setBackgroundResource(R.drawable.bg_glass_card)
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_secondary))
        }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }

    override fun onDestroy() {
        billingManager.close()
        executor.shutdown()
        super.onDestroy()
    }

    data class Credentials(
        val email: String,
        val yahooPassword: String,
        val githubToken: String,
        val archivePassphrase: String
    )
}

private class SimpleItemSelectedListener(
    private val block: (Int) -> Unit
) : android.widget.AdapterView.OnItemSelectedListener {
    override fun onItemSelected(
        parent: android.widget.AdapterView<*>?,
        view: View?,
        position: Int,
        id: Long
    ) = block(position)

    override fun onNothingSelected(parent: android.widget.AdapterView<*>?) = Unit
}
