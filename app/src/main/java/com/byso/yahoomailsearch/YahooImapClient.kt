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
import javax.mail.UIDFolder

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
        onProgress: (SyncProgress) -> Unit
    ) {
        val store = connect()
        try {
            val folders = store.defaultFolder.list("*")
                .filter { (it.type and Folder.HOLDS_MESSAGES) != 0 }
                .filterNot { isExcludedFolder(it.fullName) }
                .mapNotNull { it as? IMAPFolder }

            onProgress(
                SyncProgress(
                    stage = "Counting Yahoo emails...",
                    folderCount = folders.size
                )
            )

            val folderCounts = linkedMapOf<IMAPFolder, Int>()
            folders.forEachIndexed { index, folder ->
                var count = 0
                try {
                    folder.open(Folder.READ_ONLY)
                    count = folder.messageCount.coerceAtLeast(0)
                } finally {
                    if (folder.isOpen) folder.close(false)
                }
                folderCounts[folder] = count
                onProgress(
                    SyncProgress(
                        stage = "Counting folders...",
                        folderName = folder.fullName,
                        folderIndex = index + 1,
                        folderCount = folders.size,
                        totalMessages = folderCounts.values.sum()
                    )
                )
            }

            val totalMessages = folderCounts.values.sum()
            var processedMessages = 0
            var alreadyArchived = 0
            var newArchived = 0
            var uploadedBatches = 0

            if (totalMessages == 0) {
                onProgress(
                    SyncProgress(
                        stage = "SYNC COMPLETE",
                        folderCount = folders.size,
                        totalMessages = 0,
                        processedMessages = 0,
                        complete = true
                    )
                )
                return
            }

            folders.forEachIndexed { folderIndex, folder ->
                val folderTotal = folderCounts[folder] ?: 0
                var folderProcessed = 0

                onProgress(
                    SyncProgress(
                        stage = "Opening folder...",
                        folderName = folder.fullName,
                        folderIndex = folderIndex + 1,
                        folderCount = folders.size,
                        totalMessages = totalMessages,
                        processedMessages = processedMessages,
                        alreadyArchived = alreadyArchived,
                        newArchived = newArchived,
                        uploadedBatches = uploadedBatches,
                        folderProcessed = folderProcessed,
                        folderTotal = folderTotal
                    )
                )

                if (folderTotal <= 0) return@forEachIndexed

                folder.open(Folder.READ_ONLY)
                try {
                    val validity = folder.uidValidity
                    val saved = state.folders[folder.fullName]
                    val savedUid =
                        if (saved != null && saved.uidValidity == validity) saved.lastUid else 0L

                    var startSequence = 1
                    while (startSequence <= folderTotal) {
                        val endSequence = minOf(startSequence + MESSAGE_WINDOW - 1, folderTotal)
                        val messages = folder.getMessages(startSequence, endSequence)

                        val uidFetch = FetchProfile().apply {
                            add(UIDFolder.FetchProfileItem.UID)
                        }
                        folder.fetch(messages, uidFetch)

                        val newMessages = mutableListOf<Message>()
                        var highestUid = savedUid
                        var oldInWindow = 0

                        messages.forEach { message ->
                            val uid = folder.getUID(message)
                            if (uid > highestUid) highestUid = uid
                            if (uid > savedUid) {
                                newMessages += message
                            } else {
                                oldInWindow++
                            }
                        }

                        alreadyArchived += oldInWindow

                        if (newMessages.isNotEmpty()) {
                            onProgress(
                                SyncProgress(
                                    stage = "Downloading ${newMessages.size} new emails...",
                                    folderName = folder.fullName,
                                    folderIndex = folderIndex + 1,
                                    folderCount = folders.size,
                                    totalMessages = totalMessages,
                                    processedMessages = processedMessages,
                                    alreadyArchived = alreadyArchived,
                                    newArchived = newArchived,
                                    uploadedBatches = uploadedBatches,
                                    folderProcessed = folderProcessed,
                                    folderTotal = folderTotal
                                )
                            )

                            val fetchProfile = FetchProfile().apply {
                                add(FetchProfile.Item.ENVELOPE)
                                add(FetchProfile.Item.FLAGS)
                                add(FetchProfile.Item.CONTENT_INFO)
                                add(UIDFolder.FetchProfileItem.UID)
                            }
                            folder.fetch(newMessages.toTypedArray(), fetchProfile)

                            val records = newMessages.map { message ->
                                toRecord(folder, message)
                            }.sortedBy { it.uid }

                            records.chunked(ARCHIVE_BATCH).forEach { batch ->
                                if (batch.isNotEmpty()) {
                                    onProgress(
                                        SyncProgress(
                                            stage = "Uploading ${batch.size} emails to GitHub...",
                                            folderName = folder.fullName,
                                            folderIndex = folderIndex + 1,
                                            folderCount = folders.size,
                                            totalMessages = totalMessages,
                                            processedMessages = processedMessages,
                                            alreadyArchived = alreadyArchived,
                                            newArchived = newArchived,
                                            uploadedBatches = uploadedBatches,
                                            folderProcessed = folderProcessed,
                                            folderTotal = folderTotal
                                        )
                                    )
                                    onBatch(folder.fullName, validity, batch)
                                    newArchived += batch.size
                                    uploadedBatches++
                                }
                            }
                        }

                        if (highestUid > 0) {
                            onCheckpoint(folder.fullName, validity, highestUid)
                        }

                        val windowSize = messages.size
                        processedMessages += windowSize
                        folderProcessed += windowSize

                        onProgress(
                            SyncProgress(
                                stage = "Syncing...",
                                folderName = folder.fullName,
                                folderIndex = folderIndex + 1,
                                folderCount = folders.size,
                                totalMessages = totalMessages,
                                processedMessages = processedMessages,
                                alreadyArchived = alreadyArchived,
                                newArchived = newArchived,
                                uploadedBatches = uploadedBatches,
                                folderProcessed = folderProcessed,
                                folderTotal = folderTotal
                            )
                        )

                        startSequence = endSequence + 1
                    }
                } finally {
                    if (folder.isOpen) folder.close(false)
                }
            }

            onProgress(
                SyncProgress(
                    stage = "SYNC COMPLETE",
                    folderCount = folders.size,
                    totalMessages = totalMessages,
                    processedMessages = totalMessages,
                    alreadyArchived = alreadyArchived,
                    newArchived = newArchived,
                    uploadedBatches = uploadedBatches,
                    complete = true
                )
            )
        } finally {
            runCatching { store.close() }
        }
    }

    fun fetchRecentSince(
        afterMs: Long,
        maxPerFolder: Int = 100
    ): List<MailRecord> {
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
                    val count = folder.messageCount
                    if (count <= 0) return@forEach
                    val start = maxOf(1, count - maxPerFolder + 1)
                    val messages = folder.getMessages(start, count)
                    val fetchProfile = FetchProfile().apply {
                        add(FetchProfile.Item.ENVELOPE)
                        add(FetchProfile.Item.CONTENT_INFO)
                        add(UIDFolder.FetchProfileItem.UID)
                    }
                    folder.fetch(messages, fetchProfile)
                    messages.forEach { message ->
                        val dateMs = (message.receivedDate ?: message.sentDate)?.time ?: 0L
                        if (dateMs > afterMs) {
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
        private const val MESSAGE_WINDOW = 500
        private const val ARCHIVE_BATCH = 100
        private const val MAX_BODY_CHARS = 500_000
    }
}
