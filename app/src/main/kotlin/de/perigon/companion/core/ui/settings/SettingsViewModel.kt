package de.perigon.companion.core.ui.settings

import android.content.Context
import android.content.Intent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import de.perigon.companion.backup.network.b2.B2BackendFactory
import de.perigon.companion.core.prefs.AppPrefs
import de.perigon.companion.core.prefs.CredentialStore
import de.perigon.companion.core.db.AppDatabase
import de.perigon.companion.util.network.GitHubClient
import de.perigon.companion.track.service.BackgroundService
import de.perigon.companion.core.ui.SnackbarChannel
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import io.ktor.client.HttpClient
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    @param:ApplicationContext private val ctx: Context,
    private val db: AppDatabase,
    private val creds: CredentialStore,
    private val appPrefs: AppPrefs,
    private val b2Factory: B2BackendFactory,
    private val http: HttpClient,
) : ViewModel() {

    data class SettingsState(
        val b2Configured:       Boolean = false,
        val naclConfigured:     Boolean = false,
        val naclFingerprint:    String  = "",
        val githubConfigured:   Boolean = false,
        val githubLabel:        String  = "",
        val siteUrl:            String  = "",
        val tileSourceUri:      String? = null,
        val tileSourceLabel:    String  = "",
        val postMediaLabel:     String  = "",
        val postMediaConfigured: Boolean = false,
        val journeyStartDate:   String  = "",
        val journeyTitle:       String  = "",
        val journeyTag:         String  = "",
        val backgroundGps:      Boolean = false,
        val autosaveDebounceMs: Long    = AppPrefs.DEFAULT_AUTOSAVE_DEBOUNCE_MS,
        val autosaveMaxIntervalMs: Long = AppPrefs.DEFAULT_AUTOSAVE_MAX_INTERVAL_MS,
    )

    private val _state = MutableStateFlow(buildState())
    val state: StateFlow<SettingsState> = _state

    val snackbar = SnackbarChannel()

    private fun buildState(): SettingsState {
        val owner = appPrefs.githubOwner()
        val repo = appPrefs.githubRepo()
        val naclHex = appPrefs.naclPkHex()
        val postMediaUri = appPrefs.postMediaFolderUri()
        return SettingsState(
            b2Configured       = creds.b2AppKey() != null && appPrefs.b2Endpoint() != null,
            naclConfigured     = naclHex != null,
            naclFingerprint    = naclHex?.fingerprint() ?: "",
            githubConfigured   = creds.githubToken() != null,
            githubLabel        = if (owner != null && repo != null) "$owner/$repo" else "",
            siteUrl            = appPrefs.siteUrl() ?: "",
            tileSourceUri      = appPrefs.tileSourceUri(),
            tileSourceLabel    = appPrefs.tileSourceLabel() ?: "",
            postMediaLabel     = appPrefs.postMediaFolderLabel() ?: "App-private (hidden from gallery)",
            postMediaConfigured = postMediaUri != null,
            journeyStartDate   = appPrefs.journeyStartDate() ?: "",
            journeyTitle       = appPrefs.journeyTitle() ?: "",
            journeyTag         = appPrefs.journeyTag() ?: "",
            backgroundGps      = appPrefs.isBackgroundGpsEnabled(),
            autosaveDebounceMs = appPrefs.autosaveDebounceMs(),
            autosaveMaxIntervalMs = appPrefs.autosaveMaxIntervalMs(),
        )
    }

    fun setBackgroundGpsEnabled(enabled: Boolean) {
        viewModelScope.launch {
            appPrefs.setBackgroundGpsEnabled(enabled)
            if (!enabled && BackgroundService.status.value.isRecording) {
                val intent = Intent(ctx, BackgroundService::class.java).apply {
                    action = BackgroundService.ACTION_STOP
                }
                ctx.startService(intent)
            }
            _state.value = buildState()
        }
    }

    fun setJourneyStartDate(date: String) {
        viewModelScope.launch {
            appPrefs.setJourneyStartDate(date)
            _state.value = buildState()
        }
    }

    fun setJourneyTitle(title: String) {
        viewModelScope.launch {
            appPrefs.setJourneyTitle(title)
            _state.value = buildState()
        }
    }

    fun setJourneyTag(tag: String) {
        viewModelScope.launch {
            val cleaned = tag.removePrefix("#").trim()
            appPrefs.setJourneyTag(cleaned)
            _state.value = buildState()
        }
    }

    fun setSiteUrl(url: String) {
        viewModelScope.launch {
            appPrefs.setSiteUrl(url.trim())
            _state.value = buildState()
        }
    }

    fun setAutosaveDebounceMs(ms: Long) {
        viewModelScope.launch {
            appPrefs.setAutosaveDebounceMs(ms)
            _state.value = buildState()
        }
    }

    fun setAutosaveMaxIntervalMs(ms: Long) {
        viewModelScope.launch {
            appPrefs.setAutosaveMaxIntervalMs(ms)
            _state.value = buildState()
        }
    }

    fun setPostMediaFolder(uri: android.net.Uri, displayName: String) {
        viewModelScope.launch {
            ctx.contentResolver.takePersistableUriPermission(
                uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            appPrefs.setPostMediaFolder(uri.toString(), displayName)
            _state.value = buildState()
            snackbar.send("PostMedia folder set: $displayName")
        }
    }

    fun clearPostMediaFolder() {
        viewModelScope.launch {
            appPrefs.clearPostMediaFolder()
            _state.value = buildState()
            snackbar.send("PostMedia folder reset to app-private default")
        }
    }

    // ---- Scoped QR handlers ----

    fun onNaclQrScanned(raw: String) {
        runCatching {
            val obj = Json.parseToJsonElement(raw).jsonObject
            val type = obj["type"]?.jsonPrimitive?.content
            require(type == "nacl_backup_pubkey") { "Expected nacl_backup_pubkey QR, got: $type" }
            val hex = obj["hex"]?.jsonPrimitive?.content
                ?: error("QR missing 'hex' field")
            require(hex.length == 64) { "Expected 32-byte hex key" }
            viewModelScope.launch { handleNaclKey(hex) }
        }.getOrElse { e ->
            _state.value = buildState()
            snackbar.send("NaCl QR not recognised: ${e.message}")
        }
    }

    fun onB2QrScanned(raw: String) {
        runCatching {
            val obj = Json.parseToJsonElement(raw).jsonObject
            val keyId = obj["key_id"]?.jsonPrimitive?.content
                ?: error("QR missing 'key_id' field")
            val appKey = obj["application_key"]?.jsonPrimitive?.content
                ?: error("QR missing 'application_key' field")
            val endpoint = obj["endpoint"]?.jsonPrimitive?.content
            val bucket = obj["bucket"]?.jsonPrimitive?.content
            viewModelScope.launch {
                appPrefs.setB2Config(endpoint = endpoint, keyId = keyId, bucket = bucket)
                creds.setB2AppKey(appKey)
                _state.value = buildState()
                snackbar.send("B2 credentials imported")
            }
        }.getOrElse { e ->
            _state.value = buildState()
            snackbar.send("B2 QR not recognised: ${e.message}")
        }
    }

    fun onGithubQrScanned(raw: String) {
        runCatching {
            val obj = Json.parseToJsonElement(raw).jsonObject
            val type = obj["type"]?.jsonPrimitive?.content
            require(type == "github") { "Expected github QR, got: $type" }
            val token = obj["token"]?.jsonPrimitive?.content
                ?: error("QR missing 'token' field")
            val owner = obj["owner"]?.jsonPrimitive?.content
                ?: error("QR missing 'owner' field")
            val repo = obj["repo"]?.jsonPrimitive?.content
                ?: error("QR missing 'repo' field")
            viewModelScope.launch {
                appPrefs.setGithubRepo(owner = owner, repo = repo)
                creds.setGithubToken(token)
                _state.value = buildState()
                snackbar.send("GitHub credentials imported")
                fetchCname(token, owner, repo)
            }
        }.getOrElse { e ->
            _state.value = buildState()
            snackbar.send("GitHub QR not recognised: ${e.message}")
        }
    }

    fun onBootstrapQrScanned(raw: String) {
        runCatching {
            val obj = Json.parseToJsonElement(raw).jsonObject
            val type = obj["type"]?.jsonPrimitive?.content
            require(type == "companion_bootstrap_v1") { "Expected bootstrap QR, got: $type" }

            viewModelScope.launch {
                obj["nacl_public_key_hex"]?.jsonPrimitive?.content?.let { hex ->
                    require(hex.length == 64) { "Expected 32-byte hex key" }
                    handleNaclKey(hex)
                }

                obj["b2"]?.jsonObject?.let { b2 ->
                    val keyId = b2["key_id"]?.jsonPrimitive?.content
                        ?: error("Bootstrap missing b2.key_id")
                    val appKey = b2["application_key"]?.jsonPrimitive?.content
                        ?: error("Bootstrap missing b2.application_key")
                    val endpoint = b2["endpoint"]?.jsonPrimitive?.content
                    val bucket = b2["bucket"]?.jsonPrimitive?.content
                    appPrefs.setB2Config(endpoint = endpoint, keyId = keyId, bucket = bucket)
                    creds.setB2AppKey(appKey)
                }

                obj["github"]?.jsonObject?.let { gh ->
                    val token = gh["token"]?.jsonPrimitive?.content
                        ?: error("Bootstrap missing github.token")
                    val owner = gh["owner"]?.jsonPrimitive?.content
                        ?: error("Bootstrap missing github.owner")
                    val repo = gh["repo"]?.jsonPrimitive?.content
                        ?: error("Bootstrap missing github.repo")
                    appPrefs.setGithubRepo(owner = owner, repo = repo)
                    creds.setGithubToken(token)
                    fetchCname(token, owner, repo)
                }

                _state.value = buildState()
                snackbar.send("Bootstrap configuration imported")
            }
        }.getOrElse { e ->
            _state.value = buildState()
            snackbar.send("Bootstrap QR not recognised: ${e.message}")
        }
    }

    fun onAnyQrScanned(raw: String): Boolean {
        return runCatching {
            val obj = Json.parseToJsonElement(raw).jsonObject
            val type = obj["type"]?.jsonPrimitive?.content
            if (type == "companion_bootstrap_v1") {
                onBootstrapQrScanned(raw)
                true
            } else {
                false
            }
        }.getOrDefault(false)
    }

    private suspend fun fetchCname(token: String, owner: String, repo: String) {
        if (appPrefs.siteUrl()?.isNotBlank() == true) return
        try {
            val github = GitHubClient(http, token, owner, repo)
            val cname = github.getFileContent("CNAME")?.trim()
            if (!cname.isNullOrBlank()) {
                val url = if (cname.startsWith("http")) cname else "https://$cname"
                appPrefs.setSiteUrl(url)
                _state.value = buildState()
                snackbar.send("Site URL discovered: $url")
            }
        } catch (_: Exception) { }
    }

    private suspend fun handleNaclKey(hex: String) {
        val stored = appPrefs.naclPkHex()

        if (stored == null) {
            appPrefs.setNaclPkHex(hex)
            _state.value = buildState()
            snackbar.send("New key accepted: ${hex.fingerprint()}")
            return
        }

        if (stored == hex) {
            _state.value = buildState()
            snackbar.send("Key unchanged: ${hex.fingerprint()}")
            return
        }

        val b2Config = appPrefs.b2Config()
        val b2AppKey = creds.b2AppKey()
        if (b2Config == null || b2AppKey == null) {
            _state.value = buildState()
            snackbar.send("Key change refused - B2 not configured, cannot verify bucket is empty")
            return
        }

        try {
            val b2 = b2Factory.create(b2Config.endpoint, b2Config.bucket, b2Config.keyId, b2AppKey)
            val packs = b2.listObjects(prefix = "packs/", maxKeys = 1)

            if (packs.isNotEmpty()) {
                _state.value = buildState()
                snackbar.send("Key change refused - bucket has packs. Run backup_reset.py first.")
                return
            }

            appPrefs.setNaclPkHex(hex)
            db.resetBackupState()
            _state.value = buildState()
            snackbar.send("Key changed: ${hex.fingerprint()} - backup state reset")
        } catch (e: Exception) {
            _state.value = buildState()
            snackbar.send("Key change refused - could not check bucket: ${e.message}")
        }
    }

    fun setTileSource(uri: android.net.Uri, displayName: String) {
        viewModelScope.launch {
            ctx.contentResolver.takePersistableUriPermission(
                uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
            appPrefs.setTileSource(uri.toString(), displayName)
            _state.value = buildState()
            snackbar.send("Tile source set: $displayName")
        }
    }

    fun clearTileSource() {
        viewModelScope.launch {
            appPrefs.clearTileSource()
            _state.value = buildState()
            snackbar.send("Tile source cleared")
        }
    }

    fun clearSecrets() {
        creds.clearAll()
        _state.value = buildState()
        snackbar.send("Secrets cleared (B2 app key + GitHub token)")
    }

    fun clearAll() {
        viewModelScope.launch {
            creds.clearAll()
            appPrefs.clearAll()
            _state.value = buildState()
            snackbar.send("All settings cleared")
        }
    }
}

private fun String.fingerprint(): String =
    if (length >= 16) "${take(8)}…${takeLast(8)}" else this
