package de.perigon.companion.backup.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import de.perigon.companion.backup.data.BackupKeys
import de.perigon.companion.backup.data.BackupPrefs
import de.perigon.companion.backup.data.BackupSecrets
import de.perigon.companion.backup.data.KeyDecision
import de.perigon.companion.backup.data.fingerprint
import de.perigon.companion.core.feature.QrScan
import de.perigon.companion.core.feature.SettingsSection
import de.perigon.companion.core.feature.StagedConfig
import de.perigon.companion.core.ui.ScanRow
import de.perigon.companion.core.ui.SectionHeader
import de.perigon.companion.core.ui.StatusRow
import de.perigon.companion.core.ui.settings.parseTypedQr
import de.perigon.companion.core.ui.settings.req
import de.perigon.companion.core.ui.settings.section
import de.perigon.companion.core.ui.settings.str
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import javax.inject.Inject

internal data class B2Qr(
    val keyId: String,
    val appKey: String,
    val endpoint: String?,
    val bucket: String?,
    val region: String?,
)

/** Pure: B2 fields of a QR object; [section] names it in error messages. */
internal fun JsonObject.b2(section: String) = B2Qr(
    keyId    = req(section, "key_id"),
    appKey   = req(section, "application_key"),
    endpoint = str("endpoint"),
    bucket   = str("bucket"),
    region   = str("region"),
)

/** Pure: the hex key of a single NaCl QR. */
internal fun parseNaclQr(raw: String): String {
    val hex = parseTypedQr(raw, "nacl_backup_pubkey").str("hex") ?: error("QR missing 'hex' field")
    require(hex.length == 64) { "Expected 32-byte hex key" }
    return hex
}

@Immutable
data class BackupSettingsState(
    val naclFingerprint: String? = null,
    val b2Configured: Boolean = false,
    val b2Label: String = "",
    val phoneFingerprint: String? = null,
)

@HiltViewModel
class BackupSettingsViewModel @Inject constructor(
    private val prefs: BackupPrefs,
    private val secrets: BackupSecrets,
    private val keys: BackupKeys,
) : ViewModel() {

    val state: StateFlow<BackupSettingsState> = prefs.changes()
        .map { read() }
        .stateIn(viewModelScope, SharingStarted.Eagerly, read())

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    private fun read() = BackupSettingsState(
        naclFingerprint = prefs.naclPkHex()?.fingerprint(),
        b2Configured = secrets.b2RwAppKey() != null && prefs.b2Endpoint() != null,
        b2Label = prefs.b2Config()?.let { "${it.endpoint} / ${it.bucket}" } ?: "",
        phoneFingerprint = prefs.phonePkHex()?.fingerprint(),
    )

    fun onNaclScanned(raw: String) = report("NaCl") {
        val hex = parseNaclQr(raw)
        when (val decision = keys.decide(hex)) {
            is KeyDecision.Refuse -> "Key change refused - ${decision.reason}"
            else -> {
                keys.commit(hex, decision)
                when (decision) {
                    KeyDecision.Unchanged -> "Key unchanged: ${hex.fingerprint()}"
                    KeyDecision.Change -> "Key changed: ${hex.fingerprint()} - backup state reset"
                    else -> "New key accepted: ${hex.fingerprint()}"
                }
            }
        }
    }

    fun onB2Scanned(raw: String) = report("B2") {
        val q = parseTypedQr(raw, expectedType = null).b2("b2")
        // Secret first: the prefs write triggers the UI refresh.
        secrets.setB2AppKey(q.appKey)
        prefs.setB2Config(q.endpoint, q.keyId, q.bucket, q.region)
        "B2 credentials imported"
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
        }
    }
}

class BackupSettingsSection @Inject constructor(
    private val prefs: BackupPrefs,
    private val secrets: BackupSecrets,
    private val keys: BackupKeys,
) : SettingsSection {

    override val order = 100

    @Composable
    override fun Content(scan: QrScan) {
        val vm: BackupSettingsViewModel = hiltViewModel()
        val state by vm.state.collectAsStateWithLifecycle()
        val message by vm.message.collectAsStateWithLifecycle()

        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            SectionHeader("Backup", "Encrypted backup to B2. Secrets are device-bound and not backed up.")
            ScanRow("Backup encryption key", state.naclFingerprint != null, state.naclFingerprint.orEmpty()) {
                scan.request("Scan NaCl public key from setup_report.pdf", vm::onNaclScanned)
            }
            ScanRow("B2 storage", state.b2Configured, state.b2Label) {
                scan.request("Scan B2 credentials from setup_report.pdf", vm::onB2Scanned)
            }
            StatusRow(
                title = "Phone backup key",
                subtitle = state.phoneFingerprint ?: "Not generated - scan the bootstrap QR first",
                ok = state.phoneFingerprint != null,
                optional = true,
            )
            message?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }

    override fun stage(bootstrap: JsonObject): StagedConfig? {
        val hex = bootstrap.str("nacl_public_key_hex")?.also {
            require(it.length == 64) { "Expected 32-byte hex key" }
        }
        val b2 = bootstrap.section("b2")?.b2("b2")
        if (hex == null && b2 == null) return null

        return object : StagedConfig {
            private var decision: KeyDecision? = null

            override val summary = listOfNotNull(
                hex?.let { "Backup key: ${it.fingerprint()}" },
                b2?.let { "B2: ${it.endpoint ?: "?"} / ${it.bucket ?: "?"}" },
            )

            /** Against the CURRENT bucket — a QR cannot vouch for its own. */
            override suspend fun check(): String? {
                if (hex == null) return null
                val d = keys.decide(hex)
                decision = d
                return (d as? KeyDecision.Refuse)?.reason
            }

            override suspend fun apply() {
                if (hex != null) keys.commit(hex, decision ?: keys.decide(hex))
                if (b2 != null) {
                    secrets.setB2AppKey(b2.appKey)
                    prefs.setB2Config(b2.endpoint, b2.keyId, b2.bucket, b2.region)
                }
                keys.ensurePhoneKeypair()
            }
        }
    }

    override suspend fun clear(secretsOnly: Boolean) {
        secrets.clear()
        if (!secretsOnly) prefs.clear()
    }
}
