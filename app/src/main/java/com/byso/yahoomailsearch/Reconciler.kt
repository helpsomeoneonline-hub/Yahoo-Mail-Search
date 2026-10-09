package com.byso.yahoomailsearch

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import kotlin.math.abs

object Reconciler {
    private val zone = ZoneId.of("America/Port_of_Spain")
    private const val FIVE_DAYS_MS = 5L * 24L * 60L * 60L * 1000L

    fun reconcile(
        emails: List<MailRecord>,
        sheetEntries: List<SheetEntry>
    ): ReconciliationResult {
        val bankTransactions = emails.map(TransactionParser::parse)
        val unusedSheet = sheetEntries.toMutableList()
        val rows = mutableListOf<ReconciliationRow>()
        val today = LocalDate.now(zone)
        val bankScanStart = Instant.ofEpochMilli(System.currentTimeMillis() - FIVE_DAYS_MS)
            .atZone(zone).toLocalDate()

        bankTransactions.forEach { bank ->
            if (bank.amount == null) {
                rows += ReconciliationRow(
                    bank = bank, sheet = null,
                    status = MatchStatus.NEEDS_REVIEW,
                    reason = "No reliable payment amount found in this bank email."
                )
                return@forEach
            }

            val sameAmount = unusedSheet.filter { sheet ->
                sheet.amount != null && abs(sheet.amount - bank.amount) < 0.01
            }
            val timelyMatches = sameAmount.filter { sheet ->
                paymentDelayDays(sheet, bank.emailDateMs)?.let { it in 0L..2L } == true
            }
            if (timelyMatches.isEmpty()) {
                rows += ReconciliationRow(
                    bank = bank,
                    sheet = null,
                    status = if (sameAmount.isEmpty()) {
                        MatchStatus.MISSING_FROM_SHEET
                    } else {
                        MatchStatus.NEEDS_REVIEW
                    },
                    reason = if (sameAmount.isEmpty()) {
                        "No October 2026 Republic Bank bookkeeping sale has this amount."
                    } else {
                        "This amount exists in October bookkeeping, but no entry is dated " +
                            "within two days before this bank notification. Check dates."
                    }
                )
                return@forEach
            }

            val best = timelyMatches.maxByOrNull { sheet -> score(bank, sheet) }!!
            val totalPossible = sheetEntries.count { sheet ->
                sheet.amount != null && abs(sheet.amount - bank.amount) < 0.01 &&
                    paymentDelayDays(sheet, bank.emailDateMs)?.let { it in 0L..2L } == true
            }
            val ambiguous = totalPossible > 1
            val delay = paymentDelayDays(best, bank.emailDateMs) ?: 0L
            rows += ReconciliationRow(
                bank = bank,
                sheet = best,
                status = if (ambiguous) MatchStatus.NEEDS_REVIEW else MatchStatus.MATCHED,
                reason = if (ambiguous) {
                    "Possible match (" + delay + "-day bank arrival delay), but multiple sales " +
                        "have this amount. Verify the customer or transfer reference."
                } else {
                    "Unique amount and compatible date. Bank notification arrived " +
                        delay + " calendar day(s) after the sheet entry."
                }
            )
            unusedSheet.remove(best)
        }

        unusedSheet.forEach { sheet ->
            val sheetDate = sheet.dateMs?.let {
                Instant.ofEpochMilli(it).atZone(zone).toLocalDate()
            }
            val possibleEmail = bankTransactions.any { bank ->
                bank.amount != null && sheet.amount != null &&
                    abs(bank.amount - sheet.amount) < 0.01 &&
                    paymentDelayDays(sheet, bank.emailDateMs)?.let { it in 0L..2L } == true
            }

            val status = when {
                sheetDate == null -> MatchStatus.NEEDS_REVIEW
                !sheetDate.plusDays(2).isBefore(today) -> MatchStatus.AWAITING_BANK
                !sheetDate.isAfter(bankScanStart) -> MatchStatus.OUTSIDE_BANK_WINDOW
                possibleEmail -> MatchStatus.NEEDS_REVIEW
                else -> MatchStatus.NO_BANK_EMAIL_MATCH
            }
            val reason = when (status) {
                MatchStatus.AWAITING_BANK ->
                    "Allow up to two days for an interbank transfer to reach Republic Bank."
                MatchStatus.OUTSIDE_BANK_WINDOW ->
                    "This sale is older than the five-day bank email search, so its " +
                        "possible bank notifications were not fully checked."
                MatchStatus.NEEDS_REVIEW ->
                    "Bank email(s) with this amount exist, but cannot be assigned " +
                        "uniquely to this sale. Check duplicates and references."
                else ->
                    "No corresponding Republic Bank notification found in the five-day email scan."
            }
            rows += ReconciliationRow(
                bank = null, sheet = sheet, status = status, reason = reason
            )
        }

        return ReconciliationResult(
            rows = rows,
            bankEmailCount = emails.size,
            bankTransactionCount = bankTransactions.size,
            sheetEntryCount = sheetEntries.size
        )
    }

    private fun paymentDelayDays(sheet: SheetEntry, bankTimestamp: Long): Long? {
        val sheetTime = sheet.dateMs ?: return null
        val sheetDate = Instant.ofEpochMilli(sheetTime).atZone(zone).toLocalDate()
        val bankDate = Instant.ofEpochMilli(bankTimestamp).atZone(zone).toLocalDate()
        return ChronoUnit.DAYS.between(sheetDate, bankDate)
    }

    private fun score(bank: BankTransaction, sheet: SheetEntry): Int {
        var points = 0
        points += when (paymentDelayDays(sheet, bank.emailDateMs)) {
            0L -> 30
            1L -> 20
            2L -> 10
            else -> 0
        }
        if (bank.reference.length >= 5 && sheet.reference.contains(
                bank.reference, ignoreCase = true
            )
        ) {
            points += 80
        }
        points += keywords(bank.description).intersect(keywords(sheet.description)).size * 3
        return points
    }

    private fun keywords(value: String): Set<String> =
        value.lowercase()
            .split(Regex("""[^a-z0-9]+"""))
            .filter { it.length >= 4 && it !in STOP_WORDS }
            .toSet()

    private val STOP_WORDS = setOf(
        "republic", "bank", "transaction", "notification",
        "account", "amount", "your", "with", "from", "this"
    )
}
