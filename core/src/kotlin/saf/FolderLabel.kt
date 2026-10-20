package de.perigon.companion.core.saf

import android.net.Uri

/**
 * Pure: the display name of a SAF tree or document, "primary:DCIM/Camera" →
 * "DCIM/Camera". Replaces nine inline copies of the same expression.
 */
fun folderLabel(uri: Uri, fallback: String = uri.toString()): String =
    uri.lastPathSegment
        ?.substringAfterLast(':')
        ?.takeIf { it.isNotEmpty() }
        ?: fallback
