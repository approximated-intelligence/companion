package de.perigon.companion.media.di

import android.content.Context
import androidx.room.Room
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet
import de.perigon.companion.core.feature.FeatureEntry
import de.perigon.companion.media.MediaFeature
import de.perigon.companion.media.data.MediaDatabase
import de.perigon.companion.media.data.TransformJobDao
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object MediaModule {

    @Provides @Singleton
    fun provideMediaDatabase(@ApplicationContext ctx: Context): MediaDatabase =
        Room.databaseBuilder(ctx, MediaDatabase::class.java, "media.db")
            .addMigrations(MediaDatabase.MIGRATION_1_2)
            .build()

    @Provides
    fun provideTransformJobDao(db: MediaDatabase): TransformJobDao = db.transformJobDao()

    @Provides @IntoSet
    fun provideFeature(feature: MediaFeature): FeatureEntry = feature
}
