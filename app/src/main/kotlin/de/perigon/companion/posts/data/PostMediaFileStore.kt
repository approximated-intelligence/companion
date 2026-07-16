package de.perigon.companion.posts.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import androidx.core.content.FileProvider
import de.perigon.companion.core.prefs.AppPrefs
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

/**
 * Manages PostMedia files stored under DCIM/PostMedia/ via the app-wide DCIM SAF grant.
 * Falls back to app-private storage only when no DCIM grant exists (legacy/migration).
 *
 * Naming: "<processedHash>.<ext>" — the sha256 of the processed bytes, exactly
 * mirroring the server key. Deterministic (republish overwrites instead of
 * accumulating), content-addressed (identical processed content dedups across
 * posts), and resolvable by exact name. Pulled files already used this
 * convention; placed files now match. Legacy "POST_<ts>_<slug>_<pos>" files
 * are orphans — delete them manually when convenient.
 *
 * The folder carries a .nomedia so processed copies never appear in galleries.
 */
@Singleton
class PostMediaFileStore @Inject constructor(
    @param:ApplicationContext private val ctx: Context,
    private val appPrefs: AppPrefs,
) {
    companion object {
        private const val SUBFOLDER = "PostMedia"
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

    fun isPostMediaUri(uriStr: String): Boolean {
        val dcimUri = appPrefs.dcimTreeUri() ?: return false
        return uriStr.startsWith(dcimUri) && uriStr.contains(SUBFOLDER)
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
     * Tries the stored PostMedia copy, then the original source, then a
     * hash-named PostMedia lookup (processedHash first, contentHash for
     * legacy pulled files).
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
                ctx.contentResolver.openInputStream(Uri.parse(entity.sourceUri))?.use {
                    return ResolvedMedia(Uri.parse(entity.sourceUri), MediaSourceStatus.ORIGINAL)
                }
            } catch (_: Exception) {}
        }
        entity.processedHash?.let { hash ->
            resolveUri(hash, entity.mimeType)?.let {
                return ResolvedMedia(it, MediaSourceStatus.POST_MEDIA)
            }
        }
        resolveUri(entity.contentHash, entity.mimeType)?.let {
            return ResolvedMedia(it, MediaSourceStatus.POST_MEDIA)
        }
        return ResolvedMedia(Uri.EMPTY, MediaSourceStatus.GONE)
    }

    fun resolveDisplayUri(entity: PostMediaEntity): Uri =
        resolveDisplayMedia(entity).uri

    // ---- Write operations ----

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
     * Exact-name lookup: files are "<hash>.<ext>" — one cursor query.
     * Legacy app-private locations checked as before.
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
        val match = dir.listFiles()?.firstOrNull { it.name.contains(hash) }
        if (match != null) return Uri.fromFile(match)
        val byHash = File(dir, "$hash.$ext")
        if (byHash.exists()) return Uri.fromFile(byHash)
        return null
    }

    private fun listLegacyDir(): List<PostMediaFileInfo> {
        val dir = legacyDir()
        return dir.listFiles()?.map { file ->
            PostMediaFileInfo(
                path = file.name,
                uri = Uri.fromFile(file).toString(),
                mtime = file.lastModified(),
                size = file.length(),
            )
        } ?: emptyList()
    }
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
