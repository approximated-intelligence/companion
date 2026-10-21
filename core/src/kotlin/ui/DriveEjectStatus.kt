package de.perigon.companion.core.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.core.net.toUri
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.perigon.companion.core.saf.ExternalDriveEjectBus
import de.perigon.companion.core.saf.driveUuidOf

/**
 * The set of volume UUIDs the system has told us are going away, as Compose
 * state. Reads the process-wide bus; no per-screen subscription bookkeeping.
 */
@Composable
fun rememberEjectingUuids(bus: ExternalDriveEjectBus): Set<String> {
    val uuidSet by bus.ejectedUuids.collectAsStateWithLifecycle()
    return uuidSet
}

/** Convenience: is the drive behind [treeUri] currently being ejected? */
@Composable
fun rememberIsEjecting(bus: ExternalDriveEjectBus, treeUri: String?): Boolean {
    val ejecting = rememberEjectingUuids(bus)
    if (treeUri == null) return false
    // driveUuidOf already returns null on malformed URIs; no runCatching needed.
    val uuid = remember(treeUri) { driveUuidOf(treeUri.toUri()) }
    return uuid != null && uuid in ejecting
}
