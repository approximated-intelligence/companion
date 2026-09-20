package de.perigon.companion.core.di

import android.content.Context
import androidx.room.Room
import androidx.work.WorkManager
import de.perigon.companion.audio.data.AudioConfigPrefs
import de.perigon.companion.core.data.UserNotificationDao
import de.perigon.companion.core.db.AppDatabase
import de.perigon.companion.core.db.MIGRATION_18_1
import de.perigon.companion.core.prefs.AppPrefs
import de.perigon.companion.core.prefs.AppPrefsImpl
import de.perigon.companion.core.prefs.CredentialStore
import de.perigon.companion.core.prefs.KeyStoreCredentialStore
import de.perigon.companion.util.FileContentHashDao
import com.goterl.lazysodium.LazySodiumAndroid
import com.goterl.lazysodium.SodiumAndroid
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import io.ktor.client.HttpClient
import io.ktor.client.engine.android.Android
import io.ktor.client.plugins.HttpTimeout
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides @Singleton
    fun provideDatabase(@ApplicationContext ctx: Context): AppDatabase =
        Room.databaseBuilder(ctx, AppDatabase::class.java, "companion.db")
            // Schema collapsed to v1. Only the 18→1 downgrade is needed: every
            // client shipped at 18, and fresh installs open at 1 directly.
            .addMigrations(MIGRATION_18_1)
            .build()

    @Provides fun provideUserNotificationDao(db: AppDatabase): UserNotificationDao = db.userNotificationDao()

    @Provides fun provideFileContentHashDao(db: AppDatabase): FileContentHashDao = db.fileContentHashDao()

    /**
     * Shared client for B2/S3, HTTP media, GitHub, tile-free fetches.
     *
     * Timeouts: connect bounds dead endpoints; socket (idle between packets)
     * unwedges stalled transfers — previously a dying connection could hang a
     * backup worker for the full dataSync FGS budget. No request-level cap:
     * an 8 MB part upload on a slow uplink is legitimately minutes long, and
     * the socket timeout already catches true stalls.
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

    @Provides @Singleton
    fun provideLazySodium(): LazySodiumAndroid = LazySodiumAndroid(SodiumAndroid())

    @Provides @Singleton
    fun provideCredentialStore(impl: KeyStoreCredentialStore): CredentialStore = impl

    @Provides @Singleton
    fun provideAppPrefs(impl: AppPrefsImpl): AppPrefs = impl

    @Provides @Singleton @ApplicationScope
    fun provideApplicationScope(): CoroutineScope =
        CoroutineScope(SupervisorJob() + Dispatchers.IO)
}
