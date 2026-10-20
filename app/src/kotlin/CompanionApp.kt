package de.perigon.companion

import android.app.Application
import android.util.Log
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import dagger.hilt.android.HiltAndroidApp
import de.perigon.companion.core.di.ApplicationScope
import de.perigon.companion.core.feature.AppInitializer
import de.perigon.companion.core.feature.ChannelSpec
import de.perigon.companion.core.ui.NotificationChannels
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Knows no feature. Channels are created synchronously (a service may post
 * during this very process start); everything else features need at startup
 * runs as AppInitializers.
 */
@HiltAndroidApp
class CompanionApp : Application(), Configuration.Provider {

    @Inject lateinit var workerFactory: HiltWorkerFactory
    @Inject lateinit var channels: Set<@JvmSuppressWildcards ChannelSpec>
    @Inject lateinit var initializers: Set<@JvmSuppressWildcards AppInitializer>
    @Inject @ApplicationScope lateinit var appScope: CoroutineScope

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()

    override fun onCreate() {
        super.onCreate()
        NotificationChannels.ensure(this, channels)
        runInitializers()
    }

    /** By order; one failure never blocks the rest. */
    private fun runInitializers() {
        appScope.launch(Dispatchers.IO) {
            for (init in initializers.sortedBy { it.order }) {
                try {
                    init.run()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.e("CompanionApp", "initializer ${init.javaClass.simpleName} failed", e)
                }
            }
        }
    }
}
