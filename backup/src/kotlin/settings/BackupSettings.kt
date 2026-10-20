package de.perigon.companion.backup.settings

import android.util.Log
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import de.perigon.companion.backup.data.B2Config
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
import de.perigon.companion.core.ui.settings.QrKind
import de.perigon.companion.core.ui.settings.SettingsViewModel
import de.perigon.companion.core.ui.settings.classifyQr
import de.perigon.companion.core.ui.settings.parseTypedQr
import de.perigon.companion.core.ui.settings.req
import de.perigon.companion.core.ui.settings.section
import de.perigon.companion.core.ui.settings.str
import de.perigon.companion.core.ui.settings.wrongQrMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import javax.inject.Inject

private const val TAG = "BackupSettings"

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

/** Pure: a complete [B2Config] from a QR-derived pair, or null if partial. */
private fun B2Qr.asB2Config(): B2Config? {
    val ep = endpoint ?: return null
    val bk = bucket   ?: return null
    val rg = region   ?: return null
    return B2Config(endpoint = ep, bucket = bk, keyId = keyId, region = rg)
}

/** Pure: the hex key of a single NaCl QR. */
internal fun parseNaclQr(raw: String): String {
    val hex = parseTypedQr(raw, "nacl_backup_pubkey").str("hex") ?: error("QR missing 'hex' field")
    require(hex.length == 64) { "Expected 32-byte hex key" }
    return hex
}

/**
 * Pure: the B2 credentials QR. setup.py writes it without a `type` field, so
 * the generic [parseTypedQr] cannot distinguish it from a bootstrap payload.
 * Classify first, and if the user scanned some *other* QR, say so.
 */
internal fun parseB2Qr(raw: String): B2Qr {
    val kind = classifyQr(raw)
    if (kind != QrKind.B2) {
        error(wrongQrMessage(kind, expected = "the B2 credentials QR"))
    }
    return parseTypedQr(raw, expectedType = null).b2("b2")
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

    /**
     * `BackupSettingsState` is derived from BOTH the non-secret prefs
     * (endpoint, bucket, region, pubkey) AND the secrets (B2 app key, phone
     * secret key). Watching only one of them leaves the UI stale whenever the
     * other is what changed — the exact bug that made "B2 credentials
     * imported" appear while the ScanRow still read "Not configured".
     *
     * [SnapshotStore.data] guarantees `cache` is updated before downstream
     * operators run, so `read()` here always sees the value that triggered
     * the emission.
     */
    val state: StateFlow<BackupSettingsState> =
        merge(prefs.changes(), secrets.changes())
            .map { read() }
            .stateIn(viewModelScope, SharingStarted.Eagerly, read())

    private val _message = MutableStateFlow<String?>(null)

    /** Inline detail under the section — same as before. */
    val message: StateFlow<String?> = _message.asStateFlow()

    /**
     * Every scan outcome, as events. A StateFlow would dedupe a repeated
     * success and the UI would never re-notify; a Channel does not. The
     * section forwards these into the host's snackbar so they are visible
     * regardless of scroll position.
     */
    private val _events = Channel<String>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()

    private fun read() = BackupSettingsState(
        naclFingerprint = prefs.naclPkHex()?.fingerprint(),
        b2Configured = secrets.b2RwAppKey() != null && prefs.b2Endpoint() != null,
        b2Label = prefs.b2Config()?.let { "${it.endpoint} / ${it.bucket}" } ?: "",
        phoneFingerprint = prefs.phonePkHex()?.fingerprint(),
    )

    fun onNaclScanned(raw: String) = report {
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

    fun onB2Scanned(raw: String) = report {
        val q = parseB2Qr(raw)
        Log.i(TAG, "B2 scan: endpoint=${q.endpoint} bucket=${q.bucket} keyId=${q.keyId.take(8)}…")

        // Secret first: the prefs write triggers the UI refresh.
        secrets.setB2AppKey(q.appKey)
        prefs.setB2Config(q.endpoint, q.keyId, q.bucket, q.region)

        // Belt-and-suspenders: [SnapshotStore.edit] updates `cache` before
        // returning, so this read-back should always agree with what we just
        // wrote. If it ever does not, the write was lost and the user needs
        // to know *now*, not on the next screen. Kotlin's `check(cond)`
        // carries a contract, so `persisted` is smart-cast non-null after it.
        val persisted = prefs.b2Config()
        check(persisted != null && persisted.endpoint == q.endpoint && persisted.keyId == q.keyId) {
            "B2 config write did not take effect " +
                "(endpoint=${persisted?.endpoint}, keyId=${persisted?.keyId?.take(8)}…) — " +
                "try again; if it persists, this is a DataStore bug"
        }
        Log.i(TAG, "B2 write verified: ${persisted.endpoint} / ${persisted.bucket}")
        "B2 credentials imported"
    }

    /**
     * One place every scan result goes through. Logs unconditionally (so a
     * bug is never silent), publishes via `_message` for the inline row, and
     * pushes onto the event channel for the host snackbar. Cancellation is
     * logged before rethrow so a killed coroutine leaves a trace.
     */
    private fun report(action: suspend () -> String) {
        viewModelScope.launch {
            try {
                val msg = action()
                Log.i(TAG, "scan ok: $msg")
                _message.value = msg
                _events.trySend(msg)
            } catch (e: CancellationException) {
                Log.w(TAG, "scan cancelled: ${e.message}", e)
                throw e
            } catch (e: Exception) {
                val msg = e.message ?: "QR not recognised (${e.javaClass.simpleName})"
                Log.w(TAG, "scan failed: $msg", e)
                _message.value = msg
                _events.trySend(msg)
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
        // Same NavBackStackEntry as SettingsScreen's SettingsViewModel, so
        // this is the same instance. Its snackbar drains into the host's
        // SnackbarHostState — the message is visible whatever the scroll.
        val host: SettingsViewModel = hiltViewModel()

        val state by vm.state.collectAsStateWithLifecycle()
        val message by vm.message.collectAsStateWithLifecycle()

        LaunchedEffect(Unit) {
            vm.events.collect { host.snackbar.send(it) }
        }

        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            // Inline, at the TOP of the section so it is visible without
            // scrolling. Was previously the last row of the section.
            message?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }

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
                val d = keys.decide(
                    hex,
                    b2Override     = b2?.asB2Config(),
                    appKeyOverride = b2?.appKey,
                )
                decision = d
                Log.i(TAG, "bootstrap check: decision=$d")
                return (d as? KeyDecision.Refuse)?.reason
            }

            override suspend fun apply() {
                if (hex != null) keys.commit(hex, decision ?: keys.decide(hex))
                if (b2 != null) {
                    Log.i(TAG, "bootstrap apply: B2 endpoint=${b2.endpoint} bucket=${b2.bucket}")
                    secrets.setB2AppKey(b2.appKey)
                    prefs.setB2Config(b2.endpoint, b2.keyId, b2.bucket, b2.region)
                    val persisted = prefs.b2Config()
                    check(persisted != null && persisted.endpoint == b2.endpoint) {
                        "B2 config write did not take effect during bootstrap apply " +
                            "(endpoint=${persisted?.endpoint})"
                    }
                    Log.i(TAG, "bootstrap apply: B2 verified")
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
