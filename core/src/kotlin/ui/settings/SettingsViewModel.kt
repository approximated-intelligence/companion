package de.perigon.companion.core.ui.settings

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import de.perigon.companion.core.feature.SettingsSection
import de.perigon.companion.core.feature.StagedConfig
import de.perigon.companion.core.ui.SnackbarChannel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

private const val TAG = "SettingsViewModel"

/**
 * Host for feature settings sections. Owns only what spans sections:
 * bootstrap staging (parse all → confirm → per-section check + apply) and
 * the clear actions.
 */
@HiltViewModel
class SettingsViewModel @Inject constructor(
    sections: Set<@JvmSuppressWildcards SettingsSection>,
) : ViewModel() {

    val sections: List<SettingsSection> = sections.sortedBy { it.order }

    private val _pending = MutableStateFlow<List<StagedConfig>?>(null)

    /** Parsed, NOT yet applied bootstrap parts awaiting confirmation. */
    val pending: StateFlow<List<StagedConfig>?> = _pending.asStateFlow()

    val snackbar = SnackbarChannel()

    /**
     * Stage only. One scan could otherwise silently redirect backups to
     * foreign storage encrypted to a foreign key; the human verifying the
     * summaries against setup_report.pdf is the check.
     *
     * The failure message is shown verbatim: [parseBootstrapEnvelope] names
     * what the user actually scanned, so a "X QR not recognised:" prefix
     * would double up. Only fall back when the exception carries no message.
     *
     * The payload is not logged. This function is the destination for any
     * scan that reaches the bootstrap button, and the first 64 chars of
     * several QRs hold real secrets — the Cloudflare HMAC secret is exactly
     * 64 hex chars, and the GitHub QR and the bootstrap QR both put `ghp_…`
     * in the prefix. [classifyQr] names the shape without echoing content.
     */
    fun onBootstrapScanned(raw: String) {
        Log.i(TAG, "bootstrap scan: kind=${classifyQr(raw)}")
        runCatching {
            val obj = parseBootstrapEnvelope(raw)
            sections.mapNotNull { it.stage(obj) }
        }.onSuccess { staged ->
            Log.i(TAG, "bootstrap staged: ${staged.size} section(s)")
            if (staged.isEmpty()) snackbar.send("Bootstrap QR contains nothing this app uses")
            else _pending.value = staged
        }.onFailure { e ->
            Log.w(TAG, "bootstrap scan failed", e)
            snackbar.send(e.message ?: "Bootstrap QR not recognised")
        }
    }

    /**
     * Each section is checked and applied INDEPENDENTLY. One section's
     * refusal (e.g. backup key change onto a non-empty bucket) must not
     * silently leave the other sections unconfigured — the GitHub token, the
     * media S3 credentials, and the Cloudflare config are separate concerns
     * with separate failure modes. Previously a single refusal aborted the
     * whole import, which is why an unrelated refusal on backup also blocked
     * GitHub from being set up.
     *
     * All-or-nothing is preserved WITHIN a section: its check runs before
     * its apply, so a section either lands whole or not at all. Only the
     * cross-section cascade is removed.
     */
    fun confirmBootstrap() {
        val staged = _pending.value ?: return
        _pending.value = null
        viewModelScope.launch {
            val applied = mutableListOf<String>()
            val refused = mutableListOf<String>()
            val failed  = mutableListOf<String>()

            for ((index, config) in staged.withIndex()) {
                val label = config.summary.firstOrNull() ?: "section ${index + 1}"

                val refusal: String? = try {
                    config.check()
                } catch (e: CancellationException) {
                    Log.w(TAG, "bootstrap check cancelled for $label", e)
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "bootstrap check failed for $label", e)
                    "check failed: ${e.message ?: e.javaClass.simpleName}"
                }

                if (refusal != null) {
                    Log.i(TAG, "bootstrap refused: $label — $refusal")
                    refused += "$label — $refusal"
                    continue
                }

                try {
                    config.apply()
                    Log.i(TAG, "bootstrap applied: $label")
                    applied += label
                } catch (e: CancellationException) {
                    Log.w(TAG, "bootstrap apply cancelled for $label", e)
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "bootstrap apply failed for $label", e)
                    failed += "$label — ${e.message ?: e.javaClass.simpleName}"
                }
            }

            val summary = outcome(applied, refused, failed)
            Log.i(TAG, "bootstrap outcome: $summary")
            snackbar.send(summary)
        }
    }

    /** Pure: one-line summary of a bootstrap import's outcome. */
    private fun outcome(
        applied: List<String>,
        refused: List<String>,
        failed: List<String>,
    ): String = when {
        applied.isEmpty() && refused.isEmpty() && failed.isEmpty() ->
            "Nothing to import"
        refused.isEmpty() && failed.isEmpty() ->
            "Bootstrap applied (${applied.size} section(s))"
        else -> listOfNotNull(
            if (applied.isNotEmpty()) "applied ${applied.size}" else null,
            if (refused.isNotEmpty()) "refused ${refused.size}: ${refused.joinToString("; ")}" else null,
            if (failed.isNotEmpty())  "failed ${failed.size}: ${failed.joinToString("; ")}" else null,
        ).joinToString(" — ")
    }

    fun dismissBootstrap() {
        _pending.value = null
    }

    fun clear(secretsOnly: Boolean) {
        viewModelScope.launch {
            sections.forEach { it.clear(secretsOnly) }
            snackbar.send(if (secretsOnly) "Secrets cleared" else "All settings cleared")
        }
    }
}
