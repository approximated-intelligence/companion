package de.perigon.companion.core.di

import android.content.Context
import androidx.room.Room
import androidx.work.WorkManager
import de.perigon.companion.core.data.UserNotificationDao
import de.perigon.companion.core.db.AppDatabase
import de.perigon.companion.core.prefs.AppPrefs
import de.perigon.companion.core.prefs.AppPrefsImpl
import de.perigon.companion.core.prefs.CredentialStore
import de.perigon.companion.core.prefs.KeyStoreCredentialStore
import com.goterl.lazysodium.LazySodiumAndroid
import com.goterl.lazysodium.SodiumAndroid
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import io.ktor.client.HttpClient
import io.ktor.client.engine.android.Android
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides @Singleton
    fun provideDatabase(@ApplicationContext ctx: Context): AppDatabase =
        Room.databaseBuilder(ctx, AppDatabase::class.java, "companion.db")
            .addMigrations(
                de.perigon.companion.core.db.MIGRATION_1_2,
                de.perigon.companion.core.db.MIGRATION_2_3,
                de.perigon.companion.core.db.MIGRATION_3_4,
            )
            .build()

    @Provides fun provideUserNotificationDao(db: AppDatabase): UserNotificationDao = db.userNotificationDao()

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
}
