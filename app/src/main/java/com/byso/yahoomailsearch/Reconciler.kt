package com.byso.yahoomailsearch

import kotlin.math.abs

object Reconciler {
    private const val ONE_DAY_MS = 24L * 60L * 60L * 1000L

    fun reconcile(
        emails: List<MailRecord>,
        sheetEntries: List<SheetEntry>
    ): ReconciliationResult {
        val bankTransactions = emails.map(TransactionParser::parse)
        val unusedSheet = sheetEntries.toMutableList()
        val rows = mutableListOf<ReconciliationRow>()

        bankTransactions.forEach { bank ->
            if (bank.amount == null) {
                rows += ReconciliationRow(
                    bank = bank,
                    sheet = null,
                    status = MatchStatus.NEEDS_REVIEW,
                    reason = "Could not confidently extract a transaction amount from this Republic Bank email."
                )
                return@forEach
            }

            val amountCandidates = unusedSheet.filter { sheet ->
                sheet.amount != null && abs(sheet.amount - bank.amount) < 0.01
            }

            if (amountCandidates.isEmpty()) {
                rows += ReconciliationRow(
                    bank = bank,
                    sheet = null,
                    status = MatchStatus.MISSING_FROM_SHEET,
                    reason = "No Google Sheet row has the same amount."
                )
                return@forEach
            }

            val dateCandidates = amountCandidates.filter { sheet ->
                sheet.dateMs == null ||
                    abs(sheet.dateMs - bank.emailDateMs) <= ONE_DAY_MS
            }

            val candidates = if (dateCandidates.isNotEmpty()) dateCandidates else amountCandidates
            val best = candidates.maxByOrNull { sheet -> score(bank, sheet) }!!

            val strongDateMismatch =
                best.dateMs != null &&
                    abs(best.dateMs - bank.emailDateMs) > ONE_DAY_MS

            rows += ReconciliationRow(
                bank = bank,
                sheet = best,
                status = if (strongDateMismatch) MatchStatus.NEEDS_REVIEW else MatchStatus.MATCHED,
                reason = if (strongDateMismatch) {
                    "Amount matches, but the dates are more than one day apart."
                } else {
                    "Amount matches and the date is compatible."
                }
            )
            unusedSheet.remove(best)
        }

        unusedSheet.forEach { sheet ->
            rows += ReconciliationRow(
                bank = null,
                sheet = sheet,
                status = MatchStatus.NO_BANK_EMAIL_MATCH,
                reason = "This Google Sheet row has no matching Republic Bank email in the last 5 days."
            )
        }

        return ReconciliationResult(
            rows = rows,
            bankEmailCount = emails.size,
            bankTransactionCount = bankTransactions.size,
            sheetEntryCount = sheetEntries.size
        )
    }

    private fun score(bank: BankTransaction, sheet: SheetEntry): Int {
        var score = 0
        if (sheet.dateMs != null) {
            val diff = abs(sheet.dateMs - bank.emailDateMs)
            score += when {
                diff <= 4L * 60L * 60L * 1000L -> 40
                diff <= ONE_DAY_MS -> 25
                else -> 0
            }
        }

        if (bank.reference.isNotBlank() &&
            sheet.reference.contains(bank.reference, ignoreCase = true)
        ) {
            score += 50
        }

        val bankWords = keywords(bank.description)
        val sheetWords = keywords(sheet.description)
        score += bankWords.intersect(sheetWords).size * 3

        return score
    }

    private fun keywords(value: String): Set<String> =
        value.lowercase()
            .split(Regex("""[^a-z0-9]+"""))
            .filter { it.length >= 4 }
            .filterNot { it in STOP_WORDS }
            .toSet()

    private val STOP_WORDS = setOf(
        "republic", "bank", "transaction", "notification",
        "account", "amount", "your", "with", "from", "this"
    )
}
