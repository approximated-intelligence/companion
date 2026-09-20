package de.perigon.companion.posts.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import android.webkit.MimeTypeMap
import androidx.core.content.FileProvider
import de.perigon.companion.core.prefs.AppPrefs
import de.perigon.companion.util.copyWithSha256
import de.perigon.companion.util.saf.documentUriInTree
import de.perigon.companion.util.saf.findChildByName
import de.perigon.companion.util.saf.findFile
import de.perigon.companion.util.saf.navigateOrCreate
import de.perigon.companion.util.saf.queryChildren
import de.perigon.companion.util.saf.writeFile
import de.perigon.companion.util.saf.writeFileFromStream
import de.perigon.companion.util.sha256Hex
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import javax.inject.Inject
import javax.inject.Singleton

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
 * Owns the app's media folders under the DCIM SAF grant (app-private
 * fallback only when no grant exists):
 *
 *   DCIM/PostMedia/            processed, published bytes — "<processedHash>.<ext>",
 *                              mirroring the server key exactly.
 *   DCIM/PostMedia/Originals/  imported originals — "<contentHash>.<realExt>".
 *                              Every attached media's sourceUri points here, so a
 *                              post never depends on a gallery file, a temporary
 *                              share grant, or an evictable cache file.
 *
 * Both are content-addressed: identical content dedups across posts, and a
 * rewrite overwrites instead of accumulating. The .nomedia in PostMedia hides
 * the subfolder too, so neither copy appears in galleries.
 */
@Singleton
class PostMediaFileStore @Inject constructor(
    @param:ApplicationContext private val ctx: Context,
    private val appPrefs: AppPrefs,
) {
    companion object {
        private const val SUBFOLDER = "PostMedia"
        private const val ORIGINALS = "Originals"
        private const val LEGACY_DIR_NAME = "PostMedia"
        private const val NOMEDIA = ".nomedia"
    }

    @Volatile
    private var noMediaEnsured = false

    fun rootLabel(): String {
        val dcimLabel = appPrefs.dcimTreeLabel()
        return if (dcimLabel != null) "DCIM/$SUBFOLDER" else "App-private (no DCIM grant)"
    }

    /**
     * Check if PostMedia folder is writable. Uses the DCIM SAF grant.
     */
    fun hasWritePermission(): Boolean {
        val dcimUri = appPrefs.dcimTreeUri() ?: return false
        val uri = Uri.parse(dcimUri)
        return ctx.contentResolver.persistedUriPermissions.any {
            it.uri == uri && it.isWritePermission
        }
    }

    /**
     * A PROCESSED copy: a direct child of PostMedia. Exact parent-folder
     * match — the previous contains("PostMedia") also matched
     * PostMedia/Originals, which would have let PublishWorker ship a raw
     * original as if it were already processed.
     */
    fun isPostMediaUri(uriStr: String): Boolean = isInOwnFolder(uriStr, listOf(SUBFOLDER))

    /** An imported original: a direct child of PostMedia/Originals. */
    fun isOriginalUri(uriStr: String): Boolean = isInOwnFolder(uriStr, listOf(SUBFOLDER, ORIGINALS))

    private fun isInOwnFolder(uriStr: String, folders: List<String>): Boolean {
        if (uriStr.isBlank()) return false
        val uri = Uri.parse(uriStr)
        if (uri.scheme == "file") {
            val parent = uri.path?.let { File(it).parentFile } ?: return false
            val own = File(ctx.getExternalFilesDir(null), folders.joinToString("/"))
            return parent.absolutePath == own.absolutePath
        }
        val dcimUri = appPrefs.dcimTreeUri() ?: return false
        if (!uriStr.startsWith(dcimUri)) return false
        val treeId = runCatching { DocumentsContract.getTreeDocumentId(Uri.parse(dcimUri)) }.getOrNull()
            ?: return false
        val docId = runCatching { DocumentsContract.getDocumentId(uri) }.getOrNull() ?: return false
        return folderWithinTree(treeId, docId) == folders
    }

    /**
     * Best-effort persist of a read URI permission.
     */
    fun tryPersistReadPermission(uri: Uri): Boolean {
        return try {
            ctx.contentResolver.takePersistableUriPermission(
                uri, Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
            true
        } catch (_: SecurityException) {
            false
        }
    }

    private fun dcimTreeUri(): Uri? = appPrefs.dcimTreeUri()?.let { Uri.parse(it) }

    /**
     * Document id of the (created-if-needed) PostMedia folder, plus one-time
     * .nomedia provisioning. Cursor-based lookups throughout — no per-file
     * DocumentFile IPC.
     */
    private fun postMediaFolderId(treeUri: Uri): String? {
        val rootId = try {
            DocumentsContract.getTreeDocumentId(treeUri)
        } catch (_: Exception) {
            return null
        }
        val folderId = findChildByName(ctx, treeUri, rootId, SUBFOLDER)?.documentId
            ?: try {
                navigateOrCreate(ctx, treeUri, listOf(SUBFOLDER))
                findChildByName(ctx, treeUri, rootId, SUBFOLDER)?.documentId
            } catch (_: Exception) {
                null
            }
            ?: return null

        if (!noMediaEnsured) {
            ensureNoMedia(treeUri, folderId)
            noMediaEnsured = true
        }
        return folderId
    }

    /** Lookup only — never creates. Null when PostMedia/Originals doesn't exist. */
    private fun existingOriginalsFolderId(treeUri: Uri): String? {
        val rootId = runCatching { DocumentsContract.getTreeDocumentId(treeUri) }.getOrNull() ?: return null
        val postMedia = findChildByName(ctx, treeUri, rootId, SUBFOLDER) ?: return null
        return findChildByName(ctx, treeUri, postMedia.documentId, ORIGINALS)?.documentId
    }

    /**
     * Keep the folder gallery-hidden; the .nomedia is size 0 and therefore
     * a valid backup candidate — a restored PostMedia stays hidden too.
     * Self-heals if deleted (checked once per process).
     */
    private fun ensureNoMedia(treeUri: Uri, folderId: String) {
        try {
            if (findChildByName(ctx, treeUri, folderId, NOMEDIA) != null) return
            writeFile(ctx, treeUri, listOf(SUBFOLDER), NOMEDIA, "application/octet-stream", ByteArray(0))
        } catch (_: Exception) {
            // best effort — worst case the gallery shows PostMedia files
        }
    }

    private fun legacyDir(): File {
        val dir = File(ctx.getExternalFilesDir(null), LEGACY_DIR_NAME)
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    private fun legacyOriginalsDir(): File {
        val dir = File(legacyDir(), ORIGINALS)
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    fun legacyMediaDir(): File = File(ctx.filesDir, "post_media")

    fun shareableUri(uriStr: String): Uri {
        val uri = Uri.parse(uriStr)
        if (uri.scheme != "file") return uri
        val file = File(uri.path!!)
        return FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", file)
    }

    // ---- Query utilities ----

    fun queryMimeType(uri: Uri): String? =
        ctx.contentResolver.getType(uri)

    fun computeContentHash(uri: Uri): String =
        ctx.contentResolver.openInputStream(uri)?.use { sha256Hex(it) } ?: ""

    /**
     * Resolve the best available URI for display and report source.
     * Tries the processed PostMedia copy, then the original (normally our own
     * copy in Originals), then hash-named lookups.
     */
    fun resolveDisplayMedia(entity: PostMediaEntity): ResolvedMedia {
        if (entity.mediaStoreUri.isNotBlank()) {
            try {
                openInputStream(entity.mediaStoreUri)?.use {
                    return ResolvedMedia(Uri.parse(entity.mediaStoreUri), MediaSourceStatus.POST_MEDIA)
                }
            } catch (_: Exception) {}
        }
        if (entity.sourceUri.isNotBlank()) {
            try {
                openInputStream(entity.sourceUri)?.use {
                    return ResolvedMedia(Uri.parse(entity.sourceUri), MediaSourceStatus.ORIGINAL)
                }
            } catch (_: Exception) {}
        }
        entity.processedHash?.let { hash ->
            resolveUri(hash, entity.mimeType)?.let {
                return ResolvedMedia(it, MediaSourceStatus.POST_MEDIA)
            }
        }
        findOriginal(entity.contentHash)?.let {
            return ResolvedMedia(it, MediaSourceStatus.ORIGINAL)
        }
        resolveUri(entity.contentHash, entity.mimeType)?.let {
            return ResolvedMedia(it, MediaSourceStatus.POST_MEDIA)
        }
        return ResolvedMedia(Uri.EMPTY, MediaSourceStatus.GONE)
    }

    fun resolveDisplayUri(entity: PostMediaEntity): Uri =
        resolveDisplayMedia(entity).uri

    // ---- Originals ----

    /**
     * Copy [source] into PostMedia/Originals. One read of the source yields
     * both the copy (via a temp file) and its content hash; the file is named
     * by that hash, so content already present is not written again.
     * Throws on any failure — the caller must then NOT attach the media.
     */
    suspend fun importOriginal(source: Uri): ImportedOriginal = withContext(Dispatchers.IO) {
        val mimeType = queryMimeType(source) ?: mimeTypeFromName(source.toString()) ?: "application/octet-stream"
        val temp = File.createTempFile("import_", ".tmp", ctx.cacheDir)
        try {
            val hash = openInputStream(source.toString())?.use { input ->
                FileOutputStream(temp).use { output -> copyWithSha256(input, output) }
            } ?: error("Cannot read $source")

            val name = "$hash.${extensionFor(mimeType)}"
            val uri = findOriginalByName(name) ?: writeOriginal(name, mimeType, temp)
            ImportedOriginal(contentHash = hash, uri = uri, mimeType = mimeType)
        } finally {
            temp.delete()
        }
    }

    /** Locate an imported original by content hash (any extension). */
    fun findOriginal(contentHash: String): Uri? {
        if (contentHash.isBlank()) return null
        val prefix = "$contentHash."
        val tree = dcimTreeUri()
        if (tree != null) {
            val folderId = existingOriginalsFolderId(tree) ?: return null
            return queryChildren(ctx, tree, folderId)
                .firstOrNull { !it.isDirectory && it.name.startsWith(prefix) }
                ?.let { documentUriInTree(tree, it.documentId) }
        }
        return legacyOriginalsDir().listFiles()
            ?.firstOrNull { it.name.startsWith(prefix) }
            ?.let { Uri.fromFile(it) }
    }

    /**
     * Delete an imported original. Refuses anything outside
     * PostMedia/Originals — rows that still reference gallery files must
     * never cause a gallery deletion. Returns true if a file was deleted.
     */
    suspend fun deleteOriginalIfOwned(uriStr: String): Boolean = withContext(Dispatchers.IO) {
        if (!isOriginalUri(uriStr)) return@withContext false
        val uri = Uri.parse(uriStr)
        runCatching {
            if (uri.scheme == "file") File(uri.path!!).delete()
            else DocumentsContract.deleteDocument(ctx.contentResolver, uri)
        }.getOrDefault(false)
    }

    private fun findOriginalByName(name: String): String? {
        val tree = dcimTreeUri()
        if (tree != null) {
            val folderId = existingOriginalsFolderId(tree) ?: return null
            return findChildByName(ctx, tree, folderId, name)
                ?.let { documentUriInTree(tree, it.documentId).toString() }
        }
        return File(legacyOriginalsDir(), name).takeIf { it.exists() }?.let { Uri.fromFile(it).toString() }
    }

    private fun writeOriginal(name: String, mimeType: String, source: File): String {
        val tree = dcimTreeUri()?.takeIf { postMediaFolderId(it) != null } // also ensures .nomedia
        if (tree != null) {
            return source.inputStream().use { input ->
                writeFileFromStream(ctx, tree, listOf(SUBFOLDER, ORIGINALS), name, mimeType, input)
            }.toString()
        }
        val out = File(legacyOriginalsDir(), name)
        source.inputStream().use { input -> FileOutputStream(out).use { input.copyTo(it) } }
        return Uri.fromFile(out).toString()
    }

    // ---- Write operations (processed copies) ----

    suspend fun placeImageResult(tempFile: File): PostMediaPlacement? = withContext(Dispatchers.IO) {
        placeResult(tempFile, "jpg")
    }

    suspend fun placeVideoResult(tempFile: File): PostMediaPlacement? = withContext(Dispatchers.IO) {
        placeResult(tempFile, "mp4")
    }

    private fun placeResult(tempFile: File, ext: String): PostMediaPlacement? {
        val hash = FileInputStream(tempFile).use { sha256Hex(it) }
        val uri = writeFile("$hash.$ext", tempFile)
        tempFile.delete()
        return if (uri != null) PostMediaPlacement(hash, uri) else null
    }

    fun writeFromBytes(baseName: String, ext: String, bytes: ByteArray): String? {
        val displayName = "$baseName.$ext"
        val tempFile = File(ctx.cacheDir, "pull_$displayName")
        return try {
            tempFile.writeBytes(bytes)
            writeFile(displayName, tempFile)
        } finally {
            tempFile.delete()
        }
    }

    private fun writeFile(displayName: String, source: File): String? {
        val treeUri = dcimTreeUri()
        return if (treeUri != null && postMediaFolderId(treeUri) != null) {
            writeToSafFolder(treeUri, displayName, source)
        } else {
            writeToLegacyDir(displayName, source)
        }
    }

    private fun writeToLegacyDir(displayName: String, source: File): String {
        val dir = legacyDir()
        val outFile = File(dir, displayName)
        source.inputStream().use { input ->
            FileOutputStream(outFile).use { output -> input.copyTo(output) }
        }
        return Uri.fromFile(outFile).toString()
    }

    private fun writeToSafFolder(treeUri: Uri, displayName: String, source: File): String? {
        val mimeType = if (displayName.endsWith(".mp4")) "video/mp4" else "image/jpeg"
        return try {
            source.inputStream().use { input ->
                writeFileFromStream(ctx, treeUri, listOf(SUBFOLDER), displayName, mimeType, input)
            }.toString()
        } catch (_: Exception) {
            null
        }
    }

    // ---- Read / resolve ----

    /**
     * Exact-name lookup of a processed copy: files are "<hash>.<ext>" — one
     * cursor query. Legacy app-private locations checked as before.
     */
    fun resolveUri(hash: String, mimeType: String): Uri? {
        if (hash.isBlank()) return null
        val ext = if (mimeType.startsWith("video")) "mp4" else "jpg"
        dcimTreeUri()?.let { treeUri ->
            findFile(ctx, treeUri, listOf(SUBFOLDER), "$hash.$ext")?.let { return it.uri }
        }
        findInLegacyDir(hash, ext)?.let { return it }
        val legacy = File(legacyMediaDir(), "$hash.$ext")
        if (legacy.exists()) return Uri.fromFile(legacy)
        return null
    }

    fun openInputStream(uriStr: String): java.io.InputStream? {
        val uri = Uri.parse(uriStr)
        return when (uri.scheme) {
            "file" -> {
                val file = File(uri.path!!)
                if (file.exists()) file.inputStream() else null
            }
            else -> ctx.contentResolver.openInputStream(uri)
        }
    }

    /** Processed copies only (direct children of PostMedia; Originals is a directory). */
    fun listFiles(): List<PostMediaFileInfo> {
        val treeUri = dcimTreeUri()
        val folderId = treeUri?.let { postMediaFolderId(it) }
        return if (treeUri != null && folderId != null) {
            listSafPostMedia(treeUri, folderId)
        } else {
            listLegacyDir()
        }
    }

    // ---- SAF PostMedia operations (cursor-based, one query per listing) ----

    private fun listSafPostMedia(treeUri: Uri, folderId: String): List<PostMediaFileInfo> {
        return queryChildren(ctx, treeUri, folderId)
            .filter { !it.isDirectory }
            .map { child ->
                PostMediaFileInfo(
                    path = child.name,
                    uri = DocumentsContract.buildDocumentUriUsingTree(treeUri, child.documentId).toString(),
                    mtime = child.mtime,
                    size = child.size ?: 0L,
                )
            }
    }

    // ---- Legacy dir operations ----

    private fun findInLegacyDir(hash: String, ext: String): Uri? {
        val dir = legacyDir()
        val match = dir.listFiles()?.firstOrNull { it.isFile && it.name.contains(hash) }
        if (match != null) return Uri.fromFile(match)
        val byHash = File(dir, "$hash.$ext")
        if (byHash.exists()) return Uri.fromFile(byHash)
        return null
    }

    private fun listLegacyDir(): List<PostMediaFileInfo> {
        val dir = legacyDir()
        return dir.listFiles()?.filter { it.isFile }?.map { file ->
            PostMediaFileInfo(
                path = file.name,
                uri = Uri.fromFile(file).toString(),
                mtime = file.lastModified(),
                size = file.length(),
            )
        } ?: emptyList()
    }
}

/**
 * Pure: the folder chain between a tree's root document and a document inside
 * it, e.g. ("primary:DCIM", "primary:DCIM/PostMedia/Originals/ab.jpg") →
 * [PostMedia, Originals]. Null when the document is not under the tree.
 * Relies on path-shaped document ids (ExternalStorageProvider — i.e. DCIM).
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

/** Pure-ish (platform table): file extension for a MIME type, kept truthful (HEIC stays .heic). */
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
