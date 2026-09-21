package de.perigon.companion.core.di

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import de.perigon.companion.core.network.S3BackendFactory
import de.perigon.companion.core.network.S3BackendFactoryImpl
import javax.inject.Singleton

/** S3 is shared by backup (B2) and posts (S3 media); HTTP media is posts-only and bound there. */
@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {
    @Provides @Singleton
    fun provideS3BackendFactory(impl: S3BackendFactoryImpl): S3BackendFactory = impl
}
