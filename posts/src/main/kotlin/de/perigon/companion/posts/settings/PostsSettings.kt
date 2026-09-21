package de.perigon.companion.posts.settings

import android.util.Log
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import de.perigon.companion.core.feature.QrScan
import de.perigon.companion.core.feature.SettingsSection
import de.perigon.companion.core.feature.StagedConfig
import de.perigon.companion.core.ui.ScanRow
import de.perigon.companion.core.ui.SectionHeader
import de.perigon.companion.core.ui.StatusRow
import de.perigon.companion.core.ui.ToggleRow
import de.perigon.companion.core.ui.settings.parseTypedQr
import de.perigon.companion.core.ui.settings.req
import de.perigon.companion.core.ui.settings.section
import de.perigon.companion.posts.data.PostsPrefs
import de.perigon.companion.posts.data.PostsSecrets
import de.perigon.companion.posts.network.GitHubClient
import io.ktor.client.HttpClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "PostsSettings"

// ---- QR payloads (pure parsing) ----

internal data class GitHubQr(val token: String, val owner: String, val repo: String)
internal data class S3Qr(val endpoint: String, val staticUrl: String, val bucket: String, val keyId: String, val secretKey: String, val region: String)
internal data class HttpQr(val endpoint: String, val staticUrl: String, val user: String, val password: String)

internal fun JsonObject.gitHub(s: String) = GitHubQr(req(s, "token"), req(s, "owner"), req(s, "repo"))
internal fun JsonObject.s3(s: String) = S3Qr(
    req(s, "endpoint"), req(s, "static_url"), req(s, "bucket"), req(s, "key_id"), req(s, "secret_key"), req(s, "region"),
)
internal fun JsonObject.http(s: String) = HttpQr(req(s, "endpoint"), req(s, "static_url"), req(s, "user"), req(s, "password"))

/**
 * Applies scanned posts configuration. Secrets are written before prefs:
 * the prefs write is what refreshes the settings UI.
 */
@Singleton
class PostsConfig @Inject constructor(
    private val prefs: PostsPrefs,
    private val secrets: PostsSecrets,
    private val http: HttpClient,
) {
    /** Returns the site URL discovered from CNAME, if one was set. */
    internal suspend fun applyGitHub(q: GitHubQr): String? {
        secrets.setGithubToken(q.token)
        prefs.setGithubRepo(q.owner, q.repo)
        return discoverSiteUrl(q)
    }

    internal suspend fun applyS3(q: S3Qr) {
        secrets.setS3SecretKey(q.secretKey)
        prefs.setS3Config(q.endpoint, q.staticUrl, q.bucket, q.keyId, q.region)
    }

    internal suspend fun applyHttp(q: HttpQr) {
        secrets.setHttpMediaPassword(q.password)
        prefs.setHttpMediaConfig(q.endpoint, q.staticUrl, q.user)
    }

    /**
     * Site URL from the repo's CNAME. Uses the just-scanned token directly —
     * it may not be readable from the store yet. Best-effort but logged: a
     * silent failure only shows much later as share links without a host.
     */
    private suspend fun discoverSiteUrl(q: GitHubQr): String? {
        if (prefs.siteUrl()?.isNotBlank() == true) return null
        return try {
            val cname = GitHubClient(http, q.token, q.owner, q.repo).getFileContent("CNAME")?.trim()
            if (cname.isNullOrBlank()) {
                Log.d(TAG, "no CNAME in ${q.owner}/${q.repo} — set the site URL manually")
                null
            } else {
                val url = if (cname.startsWith("http")) cname else "https://$cname"
                prefs.setSiteUrl(url)
                url
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "CNAME lookup failed for ${q.owner}/${q.repo}", e)
            null
        }
    }
}

@Immutable
data class PostsSettingsState(
    val githubConfigured: Boolean = false,
    val githubLabel: String = "",
    val siteUrl: String = "",
    val s3Configured: Boolean = false,
    val s3Enabled: Boolean = false,
    val s3Label: String = "",
    val httpConfigured: Boolean = false,
    val httpEnabled: Boolean = false,
    val httpLabel: String = "",
    val httpHasPassword: Boolean = false,
    val httpAuthType: String = "digest",
    val journeyStartDate: String = "",
    val journeyTitle: String = "",
    val journeyTag: String = "",
    val autosaveDebounceMs: Long = PostsPrefs.DEFAULT_AUTOSAVE_DEBOUNCE_MS,
    val autosaveMaxIntervalMs: Long = PostsPrefs.DEFAULT_AUTOSAVE_MAX_INTERVAL_MS,
    val mediaPickerMode: String = PostsPrefs.MEDIA_PICKER_SYSTEM,
)

@HiltViewModel
class PostsSettingsViewModel @Inject constructor(
    private val prefs: PostsPrefs,
    private val secrets: PostsSecrets,
    private val config: PostsConfig,
) : ViewModel() {

    /** Secret writes don't tick the prefs flow; this does. */
    private val tick = MutableStateFlow(0)

    val state: StateFlow<PostsSettingsState> = combine(prefs.changes(), tick) { _, _ -> read() }
        .stateIn(viewModelScope, SharingStarted.Eagerly, read())

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    private fun read(): PostsSettingsState {
        val owner = prefs.githubOwner()
        val repo = prefs.githubRepo()
        val s3 = prefs.s3MediaConfigRaw()
        val http = prefs.httpMediaConfigRaw()
        val s3Secret = secrets.s3SecretKey() != null
        val httpPassword = secrets.httpMediaPassword() != null
        return PostsSettingsState(
            githubConfigured = secrets.githubToken() != null,
            githubLabel = if (owner != null && repo != null) "$owner/$repo" else "",
            siteUrl = prefs.siteUrl() ?: "",
            s3Configured = s3 != null && s3Secret,
            s3Enabled = prefs.isS3MediaEnabled() && s3 != null && s3Secret,
            s3Label = s3?.let { "${it.bucket} @ ${it.endpoint.removePrefix("https://")}" } ?: "",
            httpConfigured = http != null && httpPassword,
            httpEnabled = prefs.isHttpMediaEnabled() && http != null && httpPassword,
            httpLabel = http?.let { "${it.user} @ ${it.endpoint.removePrefix("https://")}" } ?: "",
            httpHasPassword = httpPassword,
            httpAuthType = prefs.httpMediaAuthType(),
            journeyStartDate = prefs.journeyStartDate() ?: "",
            journeyTitle = prefs.journeyTitle() ?: "",
            journeyTag = prefs.journeyTag() ?: "",
            autosaveDebounceMs = prefs.autosaveDebounceMs(),
            autosaveMaxIntervalMs = prefs.autosaveMaxIntervalMs(),
            mediaPickerMode = prefs.mediaPickerMode(),
        )
    }

    private fun report(what: String, action: suspend () -> String) {
        viewModelScope.launch {
            _message.value = try {
                action()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                "$what QR not recognised: ${e.message}"
            }
            tick.update { it + 1 }
        }
    }

    fun onGitHubScanned(raw: String) = report("GitHub") {
        val url = config.applyGitHub(parseTypedQr(raw, "github").gitHub("github"))
        "GitHub credentials imported" + (url?.let { "; site URL: $it" } ?: "")
    }

    fun onS3Scanned(raw: String) = report("S3") {
        config.applyS3(parseTypedQr(raw, "s3_media").s3("s3_media"))
        "S3 media credentials imported"
    }

    fun onHttpScanned(raw: String) = report("HTTP media") {
        config.applyHttp(parseTypedQr(raw, "http_media").http("http_media"))
        "HTTP media credentials imported"
    }

    private fun save(block: suspend () -> Unit) {
        viewModelScope.launch { block(); tick.update { it + 1 } }
    }

    fun setS3Enabled(enabled: Boolean) = save { prefs.setS3MediaEnabled(enabled) }
    fun setHttpEnabled(enabled: Boolean) = save { prefs.setHttpMediaEnabled(enabled) }
    fun setHttpAuthType(type: String) = save { prefs.setHttpMediaAuthType(type) }
    fun setHttpPassword(password: String) = save { secrets.setHttpMediaPassword(password) }
    fun setSiteUrl(url: String) = save { prefs.setSiteUrl(url.trim()) }
    fun setJourneyStartDate(date: String) = save { prefs.setJourneyStartDate(date) }
    fun setJourneyTitle(title: String) = save { prefs.setJourneyTitle(title) }
    fun setJourneyTag(tag: String) = save { prefs.setJourneyTag(tag.removePrefix("#").trim()) }
    fun setAutosaveDebounceMs(ms: Long) = save { prefs.setAutosaveDebounceMs(ms) }
    fun setAutosaveMaxIntervalMs(ms: Long) = save { prefs.setAutosaveMaxIntervalMs(ms) }
    fun setMediaPickerMode(mode: String) = save { prefs.setMediaPickerMode(mode) }
}

class PostsSettingsSection @Inject constructor(
    private val prefs: PostsPrefs,
    private val secrets: PostsSecrets,
    private val config: PostsConfig,
) : SettingsSection {

    override val order = 200

    @Composable
    override fun Content(scan: QrScan) {
        PostsSettingsContent(scan)
    }

    override fun stage(bootstrap: JsonObject): StagedConfig? {
        val gh = bootstrap.section("github")?.gitHub("github")
        val s3 = bootstrap.section("s3")?.s3("s3")
        val http = bootstrap.section("http")?.http("http")
        if (gh == null && s3 == null && http == null) return null
        return object : StagedConfig {
            override val summary = listOfNotNull(
                gh?.let { "GitHub: ${it.owner}/${it.repo}" },
                s3?.let { "S3: ${it.endpoint}" },
                http?.let { "HTTP: ${it.endpoint}" },
            )

            override suspend fun apply() {
                gh?.let { config.applyGitHub(it) }
                s3?.let { config.applyS3(it) }
                http?.let { config.applyHttp(it) }
            }
        }
    }

    override suspend fun clear(secretsOnly: Boolean) {
        secrets.clear()
        if (!secretsOnly) prefs.clear()
    }
}

// ---- UI ----

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PostsSettingsContent(scan: QrScan, vm: PostsSettingsViewModel = hiltViewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    var showDatePicker by remember { mutableStateOf(false) }

    // Text fields edit local state; persisting on every change must not
    // round-trip through the store into the field (cursor jumps).
    var journeyTitle by remember { mutableStateOf(state.journeyTitle) }
    var journeyTag by remember { mutableStateOf(state.journeyTag) }
    var siteUrl by remember(state.siteUrl.isEmpty()) { mutableStateOf(state.siteUrl) }
    var password by remember { mutableStateOf("") }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SectionHeader("Journey", "Day numbers and post titles are computed from these.")
        StatusRow(
            title = "Start date",
            subtitle = state.journeyStartDate.takeIf { it.isNotBlank() }?.let(::formatJourneyDate) ?: "Not set",
            ok = state.journeyStartDate.isNotBlank(),
            optional = true,
        ) {
            FilledTonalButton(onClick = { showDatePicker = true }) {
                Text(if (state.journeyStartDate.isNotBlank()) "Change" else "Set date")
            }
        }
        OutlinedTextField(
            value = journeyTitle,
            onValueChange = { journeyTitle = it; vm.setJourneyTitle(it) },
            label = { Text("Journey title") },
            placeholder = { Text("e.g. Silk Road") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = journeyTag,
            onValueChange = { journeyTag = it.removePrefix("#"); vm.setJourneyTag(it) },
            label = { Text("Journey tag") },
            placeholder = { Text("e.g. silkroad") },
            prefix = { Text("#") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )

        HorizontalDivider()
        SectionHeader("Editor")
        Text("Media picker", style = MaterialTheme.typography.bodyMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = state.mediaPickerMode == PostsPrefs.MEDIA_PICKER_SYSTEM,
                onClick = { vm.setMediaPickerMode(PostsPrefs.MEDIA_PICKER_SYSTEM) },
                label = { Text("System") },
            )
            FilterChip(
                selected = state.mediaPickerMode == PostsPrefs.MEDIA_PICKER_BUILTIN,
                onClick = { vm.setMediaPickerMode(PostsPrefs.MEDIA_PICKER_BUILTIN) },
                label = { Text("Built-in") },
            )
        }
        Text("Autosave delay", style = MaterialTheme.typography.bodyMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PostsPrefs.AUTOSAVE_OPTIONS_MS.forEach { ms ->
                FilterChip(
                    selected = state.autosaveDebounceMs == ms,
                    onClick = { vm.setAutosaveDebounceMs(ms) },
                    label = { Text(formatSeconds(ms)) },
                )
            }
        }
        Text("Max save interval", style = MaterialTheme.typography.bodyMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PostsPrefs.AUTOSAVE_MAX_INTERVAL_OPTIONS_MS.forEach { ms ->
                FilterChip(
                    selected = state.autosaveMaxIntervalMs == ms,
                    onClick = { vm.setAutosaveMaxIntervalMs(ms) },
                    label = { Text(if (ms < 60_000L) "${ms / 1000}s" else "${ms / 60_000}min") },
                )
            }
        }

        HorizontalDivider()
        SectionHeader("Publishing", "GitHub hosts the site; S3 or HTTP optionally hosts media.")
        ScanRow("GitHub repository", state.githubConfigured, state.githubLabel) {
            scan.request("Scan GitHub config from setup_report.pdf", vm::onGitHubScanned)
        }
        OutlinedTextField(
            value = siteUrl,
            onValueChange = { siteUrl = it; vm.setSiteUrl(it) },
            label = { Text("Site URL") },
            placeholder = { Text("https://yourblog.example.com") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            supportingText = { Text("Auto-detected from CNAME on GitHub scan. Used for share links.") },
        )
        ScanRow("S3 media storage", state.s3Configured, state.s3Label) {
            scan.request("Scan S3 media credentials from setup_report.pdf", vm::onS3Scanned)
        }
        if (state.s3Configured) {
            ToggleRow("S3 media upload", if (state.s3Enabled) "Media uploaded via S3" else "Off", state.s3Enabled, vm::setS3Enabled)
        }
        ScanRow("HTTP media storage", state.httpConfigured, state.httpLabel) {
            scan.request("Scan HTTP media credentials from setup_report.pdf", vm::onHttpScanned)
        }
        if (state.httpConfigured) {
            ToggleRow("HTTP media upload", if (state.httpEnabled) "Media uploaded via HTTP" else "Off", state.httpEnabled, vm::setHttpEnabled)
        }
        Text("HTTP auth type", style = MaterialTheme.typography.bodyMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("basic" to "Basic", "digest" to "Digest").forEach { (type, label) ->
                FilterChip(
                    selected = state.httpAuthType == type,
                    onClick = { vm.setHttpAuthType(type) },
                    label = { Text(label) },
                )
            }
        }
        OutlinedTextField(
            value = password,
            onValueChange = { password = it },
            label = { Text(if (state.httpHasPassword) "HTTP password (set — enter to replace)" else "HTTP password") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth(),
        )
        if (password.isNotBlank()) {
            FilledTonalButton(onClick = { vm.setHttpPassword(password); password = "" }) { Text("Save password") }
        }
        message?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }

    if (showDatePicker) {
        val initial = runCatching {
            LocalDate.parse(state.journeyStartDate).toEpochDay() * 86_400_000L
        }.getOrDefault(System.currentTimeMillis())
        val pickerState = rememberDatePickerState(initialSelectedDateMillis = initial)
        DatePickerDialog(
            onDismissRequest = { showDatePicker = false },
            confirmButton = {
                TextButton(onClick = {
                    pickerState.selectedDateMillis?.let { ms ->
                        vm.setJourneyStartDate(
                            Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()).toLocalDate().toString()
                        )
                    }
                    showDatePicker = false
                }) { Text("OK") }
            },
            dismissButton = { TextButton(onClick = { showDatePicker = false }) { Text("Cancel") } },
        ) { DatePicker(state = pickerState) }
    }
}

/** Pure. */
private fun formatJourneyDate(iso: String): String =
    runCatching { LocalDate.parse(iso).format(DateTimeFormatter.ofPattern("MMMM d, yyyy")) }.getOrDefault(iso)

/** Pure. */
private fun formatSeconds(ms: Long): String = when {
    ms < 1000L -> "${ms}ms"
    ms % 1000L == 0L -> "${ms / 1000}s"
    else -> "%.1fs".format(ms / 1000.0)
}
