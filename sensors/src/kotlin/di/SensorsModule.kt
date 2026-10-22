package de.perigon.companion.sensors.di

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
import de.perigon.companion.core.feature.RecordedTrackSource
import de.perigon.companion.core.feature.SettingsSection
import de.perigon.companion.sensors.SensorsChannels
import de.perigon.companion.sensors.SensorsFeature
import de.perigon.companion.sensors.SensorsResumeInitializer
import de.perigon.companion.sensors.SensorsSettingsSection
import de.perigon.companion.sensors.data.MIGRATION_1_2
import de.perigon.companion.sensors.data.MIGRATION_2_3
import de.perigon.companion.sensors.data.SensorsRecordedTrackSource
import de.perigon.companion.sensors.data.TrackDao
import de.perigon.companion.sensors.data.TrackDatabase
import de.perigon.companion.sensors.data.TrackRepository
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object SensorsModule {

    @Provides @Singleton
    fun provideTrackDatabase(@ApplicationContext ctx: Context): TrackDatabase =
        Room.databaseBuilder(ctx, TrackDatabase::class.java, "track.db")
            .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
            .build()

    @Provides fun provideTrackDao(db: TrackDatabase): TrackDao = db.trackDao()

    @Provides @Singleton
    fun provideTrackRepository(dao: TrackDao): TrackRepository = TrackRepository(dao)

    @Provides @IntoSet fun provideFeature(feature: SensorsFeature): FeatureEntry = feature

    // Without this binding the SensorsSettingsSection never appears in the
    // app's Settings screen — Hilt silently omits it from the Set, and the
    // SettingsScreen just renders the sections that are registered.
    @Provides @IntoSet fun provideSettings(section: SensorsSettingsSection): SettingsSection = section

    @Provides @IntoSet fun provideResume(init: SensorsResumeInitializer): AppInitializer = init

    // Offered for media's render picker; consumed as Optional<RecordedTrackSource>.
    @Provides @Singleton
    fun provideRecordedTrackSource(impl: SensorsRecordedTrackSource): RecordedTrackSource = impl

    @Provides @ElementsIntoSet
    fun provideChannels(): Set<ChannelSpec> = setOf(
        ChannelSpec(SensorsChannels.RECORDER, "Background Track"),
        ChannelSpec(SensorsChannels.EXPORT, "GPX export"),
        // Diagnostic channel for the KF sandbox's foreground service. LOW
        // importance: no sound, no heads-up — the sandbox is used with the
        // screen off for convergence, so any visible interruption would
        // defeat the purpose.
        ChannelSpec(SensorsChannels.KF_SANDBOX, "KF Sandbox"),
    )
}
