package de.perigon.companion.backup.data

import android.content.Context
import android.net.Uri
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import de.perigon.companion.core.prefs.AppPrefs
import de.perigon.companion.util.FileHasher
import de.perigon.companion.util.HashKey
import de.perigon.companion.util.saf.ScannedFile
import de.perigon.companion.util.saf.walkDocumentTree
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
 * Scans backup source folders via SAF.
 * DCIM is the single default folder — PostMedia lives under DCIM/PostMedia/
 * so it's included automatically. Custom SAF folders can be added by the user.
 *
 * Hashing is batched through [FileHasher]: one cache lookup pass for the whole
 * scan, only misses (new/changed files) are read and hashed. [onProgress]
 * relays FileHasher's throttled per-miss progress to the caller.
 *
 * Empty files (size 0, e.g. .nomedia) are valid backup candidates. Files with
 * unknown size (provider reported none) are dropped — planning them could
 * record a wrong content length — and retried on the next scan, as are files
 * that cannot be opened/hashed.
 */
@Singleton
class BackupSourceScanner @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val backupFolderDao: BackupFolderDao,
    private val appPrefs: AppPrefs,
    private val hasher: FileHasher,
) {
    companion object {
        val DEFAULT_FOLDERS = listOf(
            "DCIM" to "DCIM",
        )
    }

    suspend fun scan(
        onProgress: suspend (hashed: Int, misses: Int, current: HashKey) -> Unit = { _, _, _ -> },
    ): List<BackupScannedFile> {
        val enabled = backupFolderDao.getEnabled()

        val raw = enabled.flatMap { folder ->
            when {
                folder.isDefault && folder.uri == "DCIM" -> walkDcim()
                !folder.isDefault -> walkCustom(folder.uri, folder.displayName)
                else -> emptyList()
            }
        }

        // Deduplicate on the content-identity key; keep the first URI seen.
        // Files without a known size never enter the key set.
        var unknownSize = 0
        val byKey = LinkedHashMap<HashKey, ScannedFile>(raw.size)
        for (sf in raw) {
            val size = sf.size
            if (size == null) {
                unknownSize++
                continue
            }
            byKey.putIfAbsent(HashKey(sf.path, sf.mtime, size), sf)
        }
        if (unknownSize > 0) Log.w(TAG, "dropped $unknownSize file(s) with unknown size")

        val hashes = hasher.hashAllOrCached(byKey.keys.toList(), onProgress) { key ->
            context.contentResolver.openInputStream(Uri.parse(byKey.getValue(key).uri))
                ?: error("openInputStream returned null")
        }

        val dropped = byKey.size - hashes.size
        if (dropped > 0) Log.w(TAG, "dropped $dropped unreadable file(s) from scan")

        return byKey.mapNotNull { (key, sf) ->
            hashes[key]?.let { sha ->
                BackupScannedFile(
                    path = sf.path, uri = sf.uri,
                    mtime = key.mtime, size = key.size, sha256 = sha,
                )
            }
        }
    }

    private fun walkDcim(): List<ScannedFile> {
        val dcimUri = appPrefs.dcimTreeUri() ?: return emptyList()
        return walkDocumentTree(context, Uri.parse(dcimUri), "DCIM")
    }

    private fun walkCustom(uri: String, displayName: String): List<ScannedFile> =
        walkDocumentTree(context, Uri.parse(uri), displayName)
}
