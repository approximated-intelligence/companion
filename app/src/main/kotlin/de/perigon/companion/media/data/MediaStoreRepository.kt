package de.perigon.companion.media.data

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Encapsulates all MediaStore queries so ViewModels never touch
 * ContentResolver directly.
 */
interface MediaStoreRepository {

    /** Resolve the SHA-256 of a backed-up file by its URI metadata. */
    suspend fun resolveBackedUpSha256(
        uri: Uri,
        findConfirmedSha256: suspend (path: String, mtime: Long) -> String?,
    ): String?

    /** Scan DCIM/Camera for files not yet in DCIM/Consolidated. */
    suspend fun collectPendingConsolidation(): List<PendingConsolidationItem>

    /** Query all display names in DCIM/Consolidated. */
    suspend fun queryConsolidatedNames(): Set<String>

    /** Query the RELATIVE_PATH for a content URI. Returns null if not resolvable. */
    suspend fun queryRelativePath(uri: Uri): String?

    /** Query the DISPLAY_NAME for a content URI. Returns null if not resolvable. */
    suspend fun queryDisplayName(uri: Uri): String?
}

data class PendingConsolidationItem(
    val sourceUri: String,
    val displayName: String,
    val mediaType: String,
)

@Singleton
class MediaStoreRepositoryImpl @Inject constructor(
    @param:ApplicationContext private val ctx: Context,
) : MediaStoreRepository {

    override suspend fun resolveBackedUpSha256(
        uri: Uri,
        findConfirmedSha256: suspend (path: String, mtime: Long) -> String?,
    ): String? = withContext(Dispatchers.IO) {
        val projection = arrayOf(
            MediaStore.MediaColumns.RELATIVE_PATH,
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.DATE_MODIFIED,
        )
        ctx.contentResolver.query(uri, projection, null, null, null)
            ?.use { cursor ->
                if (!cursor.moveToFirst()) return@withContext null
                val rel = cursor.getString(0) ?: return@withContext null
                val name = cursor.getString(1) ?: return@withContext null
                val mtime = cursor.getLong(2) * 1000L
                findConfirmedSha256(rel + name, mtime)
            }
    }

    override suspend fun collectPendingConsolidation(): List<PendingConsolidationItem> =
        withContext(Dispatchers.IO) {
            val existingNames = queryConsolidatedNames()
            scanMediaStore("DCIM/Camera/%", existingNames)
        }

    override suspend fun queryConsolidatedNames(): Set<String> =
        withContext(Dispatchers.IO) {
            val names = mutableSetOf<String>()
            val projection = arrayOf(MediaStore.MediaColumns.DISPLAY_NAME)
            val selection = "${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ?"
            val args = arrayOf("%DCIM/Consolidated%")

            ctx.contentResolver.query(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                projection, selection, args, null,
            )?.use { cursor ->
                val col = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
                while (cursor.moveToNext()) names += cursor.getString(col)
            }

            ctx.contentResolver.query(
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                projection, selection, args, null,
            )?.use { cursor ->
                val col = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
                while (cursor.moveToNext()) names += cursor.getString(col)
            }

            names
        }

    override suspend fun queryRelativePath(uri: Uri): String? = withContext(Dispatchers.IO) {
        if (uri.scheme != "content") return@withContext null
        try {
            ctx.contentResolver.query(
                uri,
                arrayOf(MediaStore.MediaColumns.RELATIVE_PATH),
                null, null, null,
            )?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            }
        } catch (_: Exception) {
            null
        }
    }

    override suspend fun queryDisplayName(uri: Uri): String? = withContext(Dispatchers.IO) {
        if (uri.scheme != "content") return@withContext null
        try {
            ctx.contentResolver.query(
                uri,
                arrayOf(MediaStore.MediaColumns.DISPLAY_NAME),
                null, null, null,
            )?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun scanMediaStore(
        pathPattern: String,
        existingNames: Set<String>,
    ): List<PendingConsolidationItem> {
        val pending = mutableListOf<PendingConsolidationItem>()
        val collection = MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL)
        val projection = arrayOf(
            MediaStore.Files.FileColumns._ID,
            MediaStore.Files.FileColumns.DISPLAY_NAME,
            MediaStore.Files.FileColumns.MIME_TYPE,
        )
        ctx.contentResolver.query(
            collection, projection,
            "${MediaStore.Files.FileColumns.RELATIVE_PATH} LIKE ?",
            arrayOf(pathPattern),
            null,
        )?.use { cursor ->
            val idCol = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns._ID)
            val nameCol = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DISPLAY_NAME)
            val mimeCol = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.MIME_TYPE)
            while (cursor.moveToNext()) {
                val name = cursor.getString(nameCol) ?: continue
                val mime = cursor.getString(mimeCol) ?: continue
                if (!mime.startsWith("image/") && !mime.startsWith("video/")) continue
                val stem = name.substringBeforeLast('.')
                val outName = if (mime.startsWith("video/")) "${stem}_s.mp4" else "${stem}_s.jpg"
                if (outName in existingNames) continue
                val id = cursor.getLong(idCol)
                val uri = ContentUris.withAppendedId(collection, id).toString()
                pending += PendingConsolidationItem(
                    uri, name, if (mime.startsWith("video/")) "VIDEO" else "IMAGE",
                )
            }
        }
        return pending
    }
}
