package de.perigon.companion.track.di

import android.content.Context
import androidx.room.Room
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.ElementsIntoSet
import dagger.multibindings.IntoSet
import de.perigon.companion.core.feature.AppInitializer
import de.perigon.companion.core.feature.ChannelSpec
import de.perigon.companion.core.feature.FeatureEntry
import de.perigon.companion.core.feature.SettingsSection
import de.perigon.companion.track.TrackChannels
import de.perigon.companion.track.TrackFeature
import de.perigon.companion.track.TrackResumeInitializer
import de.perigon.companion.track.TrackSettingsSection
import de.perigon.companion.track.data.MIGRATION_1_2
import de.perigon.companion.track.data.TrackDao
import de.perigon.companion.track.data.TrackDatabase
import de.perigon.companion.track.data.TrackRepository
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object TrackModule {

    @Provides @Singleton
    fun provideTrackDatabase(@ApplicationContext ctx: Context): TrackDatabase =
        Room.databaseBuilder(ctx, TrackDatabase::class.java, "track.db")
            .addMigrations(MIGRATION_1_2)
            .build()

    @Provides fun provideTrackDao(db: TrackDatabase): TrackDao = db.trackDao()

    @Provides @Singleton
    fun provideTrackRepository(dao: TrackDao): TrackRepository = TrackRepository(dao)

    @Provides @IntoSet fun provideFeature(feature: TrackFeature): FeatureEntry = feature

    // Without this binding the TrackSettingsSection never appears in the
    // app's Settings screen — Hilt silently omits it from the Set, and the
    // SettingsScreen just renders the sections that are registered.
    @Provides @IntoSet fun provideSettings(section: TrackSettingsSection): SettingsSection = section

    @Provides @IntoSet fun provideResume(init: TrackResumeInitializer): AppInitializer = init

    @Provides @ElementsIntoSet
    fun provideChannels(): Set<ChannelSpec> = setOf(
        ChannelSpec(TrackChannels.RECORDER, "Background Track"),
        ChannelSpec(TrackChannels.EXPORT, "GPX export"),
    )
}
