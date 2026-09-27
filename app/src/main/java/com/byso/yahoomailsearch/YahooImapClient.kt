package com.byso.yahoomailsearch

import android.text.Html
import com.sun.mail.imap.IMAPFolder
import java.util.Properties
import javax.mail.FetchProfile
import javax.mail.Flags
import javax.mail.Folder
import javax.mail.Message
import javax.mail.Multipart
import javax.mail.Part
import javax.mail.Session
import javax.mail.Store

class YahooImapClient(
    private val email: String,
    private val appPassword: String
) {
    fun testConnection() {
        val store = connect()
        try {
            require(store.isConnected) { "Yahoo connection failed." }
        } finally {
            runCatching { store.close() }
        }
    }

    fun sync(
        state: SyncState,
        onBatch: (folderName: String, uidValidity: Long, records: List<MailRecord>) -> Unit,
        onCheckpoint: (folderName: String, uidValidity: Long, lastUid: Long) -> Unit,
        onProgress: (String) -> Unit
    ) {
        val store = connect()
        try {
            val folders = store.defaultFolder.list("*")
                .filter { (it.type and Folder.HOLDS_MESSAGES) != 0 }
                .filterNot { isExcludedFolder(it.fullName) }

            folders.forEachIndexed { index, rawFolder ->
                val folder = rawFolder as? IMAPFolder ?: return@forEachIndexed
                onProgress("Opening ${folder.fullName} (${index + 1}/${folders.size})")
                folder.open(Folder.READ_ONLY)
                try {
                    val validity = folder.uidValidity
                    val saved = state.folders[folder.fullName]
                    var startUid =
                        if (saved != null && saved.uidValidity == validity) saved.lastUid + 1 else 1L

                    val uidNext = folder.uidNext
                    val maxUid = if (uidNext > 0) uidNext - 1 else {
                        val newest = if (folder.messageCount > 0) folder.getMessage(folder.messageCount) else null
                        newest?.let { folder.getUID(it) } ?: 0L
                    }

                    while (startUid <= maxUid) {
                        val endUid = minOf(startUid + UID_WINDOW - 1, maxUid)
                        val messages = folder.getMessagesByUID(startUid, endUid).filterNotNull()
                        if (messages.isNotEmpty()) {
                            val fetchProfile = FetchProfile().apply {
                                add(FetchProfile.Item.ENVELOPE)
                                add(FetchProfile.Item.FLAGS)
                                add(FetchProfile.Item.CONTENT_INFO)
                            }
                            folder.fetch(messages.toTypedArray(), fetchProfile)

                            val records = messages.mapNotNull { message ->
                                runCatching { toRecord(folder, message) }.getOrNull()
                            }.sortedBy { it.uid }

                            records.chunked(ARCHIVE_BATCH).forEach { batch ->
                                if (batch.isNotEmpty()) onBatch(folder.fullName, validity, batch)
                            }
                        }

                        onCheckpoint(folder.fullName, validity, endUid)
                        onProgress(
                            "${folder.fullName}: archived through UID $endUid of $maxUid"
                        )
                        startUid = endUid + 1
                    }
                } finally {
                    if (folder.isOpen) folder.close(false)
                }
            }
        } finally {
            runCatching { store.close() }
        }
    }

    fun moveToTrash(
        keys: List<MailKey>,
        onProgress: (String) -> Unit
    ) {
        if (keys.isEmpty()) return
        val store = connect()
        try {
            val trash = findTrash(store)
            require(trash.exists()) { "Yahoo Trash folder was not found." }

            keys.groupBy { it.folder }.forEach { (folderName, folderKeys) ->
                val source = store.getFolder(folderName) as? IMAPFolder
                    ?: error("Could not open Yahoo folder: $folderName")
                if (!source.exists()) return@forEach

                source.open(Folder.READ_WRITE)
                try {
                    folderKeys.chunked(200).forEachIndexed { batchIndex, batch ->
                        val messages = source.getMessagesByUID(
                            batch.map { it.uid }.toLongArray()
                        ).filterNotNull().toTypedArray()

                        if (messages.isNotEmpty()) {
                            try {
                                source.moveMessages(messages, trash)
                            } catch (_: Exception) {
                                source.copyMessages(messages, trash)
                                source.setFlags(messages, Flags(Flags.Flag.DELETED), true)
                                source.expunge(messages)
                            }
                        }
                        onProgress(
                            "Moving ${folderName}: batch ${batchIndex + 1} of ${(folderKeys.size + 199) / 200}"
                        )
                    }
                } finally {
                    if (source.isOpen) source.close(false)
                }
            }
        } finally {
            runCatching { store.close() }
        }
    }

    private fun connect(): Store {
        val properties = Properties().apply {
            put("mail.store.protocol", "imaps")
            put("mail.imaps.host", "imap.mail.yahoo.com")
            put("mail.imaps.port", "993")
            put("mail.imaps.ssl.enable", "true")
            put("mail.imaps.connectiontimeout", "30000")
            put("mail.imaps.timeout", "90000")
            put("mail.imaps.writetimeout", "90000")
        }
        val session = Session.getInstance(properties)
        return session.getStore("imaps").apply {
            connect("imap.mail.yahoo.com", 993, email, appPassword)
        }
    }

    private fun findTrash(store: Store): Folder {
        val folders = store.defaultFolder.list("*")
        return folders.firstOrNull {
            val name = it.fullName.lowercase()
            name == "trash" || name.endsWith("/trash") || name.contains("trash")
        } ?: store.getFolder("Trash")
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
        val text = extractText(message, attachments).trim().take(MAX_BODY_CHARS)
        val sender = message.from?.joinToString(", ") { it.toString() }.orEmpty()
        val recipients = buildList {
            message.getRecipients(Message.RecipientType.TO)?.let { addAll(it.map { address -> address.toString() }) }
            message.getRecipients(Message.RecipientType.CC)?.let { addAll(it.map { address -> address.toString() }) }
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
                        val fileName = bodyPart.fileName
                        if (!fileName.isNullOrBlank() &&
                            bodyPart.disposition?.equals(Part.ATTACHMENT, true) == true
                        ) {
                            attachments += fileName
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
        private const val UID_WINDOW = 500L
        private const val ARCHIVE_BATCH = 100
        private const val MAX_BODY_CHARS = 500_000
    }
}
