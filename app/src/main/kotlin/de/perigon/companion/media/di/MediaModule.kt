package de.perigon.companion.media.di

import de.perigon.companion.core.db.AppDatabase
import de.perigon.companion.backup.consolidate.data.ConsolidateFileDao
import de.perigon.companion.backup.consolidate.data.ConsolidateFileDoneDao
import de.perigon.companion.backup.consolidate.data.ConsolidateProtectedFileDao
import de.perigon.companion.media.data.MediaStoreRepository
import de.perigon.companion.media.data.MediaStoreRepositoryImpl
import de.perigon.companion.media.data.ProcessedFileDao
import de.perigon.companion.backup.consolidate.data.SafeToDeleteDao
import de.perigon.companion.media.data.TransformJobDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object MediaModule {

    @Provides fun provideProcessedFileDao(db: AppDatabase): ProcessedFileDao = db.processedFileDao()
    @Provides fun provideTransformJobDao(db: AppDatabase): TransformJobDao = db.transformJobDao()
    @Provides fun provideConsolidateFileDao(db: AppDatabase): ConsolidateFileDao = db.consolidateFileDao()
    @Provides fun provideConsolidateFileDoneDao(db: AppDatabase): ConsolidateFileDoneDao = db.consolidateFileDoneDao()
    @Provides fun provideConsolidateProtectedFileDao(db: AppDatabase): ConsolidateProtectedFileDao = db.consolidateProtectedFileDao()
    @Provides fun provideSafeToDeleteDao(db: AppDatabase): SafeToDeleteDao = db.safeToDeleteDao()

    @Provides @Singleton
    fun provideMediaStoreRepository(impl: MediaStoreRepositoryImpl): MediaStoreRepository = impl
}
