package de.perigon.companion.audio.di

import android.content.Context
import androidx.room.Room
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.ElementsIntoSet
import dagger.multibindings.IntoSet
import de.perigon.companion.audio.AudioChannels
import de.perigon.companion.audio.AudioFeature
import de.perigon.companion.audio.data.AudioDatabase
import de.perigon.companion.audio.data.AudioRecordingDao
import de.perigon.companion.audio.data.AudioRepository
import de.perigon.companion.core.feature.ChannelSpec
import de.perigon.companion.core.feature.FeatureEntry
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AudioModule {

    @Provides @Singleton
    fun provideAudioDatabase(@ApplicationContext ctx: Context): AudioDatabase =
        Room.databaseBuilder(ctx, AudioDatabase::class.java, "audio.db").build()

    @Provides fun provideAudioRecordingDao(db: AudioDatabase): AudioRecordingDao = db.audioRecordingDao()

    @Provides @Singleton
    fun provideAudioRepository(dao: AudioRecordingDao): AudioRepository = AudioRepository(dao)

    @Provides @IntoSet fun provideFeature(feature: AudioFeature): FeatureEntry = feature

    @Provides @ElementsIntoSet
    fun provideChannels(): Set<ChannelSpec> = setOf(ChannelSpec(AudioChannels.RECORDER, "Audio Recording"))
}
