package de.perigon.companion.media.data

import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import de.perigon.companion.media.domain.BackupState
import de.perigon.companion.media.domain.MediaType
import de.perigon.companion.media.domain.UnifiedMediaItem

private val IMAGE_COLLECTION: Uri = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
private val VIDEO_COLLECTION: Uri = MediaStore.Video.Media.EXTERNAL_CONTENT_URI

private val PROJECTION = arrayOf(
    MediaStore.MediaColumns._ID,
    MediaStore.MediaColumns.DISPLAY_NAME,
    MediaStore.MediaColumns.DATE_TAKEN,
    MediaStore.MediaColumns.RELATIVE_PATH,
)

private const val CAMERA_PATH       = "DCIM/Camera"
private const val CONSOLIDATED_PATH = "DCIM/Consolidated"

fun queryUnifiedMedia(context: Context): List<UnifiedMediaItem> {
    val map = mutableMapOf<String, UnifiedMediaItemBuilder>()

    queryCollection(context, IMAGE_COLLECTION, CAMERA_PATH) { id, name, dateTaken ->
        map.getOrPut(stem(name)) { UnifiedMediaItemBuilder(stem(name), MediaType.IMAGE) }
            .applyOriginal(id, IMAGE_COLLECTION, dateTaken)
    }
    queryCollection(context, VIDEO_COLLECTION, CAMERA_PATH) { id, name, dateTaken ->
        map.getOrPut(stem(name)) { UnifiedMediaItemBuilder(stem(name), MediaType.VIDEO) }
            .applyOriginal(id, VIDEO_COLLECTION, dateTaken)
    }

    // Consolidated derivatives - only _s suffix now, no _thumb
    queryCollection(context, IMAGE_COLLECTION, CONSOLIDATED_PATH) { id, name, _ ->
        val builder = map[stem(name)] ?: return@queryCollection
        if (name.contains("_s")) builder.consolidatedUri = contentUri(IMAGE_COLLECTION, id)
    }
    queryCollection(context, VIDEO_COLLECTION, CONSOLIDATED_PATH) { id, name, _ ->
        val builder = map[stem(name)] ?: return@queryCollection
        if (name.contains("_s")) builder.consolidatedUri = contentUri(VIDEO_COLLECTION, id)
    }

    return map.values.mapNotNull { it.build() }.sortedByDescending { it.dateTaken }
}

private fun queryCollection(
    context: Context,
    collection: Uri,
    pathPrefix: String,
    onRow: (id: Long, name: String, dateTaken: Long) -> Unit,
) {
    context.contentResolver.query(
        collection, PROJECTION,
        "${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ?",
        arrayOf("$pathPrefix%"),
        "${MediaStore.MediaColumns.DATE_TAKEN} DESC",
    )?.use { cursor ->
        val idCol        = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
        val nameCol      = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
        val dateTakenCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_TAKEN)
        while (cursor.moveToNext()) {
            val name = cursor.getString(nameCol) ?: continue
            onRow(cursor.getLong(idCol), name, cursor.getLong(dateTakenCol))
        }
    }
}

private fun contentUri(collection: Uri, id: Long): Uri =
    Uri.withAppendedPath(collection, id.toString())

// Strip extension, then _s suffix. No _thumb - that's gone.
private fun stem(displayName: String): String =
    displayName.substringBeforeLast('.').removeSuffix("_s")

private class UnifiedMediaItemBuilder(
    private val stem: String,
    private val mediaType: MediaType,
) {
    private var originalUri:     Uri?  = null
    private var originalMediaId: Long  = -1L
    private var dateTaken:       Long  = 0L
    var consolidatedUri: Uri? = null

    fun applyOriginal(id: Long, collection: Uri, dateTaken: Long) {
        if (originalMediaId == -1L) {
            originalMediaId = id
            originalUri     = contentUri(collection, id)
            this.dateTaken  = dateTaken
        }
    }

    fun build(): UnifiedMediaItem? {
        val uri = originalUri ?: return null
        return UnifiedMediaItem(
            stem            = stem,
            originalUri     = uri,
            originalMediaId = originalMediaId,
            consolidatedUri = consolidatedUri,
            dateTaken       = dateTaken,
            mediaType       = mediaType,
            backupState     = BackupState.NONE,
        )
    }
}
