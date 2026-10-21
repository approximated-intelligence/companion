package de.perigon.companion.backup.ui

import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import de.perigon.companion.core.saf.ExternalDriveEjectBus
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject

/**
 * Thin VM wrapper over the [BackupDestinationSelection] singleton so both
 * Backup and Consolidate screens can obtain the same state via hiltViewModel
 * without each needing to know the singleton exists. Every screen gets its
 * own instance, but the underlying state is shared.
 *
 * [ejectBus] is exposed so the section can observe system-side eject events
 * without reaching for a second injector dependency in the composable.
 */
@HiltViewModel
class BackupDestinationViewModel @Inject constructor(
    private val selection: BackupDestinationSelection,
    val ejectBus: ExternalDriveEjectBus,
) : ViewModel() {
    val writeToB2: StateFlow<Boolean> = selection.writeToB2
    val writeToDriveUris: StateFlow<Set<String>> = selection.writeToDriveUris

    fun toggleB2() = selection.toggleB2()
    fun toggleDrive(treeUri: String) = selection.toggleDrive(treeUri)
    fun reconcile(present: Set<String>) = selection.reconcile(present)
}
