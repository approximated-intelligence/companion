package de.perigon.companion.core.db

import de.perigon.companion.backup.data.BackupFileEntity
import de.perigon.companion.backup.data.BackupFileDao
import de.perigon.companion.backup.data.BackupFileStatus
import de.perigon.companion.backup.data.BackupOpenPackEntity
import de.perigon.companion.backup.data.BackupOpenPackDao
import de.perigon.companion.backup.data.BackupCurrentFileEntity
import de.perigon.companion.backup.data.BackupCurrentFileDao
import de.perigon.companion.backup.data.BackupPartUploadedEntity
import de.perigon.companion.backup.data.BackupPartUploadedDao
import de.perigon.companion.backup.data.BackupFolderEntity
import de.perigon.companion.backup.data.BackupFolderDao
import de.perigon.companion.backup.data.BackupRecordView
import de.perigon.companion.backup.data.BackupRecordViewDao
import de.perigon.companion.backup.data.BackupIssueView
import de.perigon.companion.backup.data.BackupIssueViewDao
import de.perigon.companion.core.data.UserNotificationEntity
import de.perigon.companion.core.data.UserNotificationDao
import de.perigon.companion.core.data.NotificationType
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
        BackupOpenPackEntity::class,
        BackupCurrentFileEntity::class,
        BackupPartUploadedEntity::class,
        PostEntity::class,
        PostMediaEntity::class,
        BackupFolderEntity::class,
        TransformJobEntity::class,
        AssetEntity::class,
        TrackEntity::class,
        TrackSegmentEntity::class,
        TrackPointEntity::class,
        TrackStatsEntity::class,
        CurrentTrackEntity::class,
        UserNotificationEntity::class,
    ],
    views = [
        BackupRecordView::class,
        BackupIssueView::class,
    ],
    version = 4,
    exportSchema = true,
)
@TypeConverters(
    BackupFileStatus.Converter::class,
    PostPublishState.Converter::class,
    NotificationType.Converter::class,
    CurrentTrackStateConverter::class,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun processedFileDao(): ProcessedFileDao
    abstract fun backupFileDao(): BackupFileDao
    abstract fun backupRecordViewDao(): BackupRecordViewDao
    abstract fun backupIssueViewDao(): BackupIssueViewDao
    abstract fun backupOpenPackDao(): BackupOpenPackDao
    abstract fun backupCurrentFileDao(): BackupCurrentFileDao
    abstract fun backupPartUploadedDao(): BackupPartUploadedDao
    abstract fun postDao(): PostDao
    abstract fun postMediaDao(): PostMediaDao
    abstract fun backupFolderDao(): BackupFolderDao
    abstract fun transformJobDao(): TransformJobDao
    abstract fun assetDao(): AssetDao
    abstract fun trackDao(): TrackDao
    abstract fun userNotificationDao(): UserNotificationDao

    suspend fun resetBackupState() {
        withTransaction {
            backupOpenPackDao().clear()
            backupCurrentFileDao().clear()
            backupPartUploadedDao().deleteAll()
            backupFileDao().deleteAll()
        }
    }
}
