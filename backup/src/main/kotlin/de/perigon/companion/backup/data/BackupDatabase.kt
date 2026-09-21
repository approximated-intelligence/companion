package de.perigon.companion.backup.data

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.withTransaction
import de.perigon.companion.backup.consolidate.data.ConsolidateFileDao
import de.perigon.companion.backup.consolidate.data.ConsolidateFileDoneDao
import de.perigon.companion.backup.consolidate.data.ConsolidateFileDoneEntity
import de.perigon.companion.backup.consolidate.data.ConsolidateFileEntity
import de.perigon.companion.backup.consolidate.data.ConsolidateProtectedFileDao
import de.perigon.companion.backup.consolidate.data.ConsolidateProtectedFileEntity
import de.perigon.companion.backup.consolidate.data.SafeToDeleteDao
import de.perigon.companion.backup.consolidate.data.SafeToDeleteView

/**
 * Backup, restore and consolidation share one database: safe_to_delete_view
 * joins consolidation state with confirmed backup state, and both use the
 * same content-hash cache.
 */
@Database(
    entities = [
        BackupFileEntity::class,
        BackupPackEntity::class,
        BackupPackSealedEntity::class,
        BackupPartEntity::class,
        BackupPartEtagEntity::class,
        BackupChunkEntity::class,
        BackupOpenPackEntity::class,
        BackupFolderEntity::class,
        BackupFileDoneEntity::class,
        RestoreSelectionEntity::class,
        ConsolidateFileEntity::class,
        ConsolidateFileDoneEntity::class,
        ConsolidateProtectedFileEntity::class,
        FileContentHashEntity::class,
    ],
    views = [
        BackupFileStatusView::class,
        BackupRestoreView::class,
        SafeToDeleteView::class,
    ],
    version = 1,
    exportSchema = true,
)
abstract class BackupDatabase : RoomDatabase() {
    abstract fun backupFileDao(): BackupFileDao
    abstract fun backupPackDao(): BackupPackDao
    abstract fun backupPackSealedDao(): BackupPackSealedDao
    abstract fun backupPartDao(): BackupPartDao
    abstract fun backupPartEtagDao(): BackupPartEtagDao
    abstract fun backupChunkDao(): BackupChunkDao
    abstract fun backupOpenPackDao(): BackupOpenPackDao
    abstract fun backupFolderDao(): BackupFolderDao
    abstract fun backupFileDoneDao(): BackupFileDoneDao
    abstract fun backupFileStatusDao(): BackupFileStatusDao
    abstract fun backupRestoreViewDao(): BackupRestoreViewDao
    abstract fun restoreSelectionDao(): RestoreSelectionDao
    abstract fun consolidateFileDao(): ConsolidateFileDao
    abstract fun consolidateFileDoneDao(): ConsolidateFileDoneDao
    abstract fun consolidateProtectedFileDao(): ConsolidateProtectedFileDao
    abstract fun safeToDeleteDao(): SafeToDeleteDao
    abstract fun fileContentHashDao(): FileContentHashDao

    /** Forget all pack state, e.g. after the backup key changed onto an empty bucket. */
    suspend fun resetBackupState() {
        withTransaction {
            backupOpenPackDao().clear()
            backupChunkDao().deleteAll()
            backupPartEtagDao().deleteAll()
            backupPartDao().deleteAll()
            backupPackSealedDao().deleteAll()
            backupPackDao().deleteAll()
            backupFileDoneDao().deleteAll()
            backupFileDao().deleteAll()
        }
    }
}
