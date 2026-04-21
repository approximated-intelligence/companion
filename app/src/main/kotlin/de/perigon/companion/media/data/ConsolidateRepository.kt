package de.perigon.companion.media.data

import androidx.room.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

// ── ConsolidateFileEntity ──

@Entity(
    tableName = "consolidate_files",
    indices = [Index(value = ["path", "sha256"], unique = true)],
)
data class ConsolidateFileEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val path: String,
    val uri: String,
    val mtime: Long,
    val size: Long,
    val sha256: String,
    val createdAt: Long,
)

@Dao
interface ConsolidateFileDao {
    @Query("SELECT * FROM consolidate_files WHERE path = :path AND sha256 = :sha256 LIMIT 1")
    suspend fun getByPathSha256(path: String, sha256: String): ConsolidateFileEntity?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(files: List<ConsolidateFileEntity>): List<Long>

    @Query("SELECT cf.* FROM consolidate_files cf LEFT JOIN consolidate_files_done d ON d.consolidateFileId = cf.id WHERE d.consolidateFileId IS NULL ORDER BY cf.id ASC")
    suspend fun getPending(): List<ConsolidateFileEntity>

    @Query("SELECT COUNT(*) FROM consolidate_files")
    fun observeTotal(): Flow<Int>

    @Query("DELETE FROM consolidate_files")
    suspend fun deleteAll()

    @Query("DELETE FROM consolidate_files WHERE id IN (:ids) AND id NOT IN (SELECT consolidateFileId FROM consolidate_protected_files)")
    suspend fun deleteByIds(ids: Set<Long>)

    @Query("SELECT cf.* FROM consolidate_files cf JOIN consolidate_files_done d ON d.consolidateFileId = cf.id")
    suspend fun getAllDone(): List<ConsolidateFileEntity>
}

// ── ConsolidateFileDoneEntity ──

@Entity(
    tableName = "consolidate_files_done",
    foreignKeys = [ForeignKey(
        entity = ConsolidateFileEntity::class,
        parentColumns = ["id"],
        childColumns = ["consolidateFileId"],
        onDelete = ForeignKey.CASCADE,
    )],
)
data class ConsolidateFileDoneEntity(
    @PrimaryKey val consolidateFileId: Long,
    val destinationName: String,
    val completedAt: Long,
)

@Dao
interface ConsolidateFileDoneDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(done: ConsolidateFileDoneEntity)

    @Query("SELECT COUNT(*) FROM consolidate_files_done")
    fun observeDoneCount(): Flow<Int>

    @Query("DELETE FROM consolidate_files_done")
    suspend fun deleteAll()
}

// ── ConsolidateProtectedFileEntity ──

@Entity(
    tableName = "consolidate_protected_files",
    foreignKeys = [ForeignKey(
        entity = ConsolidateFileEntity::class,
        parentColumns = ["id"],
        childColumns = ["consolidateFileId"],
        onDelete = ForeignKey.CASCADE,
    )],
)
data class ConsolidateProtectedFileEntity(
    @PrimaryKey val consolidateFileId: Long,
    val protectedAt: Long,
)

@Dao
interface ConsolidateProtectedFileDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(entity: ConsolidateProtectedFileEntity)

    @Query("DELETE FROM consolidate_protected_files WHERE consolidateFileId = :consolidateFileId")
    suspend fun delete(consolidateFileId: Long)

    @Query("SELECT consolidateFileId FROM consolidate_protected_files")
    fun observeAllIds(): Flow<List<Long>>

    @Query("DELETE FROM consolidate_protected_files")
    suspend fun deleteAll()
}

// ── SafeToDeleteView ──

@DatabaseView(
    viewName = "safe_to_delete_view",
    value = "SELECT cf.id, cf.path, cf.uri, cf.mtime, cf.size, d.destinationName, d.completedAt as consolidatedAt, CASE WHEN p.consolidateFileId IS NOT NULL THEN 1 ELSE 0 END as isProtected FROM consolidate_files cf JOIN consolidate_files_done d ON d.consolidateFileId = cf.id JOIN backup_files bf ON bf.path = cf.path AND bf.sha256 = cf.sha256 JOIN backup_files_done bd ON bd.backupFileId = bf.id LEFT JOIN consolidate_protected_files p ON p.consolidateFileId = cf.id",
)
data class SafeToDeleteView(
    val id: Long,
    val path: String,
    val uri: String,
    val mtime: Long,
    val size: Long,
    val destinationName: String,
    val consolidatedAt: Long,
    val isProtected: Boolean,
)

@Dao
interface SafeToDeleteDao {
    @Query("SELECT * FROM safe_to_delete_view ORDER BY mtime ASC")
    fun observeAll(): Flow<List<SafeToDeleteView>>

    @Query("SELECT COUNT(*) FROM safe_to_delete_view")
    fun observeCount(): Flow<Int>
}

// ── ConsolidateRepository ──

@Singleton
class ConsolidateRepository @Inject constructor(
    private val consolidateFileDao: ConsolidateFileDao,
    private val consolidateFileDoneDao: ConsolidateFileDoneDao,
    private val consolidateProtectedFileDao: ConsolidateProtectedFileDao,
    private val safeToDeleteDao: SafeToDeleteDao,
) {
    suspend fun insertScannedFiles(files: List<ConsolidateFileEntity>) =
        consolidateFileDao.insertAll(files)

    suspend fun findByPathSha256(path: String, sha256: String) =
        consolidateFileDao.getByPathSha256(path, sha256)

    suspend fun getPending(): List<ConsolidateFileEntity> =
        consolidateFileDao.getPending()

    suspend fun markDone(consolidateFileId: Long, destinationName: String) {
        consolidateFileDoneDao.insert(ConsolidateFileDoneEntity(
            consolidateFileId = consolidateFileId,
            destinationName = destinationName,
            completedAt = System.currentTimeMillis(),
        ))
    }

    suspend fun protect(consolidateFileId: Long) {
        consolidateProtectedFileDao.insert(ConsolidateProtectedFileEntity(
            consolidateFileId = consolidateFileId,
            protectedAt = System.currentTimeMillis(),
        ))
    }

    suspend fun unprotect(consolidateFileId: Long) {
        consolidateProtectedFileDao.delete(consolidateFileId)
    }

    suspend fun deleteByIds(ids: Set<Long>) =
        consolidateFileDao.deleteByIds(ids)

    suspend fun getAllDone(): List<ConsolidateFileEntity> =
        consolidateFileDao.getAllDone()

    fun observeTotal(): Flow<Int> = consolidateFileDao.observeTotal()

    fun observeDoneCount(): Flow<Int> = consolidateFileDoneDao.observeDoneCount()

    fun observeSafeToDelete(): Flow<List<SafeToDeleteView>> = safeToDeleteDao.observeAll()

    fun observeSafeToDeleteCount(): Flow<Int> = safeToDeleteDao.observeCount()
}
