package de.perigon.companion.core.di

import android.content.Context
import androidx.room.Room
import androidx.work.WorkManager
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import de.perigon.companion.core.data.UserNotificationDao
import de.perigon.companion.core.db.CoreDatabase
import io.ktor.client.HttpClient
import io.ktor.client.engine.android.Android
import io.ktor.client.plugins.HttpTimeout
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import javax.inject.Singleton

/** Process-wide infrastructure every feature shares. Replaces AppModule. */
@Module
@InstallIn(SingletonComponent::class)
object CoreModule {

    @Provides @Singleton
    fun provideCoreDatabase(@ApplicationContext ctx: Context): CoreDatabase =
        Room.databaseBuilder(ctx, CoreDatabase::class.java, "core.db").build()

    @Provides
    fun provideUserNotificationDao(db: CoreDatabase): UserNotificationDao = db.userNotificationDao()

    /**
     * Shared client for B2/S3, HTTP media, GitHub, tiles.
     *
     * Connect timeout bounds dead endpoints; socket timeout (idle between
     * packets) unwedges stalled transfers. No request-level cap: an 8 MB part
     * on a slow uplink is legitimately minutes long.
     *
     * Retry is deliberately NOT installed here: whether a replay is safe is a
     * property of the operation. Call sites opt in via core.network.retrying().
     */
    @Provides @Singleton
    fun provideHttpClient(): HttpClient = HttpClient(Android) {
        install(HttpTimeout) {
            connectTimeoutMillis = 30_000
            socketTimeoutMillis  = 120_000
        }
    }

    @Provides @Singleton
    fun provideWorkManager(@ApplicationContext ctx: Context): WorkManager =
        WorkManager.getInstance(ctx)

    @Provides @Singleton @ApplicationScope
    fun provideApplicationScope(): CoroutineScope =
        CoroutineScope(SupervisorJob() + Dispatchers.IO)
}
