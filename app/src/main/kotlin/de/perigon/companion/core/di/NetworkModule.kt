package de.perigon.companion.core.di

import de.perigon.companion.posts.network.HttpMediaBackendFactory
import de.perigon.companion.posts.network.HttpMediaBackendFactoryImpl
import de.perigon.companion.core.network.S3BackendFactory
import de.perigon.companion.core.network.S3BackendFactoryImpl
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {

    @Provides @Singleton
    fun provideS3BackendFactory(impl: S3BackendFactoryImpl): S3BackendFactory = impl

    @Provides @Singleton
    fun provideHttpMediaBackendFactory(impl: HttpMediaBackendFactoryImpl): HttpMediaBackendFactory = impl
}
