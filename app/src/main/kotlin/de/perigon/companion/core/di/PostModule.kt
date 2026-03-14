package de.perigon.companion.core.di

import de.perigon.companion.core.db.AppDatabase
import de.perigon.companion.posts.data.PostDao
import de.perigon.companion.posts.data.PostMediaDao
import de.perigon.companion.posts.site.data.AssetDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

@Module
@InstallIn(SingletonComponent::class)
object PostModule {

    @Provides fun providePostDao(db: AppDatabase): PostDao = db.postDao()
    @Provides fun providePostMediaDao(db: AppDatabase): PostMediaDao = db.postMediaDao()
    @Provides fun provideAssetDao(db: AppDatabase): AssetDao = db.assetDao()
}
