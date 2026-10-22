package de.perigon.companion.core.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.perigon.companion.core.feature.FeatureEntry
import de.perigon.companion.core.feature.QrScan
import de.perigon.companion.core.feature.SettingsSection
import de.perigon.companion.core.prefs.CorePrefs
import de.perigon.companion.core.ui.SectionHeader
import kotlinx.coroutines.launch
import javax.inject.Inject

class GeneralSettingsSection @Inject constructor(
    private val prefs: CorePrefs,
    private val features: Set<@JvmSuppressWildcards FeatureEntry>,
) : SettingsSection {
    override val id = "general"
    override val order: Int = -1

    @Composable
    override fun Content(scan: QrScan) {
        val scope = rememberCoroutineScope()
        val current by prefs.observeStartupFeatureId()
            .collectAsStateWithLifecycle(initialValue = prefs.startupFeatureId())

        val options = features
            .filter { it.startRoute != null }
            .sortedBy { it.order }

        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SectionHeader(
                "Start screen",
                "Which screen to open when the app starts. Default uses the natural order of features.",
            )
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                FilterChip(
                    selected = current == null,
                    onClick = { scope.launch { prefs.setStartupFeatureId(null) } },
                    label = { Text("Default") },
                )
                options.forEach { f ->
                    FilterChip(
                        selected = current == f.id,
                        onClick = { scope.launch { prefs.setStartupFeatureId(f.id) } },
                        label = { Text(f.displayLabel()) },
                    )
                }
            }
        }
    }

    override suspend fun clear(secretsOnly: Boolean) {
        if (!secretsOnly) prefs.setStartupFeatureId(null)
    }
}

private fun FeatureEntry.displayLabel(): String =
    menuItems.minByOrNull { it.order }?.label ?: id
