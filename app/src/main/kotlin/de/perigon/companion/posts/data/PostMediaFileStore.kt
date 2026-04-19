package de.perigon.companion.posts.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import androidx.documentfile.provider.DocumentFile
import de.perigon.companion.core.prefs.AppPrefs
import de.perigon.companion.util.saf.navigateOrCreate
import de.perigon.companion.util.sha256Hex
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
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
 */
@Singleton
class PostMediaFileStore @Inject constructor(
    @param:ApplicationContext private val ctx: Context,
    private val appPrefs: AppPrefs,
) {
    companion object {
        private const val SUBFOLDER = "PostMedia"
        private const val LEGACY_DIR_NAME = "PostMedia"
    }

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

    private fun postMediaFolder(): DocumentFile? {
        val treeUri = dcimTreeUri() ?: return null
        return navigateOrCreate(ctx, treeUri, listOf(SUBFOLDER))
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
        resolveUri(entity.contentHash, entity.mimeType)?.let {
            return ResolvedMedia(it, MediaSourceStatus.POST_MEDIA)
        }
        return ResolvedMedia(Uri.EMPTY, MediaSourceStatus.GONE)
    }

    fun resolveDisplayUri(entity: PostMediaEntity): Uri =
        resolveDisplayMedia(entity).uri

    // ---- Write operations ----

    suspend fun placeImageResult(
        tempFile: File,
        slug: String,
        position: Int,
    ): PostMediaPlacement? = withContext(Dispatchers.IO) {
        val hash = FileInputStream(tempFile).use { sha256Hex(it) }
        val displayName = buildDisplayName(slug, position, "jpg")
        val uri = writeFile(displayName, tempFile)
        tempFile.delete()
        if (uri != null) PostMediaPlacement(hash, uri) else null
    }

    suspend fun placeVideoResult(
        tempFile: File,
        slug: String,
        position: Int,
    ): PostMediaPlacement? = withContext(Dispatchers.IO) {
        val hash = FileInputStream(tempFile).use { sha256Hex(it) }
        val displayName = buildDisplayName(slug, position, "mp4")
        val uri = writeFile(displayName, tempFile)
        tempFile.delete()
        if (uri != null) PostMediaPlacement(hash, uri) else null
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
        val folder = postMediaFolder()
        return if (folder != null) {
            writeToSafFolder(folder, displayName, source)
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

    private fun writeToSafFolder(folder: DocumentFile, displayName: String, source: File): String? {
        val mimeType = if (displayName.endsWith(".mp4")) "video/mp4" else "image/jpeg"
        folder.findFile(displayName)?.delete()
        val doc = folder.createFile(mimeType, displayName.substringBeforeLast('.')) ?: return null
        ctx.contentResolver.openOutputStream(doc.uri)?.use { output ->
            source.inputStream().use { input -> input.copyTo(output) }
        }
        return doc.uri.toString()
    }

    // ---- Read / resolve ----

    fun resolveUri(contentHash: String, mimeType: String): Uri? {
        val ext = if (mimeType.startsWith("video")) "mp4" else "jpg"
        // SAF PostMedia folder
        findInSafPostMedia(contentHash)?.let { return it }
        // Legacy app-private dirs
        findInLegacyDir(contentHash, ext)?.let { return it }
        val legacy = File(legacyMediaDir(), "$contentHash.$ext")
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
        val folder = postMediaFolder()
        return if (folder != null) {
            listSafPostMedia(folder)
        } else {
            listLegacyDir()
        }
    }

    // ---- SAF PostMedia operations ----

    private fun findInSafPostMedia(contentHash: String): Uri? {
        val folder = postMediaFolder() ?: return null
        val match = folder.listFiles().firstOrNull { it.name?.contains(contentHash) == true }
        return match?.uri
    }

    private fun listSafPostMedia(folder: DocumentFile): List<PostMediaFileInfo> {
        return folder.listFiles().mapNotNull { file ->
            if (file.isDirectory) return@mapNotNull null
            val name = file.name ?: return@mapNotNull null
            PostMediaFileInfo(
                path = name,
                uri = file.uri.toString(),
                mtime = file.lastModified(),
                size = file.length(),
            )
        }
    }

    // ---- Legacy dir operations ----

    private fun findInLegacyDir(contentHash: String, ext: String): Uri? {
        val dir = legacyDir()
        val match = dir.listFiles()?.firstOrNull { it.name.contains(contentHash) }
        if (match != null) return Uri.fromFile(match)
        val byHash = File(dir, "$contentHash.$ext")
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

    // ---- Naming ----

    private fun buildDisplayName(slug: String, position: Int, ext: String): String {
        val ts = SimpleDateFormat("yyMMdd_HHmmss", Locale.US).format(Date())
        val safeSlug = slug.ifBlank { "no-title" }.take(120)
        return "POST_${ts}_${safeSlug}_%03d.${ext}".format(position + 1)
    }
}

data class PostMediaPlacement(
    val contentHash: String,
    val uri: String,
)

data class PostMediaFileInfo(
    val path: String,
    val uri: String,
    val mtime: Long,
    val size: Long,
)
