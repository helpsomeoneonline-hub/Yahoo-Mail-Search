package com.byso.yahoomailsearch

import java.io.EOFException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.io.IOException
import javax.mail.AuthenticationFailedException
import javax.mail.FolderClosedException
import javax.mail.MessagingException
import javax.mail.StoreClosedException
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
        YahooImapClient(
            email,
            yahooAppPassword,
            YahooImapClient.NORMAL_IMAP_HOST
        ).testConnection()
        github.testAccess()
        github.ensureConfig()
    }

    fun sync(
        mode: ArchiveSyncMode = ArchiveSyncMode.LIVE_INCREMENTAL,
        onProgress: (SyncProgress) -> Unit
    ) {
        var attempt = 0
        var latest = SyncProgress(
            stage = if (mode == ArchiveSyncMode.FULL_EXPORT) {
                "Preparing full Yahoo archive..."
            } else {
                "Preparing Yahoo live sync..."
            }
        )

        while (true) {
            try {
                syncOnce(mode) { progress ->
                    latest = progress
                    onProgress(progress)
                }
                return
            } catch (error: Throwable) {
                if (!isTransientYahooDisconnect(error) || attempt >= MAX_RECONNECTS) {
                    throw error
                }

                attempt++
                val waitSeconds = minOf(3 + attempt, 15)
                onProgress(
                    latest.copy(
                        stage =
                            "Connection interrupted. Reconnecting and resuming automatically " +
                                "($attempt/$MAX_RECONNECTS) in $waitSeconds seconds...",
                        complete = false
                    )
                )

                try {
                    Thread.sleep(waitSeconds * 1_000L)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    throw error
                }
            }
        }
    }

    private fun syncOnce(
        mode: ArchiveSyncMode,
        onProgress: (SyncProgress) -> Unit
    ) {
        val source = sourceFor(mode)
        val key = archiveKey()
        val archiveRanges = loadArchiveRanges(source.archiveRoot)

        if (archiveRanges.isNotEmpty()) {
            verifyArchiveKey(key, source.archiveRoot)
        }

        val state = loadStateLenient(key, source.statePath, onProgress)
        val yahoo = YahooImapClient(
            email,
            yahooAppPassword,
            source.imapHost
        )

        onProgress(
            SyncProgress(
                stage = if (mode == ArchiveSyncMode.FULL_EXPORT) {
                    "Connecting to Yahoo full-history export server..."
                } else {
                    "Connecting to Yahoo live mail server..."
                }
            )
        )

        yahoo.sync(
            state = state,
            archivedRangesByFolderId = archiveRanges,
            onBatch = { folderName, _, records ->
                val folderId = CryptoVault.stableId(folderName)
                val firstUid = records.first().uid
                val lastUid = records.last().uid
                val path =
                    "${source.archiveRoot}/$folderId/$firstUid-$lastUid.enc"

                val compressed =
                    CryptoVault.gzip(MailRecord.listToJson(records))
                val encrypted = CryptoVault.encrypt(key, compressed)
                github.putFile(
                    path,
                    encrypted,
                    "Archive ${source.label} $folderName UIDs $firstUid-$lastUid"
                )
                cache.upsert(records)
            },
            onCheckpoint = { folderName, uidValidity, lastUid ->
                state.folders[folderName] =
                    FolderState(uidValidity, lastUid)
                saveState(key, source.statePath, state)
            },
            onProgress = onProgress
        )
    }

    private fun isTransientYahooDisconnect(error: Throwable): Boolean {
        var current: Throwable? = error
        while (current != null) {
            when (current) {
                is AuthenticationFailedException -> return false
                is FolderClosedException,
                is StoreClosedException,
                is UnknownHostException,
                is SocketTimeoutException,
                is SocketException,
                is EOFException,
                is IOException -> return true
                is MessagingException -> {
                    val message = current.message.orEmpty().lowercase()
                    if (
                        message.contains("folderclosed") ||
                        message.contains("folder closed") ||
                        message.contains("store closed") ||
                        message.contains("connection") ||
                        message.contains("socket") ||
                        message.contains("timeout") ||
                        message.contains("bye") ||
                        message.contains("reset") ||
                        message.contains("broken pipe") ||
                        message.contains("eof")
                    ) {
                        return true
                    }
                }
            }
            current = current.cause
        }
        return false
    }

    fun rebuildCache(onProgress: (String) -> Unit) {
        val key = archiveKey()
        cache.clear()

        val roots = listOf(
            "archive",
            "archive_export",
            "archive_live"
        )

        var fileNumber = 0
        roots.forEach { root ->
            val folders = github.listDirectory(root)
                .filter { it.type == "dir" }

            if (folders.isNotEmpty()) {
                verifyArchiveKey(key, root)
            }

            folders.forEach { folder ->
                val files = github.listDirectory(folder.path)
                    .filter {
                        it.type == "file" &&
                            it.name.endsWith(".enc")
                    }

                files.forEach { entry ->
                    fileNumber++
                    onProgress(
                        "Restoring search cache: file $fileNumber"
                    )
                    val remote =
                        github.getFile(entry.path) ?: return@forEach
                    val plain = CryptoVault.gunzip(
                        CryptoVault.decrypt(key, remote.bytes)
                    )
                    cache.upsert(
                        MailRecord.listFromJson(plain)
                    )
                }
            }
        }

        onProgress(
            "Search cache rebuilt: ${cache.size()} messages."
        )
    }

    private fun sourceFor(mode: ArchiveSyncMode): SyncSource =
        when (mode) {
            ArchiveSyncMode.FULL_EXPORT ->
                SyncSource(
                    imapHost = YahooImapClient.EXPORT_IMAP_HOST,
                    archiveRoot = "archive_export",
                    statePath = "state_export.enc",
                    label = "FullExport"
                )

            ArchiveSyncMode.LIVE_INCREMENTAL ->
                SyncSource(
                    imapHost = YahooImapClient.NORMAL_IMAP_HOST,
                    archiveRoot = "archive_live",
                    statePath = "state_live.enc",
                    label = "Live"
                )
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

    private fun loadArchiveRanges(
        archiveRoot: String
    ): Map<String, List<LongRange>> {
        val result =
            linkedMapOf<String, MutableList<LongRange>>()

        github.listDirectory(archiveRoot)
            .filter { it.type == "dir" }
            .forEach { folder ->
                val ranges =
                    result.getOrPut(folder.name) {
                        mutableListOf()
                    }

                github.listDirectory(folder.path)
                    .filter {
                        it.type == "file" &&
                            it.name.endsWith(".enc")
                    }
                    .forEach { entry ->
                        val match =
                            RANGE_REGEX.matchEntire(entry.name)
                                ?: return@forEach
                        val first =
                            match.groupValues[1].toLongOrNull()
                                ?: return@forEach
                        val last =
                            match.groupValues[2].toLongOrNull()
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

    private fun verifyArchiveKey(
        key: SecretKey,
        archiveRoot: String
    ) {
        val firstFile =
            github.listDirectory(archiveRoot)
                .asSequence()
                .filter { it.type == "dir" }
                .flatMap { folder ->
                    github.listDirectory(folder.path)
                        .asSequence()
                        .filter {
                            it.type == "file" &&
                                it.name.endsWith(".enc")
                        }
                }
                .firstOrNull()
                ?: return

        val remote =
            github.getFile(firstFile.path) ?: return

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
            if (
                error.message?.contains(
                    "Unsupported encrypted archive version"
                ) == true
            ) {
                throw IllegalArgumentException(
                    "An existing GitHub archive file uses an unsupported encryption format."
                )
            }
            throw error
        }
    }

    private fun loadStateLenient(
        key: SecretKey,
        statePath: String,
        onProgress: (SyncProgress) -> Unit
    ): SyncState {
        val remote =
            github.getFile(statePath) ?: return SyncState()

        if (
            remote.bytes.isEmpty() ||
            remote.bytes[0].toInt() != 1
        ) {
            onProgress(
                SyncProgress(
                    stage =
                        "Recovering from an old sync checkpoint..."
                )
            )
            return SyncState()
        }

        return try {
            val decrypted =
                CryptoVault.decrypt(key, remote.bytes)
            SyncState.fromJsonBytes(
                CryptoVault.gunzip(decrypted)
            )
        } catch (error: AEADBadTagException) {
            throw IllegalArgumentException(
                "The archive passphrase does not match the existing encrypted GitHub archive."
            )
        } catch (_: Throwable) {
            onProgress(
                SyncProgress(
                    stage =
                        "Checkpoint was damaged. Rebuilding safely from archive files..."
                )
            )
            SyncState()
        }
    }

    private fun saveState(
        key: SecretKey,
        statePath: String,
        state: SyncState
    ) {
        val packed = CryptoVault.encrypt(
            key,
            CryptoVault.gzip(state.toJsonBytes())
        )
        github.putFile(
            statePath,
            packed,
            "Update Yahoo sync checkpoint"
        )
    }

    private data class SyncSource(
        val imapHost: String,
        val archiveRoot: String,
        val statePath: String,
        val label: String
    )

    companion object {
        private const val MAX_RECONNECTS = 20
        private val RANGE_REGEX =
            Regex("""(\d+)-(\d+)\.enc""")
    }
}
