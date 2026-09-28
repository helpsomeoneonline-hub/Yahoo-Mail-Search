package com.byso.yahoomailsearch

import javax.crypto.AEADBadTagException
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
        val archiveRanges = loadArchiveRanges()

        if (archiveRanges.isNotEmpty()) {
            verifyArchiveKey(key)
        }

        val state = loadStateLenient(key, onProgress)
        val yahoo = YahooImapClient(email, yahooAppPassword)

        yahoo.sync(
            state = state,
            archivedRangesByFolderId = archiveRanges,
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
        verifyArchiveKey(key)
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
                val plain = CryptoVault.gunzip(
                    CryptoVault.decrypt(key, remote.bytes)
                )
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

    private fun loadArchiveRanges(): Map<String, List<LongRange>> {
        val result = linkedMapOf<String, MutableList<LongRange>>()
        val folders = github.listDirectory("archive")
            .filter { it.type == "dir" }

        folders.forEach { folder ->
            val ranges = result.getOrPut(folder.name) { mutableListOf() }
            github.listDirectory(folder.path)
                .filter { it.type == "file" && it.name.endsWith(".enc") }
                .forEach { entry ->
                    val match = RANGE_REGEX.matchEntire(entry.name)
                        ?: return@forEach
                    val first = match.groupValues[1].toLongOrNull()
                        ?: return@forEach
                    val last = match.groupValues[2].toLongOrNull()
                        ?: return@forEach
                    if (last >= first) {
                        ranges += first..last
                    }
                }
        }

        return result.mapValues { (_, ranges) ->
            ranges.sortedBy { it.first }
        }
    }

    private fun verifyArchiveKey(key: SecretKey) {
        val firstFile = github.listDirectory("archive")
            .asSequence()
            .filter { it.type == "dir" }
            .flatMap { folder ->
                github.listDirectory(folder.path)
                    .asSequence()
                    .filter { it.type == "file" && it.name.endsWith(".enc") }
            }
            .firstOrNull()
            ?: return

        val remote = github.getFile(firstFile.path) ?: return

        try {
            val plain = CryptoVault.gunzip(
                CryptoVault.decrypt(key, remote.bytes)
            )
            MailRecord.listFromJson(plain)
        } catch (error: AEADBadTagException) {
            throw IllegalArgumentException(
                "The archive passphrase does not match the existing encrypted GitHub archive."
            )
        } catch (error: IllegalArgumentException) {
            if (error.message?.contains("Unsupported encrypted archive version") == true) {
                throw IllegalArgumentException(
                    "An existing GitHub archive file uses an unsupported encryption format."
                )
            }
            throw error
        }
    }

    private fun loadStateLenient(
        key: SecretKey,
        onProgress: (SyncProgress) -> Unit
    ): SyncState {
        val remote = github.getFile("state.enc") ?: return SyncState()

        if (remote.bytes.isEmpty() || remote.bytes[0].toInt() != 1) {
            onProgress(
                SyncProgress(
                    stage = "Recovering from an old sync checkpoint..."
                )
            )
            return SyncState()
        }

        return try {
            val decrypted = CryptoVault.decrypt(key, remote.bytes)
            SyncState.fromJsonBytes(CryptoVault.gunzip(decrypted))
        } catch (error: AEADBadTagException) {
            throw IllegalArgumentException(
                "The archive passphrase does not match the existing encrypted GitHub archive."
            )
        } catch (_: Throwable) {
            onProgress(
                SyncProgress(
                    stage = "Checkpoint was damaged. Rebuilding safely from archive files..."
                )
            )
            SyncState()
        }
    }

    private fun saveState(key: SecretKey, state: SyncState) {
        val packed = CryptoVault.encrypt(
            key,
            CryptoVault.gzip(state.toJsonBytes())
        )
        github.putFile(
            "state.enc",
            packed,
            "Update Yahoo sync checkpoint"
        )
    }

    companion object {
        private val RANGE_REGEX = Regex("""(\d+)-(\d+)\.enc""")
    }
}
