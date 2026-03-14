package de.perigon.companion.posts.data

import android.content.Context
import android.net.Uri
import android.os.Environment
import androidx.core.content.FileProvider
import androidx.documentfile.provider.DocumentFile
import de.perigon.companion.core.prefs.AppPrefs
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

@Singleton
class PostMediaFileStore @Inject constructor(
    @param:ApplicationContext private val ctx: Context,
    private val appPrefs: AppPrefs,
) {

    companion object {
        private const val DEFAULT_DIR_NAME = "PostMedia"
    }

    fun isDefault(): Boolean = appPrefs.postMediaFolderUri() == null

    fun rootLabel(): String =
        appPrefs.postMediaFolderLabel() ?: "App-private (hidden from gallery)"

    fun rootUri(): String {
        val custom = appPrefs.postMediaFolderUri()
        if (custom != null) return custom
        return Uri.fromFile(defaultDir()).toString()
    }

    fun isPostMediaUri(uriStr: String): Boolean {
        val root = rootUri()
        return uriStr.startsWith(root)
    }

    private fun defaultDir(): File {
        val dir = File(ctx.getExternalFilesDir(null), DEFAULT_DIR_NAME)
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

    fun resolveDisplayUri(entity: PostMediaEntity): Uri {
        if (entity.mediaStoreUri.isNotBlank()) {
            try {
                openInputStream(entity.mediaStoreUri)?.use { return Uri.parse(entity.mediaStoreUri) }
            } catch (_: Exception) {}
        }
        if (entity.sourceUri.isNotBlank()) {
            try {
                ctx.contentResolver.openInputStream(Uri.parse(entity.sourceUri))?.use {
                    return Uri.parse(entity.sourceUri)
                }
            } catch (_: Exception) {}
        }
        resolveUri(entity.contentHash, entity.mimeType)?.let { return it }
        return Uri.EMPTY
    }

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

    /**
     * Write raw bytes into PostMedia storage. Returns the URI string
     * of the written file, or null on failure.
     *
     * Used by [de.perigon.companion.posts.domain.pullPostMediaFromGitHub]
     * to place downloaded media.
     */
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
        val custom = appPrefs.postMediaFolderUri()
        return if (custom != null) {
            writeToSafFolder(custom, displayName, source)
        } else {
            writeToDefaultDir(displayName, source)
        }
    }

    private fun writeToDefaultDir(displayName: String, source: File): String {
        val outFile = File(defaultDir(), displayName)
        source.inputStream().use { input ->
            FileOutputStream(outFile).use { output -> input.copyTo(output) }
        }
        return Uri.fromFile(outFile).toString()
    }

    private fun writeToSafFolder(treeUriStr: String, displayName: String, source: File): String? {
        val treeUri = Uri.parse(treeUriStr)
        val folder = DocumentFile.fromTreeUri(ctx, treeUri) ?: return null
        val mimeType = if (displayName.endsWith(".mp4")) "video/mp4" else "image/jpeg"
        val doc = folder.createFile(mimeType, displayName.substringBeforeLast('.')) ?: return null
        ctx.contentResolver.openOutputStream(doc.uri)?.use { output ->
            source.inputStream().use { input -> input.copyTo(output) }
        }
        return doc.uri.toString()
    }

    // ---- Read / resolve ----

    fun resolveUri(contentHash: String, mimeType: String): Uri? {
        val ext = if (mimeType.startsWith("video")) "mp4" else "jpg"

        val custom = appPrefs.postMediaFolderUri()
        if (custom != null) {
            findInSafFolder(custom, contentHash)?.let { return it }
        } else {
            findInDefaultDir(contentHash, ext)?.let { return it }
        }

        findInMediaStore(contentHash)?.let { return it }

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
        val custom = appPrefs.postMediaFolderUri()
        return if (custom != null) {
            listSafFolder(custom)
        } else {
            listDefaultDir()
        }
    }

    // ---- Default dir operations ----

    private fun findInDefaultDir(contentHash: String, ext: String): Uri? {
        val dir = defaultDir()
        val match = dir.listFiles()?.firstOrNull { it.name.contains(contentHash) }
        if (match != null) return Uri.fromFile(match)
        val byHash = File(dir, "$contentHash.$ext")
        if (byHash.exists()) return Uri.fromFile(byHash)
        return null
    }

    private fun listDefaultDir(): List<PostMediaFileInfo> {
        val dir = defaultDir()
        return dir.listFiles()?.map { file ->
            PostMediaFileInfo(
                path = "$DEFAULT_DIR_NAME/${file.name}",
                uri = Uri.fromFile(file).toString(),
                mtime = file.lastModified(),
                size = file.length(),
            )
        } ?: emptyList()
    }

    // ---- SAF folder operations ----

    private fun findInSafFolder(treeUriStr: String, contentHash: String): Uri? {
        val treeUri = Uri.parse(treeUriStr)
        val folder = DocumentFile.fromTreeUri(ctx, treeUri) ?: return null
        val match = folder.listFiles().firstOrNull { it.name?.contains(contentHash) == true }
        return match?.uri
    }

    private fun listSafFolder(treeUriStr: String): List<PostMediaFileInfo> {
        val treeUri = Uri.parse(treeUriStr)
        val folder = DocumentFile.fromTreeUri(ctx, treeUri) ?: return emptyList()
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

    // ---- Legacy MediaStore lookup ----

    private fun findInMediaStore(contentHash: String): Uri? {
        val collections = listOf(
            android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            android.provider.MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
        )
        for (collection in collections) {
            ctx.contentResolver.query(
                collection,
                arrayOf(android.provider.MediaStore.MediaColumns._ID),
                "${android.provider.MediaStore.MediaColumns.RELATIVE_PATH} = ? AND ${android.provider.MediaStore.MediaColumns.DISPLAY_NAME} LIKE ?",
                arrayOf("${Environment.DIRECTORY_DCIM}/PostMedia/", "%$contentHash%"),
                null,
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val id = cursor.getLong(0)
                    return Uri.withAppendedPath(collection, id.toString())
                }
            }
        }
        return null
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
