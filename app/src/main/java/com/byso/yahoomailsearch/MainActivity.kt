package com.byso.yahoomailsearch

import android.content.Context
import android.os.Bundle
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.byso.yahoomailsearch.databinding.ActivityMainBinding
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private lateinit var securePrefs: SecurePrefs
    private val executor = Executors.newSingleThreadExecutor()
    private var busy = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        applySafeAreaInsets()

        securePrefs = SecurePrefs(this)
        restoreSettings()
        updateDateWindow()

        binding.saveTestButton.setOnClickListener { testAndSave() }
        binding.changeAccountButton.setOnClickListener {
            setAccountFormVisibility(true)
            binding.yahooEmail.requestFocus()
            binding.statusText.text = "Update the Yahoo account, then test and save."
        }
        binding.reconcileButton.setOnClickListener { runReconciliation() }
        binding.clearResultsButton.setOnClickListener {
            binding.resultsContainer.removeAllViews()
            binding.resultSummary.text = "No reconciliation run yet."
            binding.bankCount.text = "0"
            binding.matchedCount.text = "0"
            binding.missingCount.text = "0"
            binding.sheetOnlyCount.text = "0"
            binding.reviewCount.text = "0"
        }
    }

    private fun applySafeAreaInsets() {
        val root = binding.rootLayout
        val left = root.paddingLeft
        val top = root.paddingTop
        val right = root.paddingRight
        val bottom = root.paddingBottom

        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or
                    WindowInsetsCompat.Type.displayCutout()
            )
            view.setPadding(
                left + bars.left,
                top + bars.top,
                right + bars.right,
                bottom + bars.bottom
            )
            insets
        }
        ViewCompat.requestApplyInsets(root)
    }

    private fun restoreSettings() {
        val savedEmail = securePrefs.get(AppKeys.KEY_EMAIL)
        val savedPassword = securePrefs.get(AppKeys.KEY_YAHOO_PASSWORD)
        binding.yahooEmail.setText(
            savedEmail.ifBlank { BuildConfig.DEFAULT_YAHOO_EMAIL.trim() }
        )
        binding.yahooAppPassword.setText(savedPassword)

        val prefs = getSharedPreferences(AppKeys.PREFS_APP, Context.MODE_PRIVATE)
        binding.sheetUrl.setText(prefs.getString(KEY_SHEET_URL, "").orEmpty())

        val hasSavedAccount = savedEmail.isNotBlank() && savedPassword.isNotBlank()
        setAccountFormVisibility(!hasSavedAccount)
        binding.statusText.text = when {
            hasSavedAccount && !binding.sheetUrl.text.isNullOrBlank() ->
                "Ready. Yahoo login saved. Check the last 5 days when ready."
            hasSavedAccount ->
                "Yahoo login saved. Enter the Google Sheet link to reconcile."
            else ->
                "First setup: enter your Yahoo app password once. It will be encrypted on this phone."
        }
    }

    private fun setAccountFormVisibility(showForm: Boolean) {
        val formVisibility = if (showForm) View.VISIBLE else View.GONE
        val savedVisibility = if (showForm) View.GONE else View.VISIBLE
        binding.yahooEmail.visibility = formVisibility
        binding.yahooAppPassword.visibility = formVisibility
        binding.saveTestButton.visibility = formVisibility
        binding.accountSavedLabel.visibility = savedVisibility
        binding.changeAccountButton.visibility = savedVisibility
        if (!showForm) {
            binding.accountSavedLabel.text =
                "✓ Yahoo account saved: " + binding.yahooEmail.text.toString().trim() + "\n" +
                "App password protected by Android Keystore."
        }
    }

    private fun updateDateWindow() {
        val formatter = SimpleDateFormat("EEE d MMM, h:mm a", Locale.getDefault())
        val now = System.currentTimeMillis()
        val cutoff = now - FIVE_DAYS_MS
        binding.fiveDayLabel.text =
            "Email window: ${formatter.format(Date(cutoff))} → ${formatter.format(Date(now))}"
    }

    private fun inputs(): Inputs? {
        val email = binding.yahooEmail.text.toString().trim()
        val password = binding.yahooAppPassword.text.toString().replace(" ", "").trim()
        val sheet = binding.sheetUrl.text.toString().trim()

        val problem = when {
            email.isBlank() -> "Enter your Yahoo email address."
            password.isBlank() -> "Enter your Yahoo app password."
            sheet.isBlank() -> "Paste your Google Sheet link."
            else -> null
        }

        if (problem != null) {
            toast(problem)
            return null
        }
        return Inputs(email, password, sheet)
    }

    private fun save(inputs: Inputs) {
        securePrefs.put(AppKeys.KEY_EMAIL, inputs.email)
        securePrefs.put(AppKeys.KEY_YAHOO_PASSWORD, inputs.password)
        getSharedPreferences(AppKeys.PREFS_APP, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_SHEET_URL, inputs.sheetUrl)
            .apply()
    }

    private fun testAndSave() {
        val inputs = inputs() ?: return
        runTask("Testing Yahoo and Google Sheet...") {
            RepublicBankEmailReader(inputs.email, inputs.password).testConnection()
            val sheetEntries = GoogleSheetCsvClient().fetchEntries(inputs.sheetUrl)
            save(inputs)

            runOnUiThread {
                binding.statusText.text =
                    "✓ Connected. Google Sheet loaded ${sheetEntries.size} usable rows."
                setAccountFormVisibility(false)
                toast("Login saved securely. Next time, just check your emails.")
            }
        }
    }

    private fun runReconciliation() {
        val inputs = inputs() ?: return
        save(inputs)
        updateDateWindow()

        binding.resultsContainer.removeAllViews()
        runTask("Reading Republic Bank emails from the last 5 days...") {
            val emails = RepublicBankEmailReader(
                inputs.email,
                inputs.password
            ).fetchLastFiveDays()

            postStatus("Found ${emails.size} Republic Bank emails. Reading Google Sheet...")
            val sheetEntries = GoogleSheetCsvClient().fetchEntries(inputs.sheetUrl)

            postStatus(
                "Comparing ${emails.size} Republic Bank emails with " +
                    "${sheetEntries.size} sheet rows..."
            )

            val result = Reconciler.reconcile(emails, sheetEntries)
            runOnUiThread { renderResult(result) }
        }
    }

    private fun renderResult(result: ReconciliationResult) {
        binding.bankCount.text = result.bankEmailCount.toString()
        binding.matchedCount.text = result.matched.toString()
        binding.missingCount.text = result.missingFromSheet.toString()
        binding.sheetOnlyCount.text = result.noBankEmailMatch.toString()
        binding.reviewCount.text = result.needsReview.toString()

        binding.resultSummary.text = buildString {
            append("Republic Bank emails: ")
            append(result.bankEmailCount)
            append("   •   Sheet rows: ")
            append(result.sheetEntryCount)
            append("\n")
            append("Matched: ")
            append(result.matched)
            append("   •   Missing from Sheet: ")
            append(result.missingFromSheet)
            append("   •   Needs review: ")
            append(result.needsReview)
        }

        val order = mapOf(
            MatchStatus.NEEDS_REVIEW to 0,
            MatchStatus.MISSING_FROM_SHEET to 1,
            MatchStatus.NO_BANK_EMAIL_MATCH to 2,
            MatchStatus.MATCHED to 3
        )

        result.rows.sortedBy { order[it.status] ?: 99 }.forEach { row ->
            addResultCard(row)
        }

        binding.statusText.text =
            "✓ Reconciliation complete — only the last 5 days were checked."
    }

    private fun addResultCard(row: ReconciliationRow) {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(12))
            setBackgroundResource(R.drawable.bg_glass_card)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dp(8)
            }
        }

        val title = TextView(this).apply {
            text = when (row.status) {
                MatchStatus.MATCHED -> "✓ MATCHED"
                MatchStatus.MISSING_FROM_SHEET -> "⚠ MISSING FROM SHEET"
                MatchStatus.NO_BANK_EMAIL_MATCH -> "◌ SHEET ROW — NO BANK EMAIL"
                MatchStatus.NEEDS_REVIEW -> "? NEEDS REVIEW"
            }
            setTextColor(
                ContextCompat.getColor(
                    this@MainActivity,
                    if (row.status == MatchStatus.MATCHED) {
                        R.color.text_primary
                    } else {
                        R.color.accent_red
                    }
                )
            )
            textSize = 14f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        }

        val body = TextView(this).apply {
            text = rowText(row)
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_secondary))
            textSize = 13f
            setPadding(0, dp(6), 0, 0)
        }

        card.addView(title)
        card.addView(body)
        binding.resultsContainer.addView(card)
    }

    private fun rowText(row: ReconciliationRow): String {
        val formatter = SimpleDateFormat("dd MMM yyyy, h:mm a", Locale.getDefault())
        return buildString {
            row.bank?.let { bank ->
                append("Bank: ")
                append(bank.amount?.let(::formatMoney) ?: "amount not found")
                append(" • ")
                append(formatter.format(Date(bank.emailDateMs)))
                append("\n")
                append(bank.description.ifBlank { bank.subject }.take(180))
                if (bank.reference.isNotBlank()) {
                    append("\nRef: ")
                    append(bank.reference)
                }
            }

            row.sheet?.let { sheet ->
                if (row.bank != null) append("\n\n")
                append("Sheet row ")
                append(sheet.rowNumber)
                append(": ")
                append(sheet.amount?.let(::formatMoney) ?: "amount not found")
                sheet.dateMs?.let {
                    append(" • ")
                    append(formatter.format(Date(it)))
                }
                if (sheet.description.isNotBlank()) {
                    append("\n")
                    append(sheet.description.take(180))
                }
            }

            append("\n\n")
            append(row.reason)
        }
    }

    private fun formatMoney(value: Double): String {
        val number = NumberFormat.getNumberInstance(Locale.US).apply {
            minimumFractionDigits = 2
            maximumFractionDigits = 2
        }
        return "TT$" + number.format(value)
    }

    private fun postStatus(message: String) {
        runOnUiThread { binding.statusText.text = message }
    }

    private fun runTask(startMessage: String, task: () -> Unit) {
        if (busy) {
            toast("A check is already running.")
            return
        }

        busy = true
        binding.progressBar.visibility = View.VISIBLE
        binding.progressBar.isIndeterminate = true
        binding.saveTestButton.isEnabled = false
        binding.reconcileButton.isEnabled = false
        binding.statusText.text = startMessage

        executor.execute {
            try {
                task()
            } catch (error: Throwable) {
                runOnUiThread {
                    binding.statusText.text =
                        "Error: " + (error.message ?: error.javaClass.simpleName)
                    toast(error.message ?: "Something went wrong.")
                }
            } finally {
                runOnUiThread {
                    busy = false
                    binding.progressBar.visibility = View.GONE
                    binding.saveTestButton.isEnabled = true
                    binding.reconcileButton.isEnabled = true
                }
            }
        }
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }

    override fun onDestroy() {
        executor.shutdownNow()
        super.onDestroy()
    }

    private data class Inputs(
        val email: String,
        val password: String,
        val sheetUrl: String
    )

    companion object {
        private const val KEY_SHEET_URL = "reconciliation_sheet_url"
        private const val FIVE_DAYS_MS = 5L * 24L * 60L * 60L * 1000L
    }
}
