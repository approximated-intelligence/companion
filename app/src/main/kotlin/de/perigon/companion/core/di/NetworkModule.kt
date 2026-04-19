package de.perigon.companion.core.di

import de.perigon.companion.util.network.HttpMediaBackendFactory
import de.perigon.companion.util.network.HttpMediaBackendFactoryImpl
import de.perigon.companion.util.network.S3BackendFactory
import de.perigon.companion.util.network.S3BackendFactoryImpl
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
