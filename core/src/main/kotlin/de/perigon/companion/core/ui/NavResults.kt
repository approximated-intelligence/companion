package de.perigon.companion.core.ui

import android.net.Uri
import androidx.lifecycle.SavedStateHandle

/**
 * Keys for results handed between destinations via a back-stack entry's
 * SavedStateHandle. One place instead of string literals at every producer
 * and consumer.
 */
object NavResultKeys {
    /** MediaPicker → MediaPrep: newline-joined content URIs. */
    const val PICKED_URIS = "picked_uris"

    /** TrackRender → MediaPrep: a single rendered-image URI. */
    const val TRACK_URI = "track_uri"

    /** Share intent → MediaPrep or TrackRender: newline-joined content URIs. */
    const val SHARED_URIS = "shared_uris"
}

fun encodeUris(uris: List<Uri>): String = uris.joinToString("\n")

fun decodeUris(raw: String?): List<Uri> =
    raw?.split("\n")?.filter { it.isNotBlank() }?.map(Uri::parse) ?: emptyList()

/** Removes and returns URIs shared into the app for this entry. One-shot. */
fun SavedStateHandle.takeSharedUris(): List<Uri> =
    decodeUris(remove<String>(NavResultKeys.SHARED_URIS))

/**
 * Removes and returns every URI handed to this entry (share, picker, track
 * render). Removal makes each hand-over one-shot: re-running the caller can
 * never re-deliver.
 */
fun SavedStateHandle.takeHandedOverUris(): List<Uri> =
    takeSharedUris() +
        decodeUris(remove<String>(NavResultKeys.PICKED_URIS)) +
        listOfNotNull(remove<String>(NavResultKeys.TRACK_URI)?.let(Uri::parse))
