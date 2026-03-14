package de.perigon.companion.core.di

import de.perigon.companion.core.db.AppDatabase
import de.perigon.companion.media.data.MediaStoreRepository
import de.perigon.companion.media.data.MediaStoreRepositoryImpl
import de.perigon.companion.media.data.ProcessedFileDao
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

    @Provides @Singleton
    fun provideMediaStoreRepository(impl: MediaStoreRepositoryImpl): MediaStoreRepository = impl
}
