package de.perigon.companion.core.saf

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Environment
import android.os.storage.StorageManager
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.util.Log
import androidx.core.net.toUri
import androidx.documentfile.provider.DocumentFile
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer

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

/**
 * Every tree URI for which this app holds a persisted write grant. This is
 * the set backup/restore writes to; [enumerateConnectedDrives] further
 * requires read and a mounted removable volume.
 */
fun listGrantedTrees(context: Context): List<Uri> =
    context.contentResolver.persistedUriPermissions
        .filter { it.isWritePermission }
        .map { it.uri }

fun hasDcimWriteGrant(context: Context, dcimUri: String?): Boolean {
    if (dcimUri.isNullOrBlank()) return false
    val uri = dcimUri.toUri()
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
    return "content://com.android.externalstorage.documents/document/primary%3A$encoded".toUri()
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
internal fun documentFileInTree(context: Context, treeUri: Uri, documentId: String): DocumentFile? =
    DocumentFile.fromTreeUri(context, documentUriInTree(treeUri, documentId))

private fun rootDocumentId(treeUri: Uri): String? = try {
    DocumentsContract.getTreeDocumentId(treeUri)
} catch (_: Exception) {
    null
}

/**
 * Tree document id of [uri], or null if [uri] is not a tree URI. Public
 * counterpart of [rootDocumentId], for callers outside saf/ that need to
 * enumerate a tree's top-level children.
 */
fun treeDocumentId(uri: Uri): String? = rootDocumentId(uri)

/** Document id of [uri]. Throws if [uri] is not a document URI. */
fun documentIdOf(uri: Uri): String = DocumentsContract.getDocumentId(uri)

/**
 * Provider-reported display name of a document. The only way for a caller
 * to verify that the name passed to [createDocument] survived provider
 * rewriting; also the reader used by [walkDocumentTree] to seed the root
 * prefix when the caller supplies none.
 */
internal fun queryDisplayName(context: Context, treeUri: Uri, documentId: String): String? {
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
 * Walk a subtree rooted at [rootDocumentId] instead of the tree root. Same
 * cursor-per-directory discipline as [walkDocumentTree]. [prefix] prepends
 * to emitted paths; empty prefix yields paths relative to [rootDocumentId].
 *
 * Used when only one subfolder of a large tree is relevant (e.g. mirroring
 * to <drive>/Mirror/ without scanning the rest of the drive).
 */
fun walkSubtree(
    context: Context,
    treeUri: Uri,
    rootDocumentId: String,
    prefix: String = "",
): List<ScannedFile> {
    val results = mutableListOf<ScannedFile>()
    val stack = ArrayDeque<Pair<String, String>>()
    stack.addLast(rootDocumentId to prefix)

    while (stack.isNotEmpty()) {
        val (dirId, dirPrefix) = stack.removeLast()
        for (child in queryChildren(context, treeUri, dirId)) {
            val childPath = if (dirPrefix.isEmpty()) child.name else "$dirPrefix/${child.name}"
            if (child.isDirectory) {
                stack.addLast(child.documentId to childPath)
            } else {
                results += ScannedFile(
                    path = childPath,
                    uri = documentUriInTree(treeUri, child.documentId).toString(),
                    mtime = child.mtime,
                    size = child.size,
                )
            }
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

/**
 * Create a document under [parentDocId] within [treeUri].
 *
 * Provider contract: the resulting name may differ from [displayName] — the
 * provider substitutes or appends the extension derived from [mimeType]
 * (FileUtils.splitFileName) when [displayName] has none, or has one that
 * maps to a different MIME, and it appends " (N)" on a name collision.
 * Callers needing an exact name must pass one whose extension already maps
 * to [mimeType] (e.g. `.txt` for `text/plain`), or verify with
 * [queryDisplayName] and re-rename.
 */
fun createDocument(
    context: Context,
    treeUri: Uri,
    parentDocId: String,
    mimeType: String,
    displayName: String,
): Uri? = DocumentsContract.createDocument(
    context.contentResolver,
    documentUriInTree(treeUri, parentDocId),
    mimeType,
    displayName,
)

/** Delete by URI. True on success, false on any failure (logged). */
fun deleteDocument(context: Context, uri: Uri): Boolean = try {
    DocumentsContract.deleteDocument(context.contentResolver, uri)
} catch (e: Exception) {
    Log.w("Documents", "delete failed: $uri", e)
    false
}

/** Delete by tree + document id. */
fun deleteDocument(context: Context, treeUri: Uri, docId: String): Boolean =
    deleteDocument(context, documentUriInTree(treeUri, docId))

/**
 * Rename a document. Same provider name-rewriting contract as
 * [createDocument]. Returns the document's new URI, or null on failure.
 */
fun renameDocument(context: Context, uri: Uri, newName: String): Uri? = try {
    DocumentsContract.renameDocument(context.contentResolver, uri, newName)
} catch (e: Exception) {
    Log.w("Documents", "rename failed: $uri -> $newName", e)
    null
}

/**
 * Doc id of the child directory [name] under [parentDocId] in [treeUri];
 * created if absent. Throws if a non-directory occupies the name — silently
 * returning a document id for a file would corrupt the caller's path.
 */
fun findOrCreateChildDirectory(
    context: Context,
    treeUri: Uri,
    parentDocId: String,
    name: String,
): String {
    val existing = findChildByName(context, treeUri, parentDocId, name)
    if (existing != null) {
        if (!existing.isDirectory) error("'$name' exists but is not a directory")
        return existing.documentId
    }
    val created = DocumentsContract.createDocument(
        context.contentResolver,
        documentUriInTree(treeUri, parentDocId),
        DocumentsContract.Document.MIME_TYPE_DIR,
        name,
    ) ?: error("Cannot create folder '$name'")
    return DocumentsContract.getDocumentId(created)
}

fun navigateToId(context: Context, treeUri: Uri, subfolders: List<String>): String? {
    var currentId = rootDocumentId(treeUri) ?: return null
    for (name in subfolders) {
        val child = findChildByName(context, treeUri, currentId, name) ?: return null
        currentId = child.documentId
    }
    return currentId
}

fun navigateOrCreateId(context: Context, treeUri: Uri, subfolders: List<String>): String {
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
internal fun navigateOrCreate(context: Context, treeUri: Uri, subfolders: List<String>): DocumentFile {
    val folderId = navigateOrCreateId(context, treeUri, subfolders)
    return documentFileInTree(context, treeUri, folderId)
        ?: error("Cannot open SAF tree: $treeUri")
}

/**
 * Navigate to a subfolder without creating it. Returns null if not found.
 */
internal fun navigateTo(context: Context, treeUri: Uri, subfolders: List<String>): DocumentFile? {
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

    openOutputStream(context, created)?.use { out ->
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
    source: InputStream,
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

    openOutputStream(context, created)?.use { out ->
        source.copyTo(out)
    } ?: error("Cannot open output stream for $created")

    return created
}

/**
 * Write [source] to [displayName] under [subfolders] atomically and durably:
 *
 *   1. create <name>.tmp under the target folder
 *   2. copy [source] into it, then fsync the write fd
 *   3. delete any existing target with the same name
 *   4. rename .tmp -> name
 *
 * A crash anywhere leaves the target untouched (a stray .tmp is cleaned by
 * the next call). fsync surfaces ENOSPC / EIO / a drive pulled mid-write at
 * write time instead of a later read. Falls back to a plain (non-fsync)
 * write if the provider rejects "rw" mode; the caller accepts that the
 * provider's durability guarantees are weaker.
 */
fun writeFileAtomic(
    context: Context,
    treeUri: Uri,
    subfolders: List<String>,
    displayName: String,
    mimeType: String,
    source: InputStream,
): Uri {
    val folder = navigateOrCreate(context, treeUri, subfolders)
    val tmpName = "$displayName.tmp"

    folder.findFile(tmpName)?.delete()

    val tmpFile = folder.createFile(mimeType, tmpName)
        ?: error("Cannot create $tmpName in ${folder.uri}")

    try {
        val pfd = try {
            openRawFd(context, tmpFile.uri, "rw")
        } catch (_: Exception) {
            null
        }
        if (pfd != null) {
            pfd.use { p ->
                FileOutputStream(p.fileDescriptor).use { out ->
                    source.copyTo(out)
                    out.flush()
                    p.fileDescriptor.sync()
                }
            }
        } else {
            openOutputStream(context, tmpFile.uri)?.use { out ->
                source.copyTo(out)
            } ?: error("Cannot open output stream for ${tmpFile.uri}")
        }
    } catch (e: Exception) {
        tmpFile.delete()
        throw e
    }

    folder.findFile(displayName)?.delete()

    if (!tmpFile.renameTo(displayName)) {
        tmpFile.delete()
        error("Cannot rename ${tmpFile.uri} to $displayName")
    }

    return folder.findFile(displayName)?.uri
        ?: error("Cannot find $displayName after rename")
}

// ---------------------------------------------------------------------------
// mtime
// ---------------------------------------------------------------------------

/**
 * Best-effort set of last-modified time on a SAF document.
 */
fun setMtime(context: Context, doc: DocumentFile, mtime: Long) {
    try {
        openRawFd(context, doc.uri, "rw")?.use { pfd ->
            java.io.File("/proc/self/fd/${pfd.fd}").setLastModified(mtime)
        }
    } catch (_: Exception) { }
}

/**
 * Set mtime by URI.
 */
fun setMtime(context: Context, uri: Uri, mtime: Long) {
    try {
        openRawFd(context, uri, "rw")?.use { pfd ->
            java.io.File("/proc/self/fd/${pfd.fd}").setLastModified(mtime)
        }
    } catch (_: Exception) { }
}

// ---------------------------------------------------------------------------
// Query helpers
// ---------------------------------------------------------------------------

/**
 * Provider-reported MIME type of a document, or null if none (e.g. `file://`
 * URIs, or a document the provider has no type for). Wraps the `getType`
 * IPC — not cheap.
 */
fun mimeTypeOf(context: Context, uri: Uri): String? =
    context.contentResolver.getType(uri)

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
/**
 * Doc id of the file [displayName] under [subfolders] in [treeUri], or null
 * if the folder or file does not exist.
 */
fun findFileId(
    context: Context,
    treeUri: Uri,
    subfolders: List<String>,
    displayName: String,
): String? {
    val folderId = navigateToId(context, treeUri, subfolders) ?: return null
    return findChildByName(context, treeUri, folderId, displayName)?.documentId
}

internal fun findFile(
    context: Context,
    treeUri: Uri,
    subfolders: List<String>,
    displayName: String,
): DocumentFile? {
    val id = findFileId(context, treeUri, subfolders, displayName) ?: return null
    return documentFileInTree(context, treeUri, id)
}

// getType returns null for file:// (no provider) and for any non-media MIME
// — either way, redaction does not apply and the caller reads the bytes
// directly.
private fun isMediaUri(context: Context, uri: Uri): Boolean =
    context.contentResolver.getType(uri)?.substringBefore('/') in setOf("image", "video")

/**
 * Resolve a SAF document URI to the URI that reads the file's ORIGINAL
 * bytes — unredacted EXIF/XMP, GPS intact.
 *
 * Why the re-mint: since Android 10 the platform strips EXIF/GPS from
 * image/video bytes read through a SAF document URI. The only recovery is
 * to map the document URI to its MediaStore identity ([MediaStore.getMediaUri])
 * and ask for the original variant ([MediaStore.setRequireOriginal]), gated
 * on ACCESS_MEDIA_LOCATION. Without this, every photo imported from DCIM or
 * a gallery comes in with its location stripped — which would silently gut
 * geo-tagged posts and track renders. That is the point of this function;
 * everything else is bookkeeping around it.
 *
 * The throws are the safety property, NOT error handling:
 *  - ACCESS_MEDIA_LOCATION denied, OR the file is not in the MediaStore
 *    index, OR setRequireOriginal fails → THROW.
 * The only alternative is to return the redacted bytes, and a caller that
 * asked for "original" must never receive a GPS-stripped file as if it
 * were the original.
 *
 * DO NOT add a "fall back to [uri]" branch on those throw paths. It reads
 * like graceful degradation; it is silent data loss.
 *
 * Non-media files return unchanged: redaction only applies to media, and
 * there is no "original" variant to distinguish. file:// URIs also land
 * here — getType has no provider for them — see PostMediaFileStore.openInputStream
 * for the matching read-side branch.
 */
fun resolveOriginalUri(context: Context, uri: Uri): Uri {
    // Non-media: nothing to promote, nothing to redact.
    if (!isMediaUri(context, uri)) return uri

    if (context.checkSelfPermission(Manifest.permission.ACCESS_MEDIA_LOCATION)
        != PackageManager.PERMISSION_GRANTED) {
        // Throw, not return [uri]: the caller asked for original bytes;
        // handing back redacted ones would be a lie it cannot detect.
        throw SecurityException("ACCESS_MEDIA_LOCATION not granted for $uri")
    }
    val mediaUri = try {
        MediaStore.getMediaUri(context, uri)
    } catch (t: Throwable) {
        throw IllegalStateException("getMediaUri failed for $uri", t)
    } ?: throw IllegalStateException(
        // Not in MediaStore — often a file the scanner has not indexed yet.
        // Throwing preserves "original or nothing". A fallback here would
        // back up / publish EXIF-stripped bytes as originals.
        "not a MediaStore URI: $uri"
    )

    return try {
        MediaStore.setRequireOriginal(mediaUri)
    } catch (t: Throwable) {
        throw IllegalStateException("setRequireOriginal failed for $mediaUri", t)
    }
}

/**
 * Open an InputStream for a SAF URI.
 */
fun openInputStream(context: Context, uri: Uri): InputStream? =
    context.contentResolver.openInputStream(resolveOriginalUri(context, uri))

/**
 * Open an InputStream for a SAF URI string.
 */
fun openInputStream(context: Context, uriStr: String): InputStream? =
    openInputStream(context, uriStr.toUri())

/**
 * Raw read, no [resolveOriginalUri]. Correct when:
 *   - the URI is a NON-media file (redaction never applied), or
 *   - the bytes on the medium were written BY THIS APP from already-
 *     resolved original bytes (e.g. a thumb-drive pack or mirror copy
 *     that this app created), so no further resolution is meaningful.
 *
 * WRONG for verifying a media file that this app wrote to a SAF tree: the
 * SAF URI still routes through MediaProvider, which redacts image/video
 * bytes on read unless [MediaStore.setRequireOriginal] is set. Use
 * [openInputStream] instead.
 *
 * Skips the [isMediaUri] IPC call (one ContentResolver.getType per read),
 * which matters for hot paths like range-reads of pack files.
 */
fun openInputStreamRaw(context: Context, uri: Uri): InputStream? =
    context.contentResolver.openInputStream(uri)

/**
 * Open a file descriptor for a SAF URI, promoted to read original bytes
 * (unredacted EXIF/GPS) the same way [openInputStream] does.
 */
fun openFileDescriptor(context: Context, uri: Uri, mode: String = "r"): android.os.ParcelFileDescriptor? =
    context.contentResolver.openFileDescriptor(resolveOriginalUri(context, uri), mode)

/**
 * Raw file descriptor, no [resolveOriginalUri]. The seekable sibling of
 * [openInputStreamRaw]: use when the caller must position to an absolute
 * byte offset. Same raw-vs-resolved rule — correct for files this app
 * wrote (packs, mirrors, sums) and non-media files; WRONG for verifying a
 * media file that routes through MediaProvider, which needs
 * [openFileDescriptor].
 */
fun openRawFd(context: Context, uri: Uri, mode: String = "r"): android.os.ParcelFileDescriptor? =
    context.contentResolver.openFileDescriptor(uri, mode)

/**
 * Open an OutputStream for a SAF URI. No Raw/Original split — writes never
 * redact, so [resolveOriginalUri] is meaningless here. [mode] follows
 * ContentResolver semantics: "w" truncates, "wa" appends, "wt" truncates
 * and will not create.
 */
fun openOutputStream(context: Context, uri: Uri, mode: String = "w"): OutputStream? =
    context.contentResolver.openOutputStream(uri, mode)

/**
 * Read bytes [from..to] inclusive of a SAF document via a raw fd. Null on
 * any failure — the caller's contract is "null = try elsewhere", not
 * "throw". See [openRawFd] for why the raw (not resolved) opener is right.
 */
fun readRange(context: Context, uri: Uri, from: Long, to: Long): ByteArray? {
    val len = (to - from + 1).toInt()
    if (len <= 0) return null
    return try {
        openRawFd(context, uri)?.use { pfd ->
            FileInputStream(pfd.fileDescriptor).use { fis ->
                val ch = fis.channel
                ch.position(from)
                val buf = ByteBuffer.allocate(len)
                while (buf.hasRemaining()) if (ch.read(buf) <= 0) break
                if (buf.hasRemaining()) null else buf.array()
            }
        }
    } catch (e: Exception) {
        Log.w("RangeRead", "range read failed for $uri [$from..$to]", e)
        null
    }
}

// ---------------------------------------------------------------------------
// Thumb-drive enumeration
// ---------------------------------------------------------------------------

data class DriveInfo(
    val uuid: String,
    val label: String,
    val treeUri: Uri,
)

/**
 * True if [uri] names a removable volume, not internal storage. SAF tree ids
 * are "primary:…" for internal and "XXXX-XXXX:…" for removable — the volume
 * UUID prefix is the only portable distinction across OEMs and API levels.
 */
fun isExternalVolume(uri: Uri): Boolean = try {
    val treeId = DocumentsContract.getTreeDocumentId(uri)
    val uuid = treeId.substringBefore(':')
    uuid.isNotEmpty() && !uuid.equals("primary", ignoreCase = true)
} catch (_: Exception) {
    false
}

/**
 * Every removable volume that is currently MOUNTED and for which this app
 * holds a persisted read+write grant. The intersection is exactly "drives
 * we can write to right now". Drives granted but not plugged in are absent
 * — the caller re-queries on resume.
 */
fun enumerateConnectedDrives(context: Context): List<DriveInfo> {
    val sm = context.getSystemService(StorageManager::class.java) ?: return emptyList()

    val grantsByUuid = mutableMapOf<String, Uri>()
    for (perm in context.contentResolver.persistedUriPermissions) {
        if (!perm.isWritePermission || !perm.isReadPermission) continue
        val treeId = runCatching { DocumentsContract.getTreeDocumentId(perm.uri) }.getOrNull()
            ?: continue
        val uuid = treeId.substringBefore(':')
        if (uuid.isEmpty() || uuid.equals("primary", ignoreCase = true)) continue
        grantsByUuid.putIfAbsent(uuid, perm.uri)
    }
    if (grantsByUuid.isEmpty()) return emptyList()

    return sm.storageVolumes
        .asSequence()
        .filter { it.isRemovable && it.state == Environment.MEDIA_MOUNTED }
        .mapNotNull { vol ->
            val uuid = vol.uuid ?: return@mapNotNull null
            val treeUri = grantsByUuid[uuid] ?: return@mapNotNull null
            DriveInfo(
                uuid = uuid,
                label = vol.getDescription(context) ?: uuid,
                treeUri = treeUri,
            )
        }
        .toList()
}

/**
 * Root URI for a removable volume. [fsUuid] is the volume's fsUuid
 * ("XXXX-XXXX"), the same key [ExternalDriveEjectBus] and FolderGrants use.
 * Used by the eject flow and the settings-UI fallbacks.
 */
fun externalStorageRootUri(fsUuid: String): Uri =
    DocumentsContract.buildRootUri("com.android.externalstorage.documents", fsUuid)

/**
 * MIME type [DocumentsContract.Root] expects for an external-storage root.
 * Exposed so settings-intent construction (DriveEject) can set the type
 * without importing DocumentsContract itself.
 */
val EXTERNAL_STORAGE_ROOT_MIME: String = DocumentsContract.Root.MIME_TYPE_ITEM

/**
 * Ask the platform to eject the removable volume [fsUuid]. Best-effort:
 * some devices do not implement ejectRoot, or refuse while the volume is
 * busy. Returns true if the request was accepted. The caller's fallback
 * is the system-settings eject UI.
 */
fun ejectStorageVolume(context: Context, fsUuid: String): Boolean = try {
    DocumentsContract.ejectRoot(context.contentResolver, externalStorageRootUri(fsUuid))
    true
} catch (_: Exception) {
    false
}

/**
 * Read bytes [from..to] inclusive of [path] under [rootFolder] from the
 * first tree root in [roots] that has it. [path] is interpreted relative
 * to [rootFolder] on each root — e.g. `rootFolder = "CompanionBackup"`,
 * `path = "<pk>/0000000042"`.
 *
 * Null when no root can supply it: [roots] is empty, the file is absent
 * from every root's [rootFolder], or every attempt threw (logged per
 * root). "Null = try the cloud" is the caller contract; whether a root
 * was missing the file or failed to read it is not distinguished,
 * matching the drive-first-B2-second strategy where both fall through to B2.
 *
 * Raw reads throughout (see [openRawFd]): files this app wrote are never
 * MediaStore-indexed, so [resolveOriginalUri] would waste an IPC per root.
 */
suspend fun readRangeFromFirstRoot(
    context: Context,
    roots: List<Uri>,
    rootFolder: String,
    path: String,
    from: Long,
    to: Long,
): ByteArray? {
    if (roots.isEmpty() || to < from) return null
    val parent = path.substringBeforeLast('/', "")
    val name = path.substringAfterLast('/')
    val subfolders = listOf(rootFolder) + parent.split('/').filter { it.isNotEmpty() }
    for (root in roots) {
        val folderId = try { navigateToId(context, root, subfolders) }
                       catch (_: Exception) { null } ?: continue
        val child = try { findChildByName(context, root, folderId, name) }
                    catch (_: Exception) { null } ?: continue
        readRange(context, documentUriInTree(root, child.documentId), from, to)?.let { return it }
    }
    return null
}
