package com.byso.yahoomailsearch

import android.text.Html
import com.sun.mail.imap.IMAPFolder
import java.util.Date
import java.util.Properties
import javax.mail.FetchProfile
import javax.mail.Folder
import javax.mail.Message
import javax.mail.Multipart
import javax.mail.Part
import javax.mail.Session
import javax.mail.Store
import javax.mail.UIDFolder
import javax.mail.search.ComparisonTerm
import javax.mail.search.ReceivedDateTerm

class RepublicBankEmailReader(
    private val email: String,
    private val appPassword: String
) {
    fun testConnection() {
        val store = connect()
        runCatching { store.close() }
    }

    fun fetchLastFiveDays(): List<MailRecord> {
        val cutoff = System.currentTimeMillis() - FIVE_DAYS_MS
        val store = connect()
        val results = mutableListOf<MailRecord>()

        try {
            val folders = store.defaultFolder.list("*")
                .filter { (it.type and Folder.HOLDS_MESSAGES) != 0 }
                .filterNot { isExcludedFolder(it.fullName) }
                .mapNotNull { it as? IMAPFolder }

            folders.forEach { folder ->
                folder.open(Folder.READ_ONLY)
                try {
                    val recent = folder.search(
                        ReceivedDateTerm(ComparisonTerm.GE, Date(cutoff))
                    )
                    if (recent.isEmpty()) return@forEach

                    val envelopeProfile = FetchProfile().apply {
                        add(FetchProfile.Item.ENVELOPE)
                        add(UIDFolder.FetchProfileItem.UID)
                    }
                    folder.fetch(recent, envelopeProfile)

                    val republicOnly = recent.filter { message ->
                        val sender = message.from
                            ?.joinToString(", ") { it.toString() }
                            .orEmpty()
                        val subject = message.subject.orEmpty()
                        looksLikeRepublicBank(sender, subject)
                    }

                    republicOnly.chunked(50).forEach { group ->
                        val contentProfile = FetchProfile().apply {
                            add(FetchProfile.Item.CONTENT_INFO)
                            add(UIDFolder.FetchProfileItem.UID)
                        }
                        folder.fetch(group.toTypedArray(), contentProfile)
                        group.forEach { message ->
                            results += toRecord(folder, message)
                        }
                    }
                } finally {
                    if (folder.isOpen) folder.close(false)
                }
            }
        } finally {
            runCatching { store.close() }
        }

        return results.sortedByDescending { it.dateMs }
    }

    private fun looksLikeRepublicBank(sender: String, subject: String): Boolean {
        val value = (sender + " " + subject).lowercase()
        return BANK_MARKERS.any { value.contains(it) }
    }

    private fun connect(): Store {
        val properties = Properties().apply {
            put("mail.store.protocol", "imaps")
            put("mail.imaps.host", IMAP_HOST)
            put("mail.imaps.port", "993")
            put("mail.imaps.ssl.enable", "true")
            put("mail.imaps.connectiontimeout", "30000")
            put("mail.imaps.timeout", "90000")
            put("mail.imaps.writetimeout", "90000")
            put("mail.imaps.peek", "true")
        }
        val session = Session.getInstance(properties)
        return session.getStore("imaps").apply {
            connect(IMAP_HOST, 993, email, appPassword)
        }
    }

    private fun isExcludedFolder(name: String): Boolean {
        val value = name.lowercase()
        return value.contains("trash") ||
            value.contains("spam") ||
            value.contains("bulk mail") ||
            value.contains("junk")
    }

    private fun toRecord(folder: IMAPFolder, message: Message): MailRecord {
        val attachments = mutableListOf<String>()
        val text = extractText(message, attachments)
            .replace(Regex("\\s+"), " ")
            .trim()
            .take(MAX_BODY_CHARS)
        val sender = message.from
            ?.joinToString(", ") { it.toString() }
            .orEmpty()
        val recipients = buildList {
            message.getRecipients(Message.RecipientType.TO)?.let {
                addAll(it.map { address -> address.toString() })
            }
            message.getRecipients(Message.RecipientType.CC)?.let {
                addAll(it.map { address -> address.toString() })
            }
        }.joinToString(", ")

        return MailRecord(
            folder = folder.fullName,
            uid = folder.getUID(message),
            subject = message.subject.orEmpty(),
            sender = sender,
            recipients = recipients,
            dateMs = (message.receivedDate ?: message.sentDate)?.time ?: 0L,
            body = text,
            hasAttachments = attachments.isNotEmpty(),
            attachmentNames = attachments.distinct().joinToString(", ")
        )
    }

    private fun extractText(part: Part, attachments: MutableList<String>): String {
        val disposition = part.disposition
        if (disposition?.equals(Part.ATTACHMENT, ignoreCase = true) == true) {
            part.fileName?.let(attachments::add)
            return ""
        }

        return when {
            part.isMimeType("text/plain") -> part.content?.toString().orEmpty()
            part.isMimeType("text/html") -> {
                val html = part.content?.toString().orEmpty()
                Html.fromHtml(html, Html.FROM_HTML_MODE_LEGACY).toString()
            }
            part.isMimeType("multipart/*") -> {
                val multipart = part.content as? Multipart ?: return ""
                buildString {
                    for (index in 0 until multipart.count) {
                        val bodyPart = multipart.getBodyPart(index)
                        if (!bodyPart.fileName.isNullOrBlank() &&
                            bodyPart.disposition?.equals(Part.ATTACHMENT, true) == true
                        ) {
                            attachments += bodyPart.fileName
                        } else {
                            append(extractText(bodyPart, attachments))
                            append('\n')
                        }
                    }
                }
            }
            part.isMimeType("message/rfc822") -> {
                val nested = part.content
                if (nested is Part) extractText(nested, attachments) else ""
            }
            else -> {
                part.fileName?.let(attachments::add)
                ""
            }
        }
    }

    companion object {
        private const val IMAP_HOST = "imap.mail.yahoo.com"
        private const val FIVE_DAYS_MS = 5L * 24L * 60L * 60L * 1000L
        private const val MAX_BODY_CHARS = 120_000
        private val BANK_MARKERS = listOf(
            "republic bank",
            "republicbank",
            "republictt",
            "republic online",
            "republiconline",
            "republic alert",
            "republicalert"
        )
    }
}
