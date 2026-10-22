package de.perigon.companion.archive.di

import android.content.Context
import androidx.room.Room
import com.goterl.lazysodium.LazySodiumAndroid
import com.goterl.lazysodium.SodiumAndroid
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.ElementsIntoSet
import dagger.multibindings.IntoSet
import de.perigon.companion.archive.BackupChannels
import de.perigon.companion.archive.BackupFeature
import de.perigon.companion.archive.BackupScheduleInitializer
import de.perigon.companion.archive.consolidate.data.ConsolidateFileDao
import de.perigon.companion.archive.consolidate.data.ConsolidateFileDoneDao
import de.perigon.companion.archive.consolidate.data.ConsolidateProtectedFileDao
import de.perigon.companion.archive.consolidate.data.SafeToDeleteDao
import de.perigon.companion.archive.data.BackupChunkDao
import de.perigon.companion.archive.data.BackupDatabase
import de.perigon.companion.archive.data.BackupFileDao
import de.perigon.companion.archive.data.BackupFileDoneDao
import de.perigon.companion.archive.data.BackupFileStatusDao
import de.perigon.companion.archive.data.BackupFolderDao
import de.perigon.companion.archive.data.BackupOpenPackDao
import de.perigon.companion.archive.data.BackupPackDao
import de.perigon.companion.archive.data.BackupPackSealedDao
import de.perigon.companion.archive.data.BackupPartDao
import de.perigon.companion.archive.data.BackupPartEtagDao
import de.perigon.companion.archive.data.BackupRestoreViewDao
import de.perigon.companion.archive.data.BackupSchedulePrefs
import de.perigon.companion.archive.data.BackupSchedulePrefsImpl
import de.perigon.companion.archive.data.FileContentHashDao
import de.perigon.companion.archive.data.RestoreSelectionDao
import de.perigon.companion.archive.mirror.MirrorChannels
import de.perigon.companion.archive.mirror.MirrorFeature
import de.perigon.companion.archive.settings.BackupSettingsSection
import de.perigon.companion.archive.settings.ConsolidationSettingsSection
import de.perigon.companion.core.feature.AppInitializer
import de.perigon.companion.core.feature.ChannelSpec
import de.perigon.companion.core.feature.FeatureEntry
import de.perigon.companion.core.feature.SettingsSection
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object BackupModule {

    @Provides @Singleton
    fun provideBackupDatabase(@ApplicationContext ctx: Context): BackupDatabase =
        Room.databaseBuilder(ctx, BackupDatabase::class.java, "backup.db").build()

    @Provides fun provideBackupFileDao(db: BackupDatabase): BackupFileDao = db.backupFileDao()
    @Provides fun provideBackupPackDao(db: BackupDatabase): BackupPackDao = db.backupPackDao()
    @Provides fun provideBackupPackSealedDao(db: BackupDatabase): BackupPackSealedDao = db.backupPackSealedDao()
    @Provides fun provideBackupPartDao(db: BackupDatabase): BackupPartDao = db.backupPartDao()
    @Provides fun provideBackupPartEtagDao(db: BackupDatabase): BackupPartEtagDao = db.backupPartEtagDao()
    @Provides fun provideBackupChunkDao(db: BackupDatabase): BackupChunkDao = db.backupChunkDao()
    @Provides fun provideBackupOpenPackDao(db: BackupDatabase): BackupOpenPackDao = db.backupOpenPackDao()
    @Provides fun provideBackupFolderDao(db: BackupDatabase): BackupFolderDao = db.backupFolderDao()
    @Provides fun provideBackupFileStatusDao(db: BackupDatabase): BackupFileStatusDao = db.backupFileStatusDao()
    @Provides fun provideBackupRestoreViewDao(db: BackupDatabase): BackupRestoreViewDao = db.backupRestoreViewDao()
    @Provides fun provideBackupFileDoneDao(db: BackupDatabase): BackupFileDoneDao = db.backupFileDoneDao()
    @Provides fun provideRestoreSelectionDao(db: BackupDatabase): RestoreSelectionDao = db.restoreSelectionDao()
    @Provides fun provideConsolidateFileDao(db: BackupDatabase): ConsolidateFileDao = db.consolidateFileDao()
    @Provides fun provideConsolidateFileDoneDao(db: BackupDatabase): ConsolidateFileDoneDao = db.consolidateFileDoneDao()
    @Provides fun provideConsolidateProtectedFileDao(db: BackupDatabase): ConsolidateProtectedFileDao = db.consolidateProtectedFileDao()
    @Provides fun provideSafeToDeleteDao(db: BackupDatabase): SafeToDeleteDao = db.safeToDeleteDao()
    @Provides fun provideFileContentHashDao(db: BackupDatabase): FileContentHashDao = db.fileContentHashDao()

    @Provides @Singleton
    fun provideBackupSchedulePrefs(impl: BackupSchedulePrefsImpl): BackupSchedulePrefs = impl

    @Provides @Singleton
    fun provideLazySodium(): LazySodiumAndroid = LazySodiumAndroid(SodiumAndroid())

    // Features
    @Provides @IntoSet fun provideFeature(feature: BackupFeature): FeatureEntry = feature
    @Provides @IntoSet fun provideMirrorFeature(feature: MirrorFeature): FeatureEntry = feature

    // Settings sections
    @Provides @IntoSet fun provideSettings(section: BackupSettingsSection): SettingsSection = section
    @Provides @IntoSet fun provideConsolidationSettings(section: ConsolidationSettingsSection): SettingsSection = section

    // Startup
    @Provides @IntoSet fun provideScheduleInit(init: BackupScheduleInitializer): AppInitializer = init

    // Notification channels
    @Provides @ElementsIntoSet
    fun provideChannels(): Set<ChannelSpec> = setOf(
        ChannelSpec(BackupChannels.BACKUP, "Backup"),
        ChannelSpec(BackupChannels.CONSOLIDATE, "Consolidation"),
        ChannelSpec(MirrorChannels.MIRROR, "Mirror"),
    )
}
