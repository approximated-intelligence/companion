package de.perigon.companion.media.data

import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * MediaStore queries for media prep UI only.
 * Consolidation uses SAF directly via util/saf/.
 */
interface MediaStoreRepository {

    /** Resolve the SHA-256 of a backed-up file by its URI metadata. */
    suspend fun resolveBackedUpSha256(
        uri: Uri,
        findConfirmedSha256: suspend (path: String, mtime: Long) -> String?,
    ): String?

    /** Query the RELATIVE_PATH for a content URI. */
    suspend fun queryRelativePath(uri: Uri): String?

    /** Query the DISPLAY_NAME for a content URI. */
    suspend fun queryDisplayName(uri: Uri): String?
}

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
}
