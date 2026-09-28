package com.byso.yahoomailsearch

import org.json.JSONArray
import org.json.JSONObject

data class MailRecord(
    val folder: String,
    val uid: Long,
    val subject: String,
    val sender: String,
    val recipients: String,
    val dateMs: Long,
    val body: String,
    val hasAttachments: Boolean,
    val attachmentNames: String
) {
    fun toJson(): JSONObject = JSONObject()
        .put("folder", folder)
        .put("uid", uid)
        .put("subject", subject)
        .put("sender", sender)
        .put("recipients", recipients)
        .put("dateMs", dateMs)
        .put("body", body)
        .put("hasAttachments", hasAttachments)
        .put("attachmentNames", attachmentNames)

    companion object {
        fun fromJson(json: JSONObject) = MailRecord(
            folder = json.optString("folder"),
            uid = json.optLong("uid"),
            subject = json.optString("subject"),
            sender = json.optString("sender"),
            recipients = json.optString("recipients"),
            dateMs = json.optLong("dateMs"),
            body = json.optString("body"),
            hasAttachments = json.optBoolean("hasAttachments"),
            attachmentNames = json.optString("attachmentNames")
        )

        fun listToJson(records: List<MailRecord>): ByteArray {
            val arr = JSONArray()
            records.forEach { arr.put(it.toJson()) }
            return arr.toString().toByteArray(Charsets.UTF_8)
        }

        fun listFromJson(bytes: ByteArray): List<MailRecord> {
            val arr = JSONArray(bytes.toString(Charsets.UTF_8))
            return (0 until arr.length()).map { fromJson(arr.getJSONObject(it)) }
        }
    }
}

data class FolderState(
    var uidValidity: Long = 0,
    var lastUid: Long = 0
)

data class SyncState(
    val folders: MutableMap<String, FolderState> = linkedMapOf()
) {
    fun toJsonBytes(): ByteArray {
        val folderJson = JSONObject()
        folders.forEach { (name, state) ->
            folderJson.put(
                name,
                JSONObject()
                    .put("uidValidity", state.uidValidity)
                    .put("lastUid", state.lastUid)
            )
        }
        return JSONObject()
            .put("version", 1)
            .put("folders", folderJson)
            .toString()
            .toByteArray(Charsets.UTF_8)
    }

    companion object {
        fun fromJsonBytes(bytes: ByteArray): SyncState {
            val root = JSONObject(bytes.toString(Charsets.UTF_8))
            val foldersJson = root.optJSONObject("folders") ?: JSONObject()
            val state = SyncState()
            val keys = foldersJson.keys()
            while (keys.hasNext()) {
                val name = keys.next()
                val item = foldersJson.getJSONObject(name)
                state.folders[name] = FolderState(
                    uidValidity = item.optLong("uidValidity"),
                    lastUid = item.optLong("lastUid")
                )
            }
            return state
        }
    }
}

data class SearchSummary(
    val total: Int,
    val preview: List<MailRecord>
)

data class MailKey(
    val folder: String,
    val uid: Long
)


data class SyncProgress(
    val stage: String,
    val folderName: String = "",
    val folderIndex: Int = 0,
    val folderCount: Int = 0,
    val totalMessages: Int = 0,
    val processedMessages: Int = 0,
    val alreadyArchived: Int = 0,
    val newArchived: Int = 0,
    val uploadedBatches: Int = 0,
    val folderProcessed: Int = 0,
    val folderTotal: Int = 0,
    val complete: Boolean = false
) {
    val percent: Int
        get() = if (totalMessages <= 0) {
            if (complete) 100 else 0
        } else {
            ((processedMessages.toDouble() / totalMessages.toDouble()) * 100.0)
                .toInt()
                .coerceIn(0, 100)
        }
}


enum class ArchiveSyncMode {
    FULL_EXPORT,
    LIVE_INCREMENTAL
}
