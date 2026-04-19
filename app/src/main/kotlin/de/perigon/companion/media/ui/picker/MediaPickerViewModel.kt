package de.perigon.companion.media.ui.picker

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

data class MediaPickerItem(
    val id: Long,
    val contentUri: Uri,
    val bucketName: String,
    val isVideo: Boolean,
    val dateTaken: Long,
    val durationMs: Long = 0,
)

data class AlbumInfo(
    val name: String,
    val count: Int,
)

@Immutable
data class MediaPickerUiState(
    val isLoading: Boolean = true,
    val allItems: List<MediaPickerItem> = emptyList(),
    val albums: List<AlbumInfo> = emptyList(),
    val selectedAlbum: String? = null,
    val typeFilter: MediaTypeFilter = MediaTypeFilter.ALL,
    val selectedUris: List<Uri> = emptyList(),
) {
    val filteredItems: List<MediaPickerItem>
        get() {
            var items = allItems
            if (selectedAlbum != null) {
                items = items.filter { it.bucketName == selectedAlbum }
            }
            items = when (typeFilter) {
                MediaTypeFilter.ALL -> items
                MediaTypeFilter.IMAGES -> items.filter { !it.isVideo }
                MediaTypeFilter.VIDEOS -> items.filter { it.isVideo }
            }
            return items
        }
}

@HiltViewModel
class MediaPickerViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
) : ViewModel() {

    private val _state = MutableStateFlow(MediaPickerUiState())
    val state: StateFlow<MediaPickerUiState> = _state.asStateFlow()

    init {
        loadMedia()
    }

    private fun loadMedia() {
        viewModelScope.launch {
            val items = withContext(Dispatchers.IO) {
                queryAllMedia()
            }
            val albums = items
                .groupBy { it.bucketName }
                .map { (name, list) -> AlbumInfo(name, list.size) }
                .sortedByDescending { it.count }

            _state.update {
                it.copy(isLoading = false, allItems = items, albums = albums)
            }
        }
    }

    fun selectAlbum(album: String?) {
        _state.update { it.copy(selectedAlbum = album) }
    }

    fun setTypeFilter(filter: MediaTypeFilter) {
        _state.update { it.copy(typeFilter = filter) }
    }

    fun toggleSelection(uri: Uri) {
        _state.update { s ->
            val current = s.selectedUris
            if (uri in current) {
                s.copy(selectedUris = current - uri)
            } else {
                s.copy(selectedUris = current + uri)
            }
        }
    }

    private fun queryAllMedia(): List<MediaPickerItem> {
        val items = mutableListOf<MediaPickerItem>()
        queryCollection(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            isVideo = false,
            items,
        )
        queryCollection(
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
            isVideo = true,
            items,
        )
        items.sortByDescending { it.dateTaken }
        return items
    }

    private fun queryCollection(
        collection: Uri,
        isVideo: Boolean,
        out: MutableList<MediaPickerItem>,
    ) {
        val projection = buildList {
            add(MediaStore.MediaColumns._ID)
            add(MediaStore.MediaColumns.BUCKET_DISPLAY_NAME)
            add(MediaStore.MediaColumns.DATE_TAKEN)
            add(MediaStore.MediaColumns.DATE_ADDED)
            if (isVideo) add(MediaStore.Video.VideoColumns.DURATION)
        }.toTypedArray()

        context.contentResolver.query(
            collection,
            projection,
            null,
            null,
            "${MediaStore.MediaColumns.DATE_TAKEN} DESC",
        )?.use { cursor ->
            val idCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
            val bucketCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.BUCKET_DISPLAY_NAME)
            val dateCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_TAKEN)
            val dateAddedCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_ADDED)
            val durationCol = if (isVideo) cursor.getColumnIndexOrThrow(MediaStore.Video.VideoColumns.DURATION) else -1

            while (cursor.moveToNext()) {
                val id = cursor.getLong(idCol)
                val bucket = cursor.getString(bucketCol) ?: "Other"
                val dateTaken = cursor.getLong(dateCol)
                val dateAdded = cursor.getLong(dateAddedCol) * 1000L // DATE_ADDED is seconds
                val sortDate = if (dateTaken > 0) dateTaken else dateAdded
                val duration = if (durationCol >= 0) cursor.getLong(durationCol) else 0L
                val contentUri = ContentUris.withAppendedId(collection, id)

                out += MediaPickerItem(
                    id = id,
                    contentUri = contentUri,
                    bucketName = bucket,
                    isVideo = isVideo,
                    dateTaken = sortDate,
                    durationMs = duration,
                )
            }
        }
    }
}
