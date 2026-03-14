package de.perigon.companion.backup.data

import androidx.room.withTransaction
import androidx.room.*
import de.perigon.companion.backup.domain.BackupFilePhase
import de.perigon.companion.backup.domain.BackupPartRecord
import de.perigon.companion.core.db.AppDatabase
import javax.inject.Inject
import javax.inject.Singleton

/**
 * All transactional backup state mutations in one place.
 * Every method that touches multiple tables does so inside a Room transaction.
 */
@Singleton
class BackupStateRepository @Inject constructor(
    private val db: AppDatabase,
    private val backupFileDao: BackupFileDao,
    private val openPackDao: BackupOpenPackDao,
    private val currentFileDao: BackupCurrentFileDao,
    private val partUploadedDao: BackupPartUploadedDao,
) {

    // ---- Pack lifecycle (happy path) ----

    suspend fun persistOpenPack(pack: BackupOpenPackEntity) = openPackDao.put(pack)

    suspend fun sealPack(packPos: Int) {
        val now = System.currentTimeMillis()
        db.withTransaction {
            backupFileDao.promoteStreamed(packPos, now = now)
            partUploadedDao.deleteForPack(packPos)
            currentFileDao.clear()
            openPackDao.clear()
        }
    }

    // ---- Abort flow ----

    suspend fun markPackForAbort(packPos: Int) {
        val now = System.currentTimeMillis()
        db.withTransaction {
            backupFileDao.resetPackFiles(packPos, now = now)
            currentFileDao.clear()
        }
    }

    suspend fun finalizeAbort(packPos: Int) {
        db.withTransaction {
            partUploadedDao.deleteForPack(packPos)
            openPackDao.clear()
        }
    }

    // ---- Reset ----

    suspend fun resetPack(packPos: Int) {
        val now = System.currentTimeMillis()
        db.withTransaction {
            backupFileDao.resetPackFiles(packPos, now = now)
            partUploadedDao.deleteForPack(packPos)
            currentFileDao.clear()
            openPackDao.clear()
        }
    }

    // ---- Pack corruption ----

    suspend fun markPackCorrupt(packPos: Int, detail: String) {
        val now = System.currentTimeMillis()
        db.withTransaction {
            backupFileDao.markPackIssue(packPos, detail, now = now)
            partUploadedDao.deleteForPack(packPos)
            currentFileDao.clear()
            openPackDao.clear()
        }
    }

    // ---- Part persistence ----

    suspend fun persistPart(packPos: Int, record: BackupPartRecord) {
        val now = System.currentTimeMillis()
        partUploadedDao.insert(BackupPartUploadedEntity(
            packPosition = packPos,
            partNumber = record.partNumber,
            etag = record.etag,
            wireSize = record.wireSize,
            uploadedAt = now,
        ))
    }

    // ---- Bytes in pack tracking ----

    suspend fun updateBytesInPack(bytes: Long) {
        openPackDao.updateBytesInPack(bytes)
    }

    suspend fun getBytesInPack(packPos: Int): Long {
        return openPackDao.get()?.bytesInPack ?: 0L
    }

    // ---- File-level state updates ----

    suspend fun markFileStreaming(
        fileId: Long,
        packPosition: Int,
        packFileIndex: Int,
        startPart: Int,
        startPartOffset: Long,
    ) {
        val now = System.currentTimeMillis()
        backupFileDao.updateStreaming(
            id = fileId,
            startPack = packPosition,
            endPack = packPosition,
            startPart = startPart,
            startPartOffset = startPartOffset,
            packFileIndex = packFileIndex,
            now = now,
        )
        currentFileDao.put(BackupCurrentFileEntity(
            backupFileId = fileId,
            byteOffset = 0L,
            phase = BackupFilePhase.STREAMING.name,
        ))
    }

    suspend fun markFileStreamed(fileId: Long, packPosition: Int) {
        val now = System.currentTimeMillis()
        db.withTransaction {
            backupFileDao.updateStreamed(id = fileId, endPack = packPosition, now = now)
            currentFileDao.clear()
        }
    }

    suspend fun markFileIssue(id: Long, detail: String) {
        val now = System.currentTimeMillis()
        backupFileDao.markIssue(id, detail, now = now)
    }

    suspend fun updateByteOffset(offset: Long) {
        currentFileDao.setByteOffset(offset)
    }

    // ---- Queries for recovery ----

    suspend fun getOpenPack(): BackupOpenPackEntity? = openPackDao.get()
    suspend fun getPartsForPack(pos: Int) = partUploadedDao.getForPack(pos)
    suspend fun getCurrentFile() = currentFileDao.get()

    suspend fun getRecoveryFileById(id: Long) = backupFileDao.getById(id)
}

// --- Merged from BackupCurrentFileEntity.kt ---

@Entity(tableName = "current_file")
data class BackupCurrentFileEntity(
    @PrimaryKey val id: Int = 1,
    val backupFileId: Long,
    val byteOffset: Long = 0L,
    val phase: String = "HASHING",
)

@Dao
interface BackupCurrentFileDao {
    @Query("SELECT * FROM current_file WHERE id = 1")
    suspend fun get(): BackupCurrentFileEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun put(file: BackupCurrentFileEntity)

    @Query("UPDATE current_file SET byteOffset = :offset WHERE id = 1")
    suspend fun setByteOffset(offset: Long)

    @Query("UPDATE current_file SET phase = :phase WHERE id = 1")
    suspend fun setPhase(phase: String)

    @Query("DELETE FROM current_file WHERE id = 1")
    suspend fun clear()
}


// --- Merged from BackupOpenPackEntity.kt ---

@Entity(tableName = "open_pack")
data class BackupOpenPackEntity(
    @PrimaryKey val id: Int = 1,
    val packPosition: Int,
    val b2UploadId: String,
    val startFileId: Long,
    val startFileOffset: Long = 0L,
    val numPartsTarget: Int,
    val bytesInPack: Long = 0L,
    val createdAt: Long,
)

@Dao
interface BackupOpenPackDao {
    @Query("SELECT * FROM open_pack WHERE id = 1")
    suspend fun get(): BackupOpenPackEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun put(pack: BackupOpenPackEntity)

    @Query("UPDATE open_pack SET bytesInPack = :bytes WHERE id = 1")
    suspend fun updateBytesInPack(bytes: Long)

    @Query("DELETE FROM open_pack WHERE id = 1")
    suspend fun clear()
}


// --- Merged from BackupPartUploadedEntity.kt ---

@Entity(
    tableName = "parts_uploaded",
    indices = [Index("packPosition")],
)
data class BackupPartUploadedEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val packPosition: Int,
    val partNumber: Int,
    val etag: String,
    val wireSize: Long = 0L,
    val uploadedAt: Long,
)

@Dao
interface BackupPartUploadedDao {
    @Query("SELECT * FROM parts_uploaded WHERE packPosition = :pos ORDER BY partNumber ASC")
    suspend fun getForPack(pos: Int): List<BackupPartUploadedEntity>

    @Query("SELECT * FROM parts_uploaded WHERE packPosition = :pos ORDER BY partNumber DESC LIMIT 1")
    suspend fun getLastForPack(pos: Int): BackupPartUploadedEntity?

    @Insert
    suspend fun insert(part: BackupPartUploadedEntity): Long

    @Query("DELETE FROM parts_uploaded WHERE packPosition = :pos")
    suspend fun deleteForPack(pos: Int)

    @Query("DELETE FROM parts_uploaded")
    suspend fun deleteAll()
}
