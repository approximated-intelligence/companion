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
    /** Query the RELATIVE_PATH for a content URI. */
    suspend fun queryRelativePath(uri: Uri): String?

    /** Query the DISPLAY_NAME for a content URI. */
    suspend fun queryDisplayName(uri: Uri): String?
}

@Singleton
class MediaStoreRepositoryImpl @Inject constructor(
    @param:ApplicationContext private val ctx: Context,
) : MediaStoreRepository {

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
