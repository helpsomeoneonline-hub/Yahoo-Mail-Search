package com.byso.yahoomailsearch

import java.net.HttpURLConnection
import java.net.URL
import java.time.LocalDate
import java.time.Month
import java.time.YearMonth
import java.time.ZoneId
import java.util.Locale

class GoogleSheetCsvClient {
    fun fetchEntries(sheetUrl: String): List<SheetEntry> {
        val csvUrl = toCsvUrl(sheetUrl)
        val connection = URL(csvUrl).openConnection() as HttpURLConnection
        connection.connectTimeout = 20_000
        connection.readTimeout = 30_000
        connection.instanceFollowRedirects = true
        connection.setRequestProperty("User-Agent", "Republic-Reconcile/1.0")

        try {
            val code = connection.responseCode
            if (code !in 200..299) {
                error(
                    "Google Sheet returned HTTP $code. Make sure the sheet is shared as Viewer " +
                        "with anyone who has the link, or use a direct CSV export URL."
                )
            }
            val text = connection.inputStream.bufferedReader().use { it.readText() }
            return parseEntries(text)
        } finally {
            connection.disconnect()
        }
    }

    private fun toCsvUrl(input: String): String {
        val value = input.trim()
        val id = Regex("""/spreadsheets/d/([a-zA-Z0-9_-]+)""")
            .find(value)?.groupValues?.getOrNull(1)
            ?: error("Paste a Google Sheets link (not an unrelated CSV).")

        // Always use the bookkeeping tab even when the shared link omits its gid.
        return "https://docs.google.com/spreadsheets/d/" + id +
            "/export?format=csv&gid=" + BOOKKEEPING_TAB_GID
    }

    internal fun parseEntries(csv: String): List<SheetEntry> {
        // Keep physical row numbers (including blanks), because results link to sheet rows.
        val rows = parseCsv(csv)
        val startDate = LocalDate.of(2026, 10, 1)
        val today = LocalDate.now(ZoneId.of("America/Port_of_Spain"))
        var activeMonth: YearMonth? = null
        var dateColumn = 2    // C: "Date" is a day-of-month number
        var amountColumn = 8  // I: "Markup Price" is the TTD sale received
        var bankColumn = 9    // J: Bank Type
        var orderColumn = 1   // B: Skybox / customer number
        var siteColumn = 3    // D: Site

        return rows.mapIndexedNotNull { index, row ->
            val first = row.firstOrNull().orEmpty().trim()
            val monthMatch = MONTH_HEADING.matchEntire(first)
            if (monthMatch != null) {
                val month = Month.valueOf(monthMatch.groupValues[1].uppercase(Locale.US))
                val year = monthMatch.groupValues[2].toInt()
                activeMonth = YearMonth.of(year, month)
                return@mapIndexedNotNull null
            }

            // Different month sections repeat headers; never treat a header as a payment.
            val header = row.map { it.trim().lowercase(Locale.US) }
            val headerDate = header.indexOfFirst { it == "date" }
            val headerPrice = header.indexOfFirst { it == "markup price" }
            if (headerDate >= 0 && headerPrice >= 0) {
                dateColumn = headerDate
                amountColumn = headerPrice
                val bankHeader = header.indexOfFirst { it == "bank type" }
                bankColumn = if (bankHeader >= 0) bankHeader else 9
                orderColumn = header.indexOfFirst { it == "skybox no#" }
                    .takeIf { it >= 0 } ?: 1
                siteColumn = header.indexOfFirst { it == "site" }
                    .takeIf { it >= 0 } ?: 3
                return@mapIndexedNotNull null
            }

            val month = activeMonth ?: return@mapIndexedNotNull null
            val day = row.getOrNull(dateColumn)?.trim()?.toIntOrNull()
                ?: return@mapIndexedNotNull null
            if (day !in 1..month.lengthOfMonth()) return@mapIndexedNotNull null

            val date = month.atDay(day)
            if (date.isBefore(startDate) || date.isAfter(today)) {
                return@mapIndexedNotNull null
            }

            // A Republic email cannot confirm a sale deposited into Scotia or Royal.
            val bankType = row.getOrNull(bankColumn)?.trim().orEmpty()
            if (!bankType.contains("republic", ignoreCase = true)) {
                return@mapIndexedNotNull null
            }

            val amount = parseAmount(row.getOrNull(amountColumn).orEmpty())
                ?: return@mapIndexedNotNull null
            if (amount <= 0) return@mapIndexedNotNull null

            val customer = row.getOrNull(orderColumn)?.trim().orEmpty()
            val site = row.getOrNull(siteColumn)?.trim().orEmpty()
            SheetEntry(
                rowNumber = index + 1,
                dateMs = date.atStartOfDay(ZoneId.of("America/Port_of_Spain"))
                    .toInstant().toEpochMilli(),
                amount = amount,
                description = listOf(customer, site).filter { it.isNotBlank() }
                    .joinToString(" • ").ifBlank { "Bookkeeping sale" },
                reference = customer,
                raw = row.joinToString(" | ")
            )
        }
    }

    private fun parseAmount(value: String): Double? {
        val cleaned = value
            .replace(",", "")
            .replace("TTD", "", ignoreCase = true)
            .replace("TT$", "", ignoreCase = true)
            .replace("$", "")
            .trim()
        if (!cleaned.matches(Regex("""-?\d+(?:\.\d{1,2})?"""))) return null
        return cleaned.toDoubleOrNull()?.let { kotlin.math.abs(it) }
    }

    private fun parseCsv(text: String): List<List<String>> {
        val rows = mutableListOf<MutableList<String>>()
        var row = mutableListOf<String>()
        val field = StringBuilder()
        var inQuotes = false
        var index = 0

        while (index < text.length) {
            val ch = text[index]
            when {
                ch == '"' && inQuotes && index + 1 < text.length && text[index + 1] == '"' -> {
                    field.append('"')
                    index++
                }
                ch == '"' -> inQuotes = !inQuotes
                ch == ',' && !inQuotes -> {
                    row += field.toString()
                    field.setLength(0)
                }
                (ch == '\n' || ch == '\r') && !inQuotes -> {
                    if (ch == '\r' && index + 1 < text.length && text[index + 1] == '\n') {
                        index++
                    }
                    row += field.toString()
                    field.setLength(0)
                    rows += row
                    row = mutableListOf()
                }
                else -> field.append(ch)
            }
            index++
        }

        if (field.isNotEmpty() || row.isNotEmpty()) {
            row += field.toString()
            rows += row
        }
        return rows
    }

    companion object {
        private const val BOOKKEEPING_TAB_GID = "578904948"
        private val MONTH_HEADING = Regex(
            """(January|February|March|April|May|June|July|August|September|October|November|December)\s+(20\d{2})""",
            RegexOption.IGNORE_CASE
        )
    }
}
