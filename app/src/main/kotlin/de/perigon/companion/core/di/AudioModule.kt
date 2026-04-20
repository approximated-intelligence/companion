package de.perigon.companion.core.di

import de.perigon.companion.audio.data.AudioRecordingDao
import de.perigon.companion.audio.data.AudioRepository
import de.perigon.companion.core.db.AppDatabase
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AudioModule {

    @Provides fun provideAudioRecordingDao(db: AppDatabase): AudioRecordingDao = db.audioRecordingDao()

    @Provides @Singleton
    fun provideAudioRepository(dao: AudioRecordingDao): AudioRepository = AudioRepository(dao)
}
