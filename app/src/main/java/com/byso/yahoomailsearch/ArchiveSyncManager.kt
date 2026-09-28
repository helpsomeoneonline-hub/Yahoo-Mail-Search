package com.byso.yahoomailsearch

import android.util.Base64
import javax.crypto.SecretKey

class ArchiveSyncManager(
    private val email: String,
    private val yahooAppPassword: String,
    private val githubToken: String,
    private val archivePassphrase: String,
    private val cache: CacheDb
) {
    private val github = GitHubStore(githubToken)

    fun testConnections() {
        YahooImapClient(email, yahooAppPassword).testConnection()
        github.testAccess()
        github.ensureConfig()
    }

    fun sync(onProgress: (SyncProgress) -> Unit) {
        val key = archiveKey()
        val state = loadState(key)
        val yahoo = YahooImapClient(email, yahooAppPassword)

        yahoo.sync(
            state = state,
            onBatch = { folderName, _, records ->
                val folderId = CryptoVault.stableId(folderName)
                val firstUid = records.first().uid
                val lastUid = records.last().uid
                val path = "archive/$folderId/$firstUid-$lastUid.enc"
                                val compressed = CryptoVault.gzip(MailRecord.listToJson(records))
                val encrypted = CryptoVault.encrypt(key, compressed)
                github.putFile(
                    path,
                    encrypted,
                    "Archive $folderName UIDs $firstUid-$lastUid"
                )
                cache.upsert(records)
            },
            onCheckpoint = { folderName, uidValidity, lastUid ->
                state.folders[folderName] = FolderState(uidValidity, lastUid)
                saveState(key, state)
            },
            onProgress = onProgress
        )
    }

    fun rebuildCache(onProgress: (String) -> Unit) {
        val key = archiveKey()
        cache.clear()
        val folders = github.listDirectory("archive").filter { it.type == "dir" }
        var fileNumber = 0

        folders.forEach { folder ->
            val files = github.listDirectory(folder.path)
                .filter { it.type == "file" && it.name.endsWith(".enc") }

            files.forEach { entry ->
                fileNumber++
                onProgress("Restoring search cache: file $fileNumber")
                val remote = github.getFile(entry.path) ?: return@forEach
                val plain = CryptoVault.gunzip(CryptoVault.decrypt(key, remote.bytes))
                cache.upsert(MailRecord.listFromJson(plain))
            }
        }
        onProgress("Search cache rebuilt: ${cache.size()} messages.")
    }

    private fun archiveKey(): SecretKey {
        require(archivePassphrase.length >= 8) {
            "Archive passphrase must be at least 8 characters."
        }
        val config = github.ensureConfig()
        return CryptoVault.deriveKey(
            archivePassphrase,
            config.salt,
            config.iterations
        )
    }

    private fun loadState(key: SecretKey): SyncState {
        val remote = github.getFile("state.enc") ?: return SyncState()
        val decrypted = CryptoVault.decrypt(key, remote.bytes)
        return SyncState.fromJsonBytes(CryptoVault.gunzip(decrypted))
    }

    private fun saveState(key: SecretKey, state: SyncState) {
        val packed = CryptoVault.encrypt(
            key,
            CryptoVault.gzip(state.toJsonBytes())
        )
        github.putFile("state.enc", packed, "Update Yahoo sync checkpoint")
    }
}
