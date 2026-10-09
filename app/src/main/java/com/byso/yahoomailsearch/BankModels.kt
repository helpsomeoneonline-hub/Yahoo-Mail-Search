package com.byso.yahoomailsearch

data class BankTransaction(
    val sourceId: String,
    val emailDateMs: Long,
    val amount: Double?,
    val description: String,
    val reference: String,
    val subject: String,
    val sender: String,
    val rawSnippet: String
)

data class SheetEntry(
    val rowNumber: Int,
    val dateMs: Long?,
    val amount: Double?,
    val description: String,
    val reference: String,
    val raw: String
)

enum class MatchStatus {
    MATCHED,
    MISSING_FROM_SHEET,
    NO_BANK_EMAIL_MATCH,
    AWAITING_BANK,
    OUTSIDE_BANK_WINDOW,
    NEEDS_REVIEW
}

data class ReconciliationRow(
    val bank: BankTransaction?,
    val sheet: SheetEntry?,
    val status: MatchStatus,
    val reason: String
)

data class ReconciliationResult(
    val rows: List<ReconciliationRow>,
    val bankEmailCount: Int,
    val bankTransactionCount: Int,
    val sheetEntryCount: Int
) {
    val matched: Int get() = rows.count { it.status == MatchStatus.MATCHED }
    val missingFromSheet: Int get() = rows.count { it.status == MatchStatus.MISSING_FROM_SHEET }
    val noBankEmailMatch: Int get() = rows.count { it.status == MatchStatus.NO_BANK_EMAIL_MATCH }
    val needsReview: Int get() = rows.count { it.status == MatchStatus.NEEDS_REVIEW }
    val awaitingBank: Int get() = rows.count { it.status == MatchStatus.AWAITING_BANK }
    val outsideBankWindow: Int get() = rows.count { it.status == MatchStatus.OUTSIDE_BANK_WINDOW }
}
