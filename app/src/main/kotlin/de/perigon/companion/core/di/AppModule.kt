package de.perigon.companion.core.di

import android.content.Context
import androidx.room.Room
import androidx.work.WorkManager
import de.perigon.companion.audio.data.AudioConfigPrefs
import de.perigon.companion.core.data.UserNotificationDao
import de.perigon.companion.core.db.AppDatabase
import de.perigon.companion.core.db.MIGRATION_1_2
import de.perigon.companion.core.db.MIGRATION_2_3
import de.perigon.companion.core.db.MIGRATION_3_4
import de.perigon.companion.core.db.MIGRATION_4_5
import de.perigon.companion.core.db.MIGRATION_5_6
import de.perigon.companion.core.db.MIGRATION_6_7
import de.perigon.companion.core.db.MIGRATION_7_8
import de.perigon.companion.core.db.MIGRATION_8_9
import de.perigon.companion.core.db.MIGRATION_9_10
import de.perigon.companion.core.db.MIGRATION_10_11
import de.perigon.companion.core.db.MIGRATION_11_12
import de.perigon.companion.core.db.MIGRATION_12_13
import de.perigon.companion.core.db.MIGRATION_13_14
import de.perigon.companion.core.db.MIGRATION_14_15
import de.perigon.companion.core.db.MIGRATION_15_16
import de.perigon.companion.core.db.MIGRATION_16_17
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
            .addMigrations(
                MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5,
                MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9,
                MIGRATION_9_10, MIGRATION_10_11, MIGRATION_11_12, MIGRATION_12_13,
                MIGRATION_13_14, MIGRATION_14_15, MIGRATION_15_16, MIGRATION_16_17,
            )
            .build()

    @Provides fun provideUserNotificationDao(db: AppDatabase): UserNotificationDao = db.userNotificationDao()

    @Provides fun provideFileContentHashDao(db: AppDatabase): FileContentHashDao = db.fileContentHashDao()

    @Provides @Singleton
    fun provideHttpClient(): HttpClient = HttpClient(Android)

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
