package de.perigon.companion.posts.site.ui.diff

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.github.difflib.DiffUtils
import com.github.difflib.patch.DeltaType
import de.perigon.companion.core.prefs.AppPrefs
import de.perigon.companion.core.prefs.CredentialStore
import de.perigon.companion.posts.site.data.AssetRepository
import de.perigon.companion.posts.site.data.AssetDao
import de.perigon.companion.util.network.GitHubClient
import de.perigon.companion.core.ui.SnackbarChannel
import dagger.hilt.android.lifecycle.HiltViewModel
import io.ktor.client.HttpClient
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

sealed class DiffSegment {
    data class Equal(val text: String) : DiffSegment()
    data class Added(val text: String) : DiffSegment()
    data class Removed(val text: String) : DiffSegment()
}

enum class DiffLineTag { EQUAL, DELETE, INSERT }

data class DiffLine(
    val oldLineNo: Int?,
    val newLineNo: Int?,
    val tag: DiffLineTag,
    val segments: List<DiffSegment>,
)

@Immutable
data class AssetDiffUiState(
    val path:       String         = "",
    val isLoading:  Boolean        = false,
    val lines:      List<DiffLine> = emptyList(),
    val error:      String?        = null,
)

@HiltViewModel
class AssetDiffViewModel @Inject constructor(
    private val assetDao: AssetDao,
    private val assetRepository: AssetRepository,
    private val appPrefs: AppPrefs,
    private val credentialStore: CredentialStore,
    private val http: HttpClient,
) : ViewModel() {

    private val _state = MutableStateFlow(AssetDiffUiState())
    val state: StateFlow<AssetDiffUiState> = _state.asStateFlow()

    val snackbar = SnackbarChannel()

    private fun githubClient(): GitHubClient? {
        val token = credentialStore.githubToken() ?: return null
        val owner = appPrefs.githubOwner() ?: return null
        val repo  = appPrefs.githubRepo() ?: return null
        return GitHubClient(http, token, owner, repo)
    }

    fun load(assetId: Long) {
        val github = githubClient()
        if (github == null) {
            _state.update { it.copy(error = "GitHub credentials not configured") }
            return
        }
        viewModelScope.launch {
            _state.update { it.copy(isLoading = true, error = null) }
            try {
                val asset = assetDao.getById(assetId)
                if (asset == null) {
                    _state.update { it.copy(isLoading = false, error = "Asset not found") }
                    return@launch
                }

                _state.update { it.copy(path = asset.path) }

                val serverContent = assetRepository.fetchServerContent(assetId, github)

                val oldLines = (serverContent ?: "").lines()
                val newLines = asset.content.lines()

                val diffLines = computeDiff(oldLines, newLines)

                _state.update { it.copy(isLoading = false, lines = diffLines) }
            } catch (e: Exception) {
                _state.update { it.copy(isLoading = false, error = "Diff failed: ${e.message}") }
            }
        }
    }
}

internal fun computeDiff(
    oldLines: List<String>,
    newLines: List<String>,
): List<DiffLine> {
    val patch = DiffUtils.diff(oldLines, newLines)
    val result = mutableListOf<DiffLine>()

    var oldPos = 0
    var newPos = 0

    for (delta in patch.deltas) {
        while (oldPos < delta.source.position) {
            oldPos++
            newPos++
            result += DiffLine(
                oldLineNo = oldPos,
                newLineNo = newPos,
                tag       = DiffLineTag.EQUAL,
                segments  = listOf(DiffSegment.Equal(oldLines[oldPos - 1])),
            )
        }

        val srcLines = delta.source.lines
        val tgtLines = delta.target.lines

        when (delta.type) {
            DeltaType.DELETE -> {
                for (line in srcLines) {
                    oldPos++
                    result += DiffLine(oldPos, null, DiffLineTag.DELETE, listOf(DiffSegment.Removed(line)))
                }
            }
            DeltaType.INSERT -> {
                for (line in tgtLines) {
                    newPos++
                    result += DiffLine(null, newPos, DiffLineTag.INSERT, listOf(DiffSegment.Added(line)))
                }
            }
            DeltaType.CHANGE -> {
                val maxLines = maxOf(srcLines.size, tgtLines.size)
                for (i in 0 until maxLines) {
                    val oldLine = srcLines.getOrNull(i)
                    val newLine = tgtLines.getOrNull(i)

                    when {
                        oldLine != null && newLine != null -> {
                            val (delSegs, insSegs) = wordDiff(oldLine, newLine)
                            oldPos++
                            result += DiffLine(oldPos, null, DiffLineTag.DELETE, delSegs)
                            newPos++
                            result += DiffLine(null, newPos, DiffLineTag.INSERT, insSegs)
                        }
                        oldLine != null -> {
                            oldPos++
                            result += DiffLine(oldPos, null, DiffLineTag.DELETE, listOf(DiffSegment.Removed(oldLine)))
                        }
                        newLine != null -> {
                            newPos++
                            result += DiffLine(null, newPos, DiffLineTag.INSERT, listOf(DiffSegment.Added(newLine)))
                        }
                    }
                }
            }
            else -> {
                for (line in srcLines) {
                    oldPos++
                    result += DiffLine(oldPos, null, DiffLineTag.DELETE, listOf(DiffSegment.Removed(line)))
                }
                for (line in tgtLines) {
                    newPos++
                    result += DiffLine(null, newPos, DiffLineTag.INSERT, listOf(DiffSegment.Added(line)))
                }
            }
        }
    }

    while (oldPos < oldLines.size) {
        oldPos++
        newPos++
        result += DiffLine(oldPos, newPos, DiffLineTag.EQUAL, listOf(DiffSegment.Equal(oldLines[oldPos - 1])))
    }

    return result
}

internal fun wordDiff(
    oldLine: String,
    newLine: String,
): Pair<List<DiffSegment>, List<DiffSegment>> {
    val oldTokens = tokenize(oldLine)
    val newTokens = tokenize(newLine)
    val patch = DiffUtils.diff(oldTokens, newTokens)

    val delSegments = mutableListOf<DiffSegment>()
    val insSegments = mutableListOf<DiffSegment>()

    var oldIdx = 0
    var newIdx = 0

    for (delta in patch.deltas) {
        while (oldIdx < delta.source.position) {
            delSegments += DiffSegment.Equal(oldTokens[oldIdx])
            oldIdx++
        }
        while (newIdx < delta.target.position) {
            insSegments += DiffSegment.Equal(newTokens[newIdx])
            newIdx++
        }

        for (token in delta.source.lines) {
            delSegments += DiffSegment.Removed(token)
            oldIdx++
        }
        for (token in delta.target.lines) {
            insSegments += DiffSegment.Added(token)
            newIdx++
        }
    }

    while (oldIdx < oldTokens.size) {
        delSegments += DiffSegment.Equal(oldTokens[oldIdx])
        oldIdx++
    }
    while (newIdx < newTokens.size) {
        insSegments += DiffSegment.Equal(newTokens[newIdx])
        newIdx++
    }

    return delSegments to insSegments
}

private fun tokenize(line: String): List<String> {
    if (line.isEmpty()) return emptyList()
    val tokens = mutableListOf<String>()
    val buf = StringBuilder()
    var inWord = line[0].isLetterOrDigit()

    for (ch in line) {
        val charIsWord = ch.isLetterOrDigit()
        if (charIsWord != inWord) {
            tokens += buf.toString()
            buf.clear()
            inWord = charIsWord
        }
        buf.append(ch)
    }
    if (buf.isNotEmpty()) tokens += buf.toString()

    return tokens
}
