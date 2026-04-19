package de.perigon.companion.core.ui.settings

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.goterl.lazysodium.LazySodiumAndroid
import de.perigon.companion.util.network.S3BackendFactory
import de.perigon.companion.util.network.HttpMediaBackendFactory
import de.perigon.companion.core.prefs.AppPrefs
import de.perigon.companion.core.prefs.CredentialStore
import de.perigon.companion.core.db.AppDatabase
import de.perigon.companion.util.network.GitHubClient
import de.perigon.companion.track.service.BackgroundService
import de.perigon.companion.core.ui.SnackbarChannel
import de.perigon.companion.util.saf.persistSafGrant
import de.perigon.companion.util.toHex
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import io.ktor.client.HttpClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    @param:ApplicationContext private val ctx: Context,
    private val db:              AppDatabase,
    private val creds:           CredentialStore,
    private val appPrefs:        AppPrefs,
    private val s3Factory:       S3BackendFactory,
    private val httpMediaFactory: HttpMediaBackendFactory,
    private val http:            HttpClient,
    private val sodium:          LazySodiumAndroid,
) : ViewModel() {

    data class SettingsState(
        val b2Configured:          Boolean = false,
        val naclConfigured:        Boolean = false,
        val naclFingerprint:       String  = "",
        val githubConfigured:      Boolean = false,
        val githubLabel:           String  = "",
        val siteUrl:               String  = "",
        val dcimConfigured:        Boolean = false,
        val dcimLabel:             String  = "",
        val journeyStartDate:      String  = "",
        val journeyTitle:          String  = "",
        val journeyTag:            String  = "",
        val backgroundGps:         Boolean = false,
        val autosaveDebounceMs:    Long    = AppPrefs.DEFAULT_AUTOSAVE_DEBOUNCE_MS,
        val autosaveMaxIntervalMs: Long    = AppPrefs.DEFAULT_AUTOSAVE_MAX_INTERVAL_MS,
        val phoneKeyConfigured:    Boolean = false,
        val phoneKeyFingerprint:   String  = "",
        val s3Configured:          Boolean = false,
        val s3Enabled:             Boolean = false,
        val s3Label:               String  = "",
        val httpMediaConfigured:   Boolean = false,
        val httpMediaEnabled:      Boolean = false,
        val httpMediaLabel:        String  = "",
        val httpMediaEndpoint:     String  = "",
        val httpMediaStaticUrl:    String  = "",
        val httpMediaUser:         String  = "",
        val httpMediaHasPassword:  Boolean = false,
        val httpMediaAuthType:     String  = "digest",
        val mediaPickerMode:       String  = AppPrefs.MEDIA_PICKER_SYSTEM,
    )

    private val _state = MutableStateFlow(buildState())
    val state: StateFlow<SettingsState> = _state

    val snackbar = SnackbarChannel()

    private fun buildState(): SettingsState {
        val owner          = appPrefs.githubOwner()
        val repo           = appPrefs.githubRepo()
        val naclHex        = appPrefs.naclPkHex()
        val phonePkHex     = appPrefs.phonePkHex()
        val s3ConfigRaw    = appPrefs.s3MediaConfigRaw()
        val httpConfigRaw  = appPrefs.httpMediaConfigRaw()
        val dcimUri        = appPrefs.dcimTreeUri()
        return SettingsState(
            b2Configured          = creds.b2RwAppKey() != null && appPrefs.b2Endpoint() != null,
            naclConfigured        = naclHex != null,
            naclFingerprint       = naclHex?.fingerprint() ?: "",
            githubConfigured      = creds.githubToken() != null,
            githubLabel           = if (owner != null && repo != null) "$owner/$repo" else "",
            siteUrl               = appPrefs.siteUrl() ?: "",
            dcimConfigured        = dcimUri != null,
            dcimLabel             = appPrefs.dcimTreeLabel() ?: "",
            journeyStartDate      = appPrefs.journeyStartDate() ?: "",
            journeyTitle          = appPrefs.journeyTitle() ?: "",
            journeyTag            = appPrefs.journeyTag() ?: "",
            backgroundGps         = appPrefs.isBackgroundGpsEnabled(),
            autosaveDebounceMs    = appPrefs.autosaveDebounceMs(),
            autosaveMaxIntervalMs = appPrefs.autosaveMaxIntervalMs(),
            phoneKeyConfigured    = phonePkHex != null,
            phoneKeyFingerprint   = phonePkHex?.fingerprint() ?: "",
            s3Configured          = s3ConfigRaw != null && creds.s3SecretKey() != null,
            s3Enabled             = appPrefs.isS3MediaEnabled() && s3ConfigRaw != null && creds.s3SecretKey() != null,
            s3Label               = s3ConfigRaw?.let { "${it.bucket} @ ${it.endpoint.removePrefix("https://")}" } ?: "",
            httpMediaConfigured   = httpConfigRaw != null && creds.httpMediaPassword() != null,
            httpMediaEnabled      = appPrefs.isHttpMediaEnabled() && httpConfigRaw != null && creds.httpMediaPassword() != null,
            httpMediaLabel        = httpConfigRaw?.let { "${it.user} @ ${it.endpoint.removePrefix("https://")}" } ?: "",
            httpMediaEndpoint     = appPrefs.httpMediaEndpoint() ?: "",
            httpMediaStaticUrl    = appPrefs.httpMediaStaticUrl() ?: "",
            httpMediaUser         = appPrefs.httpMediaUser() ?: "",
            httpMediaHasPassword  = creds.httpMediaPassword() != null,
            httpMediaAuthType     = appPrefs.httpMediaAuthType(),
            mediaPickerMode       = appPrefs.mediaPickerMode(),
        )
    }

    fun setDcimTree(uri: Uri) {
        viewModelScope.launch {
            val label = uri.lastPathSegment?.substringAfterLast(':') ?: "DCIM"
            persistSafGrant(ctx, uri, write = true)
            appPrefs.setDcimTree(uri.toString(), label)
            _state.value = buildState()
            snackbar.send("DCIM folder set: $label")
        }
    }

    fun clearDcimTree() {
        viewModelScope.launch {
            appPrefs.clearDcimTree()
            _state.value = buildState()
            snackbar.send("DCIM folder cleared")
        }
    }

    fun setBackgroundGpsEnabled(enabled: Boolean) {
        viewModelScope.launch {
            appPrefs.setBackgroundGpsEnabled(enabled)
            if (!enabled && BackgroundService.status.value.isRecording) {
                ctx.startService(Intent(ctx, BackgroundService::class.java).apply {
                    action = BackgroundService.ACTION_STOP
                })
            }
            _state.value = buildState()
        }
    }

    fun setS3MediaEnabled(enabled: Boolean) {
        viewModelScope.launch {
            appPrefs.setS3MediaEnabled(enabled)
            _state.value = buildState()
        }
    }

    fun setHttpMediaEnabled(enabled: Boolean) {
        viewModelScope.launch {
            appPrefs.setHttpMediaEnabled(enabled)
            _state.value = buildState()
        }
    }

    fun setHttpMediaEndpoint(endpoint: String) {
        viewModelScope.launch {
            appPrefs.setHttpMediaEndpoint(endpoint.trim())
            _state.value = buildState()
        }
    }

    fun setHttpMediaStaticUrl(staticUrl: String) {
        viewModelScope.launch {
            appPrefs.setHttpMediaStaticUrl(staticUrl.trim())
            _state.value = buildState()
        }
    }

    fun setHttpMediaUser(user: String) {
        viewModelScope.launch {
            appPrefs.setHttpMediaUser(user.trim())
            _state.value = buildState()
        }
    }

    fun setHttpMediaPassword(password: String) {
        creds.setHttpMediaPassword(password)
        _state.value = buildState()
    }
    fun setHttpMediaAuthType(type: String) {
        viewModelScope.launch {
            appPrefs.setHttpMediaAuthType(type)
            _state.value = buildState()
        }
    }

    fun clearHttpMediaConfig() {
        viewModelScope.launch {
            appPrefs.clearHttpMediaConfig()
            creds.clearHttpMediaPassword()
            _state.value = buildState()
            snackbar.send("HTTP media config cleared")
        }
    }

    fun setJourneyStartDate(date: String) {
        viewModelScope.launch {
            appPrefs.setJourneyStartDate(date)
            _state.value = buildState()
        }
    }

    fun setJourneyTitle(title: String) {
        _state.update { it.copy(journeyTitle = title) }
        viewModelScope.launch {
            appPrefs.setJourneyTitle(title)
        }
    }

    fun setJourneyTag(tag: String) {
        val cleaned = tag.removePrefix("#").trim()
        _state.update { it.copy(journeyTag = cleaned) }
        viewModelScope.launch {
            appPrefs.setJourneyTag(cleaned)
        }
    }

    fun setSiteUrl(url: String) {
        _state.update { it.copy(siteUrl = url) }
        viewModelScope.launch {
            appPrefs.setSiteUrl(url.trim())
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

    fun setMediaPickerMode(mode: String) {
        viewModelScope.launch {
            appPrefs.setMediaPickerMode(mode)
            _state.value = buildState()
        }
    }

    // ---- Scoped QR handlers ----

    fun onNaclQrScanned(raw: String) {
        runCatching {
            val obj  = Json.parseToJsonElement(raw).jsonObject
            val type = obj["type"]?.jsonPrimitive?.content
            require(type == "nacl_backup_pubkey") { "Expected nacl_backup_pubkey QR, got: $type" }
            val hex = obj["hex"]?.jsonPrimitive?.content ?: error("QR missing 'hex' field")
            require(hex.length == 64) { "Expected 32-byte hex key" }
            viewModelScope.launch { handleNaclKey(hex) }
        }.getOrElse { e ->
            _state.value = buildState()
            snackbar.send("NaCl QR not recognised: ${e.message}")
        }
    }

    fun onB2QrScanned(raw: String) {
        runCatching {
            val obj    = Json.parseToJsonElement(raw).jsonObject
            val keyId  = obj["key_id"]?.jsonPrimitive?.content         ?: error("QR missing 'key_id'")
            val appKey = obj["application_key"]?.jsonPrimitive?.content ?: error("QR missing 'application_key'")
            val endpoint = obj["endpoint"]?.jsonPrimitive?.content
            val bucket   = obj["bucket"]?.jsonPrimitive?.content
            val region   = obj["region"]?.jsonPrimitive?.content
            viewModelScope.launch {
                appPrefs.setB2Config(endpoint = endpoint, keyId = keyId, bucket = bucket, region = region)
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
            val obj   = Json.parseToJsonElement(raw).jsonObject
            val type  = obj["type"]?.jsonPrimitive?.content
            require(type == "github") { "Expected github QR, got: $type" }
            val token = obj["token"]?.jsonPrimitive?.content ?: error("QR missing 'token'")
            val owner = obj["owner"]?.jsonPrimitive?.content ?: error("QR missing 'owner'")
            val repo  = obj["repo"]?.jsonPrimitive?.content  ?: error("QR missing 'repo'")
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

    fun onS3QrScanned(raw: String) {
        runCatching {
            val obj       = Json.parseToJsonElement(raw).jsonObject
            val type      = obj["type"]?.jsonPrimitive?.content
            require(type == "s3_media") { "Expected s3_media QR, got: $type" }
            val endpoint  = obj["endpoint"]?.jsonPrimitive?.content   ?: error("QR missing 'endpoint'")
            val staticUrl = obj["static_url"]?.jsonPrimitive?.content ?: error("QR missing 'static_url'")
            val bucket    = obj["bucket"]?.jsonPrimitive?.content      ?: error("QR missing 'bucket'")
            val keyId     = obj["key_id"]?.jsonPrimitive?.content      ?: error("QR missing 'key_id'")
            val secretKey = obj["secret_key"]?.jsonPrimitive?.content  ?: error("QR missing 'secret_key'")
            val region    = obj["region"]?.jsonPrimitive?.content      ?: error("QR missing 'region'")
            viewModelScope.launch {
                appPrefs.setS3Config(endpoint, staticUrl, bucket, keyId, region)
                creds.setS3SecretKey(secretKey)
                _state.value = buildState()
                snackbar.send("S3 media credentials imported")
            }
        }.getOrElse { e ->
            _state.value = buildState()
            snackbar.send("S3 QR not recognised: ${e.message}")
        }
    }

    fun onHttpMediaQrScanned(raw: String) {
        runCatching {
            val obj       = Json.parseToJsonElement(raw).jsonObject
            val type      = obj["type"]?.jsonPrimitive?.content
            require(type == "http_media") { "Expected http_media QR, got: $type" }
            val endpoint  = obj["endpoint"]?.jsonPrimitive?.content   ?: error("QR missing 'endpoint'")
            val staticUrl = obj["static_url"]?.jsonPrimitive?.content ?: error("QR missing 'static_url'")
            val user      = obj["user"]?.jsonPrimitive?.content       ?: error("QR missing 'user'")
            val password  = obj["password"]?.jsonPrimitive?.content   ?: error("QR missing 'password'")
            viewModelScope.launch {
                appPrefs.setHttpMediaConfig(endpoint, staticUrl, user)
                creds.setHttpMediaPassword(password)
                _state.value = buildState()
                snackbar.send("HTTP media credentials imported")
            }
        }.getOrElse { e ->
            _state.value = buildState()
            snackbar.send("HTTP media QR not recognised: ${e.message}")
        }
    }

    fun onBootstrapQrScanned(raw: String) {
        runCatching {
            val obj  = Json.parseToJsonElement(raw).jsonObject
            val type = obj["type"]?.jsonPrimitive?.content
            require(type == "companion_bootstrap_v1") { "Expected bootstrap QR, got: $type" }

            viewModelScope.launch {
                obj["nacl_public_key_hex"]?.jsonPrimitive?.content?.let { hex ->
                    require(hex.length == 64) { "Expected 32-byte hex key" }
                    handleNaclKey(hex)
                }

                obj["b2"]?.jsonObject?.let { b2 ->
                    val keyId    = b2["key_id"]?.jsonPrimitive?.content         ?: error("Bootstrap missing b2.key_id")
                    val appKey   = b2["application_key"]?.jsonPrimitive?.content ?: error("Bootstrap missing b2.application_key")
                    val endpoint = b2["endpoint"]?.jsonPrimitive?.content
                    val bucket   = b2["bucket"]?.jsonPrimitive?.content
                    val region   = b2["region"]?.jsonPrimitive?.content
                    appPrefs.setB2Config(endpoint = endpoint, keyId = keyId, bucket = bucket, region = region)
                    creds.setB2AppKey(appKey)
                }

                obj["github"]?.jsonObject?.let { gh ->
                    val token = gh["token"]?.jsonPrimitive?.content ?: error("Bootstrap missing github.token")
                    val owner = gh["owner"]?.jsonPrimitive?.content ?: error("Bootstrap missing github.owner")
                    val repo  = gh["repo"]?.jsonPrimitive?.content  ?: error("Bootstrap missing github.repo")
                    appPrefs.setGithubRepo(owner = owner, repo = repo)
                    creds.setGithubToken(token)
                    fetchCname(token, owner, repo)
                }

                obj["s3"]?.jsonObject?.let { s3 ->
                    val endpoint  = s3["endpoint"]?.jsonPrimitive?.content   ?: error("Bootstrap missing s3.endpoint")
                    val staticUrl = s3["static_url"]?.jsonPrimitive?.content ?: error("Bootstrap missing s3.static_url")
                    val bucket    = s3["bucket"]?.jsonPrimitive?.content      ?: error("Bootstrap missing s3.bucket")
                    val keyId     = s3["key_id"]?.jsonPrimitive?.content      ?: error("Bootstrap missing s3.key_id")
                    val secretKey = s3["secret_key"]?.jsonPrimitive?.content  ?: error("Bootstrap missing s3.secret_key")
                    val region    = s3["region"]?.jsonPrimitive?.content      ?: error("Bootstrap missing s3.region")
                    appPrefs.setS3Config(endpoint, staticUrl, bucket, keyId, region)
                    creds.setS3SecretKey(secretKey)
                }

                obj["http"]?.jsonObject?.let { h ->
                    val endpoint  = h["endpoint"]?.jsonPrimitive?.content   ?: error("Bootstrap missing http.endpoint")
                    val staticUrl = h["static_url"]?.jsonPrimitive?.content ?: error("Bootstrap missing http.static_url")
                    val user      = h["user"]?.jsonPrimitive?.content       ?: error("Bootstrap missing http.user")
                    val password  = h["password"]?.jsonPrimitive?.content   ?: error("Bootstrap missing http.password")
                    appPrefs.setHttpMediaConfig(endpoint, staticUrl, user)
                    creds.setHttpMediaPassword(password)
                }

                ensurePhoneKeypair()
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
            val obj  = Json.parseToJsonElement(raw).jsonObject
            val type = obj["type"]?.jsonPrimitive?.content
            when (type) {
                "companion_bootstrap_v1" -> { onBootstrapQrScanned(raw); true }
                "s3_media"               -> { onS3QrScanned(raw); true }
                "http_media"             -> { onHttpMediaQrScanned(raw); true }
                else                     -> false
            }
        }.getOrDefault(false)
    }

    private suspend fun fetchCname(token: String, owner: String, repo: String) {
        if (appPrefs.siteUrl()?.isNotBlank() == true) return
        try {
            val github = GitHubClient(http, token, owner, repo)
            val cname  = github.getFileContent("CNAME")?.trim()
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
            ensurePhoneKeypair()
            _state.value = buildState()
            snackbar.send("New key accepted: ${hex.fingerprint()}")
            return
        }

        if (stored == hex) {
            ensurePhoneKeypair()
            _state.value = buildState()
            snackbar.send("Key unchanged: ${hex.fingerprint()}")
            return
        }

        val b2Config = appPrefs.b2Config()
        val b2RwAppKey = creds.b2RwAppKey()
        if (b2Config == null || b2RwAppKey == null) {
            _state.value = buildState()
            snackbar.send("Key change refused - B2 not configured, cannot verify bucket is empty")
            return
        }

        try {
            val b2       = s3Factory.create(b2Config.endpoint, b2Config.bucket, b2Config.keyId, b2RwAppKey, b2Config.region)
            val prefix   = appPrefs.phonePkHex() ?: "packs"
            val packs    = b2.listObjects(prefix = "$prefix/", maxKeys = 1)

            if (packs.isNotEmpty()) {
                _state.value = buildState()
                snackbar.send("Key change refused - bucket has packs. Run backup_reset.py first.")
                return
            }

            appPrefs.setNaclPkHex(hex)
            db.resetBackupState()
            ensurePhoneKeypair()
            _state.value = buildState()
            snackbar.send("Key changed: ${hex.fingerprint()} - backup state reset")
        } catch (e: Exception) {
            _state.value = buildState()
            snackbar.send("Key change refused - could not check bucket: ${e.message}")
        }
    }

    private suspend fun ensurePhoneKeypair() {
        if (creds.phoneSecretKey() != null && appPrefs.phonePkHex() != null) return
        val pk = ByteArray(32)
        val sk = ByteArray(32)
        sodium.cryptoBoxKeypair(pk, sk)
        creds.setPhoneSecretKey(sk.toHex())
        appPrefs.setPhonePkHex(pk.toHex())
    }

    fun clearSecrets() {
        creds.clearAll()
        _state.value = buildState()
        snackbar.send("Secrets cleared (B2 app key + GitHub token + phone key + S3 secret + HTTP password)")
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
