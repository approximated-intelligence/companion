package de.perigon.companion.backup.data

import androidx.room.*
import androidx.room.withTransaction
import de.perigon.companion.core.db.AppDatabase
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

@Entity(tableName = "backup_files", indices = [Index(value = ["path", "sha256"], unique = true)])
data class BackupFileEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val path: String,
    val uri: String,
    val mtime: Long,
    val size: Long,
    val sha256: String,
    val createdAt: Long,
    val updatedAt: Long,
)

@Dao
interface BackupFileDao {
    @Query("SELECT * FROM backup_files WHERE id = :id")
    suspend fun getById(id: Long): BackupFileEntity?

    @Query("SELECT * FROM backup_files WHERE path = :path AND sha256 = :sha256 LIMIT 1")
    suspend fun getByPathSha256(path: String, sha256: String): BackupFileEntity?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(files: List<BackupFileEntity>): List<Long>

    @Query("DELETE FROM backup_files")
    suspend fun deleteAll()
}

@Entity(tableName = "backup_packs")
data class BackupPackEntity(
    @PrimaryKey val packNumber: Int,
    val createdAt: Long,
)

@Dao
interface BackupPackDao {
    @Insert
    suspend fun insert(pack: BackupPackEntity): Long

    @Query("SELECT * FROM backup_packs WHERE packNumber = :packNumber")
    suspend fun getByNumber(packNumber: Int): BackupPackEntity?

    @Query("SELECT MAX(packNumber) FROM backup_packs")
    suspend fun maxPackNumber(): Int?

    @Query("DELETE FROM backup_packs")
    suspend fun deleteAll()
}

@Entity(tableName = "backup_pack_sealed", foreignKeys = [ForeignKey(entity = BackupPackEntity::class, parentColumns = ["packNumber"], childColumns = ["packNumber"], onDelete = ForeignKey.CASCADE)])
data class BackupPackSealedEntity(
    @PrimaryKey val packNumber: Int,
    val sealedAt: Long,
)

@Dao
interface BackupPackSealedDao {
    @Insert
    suspend fun insert(sealed: BackupPackSealedEntity)

    @Query("SELECT * FROM backup_pack_sealed WHERE packNumber = :packNumber")
    suspend fun getByPackNumber(packNumber: Int): BackupPackSealedEntity?

    @Query("SELECT MAX(sealedAt) FROM backup_pack_sealed")
    fun observeLastSealedAt(): Flow<Long?>

    @Query("SELECT MAX(packNumber) FROM backup_pack_sealed")
    suspend fun maxSealedPackNumber(): Int?

    @Query("DELETE FROM backup_pack_sealed")
    suspend fun deleteAll()
}

@Entity(tableName = "backup_parts", foreignKeys = [ForeignKey(entity = BackupPackEntity::class, parentColumns = ["packNumber"], childColumns = ["packNumber"], onDelete = ForeignKey.CASCADE)], indices = [Index(value = ["packNumber", "partNumber"], unique = true), Index("packNumber")])
data class BackupPartEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val packNumber: Int,
    val partNumber: Int,
)

@Dao
interface BackupPartDao {
    @Query("SELECT * FROM backup_parts WHERE packNumber = :packNumber ORDER BY partNumber ASC")
    suspend fun getForPack(packNumber: Int): List<BackupPartEntity>

    @Query("SELECT p.* FROM backup_parts p LEFT JOIN backup_part_etags e ON e.backupPartId = p.id LEFT JOIN backup_pack_sealed s ON s.packNumber = p.packNumber WHERE e.backupPartId IS NULL AND s.packNumber IS NULL ORDER BY p.packNumber ASC, p.partNumber ASC LIMIT 1")
    suspend fun getFirstPending(): BackupPartEntity?

    @Query("SELECT p.* FROM backup_parts p LEFT JOIN backup_part_etags e ON e.backupPartId = p.id LEFT JOIN backup_pack_sealed s ON s.packNumber = p.packNumber WHERE e.backupPartId IS NULL AND s.packNumber IS NULL AND (p.packNumber > :packNumber OR (p.packNumber = :packNumber AND p.partNumber > :partNumber)) ORDER BY p.packNumber ASC, p.partNumber ASC LIMIT 1")
    suspend fun getNextPending(packNumber: Int, partNumber: Int): BackupPartEntity?

    @Query("SELECT p.* FROM backup_parts p JOIN backup_part_etags e ON e.backupPartId = p.id LEFT JOIN backup_pack_sealed s ON s.packNumber = p.packNumber WHERE p.packNumber = :packNumber AND s.packNumber IS NULL ORDER BY p.partNumber ASC")
    suspend fun getUploadedForPack(packNumber: Int): List<BackupPartEntity>

    @Insert
    suspend fun insertAll(parts: List<BackupPartEntity>): List<Long>

    @Query("DELETE FROM backup_parts WHERE packNumber = :packNumber")
    suspend fun deleteForPack(packNumber: Int)

    @Query("DELETE FROM backup_parts")
    suspend fun deleteAll()
}

@Entity(tableName = "backup_part_etags", foreignKeys = [ForeignKey(entity = BackupPartEntity::class, parentColumns = ["id"], childColumns = ["backupPartId"], onDelete = ForeignKey.CASCADE)])
data class BackupPartEtagEntity(
    @PrimaryKey val backupPartId: Long,
    val etag: String,
)

data class PartEtag(val partNumber: Int, val etag: String)

@Dao
interface BackupPartEtagDao {
    @Query("SELECT p.partNumber, e.etag FROM backup_part_etags e JOIN backup_parts p ON p.id = e.backupPartId WHERE p.packNumber = :packNumber ORDER BY p.partNumber ASC")
    suspend fun getEtagsForPack(packNumber: Int): List<PartEtag>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(etag: BackupPartEtagEntity)

    @Query("DELETE FROM backup_part_etags WHERE backupPartId IN (SELECT id FROM backup_parts WHERE packNumber = :packNumber)")
    suspend fun deleteForPack(packNumber: Int)

    @Query("DELETE FROM backup_part_etags")
    suspend fun deleteAll()
}

@Entity(tableName = "backup_chunks", foreignKeys = [ForeignKey(entity = BackupFileEntity::class, parentColumns = ["id"], childColumns = ["backupFileId"], onDelete = ForeignKey.CASCADE), ForeignKey(entity = BackupPartEntity::class, parentColumns = ["id"], childColumns = ["backupPartId"], onDelete = ForeignKey.CASCADE)], indices = [Index("backupFileId"), Index("backupPartId"), Index(value = ["backupFileId", "backupPartId"], unique = true)])
data class BackupChunkEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val backupFileId: Long,
    val backupPartId: Long,
    val fileOffset: Long,
    val chunkBytes: Long,
    val partOffset: Long = -1L,
)

@Dao
interface BackupChunkDao {
    @Query("SELECT c.* FROM backup_chunks c JOIN backup_parts p ON p.id = c.backupPartId WHERE p.packNumber = :packNumber AND p.partNumber = :partNumber ORDER BY c.id ASC")
    suspend fun getForPart(packNumber: Int, partNumber: Int): List<BackupChunkEntity>

    @Query("SELECT c.* FROM backup_chunks c JOIN backup_parts p ON p.id = c.backupPartId WHERE p.packNumber = :packNumber ORDER BY p.partNumber ASC, c.id ASC")
    suspend fun getForPack(packNumber: Int): List<BackupChunkEntity>

    @Query("SELECT c.*, p.packNumber, p.partNumber FROM backup_chunks c JOIN backup_parts p ON p.id = c.backupPartId WHERE c.backupFileId = :fileId ORDER BY p.packNumber ASC, p.partNumber ASC, c.fileOffset ASC")
    suspend fun getForFile(fileId: Long): List<ChunkWithPartInfo>

    @Insert
    suspend fun insertAll(chunks: List<BackupChunkEntity>): List<Long>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(chunks: List<BackupChunkEntity>): List<Long>

    @Query("DELETE FROM backup_chunks WHERE backupPartId IN (SELECT id FROM backup_parts WHERE packNumber = :packNumber)")
    suspend fun deleteForPack(packNumber: Int)

    @Query("DELETE FROM backup_chunks")
    suspend fun deleteAll()
}

data class ChunkWithPartInfo(
    val id: Long,
    val backupFileId: Long,
    val backupPartId: Long,
    val fileOffset: Long,
    val chunkBytes: Long,
    val partOffset: Long,
    val packNumber: Int,
    val partNumber: Int,
)

@Entity(tableName = "backup_files_done", foreignKeys = [ForeignKey(entity = BackupFileEntity::class, parentColumns = ["id"], childColumns = ["backupFileId"], onDelete = ForeignKey.CASCADE)])
data class BackupFileDoneEntity(
    @PrimaryKey val backupFileId: Long,
    val sealedAt: Long,
)

@Dao
interface BackupFileDoneDao {
    @Query("INSERT OR IGNORE INTO backup_files_done (backupFileId, sealedAt) SELECT DISTINCT bf.id, :now FROM backup_files bf JOIN backup_chunks c ON c.backupFileId = bf.id JOIN backup_parts p ON p.id = c.backupPartId WHERE p.packNumber = :packNumber AND NOT EXISTS (SELECT 1 FROM backup_chunks c2 JOIN backup_parts p2 ON p2.id = c2.backupPartId LEFT JOIN backup_pack_sealed s2 ON s2.packNumber = p2.packNumber WHERE c2.backupFileId = bf.id AND s2.packNumber IS NULL)")
    suspend fun markNewlyConfirmed(packNumber: Int, now: Long)

    @Query("SELECT COUNT(*) FROM backup_files_done")
    fun observeCount(): Flow<Int>

    @Query("SELECT MAX(sealedAt) FROM backup_files_done")
    fun observeLastSealedAt(): Flow<Long?>

    @Query("DELETE FROM backup_files_done")
    suspend fun deleteAll()
}

@Entity(tableName = "open_pack")
data class BackupOpenPackEntity(
    @PrimaryKey val id: Int = 1,
    val packNumber: Int,
    val b2UploadId: String,
    val numPartsTarget: Int,
    val createdAt: Long,
)

@Dao
interface BackupOpenPackDao {
    @Query("SELECT * FROM open_pack WHERE id = 1")
    suspend fun get(): BackupOpenPackEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun put(pack: BackupOpenPackEntity)

    @Query("DELETE FROM open_pack WHERE id = 1")
    suspend fun clear()
}

@Entity(tableName = "backup_folders")
data class BackupFolderEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val uri: String,
    val displayName: String,
    val includeInBackup: Boolean = true,
    val isDefault: Boolean = false,
    val addedAt: Long,
)

@Dao
interface BackupFolderDao {
    @Query("SELECT * FROM backup_folders ORDER BY isDefault DESC, displayName ASC")
    fun observeAll(): Flow<List<BackupFolderEntity>>

    @Query("SELECT * FROM backup_folders WHERE includeInBackup = 1")
    suspend fun getEnabled(): List<BackupFolderEntity>

    @Query("SELECT * FROM backup_folders WHERE isDefault = 1")
    suspend fun getDefaults(): List<BackupFolderEntity>

    @Query("SELECT COUNT(*) FROM backup_folders WHERE isDefault = 1")
    suspend fun countDefaults(): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(folder: BackupFolderEntity): Long

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIgnore(folder: BackupFolderEntity): Long

    @Query("DELETE FROM backup_folders WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("UPDATE backup_folders SET includeInBackup = :enabled WHERE id = :id")
    suspend fun setEnabled(id: Long, enabled: Boolean)
}

@Entity(tableName = "restore_selections")
data class RestoreSelectionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val path: String,
    val sha256: String,
    val startPack: Int,
    val startPart: Int,
    val startPartOffset: Long,
    val endPack: Int,
    val endPart: Int,
    val numParts: Int,
    val size: Long,
    val mtime: Long,
)

@Dao
interface RestoreSelectionDao {
    @Query("SELECT * FROM restore_selections ORDER BY startPack ASC, startPart ASC")
    suspend fun getAll(): List<RestoreSelectionEntity>

    @Query("SELECT COUNT(*) FROM restore_selections")
    suspend fun count(): Int

    @Insert
    suspend fun insertAll(selections: List<RestoreSelectionEntity>)

    @Query("DELETE FROM restore_selections")
    suspend fun deleteAll()
}

@DatabaseView(viewName = "backup_file_status_view", value = "SELECT f.id, f.path, f.uri, f.mtime, f.size, f.sha256, CASE WHEN COUNT(c.id) = 0 THEN 0 WHEN SUM(CASE WHEN s.packNumber IS NOT NULL THEN 1 ELSE 0 END) = COUNT(c.id) THEN 2 WHEN SUM(CASE WHEN e.backupPartId IS NOT NULL THEN 1 ELSE 0 END) > 0 THEN 1 ELSE 0 END as state FROM backup_files f LEFT JOIN backup_chunks c ON c.backupFileId = f.id LEFT JOIN backup_parts p ON p.id = c.backupPartId LEFT JOIN backup_part_etags e ON e.backupPartId = p.id LEFT JOIN backup_pack_sealed s ON s.packNumber = p.packNumber GROUP BY f.id ORDER BY f.id ASC")
data class BackupFileStatusView(
    val id: Long,
    val path: String,
    val uri: String,
    val mtime: Long,
    val size: Long,
    val sha256: String,
    val state: Int,
)

@Dao
interface BackupFileStatusDao {
    @Query("SELECT * FROM backup_file_status_view WHERE state = 0 AND size > 0 ORDER BY id ASC")
    suspend fun getPendingFiles(): List<BackupFileStatusView>

    @Query("SELECT MAX(s.packNumber) FROM backup_pack_sealed s")
    suspend fun maxSealedPack(): Int?
}

@DatabaseView(viewName = "backup_restore_view", value = "SELECT f.id, f.path, f.mtime, f.size, f.sha256, MIN(p.packNumber) as startPack, MIN(CASE WHEN p.packNumber = (SELECT MIN(p2.packNumber) FROM backup_parts p2 JOIN backup_chunks c2 ON c2.backupPartId = p2.id WHERE c2.backupFileId = f.id) THEN p.partNumber ELSE NULL END) as startPart, (SELECT c2.partOffset FROM backup_chunks c2 JOIN backup_parts p2 ON p2.id = c2.backupPartId WHERE c2.backupFileId = f.id AND c2.fileOffset = 0) as startPartOffset, MAX(p.packNumber) as endPack, MAX(CASE WHEN p.packNumber = (SELECT MAX(p2.packNumber) FROM backup_parts p2 JOIN backup_chunks c2 ON c2.backupPartId = p2.id WHERE c2.backupFileId = f.id) THEN p.partNumber ELSE NULL END) as endPart, COUNT(c.id) as numParts FROM backup_files f JOIN backup_chunks c ON c.backupFileId = f.id JOIN backup_parts p ON p.id = c.backupPartId JOIN backup_pack_sealed s ON s.packNumber = p.packNumber GROUP BY f.id ORDER BY startPack, startPart")
data class BackupRestoreView(
    val id: Long,
    val path: String,
    val mtime: Long,
    val size: Long,
    val sha256: String,
    val startPack: Int,
    val startPart: Int,
    val startPartOffset: Long,
    val endPack: Int,
    val endPart: Int,
    val numParts: Int,
)

@Dao
interface BackupRestoreViewDao {
    @Query("SELECT * FROM backup_restore_view")
    suspend fun getAll(): List<BackupRestoreView>

    @Query("SELECT * FROM backup_restore_view")
    fun observeAll(): Flow<List<BackupRestoreView>>

    @Query("SELECT * FROM backup_restore_view WHERE path = :path")
    suspend fun getByPath(path: String): List<BackupRestoreView>

    @Query("SELECT * FROM backup_restore_view WHERE id = :fileId")
    suspend fun getByFileId(fileId: Long): List<BackupRestoreView>
}

@Singleton
class BackupStateRepository @Inject constructor(
    private val db: AppDatabase,
    private val backupFileDao: BackupFileDao,
    private val backupPackDao: BackupPackDao,
    private val backupPackSealedDao: BackupPackSealedDao,
    private val backupPartDao: BackupPartDao,
    private val backupPartEtagDao: BackupPartEtagDao,
    private val backupChunkDao: BackupChunkDao,
    private val backupOpenPackDao: BackupOpenPackDao,
    private val backupFolderDao: BackupFolderDao,
    private val backupFileStatusDao: BackupFileStatusDao,
    private val backupRestoreViewDao: BackupRestoreViewDao,
    private val backupFileDoneDao: BackupFileDoneDao,
    private val restoreSelectionDao: RestoreSelectionDao,
) {
    suspend fun <T> inTransaction(block: suspend BackupStateRepository.() -> T): T =
        db.withTransaction { this@BackupStateRepository.block() }

    suspend fun ensurePack(packNumber: Int) {
        if (backupPackDao.getByNumber(packNumber) == null) {
            backupPackDao.insert(BackupPackEntity(packNumber = packNumber, createdAt = System.currentTimeMillis()))
        }
    }

    suspend fun persistOpenPack(pack: BackupOpenPackEntity) = backupOpenPackDao.put(pack)
    suspend fun getOpenPack(): BackupOpenPackEntity? = backupOpenPackDao.get()

    suspend fun insertPackPlan(parts: List<BackupPartEntity>, chunks: List<BackupChunkEntity>) {
        inTransaction {
            parts.map { it.packNumber }.distinct().forEach { ensurePack(it) }
            val partIds = backupPartDao.insertAll(parts)
            val resolvedChunks = chunks.map { chunk ->
                chunk.copy(backupPartId = partIds[chunk.backupPartId.toInt()])
            }
            backupChunkDao.insertAll(resolvedChunks)
        }
    }

    suspend fun confirmPart(packNumber: Int, partNumber: Int, partId: Long, etag: String) {
        backupPartEtagDao.upsert(BackupPartEtagEntity(backupPartId = partId, etag = etag))
    }

    suspend fun sealPack(packNumber: Int) {
        inTransaction {
            val now = System.currentTimeMillis()
            backupPackSealedDao.insert(BackupPackSealedEntity(packNumber = packNumber, sealedAt = now))
            backupOpenPackDao.clear()
            backupFileDoneDao.markNewlyConfirmed(packNumber, now)
        }
    }

    suspend fun resetPack(packNumber: Int) {
        backupPartEtagDao.deleteForPack(packNumber)
        backupOpenPackDao.clear()
    }

    suspend fun resetIncompleteState() {
        val open = backupOpenPackDao.get() ?: return
        backupPartEtagDao.deleteForPack(open.packNumber)
        backupOpenPackDao.clear()
    }

    suspend fun getFirstPendingPart(): BackupPartEntity? = backupPartDao.getFirstPending()
    suspend fun getNextPendingPart(packNumber: Int, partNumber: Int): BackupPartEntity? = backupPartDao.getNextPending(packNumber, partNumber)
    suspend fun getUploadedPartsForPack(packNumber: Int): List<BackupPartEntity> = backupPartDao.getUploadedForPack(packNumber)
    suspend fun getEtagsForPack(packNumber: Int): List<PartEtag> = backupPartEtagDao.getEtagsForPack(packNumber)
    suspend fun getChunksForPart(packNumber: Int, partNumber: Int): List<BackupChunkEntity> = backupChunkDao.getForPart(packNumber, partNumber)
    suspend fun getChunksForFileInPack(fileId: Long, packNumber: Int): List<Pair<BackupChunkEntity, Int>> {
        return backupChunkDao.getForFile(fileId)
            .filter { it.packNumber == packNumber }
            .map {
                BackupChunkEntity(
                    id = it.id,
                    backupFileId = it.backupFileId,
                    backupPartId = it.backupPartId,
                    fileOffset = it.fileOffset,
                    chunkBytes = it.chunkBytes,
                    partOffset = it.partOffset,
                ) to it.partNumber
            }
    }
    suspend fun getChunksForFile(fileId: Long): List<ChunkWithPartInfo> = backupChunkDao.getForFile(fileId)
    suspend fun getFileById(id: Long): BackupFileEntity? = backupFileDao.getById(id)
    suspend fun insertScannedFiles(files: List<BackupFileEntity>) = backupFileDao.insertAll(files)
    suspend fun getPendingFiles(): List<BackupFileStatusView> = backupFileStatusDao.getPendingFiles()
    suspend fun maxSealedPack(): Int? = backupFileStatusDao.maxSealedPack()
    fun observeFolders(): Flow<List<BackupFolderEntity>> = backupFolderDao.observeAll()
    suspend fun countDefaultFolders(): Int = backupFolderDao.countDefaults()
    suspend fun getDefaultFolders(): List<BackupFolderEntity> = backupFolderDao.getDefaults()
    suspend fun insertFolderIgnore(folder: BackupFolderEntity) = backupFolderDao.insertIgnore(folder)
    suspend fun upsertFolder(folder: BackupFolderEntity) = backupFolderDao.upsert(folder)
    suspend fun deleteFolder(id: Long) = backupFolderDao.delete(id)
    suspend fun setFolderEnabled(id: Long, enabled: Boolean) = backupFolderDao.setEnabled(id, enabled)
    fun observeConfirmedCount(): Flow<Int> = backupFileDoneDao.observeCount()
    fun observeLastConfirmedAt(): Flow<Long?> = backupFileDoneDao.observeLastSealedAt()
    suspend fun getRestoreFiles(): List<BackupRestoreView> = backupRestoreViewDao.getAll()
    suspend fun getRestoreFilesByFileId(fileId: Long): List<BackupRestoreView> = backupRestoreViewDao.getByFileId(fileId)
    suspend fun findFileByPathSha256(path: String, sha256: String): BackupFileEntity? =
        backupFileDao.getByPathSha256(path, sha256)

    suspend fun upsertRestoredFileLocation(
        fileId: Long,
        sha256: String,
        packNumber: Int,
        partNumber: Int,
        partOffset: Long,
        fileOffset: Long,
        chunkBytes: Long,
    ) {
        inTransaction {
            ensurePack(packNumber)
            val existing = backupPartDao.getForPack(packNumber).firstOrNull { it.partNumber == partNumber }
            val partId = existing?.id ?: backupPartDao.insertAll(
                listOf(BackupPartEntity(packNumber = packNumber, partNumber = partNumber))
            ).first()
            backupChunkDao.upsertAll(listOf(BackupChunkEntity(
                backupFileId = fileId,
                backupPartId = partId,
                fileOffset = fileOffset,
                chunkBytes = chunkBytes,
                partOffset = partOffset,
            )))
            if (backupPackSealedDao.getByPackNumber(packNumber) == null) {
                backupPackSealedDao.insert(BackupPackSealedEntity(packNumber = packNumber, sealedAt = System.currentTimeMillis()))
            }
        }
    }

    // ---- Restore selections ----

    suspend fun setRestoreSelections(selections: List<RestoreSelectionEntity>) {
        inTransaction {
            restoreSelectionDao.deleteAll()
            if (selections.isNotEmpty()) restoreSelectionDao.insertAll(selections)
        }
    }

    suspend fun getRestoreSelections(): List<RestoreSelectionEntity> = restoreSelectionDao.getAll()
    suspend fun clearRestoreSelections() = restoreSelectionDao.deleteAll()
    suspend fun hasRestoreSelections(): Boolean = restoreSelectionDao.count() > 0
}
