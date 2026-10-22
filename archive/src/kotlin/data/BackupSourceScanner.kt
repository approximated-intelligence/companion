package de.perigon.companion.archive.data

import android.content.Context
import android.util.Log
import androidx.core.net.toUri
import dagger.hilt.android.qualifiers.ApplicationContext
import de.perigon.companion.core.prefs.FolderGrants
import de.perigon.companion.core.prefs.FolderKeys
import de.perigon.companion.core.saf.ScannedFile
import de.perigon.companion.core.saf.hasPersistedReadGrant
import de.perigon.companion.core.saf.isDcimTree
import de.perigon.companion.core.saf.openInputStream
import de.perigon.companion.core.saf.walkDocumentTree
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "BackupSourceScanner"

data class BackupScannedFile(
    val path: String,
    val uri: String,
    val mtime: Long,
    val size: Long,
    val sha256: String,
)

/**
 * A scan's output. [files] are the ones that hashed successfully; [failures]
 * are files the scan touched but could not include, in walk order — some
 * skipped before hashing (unknown size), others during it (unreadable /
 * un-indexed). Failures are returned rather than logged-and-dropped so the
 * caller can surface them: un-indexed media (a .nomedia folder, a
 * not-yet-scanned file) would otherwise vanish from the backup without a
 * trace.
 */
data class ScanResult(
    val files: List<BackupScannedFile>,
    val failures: List<HashFailure>,
    /** Folders walked (enabled rows). Zero means no sources configured. */
    val enabledFolders: Int = 0,
    /** True when a folder row exists but has no usable SAF grant. */
    val missingDcimGrant: Boolean = false,
    /** URI from FolderGrants, for diagnostics. Null when not configured. */
    val dcimGrantUri: String? = null,
    /**
     * Files the walk saw but could not hash (unindexed by MediaStore, missing
     * ACCESS_MEDIA_LOCATION, unreadable). This is normally equal to
     * failures.size minus pre-hash failures — reported explicitly so callers
     * can distinguish "DCIM genuinely empty" from "walked N files, all failed".
     */
    val unreadable: Int = 0,
)

/**
 * Scans backup source folders via SAF.
 * DCIM is the single default folder — PostMedia lives under DCIM/PostMedia/
 * so it's included automatically. Custom SAF folders can be added by the user.
 *
 * Hashing is batched through [FileHasher]: one cache lookup pass for the whole
 * scan, only misses (new/changed files) are read and hashed. [onProgress]
 * relays FileHasher's throttled per-miss progress to the caller.
 *
 * Empty files (size 0, e.g. .nomedia) are valid backup candidates. Files the
 * SAF provider reports no size for are recorded as [HashFailure]s and retried
 * on the next scan, as are files that cannot be opened/hashed.
 */
@Singleton
class BackupSourceScanner @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val backupFolderDao: BackupFolderDao,
    private val folderGrants: FolderGrants,
    private val hasher: FileHasher,
) {
    companion object {
        val DEFAULT_FOLDERS = listOf(
            "DCIM" to "DCIM",
        )

        // Sentinel size for files the SAF provider reported no size for. Not a
        // real hash key — such files never enter [hashAllOrCached], they are
        // recorded as failures before the batch starts. Distinct from 0 (a
        // genuinely empty file, which IS a valid backup candidate).
        private const val UNKNOWN_SIZE = -1L
    }

    suspend fun scan(
        onProgress: suspend (hashed: Int, misses: Int, current: HashKey) -> Unit = { _, _, _ -> },
    ): ScanResult {
        seedDefaultsIfEmpty()

        val enabled = backupFolderDao.getEnabled()
        if (enabled.isEmpty()) {
            Log.w(TAG, "scan: no enabled source folders — nothing to scan")
            return ScanResult(emptyList(), emptyList(), enabledFolders = 0)
        }
        Log.i(TAG, "scan: ${enabled.size} enabled folder(s): ${enabled.map { it.displayName }}")

        var missingDcim = false
        var dcimUriStr: String? = null
        val raw = mutableListOf<ScannedFile>()

        for (folder in enabled) {
            when {
                folder.isDefault && folder.uri == "DCIM" -> {
                    val (files, missing, uri) = walkDcim()
                    missingDcim = missingDcim || missing
                    dcimUriStr = uri
                    raw += files
                }
                !folder.isDefault -> raw += walkCustom(folder.uri, folder.displayName)
            }
        }

        Log.i(TAG, "scan: walked ${enabled.size} folder(s), found ${raw.size} raw file(s)")
        // Pre-hash skips go straight into the failure list; the hash batch
        // only sees files whose size the provider actually reported.
        val preFailures = mutableListOf<HashFailure>()
        // Deduplicate on the content-identity key; keep the first URI seen.
        val byKey = LinkedHashMap<HashKey, ScannedFile>(raw.size)
        for (sf in raw) {
            val size = sf.size
            if (size == null) {
                preFailures += HashFailure(
                    key = HashKey(sf.path, sf.mtime, UNKNOWN_SIZE),
                    cause = IllegalStateException("size not reported by SAF provider"),
                )
                continue
            }
            byKey.putIfAbsent(HashKey(sf.path, sf.mtime, size), sf)
        }

        val batch = hasher.hashAllOrCached(byKey.keys.toList(), onProgress) { key ->
            openInputStream(context, byKey.getValue(key).uri.toUri())
                ?: error("openInputStream returned null")
        }

        val allFailures = preFailures + batch.failures
        if (allFailures.isNotEmpty()) {
            Log.w(TAG, "scan: ${allFailures.size} file(s) could not be hashed:")
            allFailures.forEach { f ->
                Log.w(TAG, "  skip: ${f.key.path} — ${f.cause.javaClass.simpleName}: ${f.cause.message}")
            }
        }

        val finalFiles = byKey.mapNotNull { (key, sf) ->
            batch.hashes[key]?.let { sha ->
                BackupScannedFile(
                    path = sf.path, uri = sf.uri,
                    mtime = key.mtime, size = key.size, sha256 = sha,
                )
            }
        }

        val unreadable = byKey.size - finalFiles.size
        Log.i(TAG, "scan: ${finalFiles.size} usable, $unreadable unreadable, ${allFailures.size} total failure(s)")

        return ScanResult(
            files = finalFiles,
            failures = allFailures,
            enabledFolders = enabled.size,
            missingDcimGrant = missingDcim,
            dcimGrantUri = dcimUriStr,
            unreadable = unreadable,
        )
    }

    private suspend fun seedDefaultsIfEmpty() {
        val enabled = backupFolderDao.getEnabled()
        val hasEnabledDcim = enabled.any { it.isDefault && it.uri == "DCIM" }
        if (hasEnabledDcim) return
        Log.i(TAG, "seeding DCIM default source folder")
        backupFolderDao.insertIgnore(
            BackupFolderEntity(
                uri = "DCIM",
                displayName = "DCIM",
                includeInBackup = true,
                isDefault = true,
                addedAt = System.currentTimeMillis(),
            ),
        )
    }

    private data class DcimWalk(
        val files: List<ScannedFile>,
        val missingGrant: Boolean,
        val uriStr: String?,
    )

    private fun walkDcim(): DcimWalk {
        val uriStr = folderGrants.uri(FolderKeys.DCIM)
        if (uriStr == null) {
            Log.w(TAG, "walkDcim: no DCIM grant in FolderGrants")
            return DcimWalk(emptyList(), missingGrant = true, uriStr = null)
        }
        val uri = uriStr.toUri()
        if (!isDcimTree(uri)) {
            Log.w(TAG, "walkDcim: stored DCIM URI is not a DCIM tree: $uri")
        }
        if (!hasPersistedReadGrant(context, uri)) {
            Log.w(TAG, "walkDcim: DCIM grant stale — $uri not in persistedUriPermissions")
            return DcimWalk(emptyList(), missingGrant = true, uriStr = uriStr)
        }
        val files = walkDocumentTree(context, uri, "DCIM")
        Log.i(TAG, "walkDcim: $uri -> ${files.size} file(s)")
        return DcimWalk(files, missingGrant = false, uriStr = uriStr)
    }

    private fun walkCustom(uri: String, displayName: String): List<ScannedFile> {
        val files = walkDocumentTree(context, uri.toUri(), displayName)
        Log.i(TAG, "walkCustom '$displayName' ($uri) -> ${files.size} file(s)")
        return files
    }
}
