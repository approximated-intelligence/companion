package de.perigon.companion.util

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import java.io.InputStream
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Content-hash cache keyed by (path, mtime, size).
 *
 * Pure optimisation: dropping it only costs re-hashing. The key is what we
 * already trust to identify unchanged file content.
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

@Dao
interface FileContentHashDao {
    @Query("SELECT sha256 FROM file_content_hashes WHERE path = :path AND mtime = :mtime AND size = :size LIMIT 1")
    suspend fun lookup(path: String, mtime: Long, size: Long): String?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: FileContentHashEntity)

    @Query("DELETE FROM file_content_hashes")
    suspend fun deleteAll()
}

/**
 * Streams an input source and produces a sha256 hex digest, consulting a
 * persistent cache keyed by (path, mtime, size). On miss, hashes and stores.
 * On read failure, returns null; caller drops the file.
 */
@Singleton
class FileHasher @Inject constructor(
    private val dao: FileContentHashDao,
) {
    companion object {
        private const val IO_BUF = 64 * 1024
    }

    /** Cache lookup only — no I/O on the source. */
    suspend fun lookup(path: String, mtime: Long, size: Long): String? =
        dao.lookup(path, mtime, size)

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
        dao.lookup(path, mtime, size)?.let { return it }
        val digest = try {
            open().use { stream -> streamSha256(stream) }
        } catch (_: Exception) {
            return null
        }
        dao.upsert(FileContentHashEntity(
            path = path, mtime = mtime, size = size,
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
            open().use { stream -> streamSha256WithSize(stream) }
        } catch (_: Exception) {
            return null
        }
        dao.upsert(FileContentHashEntity(
            path = path, mtime = mtime, size = actualSize,
            sha256 = hex, computedAt = System.currentTimeMillis(),
        ))
        return hex to actualSize
    }

    private fun streamSha256(stream: InputStream): String {
        val md = MessageDigest.getInstance("SHA-256")
        val buf = ByteArray(IO_BUF)
        while (true) {
            val n = stream.read(buf)
            if (n <= 0) break
            md.update(buf, 0, n)
        }
        return bytesToHex(md.digest())
    }

    private fun streamSha256WithSize(stream: InputStream): Pair<String, Long> {
        val md = MessageDigest.getInstance("SHA-256")
        val buf = ByteArray(IO_BUF)
        var size = 0L
        while (true) {
            val n = stream.read(buf)
            if (n <= 0) break
            md.update(buf, 0, n)
            size += n
        }
        return bytesToHex(md.digest()) to size
    }

    private fun bytesToHex(bytes: ByteArray): String =
        bytes.joinToString("") { "%02x".format(it) }
}
