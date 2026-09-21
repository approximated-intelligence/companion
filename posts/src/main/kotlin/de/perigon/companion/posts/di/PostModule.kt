package de.perigon.companion.posts.di

import android.content.Context
import androidx.room.Room
import dagger.Binds
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
import de.perigon.companion.core.feature.MediaInbox
import de.perigon.companion.core.feature.SettingsSection
import de.perigon.companion.posts.PostRepairInitializer
import de.perigon.companion.posts.PostsChannels
import de.perigon.companion.posts.PostsFeature
import de.perigon.companion.posts.PostsMediaInbox
import de.perigon.companion.posts.data.PostDao
import de.perigon.companion.posts.data.PostMediaDao
import de.perigon.companion.posts.data.PostsDatabase
import de.perigon.companion.posts.network.HttpMediaBackendFactory
import de.perigon.companion.posts.network.HttpMediaBackendFactoryImpl
import de.perigon.companion.posts.settings.PostsSettingsSection
import de.perigon.companion.posts.site.data.AssetDao
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class PostModule {

    @Binds abstract fun bindMediaInbox(impl: PostsMediaInbox): MediaInbox

    @Binds @Singleton
    abstract fun bindHttpMediaBackendFactory(impl: HttpMediaBackendFactoryImpl): HttpMediaBackendFactory

    @Binds @IntoSet abstract fun bindFeature(feature: PostsFeature): FeatureEntry
    @Binds @IntoSet abstract fun bindSettings(section: PostsSettingsSection): SettingsSection
    @Binds @IntoSet abstract fun bindRepair(init: PostRepairInitializer): AppInitializer

    companion object {
        @Provides @Singleton
        fun providePostsDatabase(@ApplicationContext ctx: Context): PostsDatabase =
            Room.databaseBuilder(ctx, PostsDatabase::class.java, "posts.db").build()

        @Provides fun providePostDao(db: PostsDatabase): PostDao = db.postDao()
        @Provides fun providePostMediaDao(db: PostsDatabase): PostMediaDao = db.postMediaDao()
        @Provides fun provideAssetDao(db: PostsDatabase): AssetDao = db.assetDao()

        @Provides @ElementsIntoSet
        fun provideChannels(): Set<ChannelSpec> = setOf(ChannelSpec(PostsChannels.PUBLISH, "Publishing"))
    }
}
