package de.perigon.companion.backup.data

import androidx.room.*
import kotlinx.coroutines.flow.Flow

enum class BackupFileStatus {
    PENDING, HASHING, STREAMING, STREAMED, CONFIRMED, ISSUE;

    class Converter {
        @TypeConverter
        fun fromEnum(value: BackupFileStatus): String = value.name

        @TypeConverter
        fun toEnum(value: String): BackupFileStatus = valueOf(value)
    }
}

@Entity(
    tableName = "backup_files",
    indices = [
        Index(value = ["path", "mtime"], unique = true),
        Index("sha256"),
        Index("status"),
        Index("startPack"),
    ],
)
data class BackupFileEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val path: String,
    val uri: String,
    val mtime: Long,
    val size: Long,
    val sha256: String = "",
    val status: BackupFileStatus = BackupFileStatus.PENDING,
    val startPack: Int = 0,
    val endPack: Int = 0,
    val startPart: Int = 0,
    val startPartOffset: Long = 0L,
    val packFileIndex: Int = 0,
    val issueDetail: String? = null,
    val createdAt: Long,
    val updatedAt: Long,
)

@Dao
interface BackupFileDao {

    @Query("SELECT * FROM backup_files WHERE path = :path AND mtime = :mtime LIMIT 1")
    suspend fun findByPathMtime(path: String, mtime: Long): BackupFileEntity?

    @Query(
        "SELECT sha256 FROM backup_files " +
        "WHERE path = :path AND mtime = :mtime AND status = 'CONFIRMED' LIMIT 1"
    )
    suspend fun findConfirmedSha256(path: String, mtime: Long): String?

    @Query("SELECT * FROM backup_files WHERE status = :status ORDER BY id ASC")
    suspend fun getByStatus(status: BackupFileStatus): List<BackupFileEntity>

    @Query("SELECT * FROM backup_files WHERE status = :status ORDER BY id ASC")
    fun observeByStatus(status: BackupFileStatus): Flow<List<BackupFileEntity>>

    @Query("SELECT * FROM backup_files WHERE id = :id")
    suspend fun getById(id: Long): BackupFileEntity?

    @Query("SELECT MAX(endPack) FROM backup_files WHERE status = :status")
    suspend fun maxEndPack(status: BackupFileStatus = BackupFileStatus.CONFIRMED): Int?

    @Query("SELECT COUNT(*) FROM backup_files WHERE status = :status")
    fun countByStatus(status: BackupFileStatus): Flow<Int>

    @Query("SELECT MAX(updatedAt) FROM backup_files WHERE status = :status")
    fun lastUpdatedAt(status: BackupFileStatus = BackupFileStatus.CONFIRMED): Flow<Long?>

    @Query(
        "SELECT * FROM backup_files " +
        "WHERE id >= :startFileId AND status IN ('PENDING', 'STREAMING') " +
        "ORDER BY id ASC"
    )
    suspend fun getRecoveryFiles(startFileId: Long): List<BackupFileEntity>

    @Query("SELECT * FROM backup_files WHERE status = 'CONFIRMED' ORDER BY startPack, startPart, startPartOffset")
    suspend fun getConfirmedForRestore(): List<BackupFileEntity>

    @Query("SELECT * FROM backup_files WHERE status = 'CONFIRMED' AND startPart = 0 AND startPack > 0")
    suspend fun getConfirmedNeedingIndex(): List<BackupFileEntity>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(files: List<BackupFileEntity>): List<Long>

    @Query(
        "UPDATE backup_files SET status = :status, sha256 = :sha256, updatedAt = :now WHERE id = :id"
    )
    suspend fun updateHashed(
        id: Long,
        sha256: String,
        status: BackupFileStatus = BackupFileStatus.PENDING,
        now: Long,
    )

    @Query(
        "UPDATE backup_files SET status = :status, startPack = :startPack, endPack = :endPack, " +
        "startPart = :startPart, startPartOffset = :startPartOffset, " +
        "packFileIndex = :packFileIndex, updatedAt = :now WHERE id = :id"
    )
    suspend fun updateStreaming(
        id: Long,
        startPack: Int,
        endPack: Int,
        startPart: Int,
        startPartOffset: Long,
        packFileIndex: Int,
        status: BackupFileStatus = BackupFileStatus.STREAMING,
        now: Long,
    )

    @Query(
        "UPDATE backup_files SET status = :status, endPack = :endPack, updatedAt = :now WHERE id = :id"
    )
    suspend fun updateStreamed(
        id: Long,
        endPack: Int,
        status: BackupFileStatus = BackupFileStatus.STREAMED,
        now: Long,
    )

    @Query(
        "UPDATE backup_files SET status = :status, updatedAt = :now " +
        "WHERE status = 'STREAMED' AND endPack <= :sealedPack"
    )
    suspend fun promoteStreamed(
        sealedPack: Int,
        status: BackupFileStatus = BackupFileStatus.CONFIRMED,
        now: Long,
    )

    @Query(
        "UPDATE backup_files SET status = :status, issueDetail = :detail, updatedAt = :now WHERE id = :id"
    )
    suspend fun markIssue(
        id: Long,
        detail: String,
        status: BackupFileStatus = BackupFileStatus.ISSUE,
        now: Long,
    )

    @Query(
        "UPDATE backup_files SET status = :newStatus, issueDetail = NULL, updatedAt = :now " +
        "WHERE status = :oldStatus"
    )
    suspend fun resetIssues(
        oldStatus: BackupFileStatus = BackupFileStatus.ISSUE,
        newStatus: BackupFileStatus = BackupFileStatus.PENDING,
        now: Long,
    )

    @Query(
        "UPDATE backup_files SET status = :newStatus, updatedAt = :now " +
        "WHERE status IN ('HASHING', 'STREAMING', 'STREAMED') AND startPack = :pack"
    )
    suspend fun resetPackFiles(
        pack: Int,
        newStatus: BackupFileStatus = BackupFileStatus.PENDING,
        now: Long,
    )

    @Query(
        "UPDATE backup_files SET status = :status, issueDetail = :detail, updatedAt = :now " +
        "WHERE status IN ('HASHING', 'STREAMING', 'STREAMED') AND startPack = :pack"
    )
    suspend fun markPackIssue(
        pack: Int,
        detail: String,
        status: BackupFileStatus = BackupFileStatus.ISSUE,
        now: Long,
    )

    @Query(
        "UPDATE backup_files SET startPart = :startPart, startPartOffset = :offset, updatedAt = :now WHERE id = :id"
    )
    suspend fun updatePartIndex(
        id: Long,
        startPart: Int,
        offset: Long,
        now: Long,
    )

    @Query("DELETE FROM backup_files")
    suspend fun deleteAll()
}
