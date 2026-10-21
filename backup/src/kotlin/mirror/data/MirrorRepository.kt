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

// Accepted SUMS file names, in increasing "write order":
//   - \d{6}            — legacy "guess next number" format
//   - \d{6} (\d+)      — legacy collision suffix (the "(n)" clutter)
//   - T\d{13}          — current format: T + epoch millis
private val LEGACY_SHORT_NAME = Regex("^\\d{6}$")
private val LEGACY_SUFFIXED_NAME = Regex("^\\d{6} \\(\\d+\\)$")
private val NEW_SUMS_NAME = Regex("^T\\d{13}$")
private val ANY_SUMS_NAME = Regex("^(?:T\\d{13}|\\d{6}|\\d{6} \\(\\d+\\))$")

private fun isSumsFileName(name: String): Boolean = ANY_SUMS_NAME.matches(name)

/**
 * Sort key so that "later write wins" survives both old and new formats:
 * legacy short < legacy suffixed (same base) < new timestamped.
 */
private fun sumsSortKey(name: String): Long =
    if (name.startsWith("T")) {
        name.substring(1).toLongOrNull() ?: Long.MAX_VALUE
    } else {
        name.takeWhile { it.isDigit() }.toLongOrNull() ?: 0L
    }

@Singleton
class MirrorRepository @Inject constructor(
    @ApplicationContext private val ctx: Context,
) {

    /**
     * In-process monotonic bump for SUMS file names. Two writes in the same
     * millisecond get distinct timestamps without a directory scan. Reset per
     * process; on restart the wall clock is enough to avoid collisions with
     * everything written before.
     */
    @Volatile private var lastSumsTimestamp: Long = 0L

    // ── folders ──────────────────────────────────────────────────────────

    fun ensureMirrorFolder(driveRoot: Uri): String {
        val folder = navigateOrCreate(ctx, driveRoot, listOf(MIRROR_FOLDER))
        return DocumentsContract.getDocumentId(folder.uri)
    }

    fun findMirrorFolder(driveRoot: Uri): String? {
        val rootId = DocumentsContract.getTreeDocumentId(driveRoot)
        return findChildByName(ctx, driveRoot, rootId, MIRROR_FOLDER)?.documentId
    }

    // ── walk ─────────────────────────────────────────────────────────────

    fun walkMirror(driveRoot: Uri, mirrorDocId: String): List<ScannedFile> =
        walkSubtree(ctx, driveRoot, mirrorDocId)

    // ── copy ─────────────────────────────────────────────────────────────

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

    // ── SUMS ─────────────────────────────────────────────────────────────

    fun readAllSums(driveRoot: Uri, mirrorDocId: String): Map<String, String> {
        val result = mutableMapOf<String, String>()

        // Legacy SHA256SUM / SHA256SUM (n) files kept at the Mirror/ root.
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

        // Everything under SUMS/, across all accepted name formats, ordered
        // legacy-then-newest so later entries win per path.
        val sumsFolder = findChildByName(ctx, driveRoot, mirrorDocId, SUMS_FOLDER)
        if (sumsFolder != null && sumsFolder.isDirectory) {
            val sumsFiles = queryChildren(ctx, driveRoot, sumsFolder.documentId)
                .filter { !it.isDirectory && isSumsFileName(it.name) }
                .sortedWith(compareBy({ sumsSortKey(it.name) }, { it.name }))
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

        // Consolidate any legacy "(n)" clutter before writing the new file.
        // Content is merged into the new T-file, and only then are the old
        // files deleted.
        consolidateLegacySums(driveRoot, sumsDocId)

        val name = nextSumsName()
        val payload = buildPayload(entries)

        val docUri = DocumentsContract.createDocument(
            ctx.contentResolver,
            documentUriInTree(driveRoot, sumsDocId),
            "text/plain",
            name,
        )
        if (docUri == null) {
            Log.w(TAG, "cannot create SUMS/$name")
            return null
        }

        try {
            val out = ctx.contentResolver.openOutputStream(docUri)
            if (out == null) {
                Log.w(TAG, "cannot open output for SUMS/$name")
                return null
            }
            out.use { stream ->
                stream.write(payload)
                stream.flush()
            }
        } catch (e: Exception) {
            Log.w(TAG, "write failed for SUMS/$name", e)
            return null
        }

        Log.i(TAG, "wrote SUMS/$name with ${entries.size} entries")
        return name
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

    /**
     * Merge every legacy-suffixed file (`000001 (1)`, `000001 (2)`, …) into
     * one new T-file, then delete the originals. Only runs when there is
     * something to clean. Deletes happen only after the merge file has been
     * written and flushed, so a failure to write leaves the source files
     * intact.
     */
    private fun consolidateLegacySums(driveRoot: Uri, sumsDocId: String) {
        val suffixed = queryChildren(ctx, driveRoot, sumsDocId)
            .filter { !it.isDirectory && LEGACY_SUFFIXED_NAME.matches(it.name) }
        if (suffixed.isEmpty()) {
            return
        }

        Log.i(TAG, "consolidating ${suffixed.size} legacy suffixed SUMS file(s)")

        val merged = mutableMapOf<String, String>()
        for (f in suffixed) {
            readSumsInto(driveRoot, f.documentId, merged)
        }

        if (merged.isNotEmpty()) {
            val name = nextSumsName()
            val payload = buildPayload(merged.toList())
            val docUri = DocumentsContract.createDocument(
                ctx.contentResolver,
                documentUriInTree(driveRoot, sumsDocId),
                "text/plain",
                name,
            )
            if (docUri == null) {
                Log.w(TAG, "cannot create consolidation SUMS/$name — leaving legacy files")
                return
            }
            try {
                val out = ctx.contentResolver.openOutputStream(docUri)
                if (out == null) {
                    Log.w(TAG, "cannot open consolidation stream — leaving legacy files")
                    return
                }
                out.use { stream ->
                    stream.write(payload)
                    stream.flush()
                }
            } catch (e: Exception) {
                Log.w(TAG, "consolidation write failed — leaving legacy files", e)
                return
            }
        }

        // Only reached on successful write (or when merged was empty).
        for (f in suffixed) {
            try {
                DocumentsContract.deleteDocument(
                    ctx.contentResolver,
                    documentUriInTree(driveRoot, f.documentId),
                )
            } catch (e: Exception) {
                Log.w(TAG, "cleanup delete failed for ${f.name}", e)
            }
        }
    }

    private fun buildPayload(entries: List<Pair<String, String>>): ByteArray {
        val sb = StringBuilder()
        for ((path, hash) in entries) {
            sb.append(hash).append("  ").append(path).append('\n')
        }
        return sb.toString().toByteArray(Charsets.UTF_8)
    }

    /**
     * New SUMS name. `T%013d` = "T" + 13-digit epoch millis. The `T` prefix
     * sorts after any legacy all-digit name (ASCII 'T' > '9'), so the sort
     * used by [readAllSums] preserves the "later write wins" invariant. The
     * in-process bump guarantees two writes in the same millisecond still get
     * distinct names, avoiding SAF's silent "(N)" suffix on collision.
     */
    private fun nextSumsName(): String {
        val now = System.currentTimeMillis()
        val ts = if (now > lastSumsTimestamp) now else lastSumsTimestamp + 1L
        lastSumsTimestamp = ts
        return "T%013d".format(ts)
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

    // ── hashing ──────────────────────────────────────────────────────────

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
