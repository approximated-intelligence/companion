package de.perigon.companion.backup.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AssistChip
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import de.perigon.companion.backup.consolidate.data.ConsolidationPrefs
import de.perigon.companion.backup.consolidate.domain.ConsolidationMode
import de.perigon.companion.backup.consolidate.domain.ConsolidationSettings
import de.perigon.companion.backup.consolidate.domain.MIN_BBOX
import de.perigon.companion.core.feature.QrScan
import de.perigon.companion.core.feature.SettingsSection
import de.perigon.companion.core.ui.SectionHeader
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Consolidation output format settings. Relocated from the Consolidate
 * screen: these are set-once knobs (mode, bboxes), not per-run
 * parameters; the Consolidate screen stays operational.
 *
 * No bootstrap QR support — no secrets and no cross-device config here.
 * clear(secretsOnly = true) is a no-op; clear(false) resets to defaults.
 */
class ConsolidationSettingsSection @Inject constructor(
    private val prefs: ConsolidationPrefs,
) : SettingsSection {

    override val order = 110

    @Composable
    override fun Content(scan: QrScan) {
        ConsolidationSettingsContent()
    }

    override suspend fun clear(secretsOnly: Boolean) {
        if (!secretsOnly) prefs.update { ConsolidationSettings() }
    }
}

@HiltViewModel
class ConsolidationSettingsViewModel @Inject constructor(
    private val prefs: ConsolidationPrefs,
) : ViewModel() {

    val state: StateFlow<ConsolidationSettings> = prefs.observe()
        .stateIn(viewModelScope, SharingStarted.Eagerly, prefs.get())

    fun setMode(mode: ConsolidationMode) {
        viewModelScope.launch { prefs.update { it.copy(mode = mode) } }
    }

    fun setBboxPhotos(px: Int) {
        viewModelScope.launch { prefs.update { it.copy(bboxPhotos = px) } }
    }

    fun setBboxPngs(px: Int) {
        viewModelScope.launch { prefs.update { it.copy(bboxPngs = px) } }
    }

    fun setBboxVideos(px: Int) {
        viewModelScope.launch { prefs.update { it.copy(bboxVideos = px) } }
    }
}

@Composable
private fun ConsolidationSettingsContent(
    vm: ConsolidationSettingsViewModel = hiltViewModel(),
) {
    val settings by vm.state.collectAsStateWithLifecycle()

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SectionHeader(
            "Consolidation",
            "Output format for consolidated copies in DCIM/Consolidated.",
        )

        Text("Mode", style = MaterialTheme.typography.bodyMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = settings.mode == ConsolidationMode.COMPATIBLE,
                onClick  = { vm.setMode(ConsolidationMode.COMPATIBLE) },
                label    = { Text(ConsolidationMode.COMPATIBLE.displayName) },
            )
            FilterChip(
                selected = settings.mode == ConsolidationMode.EFFICIENT,
                onClick  = { vm.setMode(ConsolidationMode.EFFICIENT) },
                label    = { Text(ConsolidationMode.EFFICIENT.displayName) },
            )
        }
        Text(
            if (settings.mode == ConsolidationMode.EFFICIENT)
                "AVIF stills, AV1 video. Smaller files, requires encoder support."
            else "JPEG stills, H.264 video. Works on every device.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        HorizontalDivider()

        Text("Bounding boxes", style = MaterialTheme.typography.bodyMedium)
        Text(
            "Long-side pixel cap. Files within the box are copied unchanged.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        EditableBoxChip("Photos", settings.bboxPhotos, vm::setBboxPhotos)
        EditableBoxChip("PNGs",   settings.bboxPngs,   vm::setBboxPngs)
        EditableBoxChip("Videos", settings.bboxVideos, vm::setBboxVideos)
    }
}

/**
 * Looks like an AssistChip; becomes an editable number field when tapped.
 * Any value >= MIN_BBOX is accepted; no preset list.
 */
@Composable
private fun EditableBoxChip(label: String, value: Int, onChange: (Int) -> Unit) {
    var editing by remember { mutableStateOf(false) }
    var text    by remember(value, editing) { mutableStateOf(value.toString()) }

    if (editing) {
        Row(
            verticalAlignment     = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier              = Modifier.fillMaxWidth(),
        ) {
            Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.width(72.dp))
            OutlinedTextField(
                value         = text,
                onValueChange = { text = it.filter(Char::isDigit).take(5) },
                singleLine    = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier      = Modifier.width(140.dp),
                trailingIcon  = {
                    IconButton(onClick = {
                        text.toIntOrNull()?.takeIf { it >= MIN_BBOX }?.let(onChange)
                        editing = false
                    }) { Icon(Icons.Default.Check, "Apply") }
                },
            )
        }
    } else {
        Row(
            verticalAlignment     = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier              = Modifier.fillMaxWidth(),
        ) {
            Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.width(72.dp))
            AssistChip(
                onClick      = { editing = true },
                label        = { Text("$value px") },
                trailingIcon = { Icon(Icons.Default.Edit, "Edit", Modifier.width(14.dp)) },
            )
        }
    }
}
