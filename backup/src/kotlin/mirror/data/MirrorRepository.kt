package de.perigon.companion.backup.mirror.data

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.util.Log
import androidx.core.net.toUri
import dagger.hilt.android.qualifiers.ApplicationContext
import de.perigon.companion.backup.mirror.domain.DriveReadResult
import de.perigon.companion.backup.mirror.domain.LEGACY_SUMS_FILE
import de.perigon.companion.backup.mirror.domain.MIRROR_FOLDER
import de.perigon.companion.backup.mirror.domain.SHA256_LINE
import de.perigon.companion.backup.mirror.domain.SUMS_FOLDER
import de.perigon.companion.backup.mirror.domain.SUMS_NAME_REGEX
import de.perigon.companion.backup.mirror.domain.TMP_SUFFIX
import de.perigon.companion.backup.mirror.domain.fileName
import de.perigon.companion.backup.mirror.domain.guessMime
import de.perigon.companion.backup.mirror.domain.parentDirs
import de.perigon.companion.core.saf.ScannedFile
import de.perigon.companion.core.saf.documentUriInTree
import de.perigon.companion.core.saf.findChildByName
import de.perigon.companion.core.saf.navigateOrCreate
import de.perigon.companion.core.saf.openInputStream
import de.perigon.companion.core.saf.openInputStreamRaw
import de.perigon.companion.core.saf.queryChildren
import de.perigon.companion.core.saf.walkSubtree
import de.perigon.companion.core.saf.writeFileAtomic
import de.perigon.companion.core.util.sha256Hex
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "MirrorRepository"

@Singleton
class MirrorRepository @Inject constructor(
    @ApplicationContext private val ctx: Context,
) {

    fun ensureMirrorFolder(driveRoot: Uri): String {
        val folder = navigateOrCreate(ctx, driveRoot, listOf(MIRROR_FOLDER))
        return DocumentsContract.getDocumentId(folder.uri)
    }

    fun findMirrorFolder(driveRoot: Uri): String? {
        val rootId = DocumentsContract.getTreeDocumentId(driveRoot)
        return findChildByName(ctx, driveRoot, rootId, MIRROR_FOLDER)?.documentId
    }

    fun walkMirror(driveRoot: Uri, mirrorDocId: String): List<ScannedFile> =
        walkSubtree(ctx, driveRoot, mirrorDocId)

    fun copyFromSource(driveRoot: Uri, sourceUri: Uri, path: String) {
        val stream = openInputStream(ctx, sourceUri)
            ?: error("openInputStream returned null for $sourceUri")
        stream.use { src ->
            writeFileAtomic(
                context = ctx,
                treeUri = driveRoot,
                subfolders = listOf(MIRROR_FOLDER) + parentDirs(path),
                displayName = fileName(path),
                mimeType = guessMime(path),
                source = src,
            )
        }
    }

    fun sweepTmp(driveRoot: Uri, mirrorDocId: String) {
        val files = walkSubtree(ctx, driveRoot, mirrorDocId)
        for (sf in files) {
            if (!sf.path.endsWith(TMP_SUFFIX)) {
                continue
            }
            try {
                DocumentsContract.deleteDocument(ctx.contentResolver, sf.uri.toUri())
            } catch (e: Exception) {
                Log.w(TAG, "sweep failed: ${sf.path}", e)
            }
        }
    }

    fun readAllSums(driveRoot: Uri, mirrorDocId: String): Map<String, String> {
        val result = mutableMapOf<String, String>()

        val legacy = queryChildren(ctx, driveRoot, mirrorDocId)
            .filter { !it.isDirectory }
            .filter {
                it.name == LEGACY_SUMS_FILE ||
                    it.name.startsWith("$LEGACY_SUMS_FILE ")
            }
            .sortedBy { it.name }
        for (f in legacy) {
            readSumsInto(driveRoot, f.documentId, result)
        }

        val sumsFolder = findChildByName(ctx, driveRoot, mirrorDocId, SUMS_FOLDER)
        if (sumsFolder != null && sumsFolder.isDirectory) {
            val sumsFiles = queryChildren(ctx, driveRoot, sumsFolder.documentId)
                .filter { !it.isDirectory && SUMS_NAME_REGEX.matches(it.name) }
                .sortedBy { it.name }
            for (f in sumsFiles) {
                readSumsInto(driveRoot, f.documentId, result)
            }
        }

        return result
    }

    fun writeSums(
        driveRoot: Uri,
        mirrorDocId: String,
        entries: List<Pair<String, String>>,
    ): String? {
        if (entries.isEmpty()) {
            return null
        }

        val sumsDocId = ensureFolder(driveRoot, mirrorDocId, SUMS_FOLDER)
        val nextName = nextSumsName(driveRoot, sumsDocId)

        val payload = StringBuilder()
        for ((path, hash) in entries) {
            payload.append(hash).append("  ").append(path).append('\n')
        }
        val bytes = payload.toString().toByteArray(Charsets.UTF_8)

        val docUri = DocumentsContract.createDocument(
            ctx.contentResolver,
            documentUriInTree(driveRoot, sumsDocId),
            "text/plain",
            nextName,
        )
        if (docUri == null) {
            Log.w(TAG, "cannot create SUMS/$nextName")
            return null
        }

        try {
            val out = ctx.contentResolver.openOutputStream(docUri)
            if (out == null) {
                Log.w(TAG, "cannot open output for SUMS/$nextName")
                return null
            }
            out.use { stream ->
                stream.write(bytes)
                stream.flush()
            }
        } catch (e: Exception) {
            Log.w(TAG, "write failed for SUMS/$nextName", e)
            return null
        }

        Log.i(TAG, "wrote SUMS/$nextName with ${entries.size} entries")
        return nextName
    }

    private fun readSumsInto(
        driveRoot: Uri,
        docId: String,
        into: MutableMap<String, String>,
    ) {
        try {
            val stream = ctx.contentResolver.openInputStream(
                documentUriInTree(driveRoot, docId),
            )
            if (stream == null) {
                return
            }
            stream.use { s ->
                s.bufferedReader().useLines { lines ->
                    for (line in lines) {
                        val clean = line.trimEnd('\r')
                        val match = SHA256_LINE.find(clean) ?: continue
                        val hash = match.groupValues[1].lowercase()
                        val path = match.groupValues[2]
                        if (path.isNotEmpty()) {
                            into[path] = hash
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "read failed for SUMS doc $docId", e)
        }
    }

    private fun ensureFolder(
        driveRoot: Uri,
        parentDocId: String,
        name: String,
    ): String {
        val existing = findChildByName(ctx, driveRoot, parentDocId, name)
        if (existing != null) {
            if (existing.isDirectory) {
                return existing.documentId
            }
            error("'$name' exists but is not a directory")
        }

        val created = DocumentsContract.createDocument(
            ctx.contentResolver,
            documentUriInTree(driveRoot, parentDocId),
            DocumentsContract.Document.MIME_TYPE_DIR,
            name,
        )
        if (created == null) {
            error("Cannot create folder '$name'")
        }
        return DocumentsContract.getDocumentId(created)
    }

    private fun nextSumsName(driveRoot: Uri, sumsDocId: String): String {
        var maxNum = 0
        val children = queryChildren(ctx, driveRoot, sumsDocId)
        for (child in children) {
            if (!SUMS_NAME_REGEX.matches(child.name)) {
                continue
            }
            val n = child.name.toIntOrNull() ?: continue
            if (n > maxNum) {
                maxNum = n
            }
        }
        return "%06d".format(maxNum + 1)
    }

    fun readDriveFile(file: ScannedFile): DriveReadResult {
        var danceHash: String? = null
        var rawHash: String? = null
        var danceError: String? = null
        var rawError: String? = null

        try {
            val stream = openInputStream(ctx, file.uri.toUri())
            if (stream != null) {
                stream.use { s ->
                    danceHash = sha256Hex(s)
                }
            } else {
                danceError = "null stream"
            }
        } catch (e: Exception) {
            danceError = "${e.javaClass.simpleName}: ${e.message}"
        }

        if (danceHash == null) {
            try {
                val stream = openInputStreamRaw(ctx, file.uri.toUri())
                if (stream != null) {
                    stream.use { s ->
                        rawHash = sha256Hex(s)
                    }
                } else {
                    rawError = "null stream"
                }
            } catch (e: Exception) {
                rawError = "${e.javaClass.simpleName}: ${e.message}"
            }
        }

        return DriveReadResult(danceHash, rawHash, danceError, rawError)
    }
}
