package de.perigon.companion.backup.data

import androidx.room.*
import kotlinx.coroutines.flow.Flow

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
