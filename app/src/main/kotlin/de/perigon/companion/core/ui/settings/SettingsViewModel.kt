package de.perigon.companion.core.ui.settings

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.goterl.lazysodium.LazySodiumAndroid
import de.perigon.companion.core.network.S3BackendFactory
import de.perigon.companion.posts.network.HttpMediaBackendFactory
import de.perigon.companion.core.prefs.AppPrefs
import de.perigon.companion.core.prefs.CredentialStore
import de.perigon.companion.core.db.AppDatabase
import de.perigon.companion.posts.network.GitHubClient
import de.perigon.companion.track.service.BackgroundService
import de.perigon.companion.core.ui.SnackbarChannel
import de.perigon.companion.core.saf.persistSafGrant
import de.perigon.companion.core.saf.isDcimTree
import de.perigon.companion.core.util.toHex
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
import kotlin.coroutines.cancellation.CancellationException

private const val TAG = "SettingsViewModel"

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
        val pendingBootstrap:      PendingBootstrap? = null,
    )

    /**
     * Parsed, validated, NOT yet applied bootstrap awaiting user confirmation.
     * The display fields derive from the exact payload that will be applied —
     * what the user verifies is what gets written.
     */
    data class PendingBootstrap(val bootstrap: Bootstrap) {
        val naclFingerprint: String? get() = bootstrap.naclPkHex?.fingerprint()
        val b2:     String? get() = bootstrap.b2?.let { "${it.endpoint ?: "?"} / ${it.bucket ?: "?"}" }
        val github: String? get() = bootstrap.github?.let { "${it.owner}/${it.repo}" }
        val s3:     String? get() = bootstrap.s3?.endpoint
        val http:   String? get() = bootstrap.http?.endpoint
    }

    /** Outcome of offering a NaCl backup key. Side effects done; messaging is the caller's. */
    private sealed interface NaclKeyResult {
        data object Accepted : NaclKeyResult
        data object Unchanged : NaclKeyResult
        data object Changed : NaclKeyResult
        data class Refused(val reason: String) : NaclKeyResult
    }

    private val _state = MutableStateFlow(buildState())
    val state: StateFlow<SettingsState> = _state

    val snackbar = SnackbarChannel()

    /**
     * Rebuild the derived fields, preserving anything the user is currently
     * being asked about. A pending bootstrap is unapplied UI state, not
     * something buildState can recompute: assigning a fresh buildState() while
     * that dialog is open would dismiss it and silently drop the scan.
     */
    private fun refreshState() {
        val pending = _state.value.pendingBootstrap
        _state.value = buildState().copy(pendingBootstrap = pending)
    }

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
        // Same guard as the access gate: everything the app writes lands
        // under this tree, so it must be the DCIM root itself.
        if (!isDcimTree(uri)) {
            snackbar.send("That's not the DCIM folder — select DCIM at the top level of your storage")
            return
        }
        viewModelScope.launch {
            val label = uri.lastPathSegment?.substringAfterLast(':') ?: "DCIM"
            persistSafGrant(ctx, uri, write = true)
            appPrefs.setDcimTree(uri.toString(), label)
            refreshState()
            snackbar.send("DCIM folder set: $label")
        }
    }

    fun clearDcimTree() {
        viewModelScope.launch {
            appPrefs.clearDcimTree()
            refreshState()
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
            refreshState()
        }
    }

    fun setS3MediaEnabled(enabled: Boolean) {
        viewModelScope.launch {
            appPrefs.setS3MediaEnabled(enabled)
            refreshState()
        }
    }

    fun setHttpMediaEnabled(enabled: Boolean) {
        viewModelScope.launch {
            appPrefs.setHttpMediaEnabled(enabled)
            refreshState()
        }
    }

    fun setHttpMediaEndpoint(endpoint: String) {
        viewModelScope.launch {
            appPrefs.setHttpMediaEndpoint(endpoint.trim())
            refreshState()
        }
    }

    fun setHttpMediaStaticUrl(staticUrl: String) {
        viewModelScope.launch {
            appPrefs.setHttpMediaStaticUrl(staticUrl.trim())
            refreshState()
        }
    }

    fun setHttpMediaUser(user: String) {
        viewModelScope.launch {
            appPrefs.setHttpMediaUser(user.trim())
            refreshState()
        }
    }

    fun setHttpMediaPassword(password: String) {
        // KeyStore encryption is disk I/O — off the main thread.
        viewModelScope.launch {
            withContext(Dispatchers.IO) { creds.setHttpMediaPassword(password) }
            refreshState()
        }
    }
    fun setHttpMediaAuthType(type: String) {
        viewModelScope.launch {
            appPrefs.setHttpMediaAuthType(type)
            refreshState()
        }
    }

    fun clearHttpMediaConfig() {
        viewModelScope.launch {
            appPrefs.clearHttpMediaConfig()
            creds.clearHttpMediaPassword()
            refreshState()
            snackbar.send("HTTP media config cleared")
        }
    }

    fun setJourneyStartDate(date: String) {
        viewModelScope.launch {
            appPrefs.setJourneyStartDate(date)
            refreshState()
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
            refreshState()
        }
    }

    fun setAutosaveMaxIntervalMs(ms: Long) {
        viewModelScope.launch {
            appPrefs.setAutosaveMaxIntervalMs(ms)
            refreshState()
        }
    }

    fun setMediaPickerMode(mode: String) {
        viewModelScope.launch {
            appPrefs.setMediaPickerMode(mode)
            refreshState()
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
            viewModelScope.launch {
                val result = handleNaclKey(hex)
                refreshState()
                snackbar.send(result.message(hex))
            }
        }.getOrElse { e ->
            refreshState()
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
                refreshState()
                snackbar.send("B2 credentials imported")
            }
        }.getOrElse { e ->
            refreshState()
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
                refreshState()
                snackbar.send("GitHub credentials imported")
                fetchCname(token, owner, repo)
            }
        }.getOrElse { e ->
            refreshState()
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
                refreshState()
                snackbar.send("S3 media credentials imported")
            }
        }.getOrElse { e ->
            refreshState()
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
                refreshState()
                snackbar.send("HTTP media credentials imported")
            }
        }.getOrElse { e ->
            refreshState()
            snackbar.send("HTTP media QR not recognised: ${e.message}")
        }
    }

    /**
     * Parse + validate fully, then stage — do NOT apply. One scan could
     * otherwise silently redirect all future backups to attacker storage,
     * encrypted to an attacker key: handleNaclKey's bucket-empty check runs
     * against the CURRENT bucket, which a malicious QR can't vouch for. The
     * human verifying fingerprint + endpoints (confirmBootstrap) is the check.
     * A malformed payload is rejected here, before the dialog.
     */
    fun onBootstrapQrScanned(raw: String) {
        runCatching { parseBootstrap(raw) }
            .onSuccess { b -> _state.update { it.copy(pendingBootstrap = PendingBootstrap(b)) } }
            .onFailure { e ->
                refreshState()
                snackbar.send("Bootstrap QR not recognised: ${e.message}")
            }
    }

    fun confirmBootstrap() {
        val pending = _state.value.pendingBootstrap ?: return
        _state.update { it.copy(pendingBootstrap = null) }
        viewModelScope.launch { applyBootstrap(pending.bootstrap) }
    }

    fun dismissBootstrap() { _state.update { it.copy(pendingBootstrap = null) } }

    /**
     * The key goes first and gates everything: a refused key change aborts
     * the import before any destination is written. Previously the refusal
     * was a snackbar-and-return, and the new B2/GitHub/S3/HTTP config was
     * applied anyway — backups redirected to a new bucket under the OLD key.
     */
    private suspend fun applyBootstrap(b: Bootstrap) {
        runCatching {
            b.naclPkHex?.let { hex ->
                val result = handleNaclKey(hex)
                if (result is NaclKeyResult.Refused) error("Key change refused - ${result.reason}")
            }

            b.b2?.let { b2 ->
                appPrefs.setB2Config(endpoint = b2.endpoint, keyId = b2.keyId, bucket = b2.bucket, region = b2.region)
                creds.setB2AppKey(b2.appKey)
            }

            b.github?.let { gh ->
                appPrefs.setGithubRepo(owner = gh.owner, repo = gh.repo)
                creds.setGithubToken(gh.token)
            }

            b.s3?.let { s3 ->
                appPrefs.setS3Config(s3.endpoint, s3.staticUrl, s3.bucket, s3.keyId, s3.region)
                creds.setS3SecretKey(s3.secretKey)
            }

            b.http?.let { h ->
                appPrefs.setHttpMediaConfig(h.endpoint, h.staticUrl, h.user)
                creds.setHttpMediaPassword(h.password)
            }

            ensurePhoneKeypair()
        }.onSuccess {
            refreshState()
            snackbar.send("Bootstrap configuration imported")
            b.github?.let { gh -> fetchCname(gh.token, gh.owner, gh.repo) }
        }.onFailure { e ->
            refreshState()
            snackbar.send("Bootstrap import failed: ${e.message}")
        }
    }

    fun onAnyQrScanned(raw: String): Boolean {
        val type = runCatching {
            Json.parseToJsonElement(raw).jsonObject["type"]?.jsonPrimitive?.content
        }.getOrNull()
        // Bootstrap-only: it merely stages + asks for confirmation. Cross-type
        // credential QRs (s3_media / http_media) must go through the specific
        // scan the user chose — auto-applying them from any scan context was
        // silent credential injection.
        if (type != BOOTSTRAP_TYPE) return false
        onBootstrapQrScanned(raw)
        return true
    }

    /**
     * Discover the site URL from the repo's CNAME. Best-effort — but LOGGED:
     * a silent failure here leaves siteUrl empty, and the only symptom is
     * share links missing their host much later.
     */
    private suspend fun fetchCname(token: String, owner: String, repo: String) {
        if (appPrefs.siteUrl()?.isNotBlank() == true) return
        try {
            val github = GitHubClient(http, token, owner, repo)
            val cname  = github.getFileContent("CNAME")?.trim()
            if (cname.isNullOrBlank()) {
                Log.d(TAG, "no CNAME in $owner/$repo — set the site URL manually")
                return
            }
            val url = if (cname.startsWith("http")) cname else "https://$cname"
            appPrefs.setSiteUrl(url)
            refreshState()
            snackbar.send("Site URL discovered: $url")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "CNAME lookup failed for $owner/$repo", e)
        }
    }

    /**
     * Accept, keep, or refuse a backup key. A CHANGE is only allowed when the
     * current bucket holds no packs — otherwise existing backups would become
     * undecryptable to the server side's key.
     */
    private suspend fun handleNaclKey(hex: String): NaclKeyResult {
        val stored = appPrefs.naclPkHex()

        if (stored == null) {
            appPrefs.setNaclPkHex(hex)
            ensurePhoneKeypair()
            return NaclKeyResult.Accepted
        }

        if (stored == hex) {
            ensurePhoneKeypair()
            return NaclKeyResult.Unchanged
        }

        val b2Config = appPrefs.b2Config()
        val b2RwAppKey = creds.b2RwAppKey()
        if (b2Config == null || b2RwAppKey == null) {
            return NaclKeyResult.Refused("B2 not configured, cannot verify bucket is empty")
        }

        return try {
            val b2     = s3Factory.create(b2Config.endpoint, b2Config.bucket, b2Config.keyId, b2RwAppKey, b2Config.region)
            val prefix = appPrefs.phonePkHex() ?: "packs"
            val packs  = b2.listObjects(prefix = "$prefix/", maxKeys = 1)

            if (packs.isNotEmpty()) {
                NaclKeyResult.Refused("bucket has packs. Run backup_reset.py first.")
            } else {
                appPrefs.setNaclPkHex(hex)
                db.resetBackupState()
                ensurePhoneKeypair()
                NaclKeyResult.Changed
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "bucket check failed during key change", e)
            NaclKeyResult.Refused("could not check bucket: ${e.message}")
        }
    }

    private fun NaclKeyResult.message(hex: String): String = when (this) {
        NaclKeyResult.Accepted  -> "New key accepted: ${hex.fingerprint()}"
        NaclKeyResult.Unchanged -> "Key unchanged: ${hex.fingerprint()}"
        NaclKeyResult.Changed   -> "Key changed: ${hex.fingerprint()} - backup state reset"
        is NaclKeyResult.Refused -> "Key change refused - $reason"
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
        // KeyStore clear is disk I/O — off the main thread.
        viewModelScope.launch {
            withContext(Dispatchers.IO) { creds.clearAll() }
            refreshState()
            snackbar.send("Secrets cleared (B2 app key + GitHub token + phone key + S3 secret + HTTP password)")
        }
    }

    fun clearAll() {
        viewModelScope.launch {
            creds.clearAll()
            appPrefs.clearAll()
            refreshState()
            snackbar.send("All settings cleared")
        }
    }
}

private fun String.fingerprint(): String =
    if (length >= 16) "${take(8)}…${takeLast(8)}" else this
