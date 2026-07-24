package de.perigon.companion.util.saf

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import androidx.documentfile.provider.DocumentFile

/**
 * Shared SAF utilities for tree walking, file creation, and permission management.
 * All functions are pure or take explicit Context — no singletons, no DI.
 *
 * Traversal is built on DocumentsContract child cursors: one ContentResolver
 * query per directory (all columns in one projection) instead of DocumentFile's
 * one query per property per file.
 */

// ---------------------------------------------------------------------------
// Permission helpers
// ---------------------------------------------------------------------------

fun hasDcimGrant(context: Context, dcimUri: String?): Boolean {
    if (dcimUri.isNullOrBlank()) return false
    val uri = Uri.parse(dcimUri)
    return context.contentResolver.persistedUriPermissions.any {
        it.uri == uri && it.isReadPermission
    }
}

fun hasDcimWriteGrant(context: Context, dcimUri: String?): Boolean {
    if (dcimUri.isNullOrBlank()) return false
    val uri = Uri.parse(dcimUri)
    return context.contentResolver.persistedUriPermissions.any {
        it.uri == uri && it.isWritePermission
    }
}

fun persistSafGrant(context: Context, uri: Uri, write: Boolean = false) {
    val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or
        if (write) Intent.FLAG_GRANT_WRITE_URI_PERMISSION else 0
    context.contentResolver.takePersistableUriPermission(uri, flags)
}

/**
 * Check whether a persistable read permission is still held for the given URI.
 */
fun hasPersistedReadGrant(context: Context, uri: Uri): Boolean =
    context.contentResolver.persistedUriPermissions.any {
        it.uri == uri && it.isReadPermission
    }

/**
 * Check whether a persistable write permission is still held for the given URI.
 */
fun hasPersistedWriteGrant(context: Context, uri: Uri): Boolean =
    context.contentResolver.persistedUriPermissions.any {
        it.uri == uri && it.isWritePermission
    }

/**
 * Build the initial URI hint for the SAF folder picker pointing to DCIM.
 */
fun dcimPickerInitialUri(): Uri {
    val encoded = Uri.encode("DCIM")
    return Uri.parse("content://com.android.externalstorage.documents/document/primary%3A$encoded")
}

// ---------------------------------------------------------------------------
// Low-level cursor queries — the single IPC path all traversal goes through
// ---------------------------------------------------------------------------

/**
 * One child entry of a directory, fully populated from a single cursor row.
 * [size] is null when the provider does not report a size; 0 is a genuinely
 * empty file. [mtime] falls back to 0 when unreported (self-corrects via the
 * hash cache; a wrong size would corrupt a backup, hence the distinction).
 */
data class ChildDoc(
    val documentId: String,
    val name: String,
    val mimeType: String,
    val mtime: Long,
    val size: Long?,
) {
    val isDirectory: Boolean
        get() = mimeType == DocumentsContract.Document.MIME_TYPE_DIR
}

private val CHILD_PROJECTION = arrayOf(
    DocumentsContract.Document.COLUMN_DOCUMENT_ID,
    DocumentsContract.Document.COLUMN_DISPLAY_NAME,
    DocumentsContract.Document.COLUMN_MIME_TYPE,
    DocumentsContract.Document.COLUMN_LAST_MODIFIED,
    DocumentsContract.Document.COLUMN_SIZE,
)

/**
 * List all children of a directory in ONE ContentResolver query.
 * Returns an empty list on any provider failure (matches DocumentFile.listFiles).
 */
fun queryChildren(
    context: Context,
    treeUri: Uri,
    parentDocumentId: String,
): List<ChildDoc> {
    val childrenUri =
        DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentDocumentId)
    val cursor = try {
        context.contentResolver.query(childrenUri, CHILD_PROJECTION, null, null, null)
    } catch (_: Exception) {
        null
    } ?: return emptyList()

    val results = mutableListOf<ChildDoc>()
    cursor.use { c ->
        while (c.moveToNext()) {
            val docId = c.getString(0) ?: continue
            val name = c.getString(1) ?: continue
            val mime = c.getString(2) ?: ""
            val mtime = if (c.isNull(3)) 0L else c.getLong(3)
            val size = if (c.isNull(4)) null else c.getLong(4)
            results += ChildDoc(docId, name, mime, mtime, size)
        }
    }
    return results
}

/**
 * Find a direct child by display name — one cursor pass instead of
 * DocumentFile.findFile's per-entry getName() IPC scan.
 */
fun findChildByName(
    context: Context,
    treeUri: Uri,
    parentDocumentId: String,
    name: String,
): ChildDoc? =
    queryChildren(context, treeUri, parentDocumentId).firstOrNull { it.name == name }

/**
 * Tree-aware document URI for a document ID (carries the tree grant).
 */
fun documentUriInTree(treeUri: Uri, documentId: String): Uri =
    DocumentsContract.buildDocumentUriUsingTree(treeUri, documentId)

/**
 * Wrap a document within a tree as a DocumentFile. Relies on androidx
 * DocumentFile.fromTreeUri resolving document-in-tree URIs to the document
 * itself (isDocumentUri branch), preserving the tree permission grant.
 */
fun documentFileInTree(context: Context, treeUri: Uri, documentId: String): DocumentFile? =
    DocumentFile.fromTreeUri(context, documentUriInTree(treeUri, documentId))

private fun rootDocumentId(treeUri: Uri): String? = try {
    DocumentsContract.getTreeDocumentId(treeUri)
} catch (_: Exception) {
    null
}

private fun queryDisplayName(context: Context, treeUri: Uri, documentId: String): String? {
    val docUri = documentUriInTree(treeUri, documentId)
    val cursor = try {
        context.contentResolver.query(
            docUri,
            arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME),
            null, null, null,
        )
    } catch (_: Exception) {
        null
    } ?: return null
    cursor.use { c ->
        if (c.moveToFirst()) return c.getString(0)
    }
    return null
}

// ---------------------------------------------------------------------------
// Tree walking
// ---------------------------------------------------------------------------

/**
 * A file found while walking a tree. [size] is null when the provider does
 * not report one; 0 means a genuinely empty file (e.g. .nomedia), which is
 * a valid backup candidate.
 */
data class ScannedFile(
    val path: String,
    val uri: String,
    val mtime: Long,
    val size: Long?,
)

/**
 * Recursively walk a SAF document tree, collecting all files (including
 * empty ones). [prefix] is prepended to build logical paths
 * (e.g. "DCIM/Camera/").
 *
 * Cost: one ContentResolver query per directory.
 */
fun walkDocumentTree(
    context: Context,
    treeUri: Uri,
    prefix: String = "",
): List<ScannedFile> {
    val rootId = rootDocumentId(treeUri) ?: return emptyList()
    val rootPrefix =
        if (prefix.isNotEmpty()) prefix
        else queryDisplayName(context, treeUri, rootId) ?: ""

    val results = mutableListOf<ScannedFile>()
    val stack = ArrayDeque<Pair<String, String>>() // documentId to path prefix
    stack.addLast(rootId to rootPrefix)

    while (stack.isNotEmpty()) {
        val (dirId, dirPrefix) = stack.removeLast()
        for (child in queryChildren(context, treeUri, dirId)) {
            val childPath =
                if (dirPrefix.isEmpty()) child.name else "$dirPrefix/${child.name}"
            if (child.isDirectory) {
                stack.addLast(child.documentId to childPath)
                continue
            }
            results += ScannedFile(
                path = childPath,
                uri = documentUriInTree(treeUri, child.documentId).toString(),
                mtime = child.mtime,
                size = child.size,
            )
        }
    }
    return results
}

/**
 * Walk only immediate children of a subfolder within a tree.
 * Returns null if the subfolder doesn't exist.
 */
fun listSubfolder(
    context: Context,
    treeUri: Uri,
    subfolderName: String,
): List<ScannedFile>? {
    val rootId = rootDocumentId(treeUri) ?: return null
    val subfolder = findChildByName(context, treeUri, rootId, subfolderName) ?: return null
    if (!subfolder.isDirectory) return null
    return queryChildren(context, treeUri, subfolder.documentId)
        .filter { !it.isDirectory }
        .map { child ->
            ScannedFile(
                path = "$subfolderName/${child.name}",
                uri = documentUriInTree(treeUri, child.documentId).toString(),
                mtime = child.mtime,
                size = child.size,
            )
        }
}

// ---------------------------------------------------------------------------
// Navigation and file creation
// ---------------------------------------------------------------------------

private fun navigateToId(context: Context, treeUri: Uri, subfolders: List<String>): String? {
    var currentId = rootDocumentId(treeUri) ?: return null
    for (name in subfolders) {
        val child = findChildByName(context, treeUri, currentId, name) ?: return null
        currentId = child.documentId
    }
    return currentId
}

private fun navigateOrCreateId(context: Context, treeUri: Uri, subfolders: List<String>): String {
    var currentId = rootDocumentId(treeUri) ?: error("Cannot open SAF tree: $treeUri")
    for (name in subfolders) {
        val existing = findChildByName(context, treeUri, currentId, name)
        currentId = if (existing != null) {
            existing.documentId
        } else {
            val created = DocumentsContract.createDocument(
                context.contentResolver,
                documentUriInTree(treeUri, currentId),
                DocumentsContract.Document.MIME_TYPE_DIR,
                name,
            ) ?: error("Cannot create subfolder '$name'")
            DocumentsContract.getDocumentId(created)
        }
    }
    return currentId
}

/**
 * Navigate to (or create) a nested subfolder path within a SAF tree.
 * Returns the DocumentFile for the deepest folder.
 */
fun navigateOrCreate(context: Context, treeUri: Uri, subfolders: List<String>): DocumentFile {
    val folderId = navigateOrCreateId(context, treeUri, subfolders)
    return documentFileInTree(context, treeUri, folderId)
        ?: error("Cannot open SAF tree: $treeUri")
}

/**
 * Navigate to a subfolder without creating it. Returns null if not found.
 */
fun navigateTo(context: Context, treeUri: Uri, subfolders: List<String>): DocumentFile? {
    val folderId = navigateToId(context, treeUri, subfolders) ?: return null
    return documentFileInTree(context, treeUri, folderId)
}

/**
 * Write bytes to a file within a SAF tree, creating parent folders as needed.
 * Deletes any existing file with the same name first.
 * Returns the URI of the created file.
 */
fun writeFile(
    context: Context,
    treeUri: Uri,
    subfolders: List<String>,
    displayName: String,
    mimeType: String,
    bytes: ByteArray,
): Uri {
    val folderId = navigateOrCreateId(context, treeUri, subfolders)

    findChildByName(context, treeUri, folderId, displayName)?.let { existing ->
        try {
            DocumentsContract.deleteDocument(
                context.contentResolver,
                documentUriInTree(treeUri, existing.documentId),
            )
        } catch (_: Exception) {
        }
    }

    val created = DocumentsContract.createDocument(
        context.contentResolver,
        documentUriInTree(treeUri, folderId),
        mimeType,
        displayName,
    ) ?: error("Cannot create file '$displayName'")

    context.contentResolver.openOutputStream(created)?.use { out ->
        out.write(bytes)
    } ?: error("Cannot open output stream for $created")
    return created
}

/**
 * Write from an InputStream to a file within a SAF tree using DocumentsContract.
 * Uses tree-aware URIs throughout to preserve SAF permission grants.
 */
fun writeFileFromStream(
    context: Context,
    treeUri: Uri,
    subfolders: List<String>,
    displayName: String,
    mimeType: String,
    source: java.io.InputStream,
): Uri {
    val folderId = navigateOrCreateId(context, treeUri, subfolders)

    // Delete existing file with same name if present, ignoring errors
    findChildByName(context, treeUri, folderId, displayName)?.let { existing ->
        try {
            DocumentsContract.deleteDocument(
                context.contentResolver,
                documentUriInTree(treeUri, existing.documentId),
            )
        } catch (_: Exception) {
        }
    }

    val created = DocumentsContract.createDocument(
        context.contentResolver,
        documentUriInTree(treeUri, folderId),
        mimeType,
        displayName,
    ) ?: error("Cannot create file '$displayName' in folder $folderId")

    context.contentResolver.openOutputStream(created)?.use { out ->
        source.copyTo(out)
    } ?: error("Cannot open output stream for $created")

    return created
}

// ---------------------------------------------------------------------------
// mtime
// ---------------------------------------------------------------------------

/**
 * Best-effort set of last-modified time on a SAF document.
 */
fun setMtime(context: Context, doc: DocumentFile, mtime: Long) {
    try {
        context.contentResolver.openFileDescriptor(doc.uri, "rw")?.use { pfd ->
            java.io.File("/proc/self/fd/${pfd.fd}").setLastModified(mtime)
        }
    } catch (_: Exception) { }
}

/**
 * Set mtime by URI.
 */
fun setMtime(context: Context, uri: Uri, mtime: Long) {
    try {
        context.contentResolver.openFileDescriptor(uri, "rw")?.use { pfd ->
            java.io.File("/proc/self/fd/${pfd.fd}").setLastModified(mtime)
        }
    } catch (_: Exception) { }
}

// ---------------------------------------------------------------------------
// Query helpers
// ---------------------------------------------------------------------------

/**
 * Collect display names of all files in a subfolder of a SAF tree.
 * One cursor query for the folder lookup, one for the listing.
 */
fun collectFileNames(
    context: Context,
    treeUri: Uri,
    subfolderName: String,
): Set<String> {
    val rootId = rootDocumentId(treeUri) ?: return emptySet()
    val subfolder = findChildByName(context, treeUri, rootId, subfolderName) ?: return emptySet()
    if (!subfolder.isDirectory) return emptySet()
    return queryChildren(context, treeUri, subfolder.documentId)
        .filter { !it.isDirectory }
        .map { it.name }
        .toSet()
}

/**
 * Find a file by name within a subfolder. Returns its DocumentFile or null.
 */
fun findFile(
    context: Context,
    treeUri: Uri,
    subfolders: List<String>,
    displayName: String,
): DocumentFile? {
    val folderId = navigateToId(context, treeUri, subfolders) ?: return null
    val child = findChildByName(context, treeUri, folderId, displayName) ?: return null
    return documentFileInTree(context, treeUri, child.documentId)
}

/**
 * Open an InputStream for a SAF URI.
 */
fun openInputStream(context: Context, uri: Uri): java.io.InputStream? =
    context.contentResolver.openInputStream(uri)

/**
 * Open an InputStream for a SAF URI string.
 */
fun openInputStream(context: Context, uriStr: String): java.io.InputStream? =
    openInputStream(context, Uri.parse(uriStr))
