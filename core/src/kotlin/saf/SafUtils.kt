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
import java.io.FileOutputStream
import java.io.InputStream

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

    context.contentResolver.openOutputStream(created)?.use { out ->
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
            context.contentResolver.openFileDescriptor(tmpFile.uri, "rw")
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
            context.contentResolver.openOutputStream(tmpFile.uri)?.use { out ->
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
