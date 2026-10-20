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
 * bootstrap staging (parse all → confirm → check all → apply all) and the
 * clear actions.
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
     */
    fun onBootstrapScanned(raw: String) {
        runCatching {
            val obj = parseBootstrapEnvelope(raw)
            sections.mapNotNull { it.stage(obj) }
        }.onSuccess { staged ->
            if (staged.isEmpty()) snackbar.send("Bootstrap QR contains nothing this app uses")
            else _pending.value = staged
        }.onFailure { e ->
            snackbar.send("Bootstrap QR not recognised: ${e.message}")
        }
    }

    /** Every check before any apply: one refusal aborts the whole import. */
    fun confirmBootstrap() {
        val staged = _pending.value ?: return
        _pending.value = null
        viewModelScope.launch {
            try {
                val refusal = staged.firstNotNullOfOrNull { it.check() }
                if (refusal != null) {
                    snackbar.send("Bootstrap import refused: $refusal")
                    return@launch
                }
                staged.forEach { it.apply() }
                snackbar.send("Bootstrap configuration imported")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "bootstrap apply failed", e)
                snackbar.send("Bootstrap import failed: ${e.message}")
            }
        }
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
