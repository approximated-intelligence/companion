package de.perigon.companion.backup.ui.restore

import android.content.Context
import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.goterl.lazysodium.LazySodiumAndroid
import de.perigon.companion.backup.network.b2.B2Backend
import de.perigon.companion.backup.network.b2.B2BackendFactory
import de.perigon.companion.backup.domain.BackupPackEngine
import de.perigon.companion.backup.domain.BackupPartEncryptor
import de.perigon.companion.backup.domain.BackupPaxWriter
import de.perigon.companion.backup.domain.RestoreFileUseCase
import de.perigon.companion.backup.domain.RestoreFilePlan
import de.perigon.companion.backup.domain.RestoreResult
import de.perigon.companion.media.data.MediaStoreWriter
import de.perigon.companion.backup.data.BackupFileDao
import de.perigon.companion.backup.data.BackupFileEntity
import de.perigon.companion.core.ui.SnackbarChannel
import de.perigon.companion.util.fromHex
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import io.ktor.client.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import javax.inject.Inject

data class RestoreFileEntry(
    val path: String,
    val size: Long,
    val mtime: Long,
    val sha256: String,
    val startPack: Int,
    val startPart: Int,
    val startPartOffset: Long,
    val endPack: Int,
    val selected: Boolean = false,
)

enum class RestorePhase {
    NEED_CREDENTIALS,
    READY,
    LISTING,
    REBUILDING_INDEX,
    RESTORING,
    DONE,
    ERROR,
}

@Immutable
data class RestoreUiState(
    val phase: RestorePhase = RestorePhase.NEED_CREDENTIALS,
    val hasSecretQr: Boolean = false,
    val files: List<RestoreFileEntry> = emptyList(),
    val needsIndexRebuild: Boolean = false,
    val rebuildProgress: Int = 0,
    val rebuildTotal: Int = 0,
    val restoreProgress: Int = 0,
    val restoreTotal: Int = 0,
    val currentFile: String = "",
    val errors: List<String> = emptyList(),
)

@HiltViewModel
class RestoreViewModel @Inject constructor(
    @param:ApplicationContext private val ctx: Context,
    private val backupFileDao: BackupFileDao,
    private val sodium: LazySodiumAndroid,
    private val b2Factory: B2BackendFactory,
    private val http: HttpClient,
) : ViewModel() {

    private val _state = MutableStateFlow(RestoreUiState())
    val state: StateFlow<RestoreUiState> = _state.asStateFlow()

    val snackbar = SnackbarChannel()

    private var secretKey: ByteArray? = null
    private var publicKey: ByteArray? = null
    private var b2Endpoint: String? = null
    private var b2Bucket: String? = null
    private var b2KeyId: String? = null
    private var b2AppKey: String? = null

    fun onSecretQrScanned(raw: String) {
        runCatching {
            val obj = Json.parseToJsonElement(raw).jsonObject
            val type = obj["type"]?.jsonPrimitive?.content
            require(type == "restore_secret") { "Expected restore_secret QR, got: $type" }

            val skHex = obj["sk"]?.jsonPrimitive?.content
                ?: error("QR missing 'sk' field")
            val pkHex = obj["pk"]?.jsonPrimitive?.content
                ?: error("QR missing 'pk' field")
            require(skHex.length == 64) { "Secret key must be 32 bytes hex" }
            require(pkHex.length == 64) { "Public key must be 32 bytes hex" }

            secretKey = skHex.fromHex()
            publicKey = pkHex.fromHex()

            b2Endpoint = obj["endpoint"]?.jsonPrimitive?.content
                ?: error("QR missing 'endpoint' field")
            b2Bucket = obj["bucket"]?.jsonPrimitive?.content
                ?: error("QR missing 'bucket' field")
            b2KeyId = obj["key_id"]?.jsonPrimitive?.content
                ?: error("QR missing 'key_id' field")
            b2AppKey = obj["application_key"]?.jsonPrimitive?.content
                ?: error("QR missing 'application_key' field")

            _state.update { it.copy(hasSecretQr = true) }
            updatePhase()
            snackbar.send("Credentials loaded")
        }.getOrElse { e ->
            snackbar.send("QR not recognised: ${e.message}")
        }
    }

    private fun updatePhase() {
        _state.update { it.copy(
            phase = if (secretKey != null) RestorePhase.READY else RestorePhase.NEED_CREDENTIALS,
            hasSecretQr = secretKey != null,
        )}
    }

    private fun createB2(): B2Backend? {
        val endpoint = b2Endpoint ?: return null
        val bucket = b2Bucket ?: return null
        val keyId = b2KeyId ?: return null
        val appKey = b2AppKey ?: return null
        return b2Factory.create(endpoint, bucket, keyId, appKey)
    }

    fun loadFileList() {
        viewModelScope.launch {
            _state.update { it.copy(phase = RestorePhase.LISTING, errors = emptyList()) }
            try {
                val confirmed = backupFileDao.getConfirmedForRestore()
                val needsRebuild = confirmed.any { it.startPack > 0 && it.startPart == 0 }
                val entries = confirmed.map { it.toRestoreEntry() }
                _state.update { it.copy(
                    phase = RestorePhase.READY,
                    files = entries,
                    needsIndexRebuild = needsRebuild,
                )}
            } catch (e: Exception) {
                _state.update { it.copy(
                    phase = RestorePhase.ERROR,
                    errors = listOf("Failed to load file list: ${e.message}"),
                )}
            }
        }
    }

    fun rebuildIndex() {
        val sk = secretKey ?: return
        val pk = publicKey ?: return
        val b2 = createB2() ?: return

        viewModelScope.launch {
            _state.update { it.copy(phase = RestorePhase.REBUILDING_INDEX, errors = emptyList()) }

            try {
                val needsIndex = backupFileDao.getConfirmedNeedingIndex()

                if (needsIndex.isEmpty()) {
                    snackbar.send("Index already up to date")
                    _state.update { it.copy(phase = RestorePhase.READY, needsIndexRebuild = false) }
                    return@launch
                }

                val packsToScan = needsIndex.map { it.startPack }.distinct().sorted()
                _state.update { it.copy(rebuildTotal = packsToScan.size, rebuildProgress = 0) }

                var totalUpdated = 0

                for ((packIdx, packPos) in packsToScan.withIndex()) {
                    _state.update { it.copy(rebuildProgress = packIdx) }

                    val filesInPack = needsIndex.filter { it.startPack == packPos }
                    val sha256Set = filesInPack.map { it.sha256 }.toSet()
                    val updated = scanPackForIndex(b2, sk, pk, packPos, sha256Set, filesInPack)
                    totalUpdated += updated
                }

                _state.update { it.copy(rebuildProgress = packsToScan.size) }
                snackbar.send("Index rebuilt: $totalUpdated file(s) updated")

                val confirmed = backupFileDao.getConfirmedForRestore()
                val stillNeeds = confirmed.any { it.startPack > 0 && it.startPart == 0 }
                _state.update { it.copy(
                    phase = RestorePhase.READY,
                    files = confirmed.map { it.toRestoreEntry() },
                    needsIndexRebuild = stillNeeds,
                )}
            } catch (e: Exception) {
                _state.update { it.copy(
                    phase = RestorePhase.ERROR,
                    errors = listOf("Index rebuild failed: ${e.message}"),
                )}
            }
        }
    }

    private suspend fun scanPackForIndex(
        b2: B2Backend,
        sk: ByteArray,
        pk: ByteArray,
        packPos: Int,
        targetSha256s: Set<String>,
        files: List<BackupFileEntity>,
    ): Int = withContext(Dispatchers.IO) {
        val packKey = "packs/%010d".format(packPos)
        var updated = 0
        var partNum = 1
        val remaining = targetSha256s.toMutableSet()

        while (remaining.isNotEmpty()) {
            val wireData = try {
                b2.getRange(
                    packKey,
                    (partNum - 1).toLong() * BackupPackEngine.PART_WIRE_SIZE,
                    partNum.toLong() * BackupPackEngine.PART_WIRE_SIZE - 1,
                )
            } catch (_: Exception) {
                break
            }

            val plaintext = BackupPartEncryptor.unseal(wireData, pk, sk, sodium)
                ?: break

            var offset = 0
            while (offset + BackupPaxWriter.BLOCK <= plaintext.size && remaining.isNotEmpty()) {
                val block = plaintext.copyOfRange(offset, offset + BackupPaxWriter.BLOCK)
                val entry = BackupPaxWriter.parseEntry(block)
                if (entry != null && entry.sha256 in remaining) {
                    val match = files.find { it.sha256 == entry.sha256 }
                    if (match != null) {
                        backupFileDao.updatePartIndex(match.id, partNum, offset.toLong(), now = System.currentTimeMillis())
                        remaining -= entry.sha256
                        updated++
                    }
                    val dataSize = if (entry.offset > 0) {
                        entry.realSize - entry.offset
                    } else {
                        entry.realSize
                    }
                    val paddedData = BackupPaxWriter.paddedDataSize(dataSize)
                    offset += BackupPaxWriter.BLOCK + paddedData.toInt()
                    continue
                }
                offset += BackupPaxWriter.BLOCK
            }

            partNum++
        }

        updated
    }

    fun toggleFile(path: String) {
        _state.update { s ->
            s.copy(files = s.files.map {
                if (it.path == path) it.copy(selected = !it.selected) else it
            })
        }
    }

    fun selectAll() {
        _state.update { s -> s.copy(files = s.files.map { it.copy(selected = true) }) }
    }

    fun deselectAll() {
        _state.update { s -> s.copy(files = s.files.map { it.copy(selected = false) }) }
    }

    fun restoreSelected() {
        val sk = secretKey ?: return
        val pk = publicKey ?: return
        val b2 = createB2() ?: return

        val selected = _state.value.files.filter { it.selected }
        if (selected.isEmpty()) {
            snackbar.send("No files selected")
            return
        }

        viewModelScope.launch {
            _state.update { it.copy(
                phase = RestorePhase.RESTORING,
                restoreProgress = 0,
                restoreTotal = selected.size,
                errors = emptyList(),
            )}

            try {
                val errors = mutableListOf<String>()
                val useCase = RestoreFileUseCase(b2, BackupPartEncryptor, pk, sk, sodium, ctx.cacheDir)

                for ((idx, file) in selected.withIndex()) {
                    _state.update { it.copy(
                        restoreProgress = idx,
                        currentFile = file.path,
                    )}

                    try {
                        val plan = RestoreFilePlan(
                            packKey = "packs/%010d".format(file.startPack),
                            startPart = file.startPart,
                            startPartOffset = file.startPartOffset,
                            expectedSha256 = file.sha256,
                            fileSize = file.size,
                        )
                        when (val result = useCase.restore(plan)) {
                            is RestoreResult.Success -> {
                                try {
                                    writeToMediaStore(file, result.tempFile)
                                } finally {
                                    result.tempFile.delete()
                                }
                            }
                            is RestoreResult.DecryptionFailed ->
                                errors += "${file.path}: Decryption failed at part ${result.part}"
                            is RestoreResult.HeaderMismatch ->
                                errors += "${file.path}: PAX header mismatch at part ${result.part}"
                            is RestoreResult.HashMismatch ->
                                errors += "${file.path}: SHA-256 mismatch"
                        }
                    } catch (e: Exception) {
                        errors += "${file.path}: ${e.message}"
                    }
                }

                _state.update { it.copy(
                    phase = if (errors.isEmpty()) RestorePhase.DONE else RestorePhase.ERROR,
                    restoreProgress = selected.size,
                    errors = errors,
                )}

                if (errors.isEmpty()) {
                    snackbar.send("Restored ${selected.size} file(s)")
                } else {
                    snackbar.send("Restored with ${errors.size} error(s)")
                }
            } catch (e: Exception) {
                _state.update { it.copy(
                    phase = RestorePhase.ERROR,
                    errors = listOf("Restore failed: ${e.message}"),
                )}
            }
        }
    }

    private suspend fun writeToMediaStore(
        file: RestoreFileEntry,
        tempFile: java.io.File,
    ) = withContext(Dispatchers.IO) {
        val displayName = file.path.substringAfterLast('/')
        val mimeType = when {
            displayName.endsWith(".mp4", true) -> "video/mp4"
            displayName.endsWith(".jpg", true) || displayName.endsWith(".jpeg", true) -> "image/jpeg"
            displayName.endsWith(".png", true) -> "image/png"
            else -> "application/octet-stream"
        }

        if (mimeType.startsWith("video")) {
            MediaStoreWriter.insertVideo(ctx, displayName, "DCIM/Restored", tempFile)
        } else {
            tempFile.inputStream().use { stream ->
                MediaStoreWriter.insertImage(ctx, displayName, mimeType, "DCIM/Restored", stream)
            }
        }
    }

    fun clearCredentials() {
        secretKey?.fill(0)
        secretKey = null
        publicKey = null
        b2Endpoint = null
        b2Bucket = null
        b2KeyId = null
        b2AppKey = null
        _state.update { RestoreUiState() }
    }

    override fun onCleared() {
        clearCredentials()
        super.onCleared()
    }

    fun onPause() {
        clearCredentials()
    }
}

private fun BackupFileEntity.toRestoreEntry() = RestoreFileEntry(
    path = path,
    size = size,
    mtime = mtime,
    sha256 = sha256,
    startPack = startPack,
    startPart = startPart,
    startPartOffset = startPartOffset,
    endPack = endPack,
)
