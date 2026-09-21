package de.perigon.companion.util.saf

import android.net.Uri
import android.provider.DocumentsContract

private const val EXTERNAL_STORAGE_AUTHORITY = "com.android.externalstorage.documents"

/**
 * Whether [treeUri] is the top-level DCIM folder of a storage volume —
 * "primary:DCIM" on internal storage, "XXXX-XXXX:DCIM" on an SD card.
 *
 * The folder picker lets the user choose ANY folder. Everything the app
 * writes (PostMedia and its Originals, consolidation output, restores) lands
 * under the chosen tree, so a mis-pick like Downloads would silently scatter
 * the app's files into the wrong place. Validate before persisting the grant.
 */
fun isDcimTree(treeUri: Uri): Boolean {
    if (treeUri.authority != EXTERNAL_STORAGE_AUTHORITY) return false
    val documentId = runCatching { DocumentsContract.getTreeDocumentId(treeUri) }.getOrNull()
        ?: return false
    return isDcimDocumentId(documentId)
}

/**
 * Pure: a "volume:path" document id that names a volume's top-level DCIM
 * folder. Exact match — "primary:DCIM/Camera" or "primary:Pictures/DCIM"
 * are not DCIM roots.
 */
internal fun isDcimDocumentId(documentId: String): Boolean =
    documentId.substringAfter(':', missingDelimiterValue = "") == "DCIM"
