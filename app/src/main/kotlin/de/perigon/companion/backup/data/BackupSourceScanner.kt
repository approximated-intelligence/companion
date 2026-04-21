package de.perigon.companion.backup.data

import android.content.Context
import android.net.Uri
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import de.perigon.companion.core.prefs.AppPrefs
import de.perigon.companion.util.FileHasher
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
 * Every scanned file is hashed (via [FileHasher] cache). Files that cannot be
 * opened/hashed are dropped silently — they'll be retried on the next scan.
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

    suspend fun scan(): List<BackupScannedFile> {
        val results = mutableListOf<BackupScannedFile>()
        val enabled = backupFolderDao.getEnabled()
        var dropped = 0

        for (folder in enabled) {
            val raw = when {
                folder.isDefault && folder.uri == "DCIM" -> walkDcim()
                !folder.isDefault -> walkCustom(folder.uri, folder.displayName)
                else -> emptyList()
            }
            for (sf in raw) {
                if (sf.size == 0L) continue
                val sha = hasher.hashOrCached(sf.path, sf.mtime, sf.size) {
                    context.contentResolver.openInputStream(Uri.parse(sf.uri))
                        ?: error("openInputStream returned null")
                }
                if (sha == null) {
                    dropped++
                    continue
                }
                results += BackupScannedFile(
                    path = sf.path, uri = sf.uri,
                    mtime = sf.mtime, size = sf.size, sha256 = sha,
                )
            }
        }

        if (dropped > 0) Log.w(TAG, "dropped $dropped unreadable file(s) from scan")
        return results
    }

    private fun walkDcim(): List<de.perigon.companion.util.saf.ScannedFile> {
        val dcimUri = appPrefs.dcimTreeUri() ?: return emptyList()
        return walkDocumentTree(context, Uri.parse(dcimUri), "DCIM")
    }

    private fun walkCustom(uri: String, displayName: String): List<de.perigon.companion.util.saf.ScannedFile> =
        walkDocumentTree(context, Uri.parse(uri), displayName)
}
