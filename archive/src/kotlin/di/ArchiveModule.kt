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
import de.perigon.companion.archive.ArchiveChannels
import de.perigon.companion.archive.ArchiveFeature
import de.perigon.companion.archive.BackupScheduleInitializer
import de.perigon.companion.archive.consolidate.data.ConsolidateFileDao
import de.perigon.companion.archive.consolidate.data.ConsolidateFileDoneDao
import de.perigon.companion.archive.consolidate.data.ConsolidateProtectedFileDao
import de.perigon.companion.archive.consolidate.data.SafeToDeleteDao
import de.perigon.companion.archive.data.BackupChunkDao
import de.perigon.companion.archive.data.ArchiveDatabase
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
import de.perigon.companion.archive.backup.data.BackupSchedulePrefs
import de.perigon.companion.archive.backup.data.BackupSchedulePrefsImpl
import de.perigon.companion.archive.data.FileContentHashDao
import de.perigon.companion.archive.data.RestoreSelectionDao
import de.perigon.companion.archive.backup.settings.BackupScheduleSettingsSection
import de.perigon.companion.archive.backup.settings.BackupSettingsSection
import de.perigon.companion.archive.backup.settings.BackupSourcesSettingsSection
import de.perigon.companion.archive.consolidate.settings.ConsolidationSettingsSection
import de.perigon.companion.core.feature.AppInitializer
import de.perigon.companion.core.feature.ChannelSpec
import de.perigon.companion.core.feature.FeatureEntry
import de.perigon.companion.core.feature.SettingsSection
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object ArchiveModule {

    @Provides @Singleton
    fun provideBackupDatabase(@ApplicationContext ctx: Context): ArchiveDatabase =
        Room.databaseBuilder(ctx, ArchiveDatabase::class.java, "backup.db").build()

    @Provides fun provideBackupFileDao(db: ArchiveDatabase): BackupFileDao = db.backupFileDao()
    @Provides fun provideBackupPackDao(db: ArchiveDatabase): BackupPackDao = db.backupPackDao()
    @Provides fun provideBackupPackSealedDao(db: ArchiveDatabase): BackupPackSealedDao = db.backupPackSealedDao()
    @Provides fun provideBackupPartDao(db: ArchiveDatabase): BackupPartDao = db.backupPartDao()
    @Provides fun provideBackupPartEtagDao(db: ArchiveDatabase): BackupPartEtagDao = db.backupPartEtagDao()
    @Provides fun provideBackupChunkDao(db: ArchiveDatabase): BackupChunkDao = db.backupChunkDao()
    @Provides fun provideBackupOpenPackDao(db: ArchiveDatabase): BackupOpenPackDao = db.backupOpenPackDao()
    @Provides fun provideBackupFolderDao(db: ArchiveDatabase): BackupFolderDao = db.backupFolderDao()
    @Provides fun provideBackupFileStatusDao(db: ArchiveDatabase): BackupFileStatusDao = db.backupFileStatusDao()
    @Provides fun provideBackupRestoreViewDao(db: ArchiveDatabase): BackupRestoreViewDao = db.backupRestoreViewDao()
    @Provides fun provideBackupFileDoneDao(db: ArchiveDatabase): BackupFileDoneDao = db.backupFileDoneDao()
    @Provides fun provideRestoreSelectionDao(db: ArchiveDatabase): RestoreSelectionDao = db.restoreSelectionDao()
    @Provides fun provideConsolidateFileDao(db: ArchiveDatabase): ConsolidateFileDao = db.consolidateFileDao()
    @Provides fun provideConsolidateFileDoneDao(db: ArchiveDatabase): ConsolidateFileDoneDao = db.consolidateFileDoneDao()
    @Provides fun provideConsolidateProtectedFileDao(db: ArchiveDatabase): ConsolidateProtectedFileDao = db.consolidateProtectedFileDao()
    @Provides fun provideSafeToDeleteDao(db: ArchiveDatabase): SafeToDeleteDao = db.safeToDeleteDao()
    @Provides fun provideFileContentHashDao(db: ArchiveDatabase): FileContentHashDao = db.fileContentHashDao()

    @Provides @Singleton
    fun provideBackupSchedulePrefs(impl: BackupSchedulePrefsImpl): BackupSchedulePrefs = impl

    @Provides @Singleton
    fun provideLazySodium(): LazySodiumAndroid = LazySodiumAndroid(SodiumAndroid())

    // Features — one for Consolidate / Restore / Mirror, exposed as tabs.
    @Provides @IntoSet fun provideFeature(feature: ArchiveFeature): FeatureEntry = feature

    // Settings sections
    @Provides @IntoSet fun provideScheduleSettings(section: BackupScheduleSettingsSection): SettingsSection = section
    @Provides @IntoSet fun provideSourcesSettings(section: BackupSourcesSettingsSection): SettingsSection = section
    @Provides @IntoSet fun provideSettings(section: BackupSettingsSection): SettingsSection = section
    @Provides @IntoSet fun provideConsolidationSettings(section: ConsolidationSettingsSection): SettingsSection = section

    // Startup
    @Provides @IntoSet fun provideScheduleInit(init: BackupScheduleInitializer): AppInitializer = init

    // Notification channels
    @Provides @ElementsIntoSet
    fun provideChannels(): Set<ChannelSpec> = setOf(
        ChannelSpec(ArchiveChannels.BACKUP, "Backup"),
        ChannelSpec(ArchiveChannels.CONSOLIDATE, "Consolidation"),
        ChannelSpec(ArchiveChannels.MIRROR, "Mirror"),
    )
}
