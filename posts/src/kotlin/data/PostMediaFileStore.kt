package de.perigon.companion.posts.data

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.util.Log
import android.webkit.MimeTypeMap
import androidx.core.net.toUri
import dagger.hilt.android.qualifiers.ApplicationContext
import de.perigon.companion.core.prefs.FolderGrants
import de.perigon.companion.core.prefs.FolderKeys
import de.perigon.companion.core.saf.documentUriInTree
import de.perigon.companion.core.saf.findChildByName
import de.perigon.companion.core.saf.findFile
import de.perigon.companion.core.saf.hasPersistedWriteGrant
import de.perigon.companion.core.saf.isReadable as canRead
import de.perigon.companion.core.saf.navigateOrCreate
import de.perigon.companion.core.saf.queryChildren
import de.perigon.companion.core.saf.writeFile
import de.perigon.companion.core.saf.writeFileFromStream
import de.perigon.companion.core.util.copyWithSha256
import de.perigon.companion.core.util.sha256Hex
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "PostMediaFileStore"

enum class MediaSourceStatus { ORIGINAL, POST_MEDIA, GONE }

data class ResolvedMedia(
    val uri: Uri,
    val status: MediaSourceStatus,
)

/** An original copied into PostMedia/Originals, named by its content hash. */
data class ImportedOriginal(
    val contentHash: String,
    val uri: String,
    val mimeType: String,
)

/**
 * Owns the posts media folders under the DCIM grant — and nothing else:
 *
 *   DCIM/PostMedia/            processed, published bytes — "<processedHash>.<ext>".
 *   DCIM/PostMedia/Originals/  imported originals — "<contentHash>.<realExt>".
 *
 * There is no app-private fallback: DCIM is a required grant, so an
 * unreadable original is broken state, never a reason to look elsewhere.
 */
@Singleton
class PostMediaFileStore @Inject constructor(
    @param:ApplicationContext private val ctx: Context,
    private val folderGrants: FolderGrants,
) {
    companion object {
        private const val SUBFOLDER = "PostMedia"
        private const val ORIGINALS = "Originals"
        private const val NOMEDIA = ".nomedia"
    }

    @Volatile
    private var noMediaEnsured = false

    private fun treeUri(): Uri? = folderGrants.uri(FolderKeys.DCIM)?.toUri()

    fun rootLabel(): String =
        folderGrants.label(FolderKeys.DCIM)?.let { "$it/$SUBFOLDER" } ?: "DCIM not granted"

    fun hasWritePermission(): Boolean =
        treeUri()?.let { hasPersistedWriteGrant(ctx, it) } ?: false

    /** Open-and-close probe; never reads content. */
    fun isReadable(uriStr: String): Boolean = canRead(ctx, uriStr)

    /** A PROCESSED copy: a direct child of PostMedia (exact parent match). */
    fun isPostMediaUri(uriStr: String): Boolean = isInOwnFolder(uriStr, listOf(SUBFOLDER))

    /** An imported original: a direct child of PostMedia/Originals. */
    fun isOriginalUri(uriStr: String): Boolean = isInOwnFolder(uriStr, listOf(SUBFOLDER, ORIGINALS))

    private fun isInOwnFolder(uriStr: String, folders: List<String>): Boolean {
        if (uriStr.isBlank()) return false
        val tree = treeUri() ?: return false
        if (!uriStr.startsWith(tree.toString())) return false
        val treeId = runCatching { DocumentsContract.getTreeDocumentId(tree) }.getOrNull() ?: return false
        val docId = runCatching { DocumentsContract.getDocumentId(uriStr.toUri()) }.getOrNull() ?: return false
        return folderWithinTree(treeId, docId) == folders
    }

    /**
     * Delete [uri] if it is one of this app's own cache temp files (frame
     * grabs): once imported, the copy lives on in Originals.
     */
    fun discardIfTemp(uri: Uri) {
        if (uri.scheme != "file") return
        val file = File(uri.path ?: return)
        if (file.canonicalPath.startsWith(ctx.cacheDir.canonicalPath + File.separator) && !file.delete()) {
            Log.w(TAG, "could not delete temp $file")
        }
    }

    /** Document id of the (created-if-needed) PostMedia folder, plus one-time .nomedia provisioning. */
    private fun postMediaFolderId(treeUri: Uri): String? {
        val rootId = runCatching { DocumentsContract.getTreeDocumentId(treeUri) }.getOrNull() ?: return null
        val folderId = findChildByName(ctx, treeUri, rootId, SUBFOLDER)?.documentId
            ?: runCatching {
                navigateOrCreate(ctx, treeUri, listOf(SUBFOLDER))
                findChildByName(ctx, treeUri, rootId, SUBFOLDER)?.documentId
            }.onFailure { Log.w(TAG, "cannot create $SUBFOLDER", it) }.getOrNull()
            ?: return null

        if (!noMediaEnsured) {
            ensureNoMedia(treeUri, folderId)
            noMediaEnsured = true
        }
        return folderId
    }

    /** Lookup only — never creates. */
    private fun existingOriginalsFolderId(treeUri: Uri): String? {
        val rootId = runCatching { DocumentsContract.getTreeDocumentId(treeUri) }.getOrNull() ?: return null
        val postMedia = findChildByName(ctx, treeUri, rootId, SUBFOLDER) ?: return null
        return findChildByName(ctx, treeUri, postMedia.documentId, ORIGINALS)?.documentId
    }

    /** Keep the folder gallery-hidden; self-heals once per process. */
    private fun ensureNoMedia(treeUri: Uri, folderId: String) {
        try {
            if (findChildByName(ctx, treeUri, folderId, NOMEDIA) != null) return
            writeFile(ctx, treeUri, listOf(SUBFOLDER), NOMEDIA, "application/octet-stream", ByteArray(0))
        } catch (e: Exception) {
            Log.w(TAG, "cannot write $NOMEDIA — PostMedia may show in galleries", e)
        }
    }

    // ---- Query ----

    fun queryMimeType(uri: Uri): String? = ctx.contentResolver.getType(uri)

    /** Processed copy first, then the original; missing media is broken, not "look elsewhere". */
    fun resolveDisplayMedia(entity: PostMediaEntity): ResolvedMedia = when {
        isReadable(entity.mediaStoreUri) -> ResolvedMedia(entity.mediaStoreUri.toUri(), MediaSourceStatus.POST_MEDIA)
        isReadable(entity.sourceUri) -> ResolvedMedia(entity.sourceUri.toUri(), MediaSourceStatus.ORIGINAL)
        else -> ResolvedMedia(Uri.EMPTY, MediaSourceStatus.GONE)
    }

    fun resolveDisplayUri(entity: PostMediaEntity): Uri = resolveDisplayMedia(entity).uri

    // ---- Originals ----

    /**
     * Copy [source] into PostMedia/Originals. One read yields both the copy
     * (via a temp file) and its content hash; content already present is not
     * written again. Throws on any failure — the caller must NOT attach.
     */
    suspend fun importOriginal(source: Uri): ImportedOriginal = withContext(Dispatchers.IO) {
        val tree = treeUri() ?: error("DCIM folder not granted")
        val mimeType = queryMimeType(source) ?: mimeTypeFromName(source.toString()) ?: "application/octet-stream"
        val temp = File.createTempFile("import_", ".tmp", ctx.cacheDir)
        try {
            val hash = openInputStream(source.toString())?.use { input ->
                FileOutputStream(temp).use { output -> copyWithSha256(input, output) }
            } ?: error("Cannot read $source")

            val name = "$hash.${extensionFor(mimeType)}"
            val uri = findOriginalByName(tree, name) ?: writeOriginal(tree, name, mimeType, temp)
            ImportedOriginal(contentHash = hash, uri = uri, mimeType = mimeType)
        } finally {
            temp.delete()
        }
    }

    /** Locate an imported original by content hash (any extension). */
    fun findOriginal(contentHash: String): Uri? {
        if (contentHash.isBlank()) return null
        val tree = treeUri() ?: return null
        val folderId = existingOriginalsFolderId(tree) ?: return null
        return queryChildren(ctx, tree, folderId)
            .firstOrNull { !it.isDirectory && it.name.startsWith("$contentHash.") }
            ?.let { documentUriInTree(tree, it.documentId) }
    }

    /** Delete an imported original; refuses anything outside PostMedia/Originals. */
    suspend fun deleteOriginalIfOwned(uriStr: String): Boolean = withContext(Dispatchers.IO) {
        if (!isOriginalUri(uriStr)) return@withContext false
        try {
            DocumentsContract.deleteDocument(ctx.contentResolver, uriStr.toUri())
        } catch (e: Exception) {
            Log.w(TAG, "cannot delete original $uriStr", e)
            false
        }
    }

    private fun findOriginalByName(tree: Uri, name: String): String? {
        val folderId = existingOriginalsFolderId(tree) ?: return null
        return findChildByName(ctx, tree, folderId, name)
            ?.let { documentUriInTree(tree, it.documentId).toString() }
    }

    private fun writeOriginal(tree: Uri, name: String, mimeType: String, source: File): String {
        postMediaFolderId(tree) ?: error("Cannot create $SUBFOLDER") // also ensures .nomedia
        return source.inputStream().use { input ->
            writeFileFromStream(ctx, tree, listOf(SUBFOLDER, ORIGINALS), name, mimeType, input)
        }.toString()
    }

    // ---- Processed copies ----

    suspend fun placeImageResult(tempFile: File): PostMediaPlacement? = withContext(Dispatchers.IO) {
        placeResult(tempFile, "jpg")
    }

    suspend fun placeVideoResult(tempFile: File): PostMediaPlacement? = withContext(Dispatchers.IO) {
        placeResult(tempFile, "mp4")
    }

    private fun placeResult(tempFile: File, ext: String): PostMediaPlacement? {
        val hash = FileInputStream(tempFile).use { sha256Hex(it) }
        val uri = writeProcessed("$hash.$ext", tempFile)
        tempFile.delete()
        return uri?.let { PostMediaPlacement(hash, it) }
    }

    fun writeFromBytes(baseName: String, ext: String, bytes: ByteArray): String? {
        val displayName = "$baseName.$ext"
        val tempFile = File(ctx.cacheDir, "pull_$displayName")
        return try {
            tempFile.writeBytes(bytes)
            writeProcessed(displayName, tempFile)
        } finally {
            tempFile.delete()
        }
    }

    private fun writeProcessed(displayName: String, source: File): String? {
        val tree = treeUri() ?: return null
        postMediaFolderId(tree) ?: return null
        val mimeType = if (displayName.endsWith(".mp4")) "video/mp4" else "image/jpeg"
        return try {
            source.inputStream().use { input ->
                writeFileFromStream(ctx, tree, listOf(SUBFOLDER), displayName, mimeType, input)
            }.toString()
        } catch (e: Exception) {
            Log.e(TAG, "cannot write $displayName", e)
            null
        }
    }

    // ---- Read ----

    /** Exact-name lookup of a processed copy "<hash>.<ext>". */
    fun resolveUri(hash: String, mimeType: String): Uri? {
        if (hash.isBlank()) return null
        val tree = treeUri() ?: return null
        val ext = if (mimeType.startsWith("video")) "mp4" else "jpg"
        return findFile(ctx, tree, listOf(SUBFOLDER), "$hash.$ext")?.uri
    }

    /** file:// only for this app's own temp files (frame grabs); everything else via the resolver. */
    fun openInputStream(uriStr: String): InputStream? {
        val uri = uriStr.toUri()
        return when (uri.scheme) {
            "file" -> uri.path?.let(::File)?.takeIf { it.exists() }?.inputStream()
            else -> ctx.contentResolver.openInputStream(uri)
        }
    }

    /** Processed copies only (direct children of PostMedia; Originals is a directory). */
    fun listFiles(): List<PostMediaFileInfo> {
        val tree = treeUri() ?: return emptyList()
        val folderId = postMediaFolderId(tree) ?: return emptyList()
        return queryChildren(ctx, tree, folderId)
            .filter { !it.isDirectory }
            .map { child ->
                PostMediaFileInfo(
                    path = child.name,
                    uri = DocumentsContract.buildDocumentUriUsingTree(tree, child.documentId).toString(),
                    mtime = child.mtime,
                    size = child.size ?: 0L,
                )
            }
    }
}

/**
 * Pure: the folder chain between a tree's root document and a document
 * inside it, e.g. ("primary:DCIM", "primary:DCIM/PostMedia/Originals/ab.jpg")
 * → [PostMedia, Originals]. Null when the document is not under the tree.
 */
internal fun folderWithinTree(treeDocumentId: String, documentId: String): List<String>? {
    val prefix = "$treeDocumentId/"
    if (!documentId.startsWith(prefix)) return null
    return documentId.removePrefix(prefix).split('/').dropLast(1)
}

/** Pure-ish (platform table): MIME type from a file name / URL extension. */
private fun mimeTypeFromName(name: String): String? {
    val ext = MimeTypeMap.getFileExtensionFromUrl(name).lowercase()
    if (ext.isEmpty()) return null
    return MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext)
}

/** Pure-ish (platform table): extension for a MIME type, kept truthful (HEIC stays .heic). */
private fun extensionFor(mimeType: String): String =
    MimeTypeMap.getSingleton().getExtensionFromMimeType(mimeType)
        ?: when {
            mimeType.startsWith("image/") -> "jpg"
            mimeType.startsWith("video/") -> "mp4"
            else -> "bin"
        }

data class PostMediaPlacement(
    val processedHash: String,
    val uri: String,
)

data class PostMediaFileInfo(
    val path: String,
    val uri: String,
    val mtime: Long,
    val size: Long,
)
