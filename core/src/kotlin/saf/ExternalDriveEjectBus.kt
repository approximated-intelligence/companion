package de.perigon.companion.core.saf

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "ExternalDriveEjectBus"

/**
 * Process-local record of drives the system has asked us to release, either
 * because the user started ejecting them (ACTION_MEDIA_EJECT) or because they
 * physically vanished (StorageVolumeCallback reporting MEDIA_UNMOUNTED /
 * REMOVED / BAD_REMOVAL / UNMOUNTABLE).
 *
 * Eject and mount are SYMMETRIC. The state must be cleared when a volume
 * transitions back to MEDIA_MOUNTED, or a re-plugged drive keeps showing as
 * "ejecting" for the rest of the process's life — that was the bug this
 * class now guards against: without [reportMounted] the UUID set was
 * monotonic, and any drive that had ever been ejected was permanently greyed
 * out on every subsequent mount.
 *
 * Why a singleton: the receiver has to live in the Application, and the
 * worker has to read the signal from a coroutine that is not tied to any
 * Activity. Both sides need one object, and it has to outlive any screen.
 *
 * Workers consult [isEjecting] before each write and check the
 * per-destination generation in [generationOf] to distinguish "ejected" from
 * "transport failure". Screens observe [ejectedUuids] to grey out a drive's
 * eject button the instant the system starts ejecting it.
 *
 * UUID keys, not URIs: the broadcast carries the mount path, not the SAF
 * tree URI, and the volume callback only knows the volume UUID. A tree URI
 * is resolved to its volume UUID at grant time (the "XXXX-XXXX:" prefix of
 * the tree document id), so both signals land on the same key.
 */
@Singleton
class ExternalDriveEjectBus @Inject constructor() {

    private val _ejectedUuids = MutableStateFlow<Set<String>>(emptySet())

    /** Volumes the system has told us are going away, until they come back. */
    val ejectedUuids: StateFlow<Set<String>> = _ejectedUuids.asStateFlow()

    private val _generations = MutableStateFlow<Map<String, Long>>(emptyMap())

    /**
     * Per-UUID monotonic counter, bumped once per eject event. Deliberately
     * not reset by [reportMounted] — the counter answers "did a new eject
     * happen since I started watching?", which is a fact about history, not
     * about current mount state.
     */
    val generations: StateFlow<Map<String, Long>> = _generations.asStateFlow()

    fun isEjecting(uuid: String?): Boolean =
        uuid != null && uuid in _ejectedUuids.value

    /** Bumped each time [reportUnmounted] fires for [uuid]; 0 means never. */
    fun generationOf(uuid: String?): Long =
        if (uuid == null) 0L else _generations.value[uuid] ?: 0L

    /**
     * Called by the Application-level broadcast receiver for
     * ACTION_MEDIA_EJECT / BAD_REMOVAL / REMOVED / UNMOUNTED. The Intent's
     * data is a file:// URI pointing at the mount root, whose last path
     * segment is the volume UUID (or "primary" / "emulated" for internal
     * storage, which we deliberately ignore).
     */
    fun onMediaBroadcast(intent: Intent) {
        val path = intent.data?.path ?: return
        val segment = path.trimEnd('/').substringAfterLast('/')
        if (segment.isEmpty()) return
        if (segment.equals("primary", ignoreCase = true)) return
        if (segment.equals("emulated", ignoreCase = true)) return
        when (intent.action) {
            Intent.ACTION_MEDIA_EJECT,
            Intent.ACTION_MEDIA_BAD_REMOVAL,
            Intent.ACTION_MEDIA_REMOVED,
            Intent.ACTION_MEDIA_UNMOUNTED -> reportUnmounted(segment)
            Intent.ACTION_MEDIA_MOUNTED -> reportMounted(segment)
            else -> Unit
        }
    }

    /** Called by the StorageVolumeCallback fallback. Idempotent. */
    fun reportUnmounted(uuid: String) {
        if (uuid.isEmpty() || uuid.equals("primary", ignoreCase = true)) return
        _ejectedUuids.update { it + uuid }
        _generations.update { it + (uuid to ((it[uuid] ?: 0L) + 1L)) }
        Log.i(TAG, "drive $uuid reported ejecting/unmounted")
    }

    /**
     * Clears the ejecting flag for [uuid]. Called by both the broadcast
     * receiver (ACTION_MEDIA_MOUNTED) and the StorageVolumeCallback
     * (MEDIA_MOUNTED / MEDIA_MOUNTED_READ_ONLY). Without this, a re-plugged
     * drive would stay greyed out as "ejecting" for the rest of the process.
     */
    fun reportMounted(uuid: String) {
        if (uuid.isEmpty() || uuid.equals("primary", ignoreCase = true)) return
        // Fast path: nothing to clear.
        if (uuid !in _ejectedUuids.value) return
        _ejectedUuids.update { it - uuid }
        Log.i(TAG, "drive $uuid re-mounted; cleared ejecting flag")
    }

    /**
     * Resolved receiver. One instance owned here, registered once by the
     * Application, so it is never re-created across activity recreation.
     */
    val receiver: BroadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent == null) return
            onMediaBroadcast(intent)
        }
    }
}

/** Pure: the volume UUID from a SAF tree URI ("XXXX-XXXX:path"), or null. */
fun driveUuidOf(treeUri: Uri): String? = try {
    val id = android.provider.DocumentsContract.getTreeDocumentId(treeUri)
    val uuid = id.substringBefore(':')
    if (uuid.isEmpty() || uuid.equals("primary", ignoreCase = true)) null else uuid
} catch (_: Exception) {
    null
}
