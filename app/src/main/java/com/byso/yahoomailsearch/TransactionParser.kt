package com.byso.yahoomailsearch

object TransactionParser {
    private val amountRegexes = listOf(
        Regex("""(?i)(?:TTD|TT\$|TT)\s*\$?\s*([0-9]{1,3}(?:,[0-9]{3})*(?:\.\d{2})|[0-9]+(?:\.\d{2}))"""),
        Regex("""(?i)(?:amount|value|transaction)\s*(?:is|of|:|-)?\s*\$?\s*([0-9]{1,3}(?:,[0-9]{3})*(?:\.\d{2})|[0-9]+(?:\.\d{2}))"""),
        Regex("""\$\s*([0-9]{1,3}(?:,[0-9]{3})*(?:\.\d{2})|[0-9]+(?:\.\d{2}))""")
    )

    private val referenceRegex = Regex(
        """(?i)(?:reference|ref(?:erence)?\s*(?:no|number)?|transaction\s*id|txn\s*id|approval\s*code)\s*[:#-]?\s*([A-Z0-9][A-Z0-9\-]{3,})"""
    )

    fun parse(record: MailRecord): BankTransaction {
        val combined = buildString {
            append(record.subject)
            append('\n')
            append(record.body)
        }

        val amount = findAmount(combined)
        val reference = referenceRegex.find(combined)?.groupValues?.getOrNull(1).orEmpty()
        val description = bestDescription(record.subject, record.body)

        return BankTransaction(
            sourceId = "${record.folder}:${record.uid}",
            emailDateMs = record.dateMs,
            amount = amount,
            description = description,
            reference = reference,
            subject = record.subject,
            sender = record.sender,
            rawSnippet = combined.replace(Regex("\\s+"), " ").trim().take(500)
        )
    }

    private fun findAmount(text: String): Double? {
        val amountLines = text.lineSequence()
            .filter {
                val value = it.lowercase()
                value.contains("amount") ||
                    value.contains("transaction") ||
                    value.contains("purchase") ||
                    value.contains("debit") ||
                    value.contains("credit")
            }
            .toList()

        (amountLines + listOf(text)).forEach { candidate ->
            amountRegexes.forEach { regex ->
                val match = regex.find(candidate) ?: return@forEach
                val value = match.groupValues[1]
                    .replace(",", "")
                    .toDoubleOrNull()
                if (value != null && value > 0.0) return value
            }
        }
        return null
    }

    private fun bestDescription(subject: String, body: String): String {
        if (subject.isNotBlank()) return subject.trim()
        return body.lineSequence()
            .map { it.trim() }
            .firstOrNull { it.length >= 4 }
            .orEmpty()
            .take(160)
    }
}
