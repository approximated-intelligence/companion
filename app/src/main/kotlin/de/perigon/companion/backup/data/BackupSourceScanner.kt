package de.perigon.companion.backup.data

import android.content.Context
import android.net.Uri
import dagger.hilt.android.qualifiers.ApplicationContext
import de.perigon.companion.core.prefs.AppPrefs
import de.perigon.companion.util.saf.walkDocumentTree
import javax.inject.Inject
import javax.inject.Singleton

data class BackupScannedFile(
    val path: String,
    val uri: String,
    val mtime: Long,
    val size: Long,
)

/**
 * Scans backup source folders via SAF.
 * DCIM is the single default folder — PostMedia lives under DCIM/PostMedia/
 * so it's included automatically. Custom SAF folders can be added by the user.
 */
@Singleton
class BackupSourceScanner @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val backupFolderDao: BackupFolderDao,
    private val appPrefs: AppPrefs,
) {
    companion object {
        val DEFAULT_FOLDERS = listOf(
            "DCIM" to "DCIM",
        )
    }

    suspend fun scan(): List<BackupScannedFile> {
        val results = mutableListOf<BackupScannedFile>()
        val enabled = backupFolderDao.getEnabled()

        for (folder in enabled) {
            if (folder.isDefault && folder.uri == "DCIM") {
                results += scanDcim()
            } else if (!folder.isDefault) {
                val treeUri = Uri.parse(folder.uri)
                results += scanSafTree(treeUri, folder.displayName)
            }
        }

        return results
    }

    private fun scanDcim(): List<BackupScannedFile> {
        val dcimUri = appPrefs.dcimTreeUri() ?: return emptyList()
        val treeUri = Uri.parse(dcimUri)
        return walkDocumentTree(context, treeUri, "DCIM").map { sf ->
            BackupScannedFile(
                path = sf.path,
                uri = sf.uri,
                mtime = sf.mtime,
                size = sf.size,
            )
        }
    }

    private fun scanSafTree(treeUri: Uri, displayName: String): List<BackupScannedFile> {
        return walkDocumentTree(context, treeUri, displayName).map { sf ->
            BackupScannedFile(
                path = sf.path,
                uri = sf.uri,
                mtime = sf.mtime,
                size = sf.size,
            )
        }
    }
}
