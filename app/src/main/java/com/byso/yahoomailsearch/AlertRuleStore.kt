package com.byso.yahoomailsearch

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

data class AlertRule(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val query: String,
    val target: String = TARGET_ANY,
    val enabled: Boolean = true
) {
    fun matches(mail: MailRecord): Boolean {
        val needle = query.trim()
        if (needle.isBlank()) return false
        val text = when (target) {
            TARGET_SENDER -> mail.sender
            TARGET_SUBJECT -> mail.subject
            TARGET_BODY -> mail.body
            TARGET_ATTACHMENT -> mail.attachmentNames
            else -> listOf(
                mail.sender,
                mail.recipients,
                mail.subject,
                mail.body,
                mail.attachmentNames
            ).joinToString("\n")
        }
        return text.contains(needle, ignoreCase = true)
    }

    companion object {
        const val TARGET_ANY = "ANY"
        const val TARGET_SENDER = "SENDER"
        const val TARGET_SUBJECT = "SUBJECT"
        const val TARGET_BODY = "BODY"
        const val TARGET_ATTACHMENT = "ATTACHMENT"

        fun fromJson(json: JSONObject) = AlertRule(
            id = json.optString("id").ifBlank { UUID.randomUUID().toString() },
            name = json.optString("name"),
            query = json.optString("query"),
            target = json.optString("target", TARGET_ANY),
            enabled = json.optBoolean("enabled", true)
        )
    }

    fun toJson(): JSONObject = JSONObject()
        .put("id", id)
        .put("name", name)
        .put("query", query)
        .put("target", target)
        .put("enabled", enabled)
}

class AlertRuleStore(context: Context) {
    private val prefs = context.getSharedPreferences("alert-rules", Context.MODE_PRIVATE)

    fun all(): List<AlertRule> {
        val raw = prefs.getString(KEY_RULES, "[]") ?: "[]"
        return runCatching {
            val array = JSONArray(raw)
            (0 until array.length()).map {
                AlertRule.fromJson(array.getJSONObject(it))
            }
        }.getOrDefault(emptyList())
    }

    fun save(rule: AlertRule) {
        val items = all().toMutableList()
        val index = items.indexOfFirst { it.id == rule.id }
        if (index >= 0) items[index] = rule else items.add(rule)
        write(items)
    }

    fun delete(id: String) {
        write(all().filterNot { it.id == id })
    }

    fun setEnabled(id: String, enabled: Boolean) {
        write(all().map { if (it.id == id) it.copy(enabled = enabled) else it })
    }

    private fun write(items: List<AlertRule>) {
        val array = JSONArray()
        items.forEach { array.put(it.toJson()) }
        prefs.edit().putString(KEY_RULES, array.toString()).apply()
    }

    companion object {
        private const val KEY_RULES = "rules"
    }
}
