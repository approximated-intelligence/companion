package de.perigon.companion.core.di

import de.perigon.companion.core.db.AppDatabase
import de.perigon.companion.track.data.TrackDao
import de.perigon.companion.track.data.TrackRepository
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object TrackModule {

    @Provides fun provideTrackDao(db: AppDatabase): TrackDao = db.trackDao()

    @Provides @Singleton
    fun provideTrackRepository(dao: TrackDao): TrackRepository = TrackRepository(dao)
}
