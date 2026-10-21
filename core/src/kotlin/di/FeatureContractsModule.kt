package de.perigon.companion.core.di

import dagger.Binds
import dagger.BindsOptionalOf
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet
import dagger.multibindings.Multibinds
import de.perigon.companion.core.feature.AppInitializer
import de.perigon.companion.core.feature.ChannelSpec
import de.perigon.companion.core.feature.FeatureEntry
import de.perigon.companion.core.feature.MediaInbox
import de.perigon.companion.core.feature.RecordedTrackSource
import de.perigon.companion.core.feature.SettingsSection
import de.perigon.companion.core.files.CacheSweep
import de.perigon.companion.core.ui.settings.GeneralSettingsSection
import de.perigon.companion.core.ui.settings.SettingsFeature

/**
 * Declares the contribution points so they resolve (possibly empty) before
 * any feature contributes, and registers core's own contributions.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class FeatureContractsModule {
    @Multibinds abstract fun features(): Set<FeatureEntry>
    @Multibinds abstract fun settingsSections(): Set<SettingsSection>
    @Multibinds abstract fun initializers(): Set<AppInitializer>
    @Multibinds abstract fun channels(): Set<ChannelSpec>

    @BindsOptionalOf abstract fun mediaInbox(): MediaInbox
    @BindsOptionalOf abstract fun recordedTrackSource(): RecordedTrackSource

    @Binds @IntoSet abstract fun cacheSweep(impl: CacheSweep): AppInitializer
    @Binds @IntoSet abstract fun settingsFeature(impl: SettingsFeature): FeatureEntry
    @Binds @IntoSet abstract fun generalSettings(impl: GeneralSettingsSection): SettingsSection
}
