package com.byso.yahoomailsearch

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import java.io.File

class CacheDb(context: Context) {
    private val dbFile = File(context.cacheDir, "yahoo-mail-search-cache.db")
    private val db = SQLiteDatabase.openOrCreateDatabase(dbFile, null)

    init {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS mail (
                folder TEXT NOT NULL,
                uid INTEGER NOT NULL,
                subject TEXT NOT NULL,
                sender TEXT NOT NULL,
                recipients TEXT NOT NULL,
                date_ms INTEGER NOT NULL,
                body TEXT NOT NULL,
                has_attachments INTEGER NOT NULL,
                attachment_names TEXT NOT NULL,
                PRIMARY KEY(folder, uid)
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_mail_date ON mail(date_ms DESC)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_mail_sender ON mail(sender)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_mail_subject ON mail(subject)")
    }

    fun upsert(records: List<MailRecord>) {
        if (records.isEmpty()) return
        db.beginTransaction()
        try {
            records.forEach { record ->
                val values = ContentValues().apply {
                    put("folder", record.folder)
                    put("uid", record.uid)
                    put("subject", record.subject)
                    put("sender", record.sender)
                    put("recipients", record.recipients)
                    put("date_ms", record.dateMs)
                    put("body", record.body)
                    put("has_attachments", if (record.hasAttachments) 1 else 0)
                    put("attachment_names", record.attachmentNames)
                }
                db.insertWithOnConflict("mail", null, values, SQLiteDatabase.CONFLICT_REPLACE)
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun search(
        query: String,
        target: String = AlertRule.TARGET_ANY,
        attachmentOnly: Boolean = false,
        previewLimit: Int = 60
    ): SearchSummary {
        val where = whereClause(query, target, attachmentOnly)
        val total = db.rawQuery(
            "SELECT COUNT(*) FROM mail ${where.first}",
            where.second
        ).use { cursor ->
            cursor.moveToFirst()
            cursor.getInt(0)
        }

        val sql = """
            SELECT folder, uid, subject, sender, recipients, date_ms, has_attachments, attachment_names
            FROM mail
            ${where.first}
            ORDER BY date_ms DESC
            LIMIT $previewLimit
        """.trimIndent()

        val preview = db.rawQuery(sql, where.second).use { cursor ->
            val result = mutableListOf<MailRecord>()
            while (cursor.moveToNext()) {
                result += MailRecord(
                    folder = cursor.getString(0),
                    uid = cursor.getLong(1),
                    subject = cursor.getString(2),
                    sender = cursor.getString(3),
                    recipients = cursor.getString(4),
                    dateMs = cursor.getLong(5),
                    body = "",
                    hasAttachments = cursor.getInt(6) == 1,
                    attachmentNames = cursor.getString(7)
                )
            }
            result
        }
        return SearchSummary(total, preview)
    }

    fun countMatches(
        query: String,
        target: String = AlertRule.TARGET_ANY,
        attachmentOnly: Boolean = false
    ): Int {
        val where = whereClause(query, target, attachmentOnly)
        return db.rawQuery(
            "SELECT COUNT(*) FROM mail ${where.first}",
            where.second
        ).use { cursor ->
            cursor.moveToFirst()
            cursor.getInt(0)
        }
    }

    fun matchingKeys(
        query: String,
        target: String = AlertRule.TARGET_ANY,
        attachmentOnly: Boolean = false
    ): List<MailKey> {
        val where = whereClause(query, target, attachmentOnly)
        return db.rawQuery(
            "SELECT folder, uid FROM mail ${where.first} ORDER BY date_ms DESC",
            where.second
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    add(MailKey(cursor.getString(0), cursor.getLong(1)))
                }
            }
        }
    }

    fun deleteKeys(keys: List<MailKey>) {
        db.beginTransaction()
        try {
            keys.forEach { key ->
                db.delete("mail", "folder = ? AND uid = ?", arrayOf(key.folder, key.uid.toString()))
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun size(): Int =
        db.rawQuery("SELECT COUNT(*) FROM mail", null).use {
            it.moveToFirst()
            it.getInt(0)
        }

    fun clear() {
        db.execSQL("DELETE FROM mail")
        runCatching { db.execSQL("VACUUM") }
    }

    private fun whereClause(
        query: String,
        target: String,
        attachmentOnly: Boolean
    ): Pair<String, Array<String>?> {
        val q = query.trim()
        val clauses = mutableListOf<String>()
        val args = mutableListOf<String>()

        if (q.isNotBlank()) {
            val like = "%${q.replace("%", "\\%").replace("_", "\\_")}%"
            when (target) {
                AlertRule.TARGET_SENDER -> {
                    clauses += "sender LIKE ? ESCAPE '\\'"
                    args += like
                }
                AlertRule.TARGET_SUBJECT -> {
                    clauses += "subject LIKE ? ESCAPE '\\'"
                    args += like
                }
                AlertRule.TARGET_BODY -> {
                    clauses += "body LIKE ? ESCAPE '\\'"
                    args += like
                }
                AlertRule.TARGET_ATTACHMENT -> {
                    clauses += "attachment_names LIKE ? ESCAPE '\\'"
                    args += like
                }
                else -> {
                    clauses += """
                        (
                            subject LIKE ? ESCAPE '\\'
                            OR sender LIKE ? ESCAPE '\\'
                            OR recipients LIKE ? ESCAPE '\\'
                            OR body LIKE ? ESCAPE '\\'
                            OR attachment_names LIKE ? ESCAPE '\\'
                        )
                    """.trimIndent()
                    repeat(5) { args += like }
                }
            }
        }

        if (attachmentOnly) clauses += "has_attachments = 1"

        if (clauses.isEmpty()) return "" to null
        return "WHERE ${clauses.joinToString(" AND ")}" to args.toTypedArray()
    }
}
