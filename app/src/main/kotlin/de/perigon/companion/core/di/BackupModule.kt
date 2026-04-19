package de.perigon.companion.core.di

import de.perigon.companion.backup.data.BackupChunkDao
import de.perigon.companion.backup.data.BackupFileDao
import de.perigon.companion.backup.data.BackupFileDoneDao
import de.perigon.companion.backup.data.BackupFileHashDao
import de.perigon.companion.backup.data.BackupFileStatusDao
import de.perigon.companion.backup.data.BackupFolderDao
import de.perigon.companion.backup.data.BackupOpenPackDao
import de.perigon.companion.backup.data.BackupPackDao
import de.perigon.companion.backup.data.BackupPackSealedDao
import de.perigon.companion.backup.data.BackupPartDao
import de.perigon.companion.backup.data.BackupPartEtagDao
import de.perigon.companion.backup.data.BackupRestoreViewDao
import de.perigon.companion.backup.data.BackupSchedulePrefs
import de.perigon.companion.backup.data.BackupSchedulePrefsImpl
import de.perigon.companion.backup.data.RestoreSelectionDao
import de.perigon.companion.core.db.AppDatabase
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object BackupModule {

    @Provides fun provideBackupFileDao(db: AppDatabase): BackupFileDao = db.backupFileDao()
    @Provides fun provideBackupFileHashDao(db: AppDatabase): BackupFileHashDao = db.backupFileHashDao()
    @Provides fun provideBackupPackDao(db: AppDatabase): BackupPackDao = db.backupPackDao()
    @Provides fun provideBackupPackSealedDao(db: AppDatabase): BackupPackSealedDao = db.backupPackSealedDao()
    @Provides fun provideBackupPartDao(db: AppDatabase): BackupPartDao = db.backupPartDao()
    @Provides fun provideBackupPartEtagDao(db: AppDatabase): BackupPartEtagDao = db.backupPartEtagDao()
    @Provides fun provideBackupChunkDao(db: AppDatabase): BackupChunkDao = db.backupChunkDao()
    @Provides fun provideBackupOpenPackDao(db: AppDatabase): BackupOpenPackDao = db.backupOpenPackDao()
    @Provides fun provideBackupFolderDao(db: AppDatabase): BackupFolderDao = db.backupFolderDao()
    @Provides fun provideBackupFileStatusDao(db: AppDatabase): BackupFileStatusDao = db.backupFileStatusDao()
    @Provides fun provideBackupRestoreViewDao(db: AppDatabase): BackupRestoreViewDao = db.backupRestoreViewDao()
    @Provides fun provideBackupFileDoneDao(db: AppDatabase): BackupFileDoneDao = db.backupFileDoneDao()
    @Provides fun provideRestoreSelectionDao(db: AppDatabase): RestoreSelectionDao = db.restoreSelectionDao()

    @Provides @Singleton
    fun provideBackupSchedulePrefs(impl: BackupSchedulePrefsImpl): BackupSchedulePrefs = impl
}
