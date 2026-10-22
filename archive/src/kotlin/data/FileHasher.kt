package de.perigon.companion.archive.data

import de.perigon.companion.core.util.sha256Hex
import de.perigon.companion.core.util.sha256HexWithSize

import android.util.Log
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import java.io.InputStream
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Content-hash cache keyed by (path, mtime, size).
 *
 * Pure optimisation: dropping it only costs re-hashing. The key is what we
 * already trust to identify unchanged file content — if mtime or size changed,
 * the file changed and gets a fresh sha256.
 *
 * mtime is normalized to SECOND granularity for cache keys: writers observe
 * the same file through APIs of different precision (SAF LAST_MODIFIED in
 * millis, MediaStore DATE_MODIFIED in seconds), and sub-second disagreement
 * caused spurious cache misses and duplicate rows. Existing rows carrying a
 * sub-second remainder miss once and are re-hashed under the normalized key.
 *
 * Digest computation is shared with util/Hashing.kt (sha256Hex /
 * sha256HexWithSize) — no duplicated hashing loops.
 */

@Entity(
    tableName = "file_content_hashes",
    primaryKeys = ["path", "mtime", "size"],
)
data class FileContentHashEntity(
    val path: String,
    val mtime: Long,
    val size: Long,
    val sha256: String,
    val computedAt: Long,
)

/**
 * Identity of one file version, as trusted by the cache.
 */
data class HashKey(
    val path: String,
    val mtime: Long,
    val size: Long,
)

/**
 * A file the pipeline could not include in this run. Carried out of
 * [FileHasher] (and, for pre-hash skips, constructed by the scanner) and out
 * of [de.perigon.companion.archive.backup.domain.BackupOrchestrator] (for upload-time
 * skips), so callers can surface them — the failure is usually
 * user-actionable (a .nomedia folder keeping a media file out of the
 * MediaStore index, a revoked ACCESS_MEDIA_LOCATION grant, a file whose size
 * the SAF provider did not report, a file that changed on disk between scan
 * and upload) and silently omitting it from a backup would look like a
 * success.
 *
 * [key] may carry a synthetic path (e.g. "(unknown file #123)") when the
 * file row itself disappeared mid-run; the classification in [userMessage]
 * keys off [cause]'s message, not the path.
 */
data class HashFailure(
    val key: HashKey,
    val cause: Exception,
)

/**
 * Result of [FileHasher.hashAllOrCached]. [hashes] holds digests for every
 * key that could be read (cache hit or freshly hashed); [failures] lists the
 * ones that could not, in scan order.
 */
data class HashBatchResult(
    val hashes: Map<HashKey, String>,
    val failures: List<HashFailure>,
)

/**
 * Presentation-ready reason for a skip, for a notification body or snackbar.
 * The classified cases are the ones that actually happen on a device:
 *  - missing ACCESS_MEDIA_LOCATION grant,
 *  - media absent from the MediaStore index (typically a folder carrying
 *    .nomedia, or a file the system has not scanned yet),
 *  - a size the SAF provider did not report (rare, legitimate skip),
 *  - a file whose content changed on disk between scan and upload (rehash
 *    mismatch — the reserved bytes get pad-filled so alignment is preserved,
 *    but the file is NOT in the archive),
 *  - a plan row that vanished mid-run (DB reset, interrupted resume) — the
 *    chunk is padded but the file has no descriptor to write,
 *  - a stream that failed partway through (the file's entry is in the
 *    archive, but its content is incomplete; restore will detect the
 *    sha256 mismatch and fail for that one file).
 *
 * Everything else falls through with the exception's own message.
 */
fun HashFailure.userMessage(): String = when {
    cause is SecurityException ->
        "$key.path: ACCESS_MEDIA_LOCATION missing — grant it in Settings"
    cause is IllegalStateException &&
        cause.message?.contains("not a MediaStore URI") == true ->
        "$key.path: not indexed by MediaStore — folder may contain .nomedia, or the file is not yet scanned"
    cause is IllegalStateException &&
        cause.message?.contains("size not reported") == true ->
        "$key.path: size not reported by SAF provider — could not be backed up"
    cause is IllegalStateException &&
        cause.message?.contains("changed on disk") == true ->
        "$key.path: changed on disk after the scan — re-run backup to include it"
    cause is IllegalStateException &&
        cause.message?.contains("lost its backup plan") == true ->
        "$key.path: lost its backup plan mid-run — re-run backup to rebuild"
    cause is IllegalStateException &&
        cause.message?.contains("stream failed mid-read") == true ->
        "$key.path: read failed mid-backup — restore will fail for this file"
    else ->
        "${key.path}: ${cause.message ?: cause.javaClass.simpleName}"
}

@Dao
interface FileContentHashDao {
    @Query("SELECT sha256 FROM file_content_hashes WHERE path = :path AND mtime = :mtime AND size = :size LIMIT 1")
    suspend fun lookup(path: String, mtime: Long, size: Long): String?

    @Query("SELECT * FROM file_content_hashes WHERE path IN (:paths)")
    suspend fun lookupByPaths(paths: List<String>): List<FileContentHashEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: FileContentHashEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(entities: List<FileContentHashEntity>)

    @Query("DELETE FROM file_content_hashes")
    suspend fun deleteAll()
}

/**
 * Streams an input source and produces a sha256 hex digest, consulting a
 * persistent cache keyed by (path, mtime, size). On miss, hashes and stores.
 * On read failure (singular [hashOrCached]), returns null; caller drops the
 * file. (Batch [hashAllOrCached] instead returns the failures alongside the
 * successes — see its doc.)
 *
 * Batch entry points ([lookupAll], [hashAllOrCached]) exist so scanners do a
 * constant number of DB round-trips per scan instead of one per file.
 */
@Singleton
class FileHasher @Inject constructor(
    private val dao: FileContentHashDao,
) {
    companion object {
        // Stay under SQLite's 999 bound-variable limit per statement.
        private const val SQL_CHUNK = 900

        // Minimum interval between progress callbacks while hashing misses.
        private const val PROGRESS_INTERVAL_MS = 2_000L

        /** Second-granularity mtime for cache keys (see class doc). */
        private fun normMtime(mtime: Long): Long = mtime - (mtime % 1000)
    }

    /** Cache lookup only — no I/O on the source. */
    suspend fun lookup(path: String, mtime: Long, size: Long): String? =
        dao.lookup(path, normMtime(mtime), size)

    /**
     * Batched cache lookup. Returns sha256 for every key that hits the cache;
     * absent keys are misses. Chunked IN-queries, no per-file round-trips.
     * Caller keys are matched via normalized mtime; the returned map uses the
     * caller's original keys.
     */
    suspend fun lookupAll(keys: Collection<HashKey>): Map<HashKey, String> {
        if (keys.isEmpty()) return emptyMap()

        // normalized key → original caller keys (usually 1:1)
        val byNorm = HashMap<HashKey, MutableList<HashKey>>(keys.size)
        for (key in keys) {
            byNorm.getOrPut(key.copy(mtime = normMtime(key.mtime))) { mutableListOf() } += key
        }

        val result = HashMap<HashKey, String>(keys.size)
        val paths = byNorm.keys.map { it.path }.distinct()
        for (batch in paths.chunked(SQL_CHUNK)) {
            for (row in dao.lookupByPaths(batch)) {
                val normKey = HashKey(row.path, normMtime(row.mtime), row.size)
                byNorm[normKey]?.forEach { original -> result[original] = row.sha256 }
            }
        }
        return result
    }

    /**
     * Batched [hashOrCached]: one lookup pass, hashing only misses, one
     * batched upsert of freshly computed digests. [open] is invoked lazily
     * per miss.
     *
     * Failures do NOT abort the batch. Every key whose source fails to
     * open/read is recorded as a [HashFailure] and returned alongside the
     * successes, so callers can surface them to the user (see
     * [userMessage]) instead of silently omitting those files from the
     * backup. The dominant case is un-indexed media: resolveOriginalUri
     * refuses to hand back a redacted copy as if it were the original, and
     * the user fixes it by removing .nomedia or re-granting a permission.
     *
     * [onProgress] fires on the hashing thread as each miss is read (1-based:
     * "n of m" means the n-th miss is being hashed), at most every 2 seconds
     * plus the first and last miss. Cache hits cost no I/O and report no
     * progress; a healthy steady-state scan should therefore report ~0
     * misses — a large miss count on an unchanged tree indicates a broken
     * cache key.
     */
    suspend fun hashAllOrCached(
        keys: List<HashKey>,
        onProgress: suspend (hashed: Int, misses: Int, current: HashKey) -> Unit = { _, _, _ -> },
        open: (HashKey) -> InputStream,
    ): HashBatchResult {
        if (keys.isEmpty()) return HashBatchResult(emptyMap(), emptyList())
        val result = lookupAll(keys).toMutableMap()

        val missKeys = keys.filter { it !in result }
        val fresh = mutableListOf<FileContentHashEntity>()
        val failures = mutableListOf<HashFailure>()
        val now = System.currentTimeMillis()
        var lastEmit = 0L

        for ((index, key) in missKeys.withIndex()) {
            val clock = System.currentTimeMillis()
            if (clock - lastEmit >= PROGRESS_INTERVAL_MS || index == 0 || index == missKeys.lastIndex) {
                onProgress(index + 1, missKeys.size, key)
                lastEmit = clock
            }

            val digest = try {
                open(key).use { stream -> sha256Hex(stream) }
            } catch (e: Exception) {
                Log.w("FileHasher", "hash failed for ${key.path} (${e.javaClass.simpleName}): ${e.message}")
                failures += HashFailure(key, e)
                continue
            }
            result[key] = digest
            fresh += FileContentHashEntity(
                path = key.path, mtime = normMtime(key.mtime), size = key.size,
                sha256 = digest, computedAt = now,
            )
        }

        // Distinct by primary key: two caller keys differing only in
        // sub-second mtime normalize to one row.
        val distinctFresh = fresh.distinctBy { Triple(it.path, it.mtime, it.size) }
        for (batch in distinctFresh.chunked(SQL_CHUNK)) {
            dao.upsertAll(batch)
        }
        return HashBatchResult(result, failures)
    }

    /**
     * Return sha256 hex for the given file, using the cache if possible.
     * [open] is invoked lazily only on cache miss. Returns null on read error.
     */
    suspend fun hashOrCached(
        path: String,
        mtime: Long,
        size: Long,
        open: () -> InputStream,
    ): String? {
        dao.lookup(path, normMtime(mtime), size)?.let { return it }
        val digest = try {
            open().use { stream -> sha256Hex(stream) }
        } catch (e: Exception) {
            Log.w("FileHasher", "hashOrCached failed for $path (${e.javaClass.simpleName}): ${e.message}")

            return null
        }
        dao.upsert(FileContentHashEntity(
            path = path, mtime = normMtime(mtime), size = size,
            sha256 = digest, computedAt = System.currentTimeMillis(),
        ))
        return digest
    }

    /**
     * Re-hash at upload/consolidation time to detect content changes. Reads
     * the stream, returns (sha256, actualSize). Updates the cache with the
     * observed (mtime, actualSize) key. Returns null on read error.
     */
    suspend fun rehash(
        path: String,
        mtime: Long,
        open: () -> InputStream,
    ): Pair<String, Long>? {
        val (hex, actualSize) = try {
            open().use { stream -> sha256HexWithSize(stream) }
        } catch (e: Exception) {
            Log.w("FileHasher", "rehash failed for $path (${e.javaClass.simpleName}): ${e.message}")
            return null
        }
        dao.upsert(FileContentHashEntity(
            path = path, mtime = normMtime(mtime), size = actualSize,
            sha256 = hex, computedAt = System.currentTimeMillis(),
        ))
        return hex to actualSize
    }
}
