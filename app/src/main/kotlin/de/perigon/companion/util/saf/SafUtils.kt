package de.perigon.companion.util.saf

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import androidx.documentfile.provider.DocumentFile

/**
 * Shared SAF utilities for tree walking, file creation, and permission management.
 * All functions are pure or take explicit Context — no singletons, no DI.
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
// Tree walking
// ---------------------------------------------------------------------------

data class ScannedFile(
    val path: String,
    val uri: String,
    val mtime: Long,
    val size: Long,
)

/**
 * Recursively walk a SAF document tree, collecting all files.
 * [prefix] is prepended to build logical paths (e.g. "DCIM/Camera/").
 */
fun walkDocumentTree(
    context: Context,
    treeUri: Uri,
    prefix: String = "",
): List<ScannedFile> {
    val root = DocumentFile.fromTreeUri(context, treeUri) ?: return emptyList()
    val rootName = if (prefix.isNotEmpty()) prefix else (root.name ?: "")
    return walkDocumentTreeRecursive(context, root, rootName)
}

private fun walkDocumentTreeRecursive(
    context: Context,
    dir: DocumentFile,
    prefix: String,
): List<ScannedFile> {
    val results = mutableListOf<ScannedFile>()
    for (file in dir.listFiles()) {
        if (file.isDirectory) {
            val dirName = file.name ?: continue
            val subPrefix = if (prefix.isEmpty()) dirName else "$prefix/$dirName"
            results += walkDocumentTreeRecursive(context, file, subPrefix)
            continue
        }
        val name = file.name ?: continue
        if (name == ".nomedia") continue
        val path = if (prefix.isEmpty()) name else "$prefix/$name"
        results += ScannedFile(
            path = path,
            uri = file.uri.toString(),
            mtime = file.lastModified(),
            size = file.length(),
        )
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
    val root = DocumentFile.fromTreeUri(context, treeUri) ?: return null
    val subfolder = root.findFile(subfolderName) ?: return null
    if (!subfolder.isDirectory) return null
    return subfolder.listFiles().mapNotNull { file ->
        if (file.isDirectory) return@mapNotNull null
        val name = file.name ?: return@mapNotNull null
        ScannedFile(
            path = "$subfolderName/$name",
            uri = file.uri.toString(),
            mtime = file.lastModified(),
            size = file.length(),
        )
    }
}

// ---------------------------------------------------------------------------
// Navigation and file creation
// ---------------------------------------------------------------------------

/**
 * Navigate to (or create) a nested subfolder path within a SAF tree.
 * Returns the DocumentFile for the deepest folder.
 */
fun navigateOrCreate(context: Context, treeUri: Uri, subfolders: List<String>): DocumentFile {
    var current = DocumentFile.fromTreeUri(context, treeUri)
        ?: error("Cannot open SAF tree: $treeUri")
    for (name in subfolders) {
        current = current.findFile(name)
            ?: current.createDirectory(name)
            ?: error("Cannot create subfolder '$name'")
    }
    return current
}

/**
 * Navigate to a subfolder without creating it. Returns null if not found.
 */
fun navigateTo(context: Context, treeUri: Uri, subfolders: List<String>): DocumentFile? {
    var current = DocumentFile.fromTreeUri(context, treeUri) ?: return null
    for (name in subfolders) {
        current = current.findFile(name) ?: return null
    }
    return current
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
    val folder = navigateOrCreate(context, treeUri, subfolders)
    folder.findFile(displayName)?.delete()
    val doc = folder.createFile(mimeType, displayName)
        ?: error("Cannot create file '$displayName'")
    context.contentResolver.openOutputStream(doc.uri)?.use { out ->
        out.write(bytes)
    } ?: error("Cannot open output stream for ${doc.uri}")
    return doc.uri
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
    val folder = navigateOrCreate(context, treeUri, subfolders)

    // Delete existing file with same name if present, ignoring errors
    try {
        folder.findFile(displayName)?.let { existing ->
            DocumentsContract.deleteDocument(context.contentResolver, existing.uri)
        }
    } catch (_: Exception) {
    }

    // Build a tree-aware parent URI that carries the original tree grant
    val folderDocId = DocumentsContract.getDocumentId(folder.uri)
    val parentUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, folderDocId)

    val created = DocumentsContract.createDocument(
        context.contentResolver, parentUri, mimeType, displayName,
    ) ?: error("Cannot create file '$displayName' in $parentUri")

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
 */
fun collectFileNames(
    context: Context,
    treeUri: Uri,
    subfolderName: String,
): Set<String> {
    val root = DocumentFile.fromTreeUri(context, treeUri) ?: return emptySet()
    val subfolder = root.findFile(subfolderName) ?: return emptySet()
    if (!subfolder.isDirectory) return emptySet()
    return subfolder.listFiles()
        .filter { !it.isDirectory }
        .mapNotNull { it.name }
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
    val folder = navigateTo(context, treeUri, subfolders) ?: return null
    return folder.findFile(displayName)
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
