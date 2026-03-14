package de.perigon.companion.core.di

import de.perigon.companion.backup.data.BackupCurrentFileDao
import de.perigon.companion.backup.data.BackupFileDao
import de.perigon.companion.backup.data.BackupFolderDao
import de.perigon.companion.backup.data.BackupIssueViewDao
import de.perigon.companion.backup.data.BackupOpenPackDao
import de.perigon.companion.backup.data.BackupPartUploadedDao
import de.perigon.companion.backup.data.BackupRecordViewDao
import de.perigon.companion.backup.data.BackupSchedulePrefs
import de.perigon.companion.backup.data.BackupSchedulePrefsImpl
import de.perigon.companion.backup.network.b2.B2BackendFactory
import de.perigon.companion.backup.network.b2.B2BackendFactoryImpl
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
    @Provides fun provideBackupRecordViewDao(db: AppDatabase): BackupRecordViewDao = db.backupRecordViewDao()
    @Provides fun provideBackupIssueViewDao(db: AppDatabase): BackupIssueViewDao = db.backupIssueViewDao()
    @Provides fun provideBackupOpenPackDao(db: AppDatabase): BackupOpenPackDao = db.backupOpenPackDao()
    @Provides fun provideBackupCurrentFileDao(db: AppDatabase): BackupCurrentFileDao = db.backupCurrentFileDao()
    @Provides fun provideBackupPartUploadedDao(db: AppDatabase): BackupPartUploadedDao = db.backupPartUploadedDao()
    @Provides fun provideBackupFolderDao(db: AppDatabase): BackupFolderDao = db.backupFolderDao()

    @Provides @Singleton
    fun provideB2BackendFactory(impl: B2BackendFactoryImpl): B2BackendFactory = impl

    @Provides @Singleton
    fun provideBackupSchedulePrefs(impl: BackupSchedulePrefsImpl): BackupSchedulePrefs = impl
}
