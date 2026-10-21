package de.perigon.companion.backup.ui

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The user's current destination selection for the next backup, shared across
 * every screen that can trigger one (Backup, Consolidate). Session-scoped by
 * design — a drive picked yesterday may be unplugged today, and persisting
 * the drive selection would silently re-check it after a restart.
 *
 * Holds only the explicit choices. Never enumerates drives: that belongs to
 * `rememberConnectedDrives` in core, and the picked set is reconciled against
 * it whenever the composed list changes.
 */
@Singleton
class BackupDestinationSelection @Inject constructor() {

    private val _writeToB2 = MutableStateFlow(true)
    val writeToB2: StateFlow<Boolean> = _writeToB2.asStateFlow()

    private val _writeToDriveUris = MutableStateFlow<Set<String>>(emptySet())
    val writeToDriveUris: StateFlow<Set<String>> = _writeToDriveUris.asStateFlow()

    fun toggleB2() {
        _writeToB2.update { !it }
    }

    fun toggleDrive(treeUri: String) {
        _writeToDriveUris.update { if (treeUri in it) it - treeUri else it + treeUri }
    }

    /** Drop selections for drives that are no longer attached. */
    fun reconcile(present: Set<String>) {
        _writeToDriveUris.update { it.intersect(present) }
    }

    /**
     * Destination strings for `BackupRequests.manual(...)`.
     *
     * Thumb drives are emitted BEFORE B2 so their writes finish first: the
     * user can eject each drive while the B2 leg (usually the slow one) is
     * still uploading. The BackupWorker's per-destination progress in
     * [de.perigon.companion.backup.ui.BackupProgress.currentDestination]
     * drives the eject button's enabled state.
     */
    fun current(): List<String> = buildList {
        _writeToDriveUris.value.forEach { add("THUMBDRIVE:$it") }
        if (_writeToB2.value) add("B2")
    }
}
