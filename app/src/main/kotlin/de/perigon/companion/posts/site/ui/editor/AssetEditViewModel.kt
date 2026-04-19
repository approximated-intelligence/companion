package de.perigon.companion.posts.site.ui.editor

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import de.perigon.companion.posts.site.data.AssetDao
import de.perigon.companion.posts.site.data.AssetEntity
import de.perigon.companion.posts.site.data.AssetSyncState
import de.perigon.companion.core.ui.SnackbarChannel
import de.perigon.companion.util.gitBlobSha1
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

@Immutable
data class AssetEditUiState(
    val asset:             AssetEntity? = null,
    val editContent:       String       = "",
    val wrapLines:         Boolean      = true,
    val isDirty:           Boolean      = false,
    val searchQuery:       String       = "",
    val replaceText:       String       = "",
    val isSearchOpen:      Boolean      = false,
    val isReplaceOpen:     Boolean      = false,
    val matchRanges:       List<IntRange> = emptyList(),
    val currentMatchIndex: Int          = -1,
)

@HiltViewModel
class AssetEditViewModel @Inject constructor(
    private val assetDao: AssetDao,
) : ViewModel() {

    private val _state = MutableStateFlow(AssetEditUiState())
    val state: StateFlow<AssetEditUiState> = _state.asStateFlow()

    val snackbar = SnackbarChannel()

    fun load(assetId: Long) {
        viewModelScope.launch {
            val asset = assetDao.getById(assetId) ?: return@launch
            _state.update { it.copy(
                asset       = asset,
                editContent = asset.content,
                isDirty     = false,
            )}
        }
    }

    fun setContent(content: String) {
        val asset = _state.value.asset ?: return
        val matches = findMatches(content, _state.value.searchQuery)
        _state.update { it.copy(
            editContent       = content,
            isDirty            = content != asset.content,
            matchRanges        = matches,
            currentMatchIndex  = clampIndex(it.currentMatchIndex, matches.size),
        )}
    }

    fun toggleWrapLines() = _state.update { it.copy(wrapLines = !it.wrapLines) }

    fun toggleSearch() {
        val wasOpen = _state.value.isSearchOpen
        _state.update { it.copy(
            isSearchOpen      = !wasOpen,
            isReplaceOpen     = if (wasOpen) false else it.isReplaceOpen,
            searchQuery       = if (wasOpen) "" else it.searchQuery,
            replaceText       = if (wasOpen) "" else it.replaceText,
            matchRanges       = if (wasOpen) emptyList() else it.matchRanges,
            currentMatchIndex = -1,
        )}
    }

    fun toggleReplace() =
        _state.update { it.copy(isReplaceOpen = !it.isReplaceOpen) }

    fun setSearchQuery(query: String) {
        val matches = findMatches(_state.value.editContent, query)
        _state.update { it.copy(
            searchQuery       = query,
            matchRanges        = matches,
            currentMatchIndex  = if (matches.isNotEmpty()) 0 else -1,
        )}
    }

    fun setReplaceText(text: String) =
        _state.update { it.copy(replaceText = text) }

    fun nextMatch() {
        _state.update { s ->
            if (s.matchRanges.isEmpty()) return@update s
            s.copy(currentMatchIndex = (s.currentMatchIndex + 1) % s.matchRanges.size)
        }
    }

    fun previousMatch() {
        _state.update { s ->
            if (s.matchRanges.isEmpty()) return@update s
            val prev = if (s.currentMatchIndex <= 0) s.matchRanges.size - 1
                       else s.currentMatchIndex - 1
            s.copy(currentMatchIndex = prev)
        }
    }

    fun replaceAll() {
        val s = _state.value
        if (s.searchQuery.isEmpty() || s.matchRanges.isEmpty()) return
        val newContent = s.editContent.replace(s.searchQuery, s.replaceText, ignoreCase = true)
        val asset = s.asset ?: return
        val newMatches = findMatches(newContent, s.searchQuery)
        _state.update { it.copy(
            editContent       = newContent,
            isDirty            = newContent != asset.content,
            matchRanges        = newMatches,
            currentMatchIndex  = if (newMatches.isNotEmpty()) 0 else -1,
        )}
    }

    fun save() {
        val asset   = _state.value.asset ?: return
        val content = _state.value.editContent
        viewModelScope.launch {
            val hash     = gitBlobSha1(content)
            val newState = when (asset.syncState) {
                AssetSyncState.IN_SYNC, AssetSyncState.SERVER_AHEAD ->
                    if (hash != asset.serverSha) AssetSyncState.LOCAL_AHEAD
                    else AssetSyncState.IN_SYNC
                AssetSyncState.SERVER_ONLY ->
                    AssetSyncState.CONFLICT
                else -> asset.syncState
            }
            assetDao.updateContent(asset.id, content, hash, newState)
            _state.update { it.copy(
                asset    = asset.copy(content = content, localHash = hash, syncState = newState),
                isDirty  = false,
            )}
            snackbar.send("Saved")
        }
    }
}

internal fun findMatches(text: String, query: String): List<IntRange> {
    if (query.isEmpty()) return emptyList()
    val matches = mutableListOf<IntRange>()
    val lowerText = text.lowercase()
    val lowerQuery = query.lowercase()
    var start = 0
    while (true) {
        val idx = lowerText.indexOf(lowerQuery, start)
        if (idx == -1) break
        matches += idx until (idx + lowerQuery.length)
        start = idx + lowerQuery.length
    }
    return matches
}

private fun clampIndex(current: Int, size: Int): Int = when {
    size == 0        -> -1
    current < 0      -> 0
    current >= size  -> size - 1
    else             -> current
}
