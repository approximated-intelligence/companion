package de.perigon.companion.core.db

import de.perigon.companion.audio.data.AudioRecordingDao
import de.perigon.companion.audio.data.AudioRecordingEntity
import de.perigon.companion.backup.data.BackupFileEntity
import de.perigon.companion.backup.data.BackupFileDao
import de.perigon.companion.backup.data.BackupFileHashEntity
import de.perigon.companion.backup.data.BackupFileHashDao
import de.perigon.companion.backup.data.BackupFileStatusView
import de.perigon.companion.backup.data.BackupFileStatusDao
import de.perigon.companion.backup.data.BackupPackEntity
import de.perigon.companion.backup.data.BackupPackDao
import de.perigon.companion.backup.data.BackupPackSealedEntity
import de.perigon.companion.backup.data.BackupPackSealedDao
import de.perigon.companion.backup.data.BackupPartEntity
import de.perigon.companion.backup.data.BackupPartDao
import de.perigon.companion.backup.data.BackupPartEtagEntity
import de.perigon.companion.backup.data.BackupPartEtagDao
import de.perigon.companion.backup.data.BackupChunkEntity
import de.perigon.companion.backup.data.BackupChunkDao
import de.perigon.companion.backup.data.ChunkWithPartInfo
import de.perigon.companion.backup.data.BackupOpenPackEntity
import de.perigon.companion.backup.data.BackupOpenPackDao
import de.perigon.companion.backup.data.BackupFolderEntity
import de.perigon.companion.backup.data.BackupFolderDao
import de.perigon.companion.backup.data.BackupFileDoneEntity
import de.perigon.companion.backup.data.BackupFileDoneDao
import de.perigon.companion.backup.data.BackupRestoreView
import de.perigon.companion.backup.data.BackupRestoreViewDao
import de.perigon.companion.backup.data.RestoreSelectionEntity
import de.perigon.companion.backup.data.RestoreSelectionDao
import de.perigon.companion.core.data.UserNotificationEntity
import de.perigon.companion.core.data.UserNotificationDao
import de.perigon.companion.core.data.NotificationType
import de.perigon.companion.media.data.ConsolidateFileEntity
import de.perigon.companion.media.data.ConsolidateFileDao
import de.perigon.companion.media.data.ConsolidateFileDoneEntity
import de.perigon.companion.media.data.ConsolidateFileDoneDao
import de.perigon.companion.media.data.ConsolidateProtectedFileEntity
import de.perigon.companion.media.data.ConsolidateProtectedFileDao
import de.perigon.companion.media.data.SafeToDeleteView
import de.perigon.companion.media.data.SafeToDeleteDao
import de.perigon.companion.media.data.ProcessedFileEntity
import de.perigon.companion.media.data.ProcessedFileDao
import de.perigon.companion.media.data.TransformJobEntity
import de.perigon.companion.media.data.TransformJobDao
import de.perigon.companion.posts.data.PostEntity
import de.perigon.companion.posts.data.PostMediaEntity
import de.perigon.companion.posts.data.PostDao
import de.perigon.companion.posts.data.PostMediaDao
import de.perigon.companion.posts.domain.PostPublishState
import de.perigon.companion.posts.site.data.AssetEntity
import de.perigon.companion.posts.site.data.AssetDao
import de.perigon.companion.track.data.CurrentTrackEntity
import de.perigon.companion.track.data.TrackEntity
import de.perigon.companion.track.data.TrackSegmentEntity
import de.perigon.companion.track.data.TrackPointEntity
import de.perigon.companion.track.data.TrackStatsEntity
import de.perigon.companion.track.data.CurrentTrackState
import de.perigon.companion.track.data.TrackDao
import de.perigon.companion.track.data.TrackPointRow
import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import androidx.room.withTransaction

class CurrentTrackStateConverter {
    @TypeConverter fun fromState(state: CurrentTrackState): String = state.name
    @TypeConverter fun toState(value: String): CurrentTrackState = CurrentTrackState.valueOf(value)
}

@Database(
    entities = [
        ProcessedFileEntity::class,
        BackupFileEntity::class,
        BackupFileHashEntity::class,
        BackupPackEntity::class,
        BackupPackSealedEntity::class,
        BackupPartEntity::class,
        BackupPartEtagEntity::class,
        BackupChunkEntity::class,
        BackupOpenPackEntity::class,
        BackupFolderEntity::class,
        BackupFileDoneEntity::class,
        RestoreSelectionEntity::class,
        PostEntity::class,
        PostMediaEntity::class,
        TransformJobEntity::class,
        AssetEntity::class,
        TrackEntity::class,
        TrackSegmentEntity::class,
        TrackPointEntity::class,
        TrackStatsEntity::class,
        CurrentTrackEntity::class,
        UserNotificationEntity::class,
        ConsolidateFileEntity::class,
        ConsolidateFileDoneEntity::class,
        ConsolidateProtectedFileEntity::class,
        AudioRecordingEntity::class,
    ],
    views = [
        BackupFileStatusView::class,
        BackupRestoreView::class,
        SafeToDeleteView::class,
        TrackPointRow::class,
    ],
    version = 16,
    exportSchema = true,
)
@TypeConverters(
    PostPublishState.Converter::class,
    NotificationType.Converter::class,
    CurrentTrackStateConverter::class,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun processedFileDao(): ProcessedFileDao
    abstract fun backupFileDao(): BackupFileDao
    abstract fun backupFileHashDao(): BackupFileHashDao
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
    abstract fun postDao(): PostDao
    abstract fun postMediaDao(): PostMediaDao
    abstract fun transformJobDao(): TransformJobDao
    abstract fun assetDao(): AssetDao
    abstract fun trackDao(): TrackDao
    abstract fun userNotificationDao(): UserNotificationDao
    abstract fun consolidateFileDao(): ConsolidateFileDao
    abstract fun consolidateFileDoneDao(): ConsolidateFileDoneDao
    abstract fun consolidateProtectedFileDao(): ConsolidateProtectedFileDao
    abstract fun safeToDeleteDao(): SafeToDeleteDao
    abstract fun audioRecordingDao(): AudioRecordingDao

    suspend fun resetBackupState() {
        withTransaction {
            backupOpenPackDao().clear()
            backupChunkDao().deleteAll()
            backupPartEtagDao().deleteAll()
            backupPartDao().deleteAll()
            backupPackSealedDao().deleteAll()
            backupPackDao().deleteAll()
            backupFileHashDao().deleteAll()
            backupFileDoneDao().deleteAll()
            backupFileDao().deleteAll()
        }
    }
}
