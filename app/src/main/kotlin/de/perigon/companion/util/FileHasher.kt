package de.perigon.companion.util

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
 * On read failure, returns null; caller drops the file.
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
     * per miss. Keys whose source fails to open/read are absent from the
     * result — callers drop those files and retry next scan.
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
    ): Map<HashKey, String> {
        if (keys.isEmpty()) return emptyMap()
        val result = lookupAll(keys).toMutableMap()

        val missKeys = keys.filter { it !in result }
        val fresh = mutableListOf<FileContentHashEntity>()
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
            } catch (_: Exception) {
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
        return result
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
        } catch (_: Exception) {
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
        } catch (_: Exception) {
            return null
        }
        dao.upsert(FileContentHashEntity(
            path = path, mtime = normMtime(mtime), size = actualSize,
            sha256 = hex, computedAt = System.currentTimeMillis(),
        ))
        return hex to actualSize
    }
}
