package de.perigon.companion.backup.data

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import androidx.documentfile.provider.DocumentFile
import dagger.hilt.android.qualifiers.ApplicationContext
import de.perigon.companion.posts.data.PostMediaFileStore
import javax.inject.Inject
import javax.inject.Singleton

data class BackupScannedFile(
    val path: String,
    val uri: String,
    val mtime: Long,
    val size: Long,
)

@Singleton
class BackupSourceScanner @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val backupFolderDao: BackupFolderDao,
    private val postMediaFileStore: PostMediaFileStore,
) {
    companion object {
        /** MediaStore path patterns for default folders. URI field stores the pattern. */
        val DEFAULT_FOLDERS = listOf(
            "DCIM/Camera/" to "DCIM/Camera",
            "DCIM/Consolidated/" to "DCIM/Consolidated",
            "PostMedia/" to "PostMedia",
        )
    }

    suspend fun scan(): List<BackupScannedFile> {
        val results = mutableListOf<BackupScannedFile>()
        val enabled = backupFolderDao.getEnabled()

        for (folder in enabled) {
            if (folder.isDefault) {
                if (folder.uri == "PostMedia/") {
                    results += scanPostMedia()
                } else {
                    results += scanMediaStorePath("${folder.uri}%")
                }
            } else {
                val treeUri = Uri.parse(folder.uri)
                val docFile = DocumentFile.fromTreeUri(context, treeUri) ?: continue
                results += scanDocumentTree(docFile, "${folder.displayName}/")
            }
        }

        return results
    }

    private fun scanPostMedia(): List<BackupScannedFile> {
        return postMediaFileStore.listFiles().map { info ->
            BackupScannedFile(
                path = "PostMedia/${info.path}",
                uri = info.uri,
                mtime = info.mtime,
                size = info.size,
            )
        }
    }

    private fun scanMediaStorePath(pathPattern: String): List<BackupScannedFile> {
        val results = mutableListOf<BackupScannedFile>()
        val collection = MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL)
        val projection = arrayOf(
            MediaStore.Files.FileColumns._ID,
            MediaStore.Files.FileColumns.RELATIVE_PATH,
            MediaStore.Files.FileColumns.DISPLAY_NAME,
            MediaStore.Files.FileColumns.DATE_MODIFIED,
            MediaStore.Files.FileColumns.SIZE,
        )
        context.contentResolver.query(
            collection, projection,
            "${MediaStore.Files.FileColumns.RELATIVE_PATH} LIKE ?",
            arrayOf(pathPattern),
            "${MediaStore.Files.FileColumns.DATE_MODIFIED} ASC",
        )?.use { cursor ->
            val idCol = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns._ID)
            val relCol = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.RELATIVE_PATH)
            val nameCol = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DISPLAY_NAME)
            val mtimeCol = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DATE_MODIFIED)
            val sizeCol = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.SIZE)
            while (cursor.moveToNext()) {
                val id = cursor.getLong(idCol)
                val name = cursor.getString(nameCol) ?: continue
                if (name == ".nomedia") continue
                results += BackupScannedFile(
                    path = cursor.getString(relCol) + name,
                    uri = ContentUris.withAppendedId(collection, id).toString(),
                    mtime = cursor.getLong(mtimeCol) * 1000L,
                    size = cursor.getLong(sizeCol),
                )
            }
        }
        return results
    }

    private fun scanDocumentTree(
        dir: DocumentFile,
        prefix: String,
    ): List<BackupScannedFile> {
        val results = mutableListOf<BackupScannedFile>()
        dir.listFiles().forEach { file ->
            if (file.isDirectory) {
                val dirName = file.name ?: return@forEach
                results += scanDocumentTree(file, "$prefix$dirName/")
                return@forEach
            }
            val mime = file.type ?: return@forEach
            val name = file.name ?: return@forEach
            results += BackupScannedFile(
                path = "$prefix$name",
                uri = file.uri.toString(),
                mtime = file.lastModified(),
                size = file.length(),
            )
        }
        return results
    }
}
