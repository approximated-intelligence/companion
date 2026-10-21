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
import de.perigon.companion.core.saf.ChildDoc
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

// Name patterns. Real providers mutate the name in two ways: they append a
// MIME-derived extension when the display name has none (text/plain ->
// ".txt"), and they silently add " (N)" on a name collision. Both mutations
// are handled below, so files this code has ever written — with or without
// an extension, with or without a collision suffix — are recognized.
//
//   000001               000001.txt
//   000001 (1)           000001 (1).txt
//   T0001234567890       T0001234567890.txt
private val LEGACY_SHORT_NAME    = Regex("^\\d{6}(?:\\.[A-Za-z0-9]+)?$")
private val LEGACY_SUFFIXED_NAME = Regex("^\\d{6} \\(\\d+\\)(?:\\.[A-Za-z0-9]+)?$")
private val NEW_SUMS_NAME        = Regex("^T\\d{13}(?:\\.[A-Za-z0-9]+)?$")

/**
 * Legacy root-level SHA256SUM files that pre-date the SUMS/ folder.
 * Handles: bare "SHA256SUM", collision suffix "SHA256SUM (1)", and
 * provider-appended extension on either ("SHA256SUM.txt", "SHA256SUM (1).txt").
 *
 * Without the ".txt" branch the check silently skipped provider-renamed
 * files: a plain-text SHA256SUM written via DocumentsContract.createDocument
 * with "text/plain" gets ".txt" appended on most devices, and the read side
 * then never noticed the file existed.
 */
private val LEGACY_ROOT_SUMS_NAME =
    Regex("^${Regex.escape(LEGACY_SUMS_FILE)}(?: \\(\\d+\\))?(?:\\.[A-Za-z0-9]+)?$")

private fun isNewSumsName(name: String): Boolean = NEW_SUMS_NAME.matches(name)

private fun isLegacySumsName(name: String): Boolean =
    LEGACY_SHORT_NAME.matches(name) || LEGACY_SUFFIXED_NAME.matches(name)

private fun isLegacyRootSumsName(name: String): Boolean =
    LEGACY_ROOT_SUMS_NAME.matches(name)

/**
 * Ordering key for SUMS files. Legacy files always sort before timestamped
 * files; within legacy, (base, suffix) is the write order.
 *
 * Legacy: base < 1_000_000, suffix < 10_000 → key < 10^10.
 * T-file: 13-digit epoch ms → key > 10^12.
 * So T-files always sort after every legacy file, and "later write wins"
 * holds across the format migration.
 */
private fun sumsSortKey(name: String): Long {
    if (name.startsWith("T")) {
        return name.substring(1).takeWhile { it.isDigit() }.toLongOrNull() ?: Long.MAX_VALUE
    }
    val base = name.takeWhile { it.isDigit() }.take(6).toLongOrNull() ?: 0L
    val suffix = Regex("\\((\\d+)\\)").find(name)?.groupValues?.get(1)?.toLongOrNull() ?: 0L
    return base * 10_000L + suffix
}

@Singleton
class MirrorRepository @Inject constructor(
    @ApplicationContext private val ctx: Context,
) {

    /**
     * In-process monotonic bump so two writes in the same millisecond get
     * distinct SUMS names. Cross-process collisions are also handled by
     * [pickSumsName], which checks the directory.
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

    // ── SUMS read ────────────────────────────────────────────────────────

    fun readAllSums(driveRoot: Uri, mirrorDocId: String): Map<String, String> {
        val result = mutableMapOf<String, String>()

        // Legacy SHA256SUM files at the Mirror/ root (pre-SUMS-folder era).
        val legacyRootFiles = queryChildren(ctx, driveRoot, mirrorDocId)
            .filter { !it.isDirectory }
            .filter { isLegacyRootSumsName(it.name) }
            .sortedBy { it.name }
        for (f in legacyRootFiles) {
            readSumsInto(driveRoot, f.documentId, result)
        }

        // SUMS/ folder: read every recognized file in write order.
        val sumsFolder = findChildByName(ctx, driveRoot, mirrorDocId, SUMS_FOLDER)
        if (sumsFolder != null && sumsFolder.isDirectory) {
            val sumsFiles = queryChildren(ctx, driveRoot, sumsFolder.documentId)
                .filter { !it.isDirectory }
                .filter { isNewSumsName(it.name) || isLegacySumsName(it.name) }
                .sortedWith(compareBy({ sumsSortKey(it.name) }, { it.name }))
            for (f in sumsFiles) {
                readSumsInto(driveRoot, f.documentId, result)
            }
        }

        return result
    }

    // ── SUMS write ───────────────────────────────────────────────────────

    /**
     * Write one new SUMS file for [entries], consolidating every legacy file
     * found in the SUMS folder into the same write.
     *
     *   1. Read every existing legacy file (short / suffixed, with or without
     *      provider-appended extension) and merge into a single map. Current
     *      entries override historical values for the same path.
     *   2. Create a new `T<epochms>.txt` file with the merged content.
     *   3. Only after a successful write, delete the legacy files that were
     *      merged in. If the write fails, nothing is deleted.
     *
     * This runs on the first write after this code ships — your existing
     * `000001.txt`, `000001 (1).txt`, … set is folded into one T-file and
     * removed, leaving exactly one new file behind. Subsequent writes create
     * new T-files with only the fresh entries.
     */
    fun writeSums(
        driveRoot: Uri,
        mirrorDocId: String,
        entries: List<Pair<String, String>>,
    ): String? {
        if (entries.isEmpty()) {
            return null
        }

        val sumsDocId = ensureFolder(driveRoot, mirrorDocId, SUMS_FOLDER)

        // Merge: legacy files first, current entries last (they win).
        val merged = mutableMapOf<String, String>()
        val legacyDocs = mutableListOf<ChildDoc>()
        val existing = queryChildren(ctx, driveRoot, sumsDocId)
        for (child in existing) {
            if (child.isDirectory) continue
            if (isNewSumsName(child.name)) continue
            if (!isLegacySumsName(child.name)) continue
            legacyDocs += child
            readSumsInto(driveRoot, child.documentId, merged)
        }
        for ((path, hash) in entries) {
            merged[path] = hash
        }

        val name = pickSumsName(driveRoot, sumsDocId)
        val payload = buildPayload(merged.toList())

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

        val wroteOk: Boolean = try {
            val out = ctx.contentResolver.openOutputStream(docUri)
            if (out == null) {
                Log.w(TAG, "cannot open output for SUMS/$name")
                false
            } else {
                out.use { stream ->
                    stream.write(payload)
                    stream.flush()
                }
                true
            }
        } catch (e: Exception) {
            Log.w(TAG, "write failed for SUMS/$name", e)
            false
        }

        if (!wroteOk) {
            // Partial file is unreadable garbage; drop it. Legacy originals
            // stay untouched so nothing is lost.
            try {
                DocumentsContract.deleteDocument(ctx.contentResolver, docUri)
            } catch (_: Exception) {
            }
            return null
        }

        Log.i(TAG, "wrote SUMS/$name with ${merged.size} entries " +
            "(${legacyDocs.size} legacy file(s) merged)")

        // Now that the merge file is on disk and flushed, the legacy files
        // are redundant. Deleting them is safe; a failure is cosmetic.
        for (doc in legacyDocs) {
            try {
                DocumentsContract.deleteDocument(
                    ctx.contentResolver,
                    documentUriInTree(driveRoot, doc.documentId),
                )
                Log.i(TAG, "removed legacy SUMS/${doc.name}")
            } catch (e: Exception) {
                Log.w(TAG, "cleanup delete failed for ${doc.name}", e)
            }
        }

        return name
    }

    /**
     * Pick a SUMS filename that does not already exist. Uses epoch-ms as the
     * base; bumps past any in-process previously used value and any file
     * actually present on disk. The `.txt` suffix is explicit so providers
     * that infer from text/plain do not append their own extension.
     */
    private fun pickSumsName(driveRoot: Uri, sumsDocId: String): String {
        var ts = maxOf(System.currentTimeMillis(), lastSumsTimestamp + 1L)
        while (true) {
            val name = "T%013d.txt".format(ts)
            if (findChildByName(ctx, driveRoot, sumsDocId, name) == null) {
                lastSumsTimestamp = ts
                return name
            }
            ts++
        }
    }

    private fun buildPayload(entries: List<Pair<String, String>>): ByteArray {
        val sb = StringBuilder()
        for ((path, hash) in entries) {
            sb.append(hash).append("  ").append(path).append('\n')
        }
        return sb.toString().toByteArray(Charsets.UTF_8)
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
