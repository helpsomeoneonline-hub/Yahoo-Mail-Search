package com.byso.yahoomailsearch

import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
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
        if (value.contains("format=csv", ignoreCase = true) ||
            value.endsWith(".csv", ignoreCase = true)
        ) {
            return value
        }

        val id = Regex("""/spreadsheets/d/([a-zA-Z0-9_-]+)""")
            .find(value)
            ?.groupValues
            ?.getOrNull(1)
            ?: error("Paste a valid Google Sheets link or CSV export URL.")

        val gid = Regex("""(?:[#?&]gid=)(\d+)""")
            .find(value)
            ?.groupValues
            ?.getOrNull(1)

        return buildString {
            append("https://docs.google.com/spreadsheets/d/")
            append(id)
            append("/export?format=csv")
            if (!gid.isNullOrBlank()) {
                append("&gid=")
                append(gid)
            }
        }
    }

    private fun parseEntries(csv: String): List<SheetEntry> {
        val rows = parseCsv(csv).filter { row -> row.any { it.isNotBlank() } }
        if (rows.isEmpty()) return emptyList()

        val header = rows.first().map { it.trim().lowercase() }
        val hasHeader = header.any {
            it.contains("date") ||
                it.contains("amount") ||
                it.contains("description") ||
                it.contains("reference") ||
                it.contains("details")
        }

        val dateIndex = findColumn(header, listOf("date", "transaction date", "day"))
        val amountIndex = findColumn(header, listOf("amount", "ttd", "value", "total", "paid"))
        val descriptionIndex = findColumn(
            header,
            listOf("description", "details", "narration", "note", "name", "customer")
        )
        val referenceIndex = findColumn(
            header,
            listOf("reference", "ref", "transaction id", "receipt", "id")
        )

        val dataRows = if (hasHeader) rows.drop(1) else rows
        val offset = if (hasHeader) 2 else 1

        return dataRows.mapIndexedNotNull { index, row ->
            val amount = when {
                amountIndex >= 0 -> parseAmount(row.getOrNull(amountIndex).orEmpty())
                else -> row.firstNotNullOfOrNull { parseAmount(it) }
            }
            val dateMs = when {
                dateIndex >= 0 -> parseDate(row.getOrNull(dateIndex).orEmpty())
                else -> row.firstNotNullOfOrNull { parseDate(it) }
            }
            val description = when {
                descriptionIndex >= 0 -> row.getOrNull(descriptionIndex).orEmpty().trim()
                else -> row.joinToString(" | ").trim()
            }
            val reference = when {
                referenceIndex >= 0 -> row.getOrNull(referenceIndex).orEmpty().trim()
                else -> ""
            }

            if (amount == null && dateMs == null && description.isBlank()) {
                null
            } else {
                SheetEntry(
                    rowNumber = index + offset,
                    dateMs = dateMs,
                    amount = amount,
                    description = description,
                    reference = reference,
                    raw = row.joinToString(" | ")
                )
            }
        }
    }

    private fun findColumn(header: List<String>, names: List<String>): Int {
        names.forEach { name ->
            val exact = header.indexOfFirst { it == name }
            if (exact >= 0) return exact
        }
        names.forEach { name ->
            val partial = header.indexOfFirst { it.contains(name) }
            if (partial >= 0) return partial
        }
        return -1
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

    private fun parseDate(value: String): Long? {
        val text = value.trim()
        if (text.length !in 6..30) return null

        DATE_PATTERNS.forEach { pattern ->
            val parser = SimpleDateFormat(pattern, Locale.US)
            parser.isLenient = false
            runCatching { parser.parse(text)?.time }.getOrNull()?.let { return it }
        }
        return null
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
        private val DATE_PATTERNS = listOf(
            "M/d/yyyy",
            "MM/dd/yyyy",
            "d/M/yyyy",
            "dd/MM/yyyy",
            "yyyy-MM-dd",
            "dd-MM-yyyy",
            "MMM d, yyyy",
            "d MMM yyyy",
            "M/d/yyyy h:mm a",
            "d/M/yyyy h:mm a",
            "yyyy-MM-dd HH:mm:ss"
        )
    }
}
