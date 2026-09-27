package com.byso.yahoomailsearch

import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URLEncoder
import java.net.URL

data class RemoteFile(val bytes: ByteArray, val sha: String)
data class GitHubEntry(val name: String, val path: String, val type: String)
data class ArchiveConfig(val salt: ByteArray, val iterations: Int)

class GitHubStore(
    private val token: String,
    private val owner: String = "helpsomeoneonline-hub",
    private val repo: String = "Yahoo-Mail-Data",
    private val branch: String = "main"
) {
    fun testAccess() {
        val file = getFile("README.md")
        require(file != null) { "Could not read the private Yahoo-Mail-Data repository." }
    }

    fun ensureConfig(): ArchiveConfig {
        val existing = getFile("config.json")
        if (existing != null) {
            val json = JSONObject(existing.bytes.toString(Charsets.UTF_8))
            return ArchiveConfig(
                salt = Base64.decode(json.getString("salt"), Base64.NO_WRAP),
                iterations = json.optInt("iterations", 210_000)
            )
        }

        val salt = CryptoVault.randomSalt()
        val iterations = 210_000
        val json = JSONObject()
            .put("formatVersion", 1)
            .put("encryption", "AES-256-GCM")
            .put("kdf", "PBKDF2WithHmacSHA256")
            .put("iterations", iterations)
            .put("salt", Base64.encodeToString(salt, Base64.NO_WRAP))
            .put("note", "Salt is not secret. Mail content and sync state are encrypted.")
        putFile("config.json", json.toString(2).toByteArray(), "Initialize encrypted archive config")
        return ArchiveConfig(salt, iterations)
    }

    fun getFile(path: String): RemoteFile? {
        val response = request("GET", contentUrl(path) + "?ref=" + encode(branch), null)
        if (response.first == 404) return null
        checkSuccess(response.first, response.second)
        val json = JSONObject(response.second)
        val encoded = json.getString("content").replace("\n", "")
        return RemoteFile(
            Base64.decode(encoded, Base64.DEFAULT),
            json.getString("sha")
        )
    }

    fun putFile(path: String, bytes: ByteArray, message: String) {
        val current = getFile(path)
        val json = JSONObject()
            .put("message", message)
            .put("branch", branch)
            .put("content", Base64.encodeToString(bytes, Base64.NO_WRAP))
        current?.let { json.put("sha", it.sha) }
        val response = request("PUT", contentUrl(path), json.toString())
        checkSuccess(response.first, response.second)
    }

    fun listDirectory(path: String): List<GitHubEntry> {
        val response = request("GET", contentUrl(path) + "?ref=" + encode(branch), null)
        if (response.first == 404) return emptyList()
        checkSuccess(response.first, response.second)
        val array = JSONArray(response.second)
        return (0 until array.length()).map { index ->
            val item = array.getJSONObject(index)
            GitHubEntry(
                name = item.getString("name"),
                path = item.getString("path"),
                type = item.getString("type")
            )
        }
    }

    private fun contentUrl(path: String): String =
        "https://api.github.com/repos/$owner/$repo/contents/${encodePath(path)}"

    private fun encodePath(path: String): String =
        path.split("/").joinToString("/") { encode(it) }

    private fun encode(value: String): String =
        URLEncoder.encode(value, "UTF-8").replace("+", "%20")

    private fun request(method: String, url: String, body: String?): Pair<Int, String> {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 30_000
            readTimeout = 90_000
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("Authorization", "Bearer $token")
            setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
            setRequestProperty("User-Agent", "Yahoo-Mail-Search-Android")
            if (body != null) {
                doOutput = true
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
            }
        }
        if (body != null) {
            connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
        }

        val code = connection.responseCode
        val stream = if (code in 200..299) connection.inputStream else connection.errorStream
        val text = stream?.let {
            BufferedReader(InputStreamReader(it)).use { reader -> reader.readText() }
        }.orEmpty()
        connection.disconnect()
        return code to text
    }

    private fun checkSuccess(code: Int, body: String) {
        if (code !in 200..299) {
            val message = runCatching { JSONObject(body).optString("message") }.getOrDefault("")
            error("GitHub request failed ($code): ${message.ifBlank { body.take(250) }}")
        }
    }
}
