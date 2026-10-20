package de.perigon.companion.backup.data

import android.content.Context
import androidx.core.net.toUri
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import de.perigon.companion.core.prefs.FolderGrants
import de.perigon.companion.core.prefs.FolderKeys
import de.perigon.companion.backup.data.FileHasher
import de.perigon.companion.backup.data.HashKey
import de.perigon.companion.core.saf.ScannedFile
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
        val enabled = backupFolderDao.getEnabled()

        val raw = enabled.flatMap { folder ->
            when {
                folder.isDefault && folder.uri == "DCIM" -> walkDcim()
                !folder.isDefault -> walkCustom(folder.uri, folder.displayName)
                else -> emptyList()
            }
        }

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
            Log.w(TAG, "dropped ${allFailures.size} file(s) from scan " +
                "(unreadable or unknown size)")
        }

        return ScanResult(
            files = byKey.mapNotNull { (key, sf) ->
                batch.hashes[key]?.let { sha ->
                    BackupScannedFile(
                        path = sf.path, uri = sf.uri,
                        mtime = key.mtime, size = key.size, sha256 = sha,
                    )
                }
            },
            failures = allFailures,
        )
    }

    private fun walkDcim(): List<ScannedFile> {
        val dcimUri = folderGrants.uri(FolderKeys.DCIM) ?: return emptyList()
        return walkDocumentTree(context, dcimUri.toUri(), "DCIM")
    }

    private fun walkCustom(uri: String, displayName: String): List<ScannedFile> =
        walkDocumentTree(context, uri.toUri(), displayName)
}
