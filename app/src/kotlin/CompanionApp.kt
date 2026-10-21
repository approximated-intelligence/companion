package de.perigon.companion

import android.app.Application
import android.content.Intent
import android.content.IntentFilter
import android.os.Environment
import android.os.storage.StorageManager
import android.os.storage.StorageVolume
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import dagger.hilt.android.HiltAndroidApp
import de.perigon.companion.core.di.ApplicationScope
import de.perigon.companion.core.feature.AppInitializer
import de.perigon.companion.core.feature.ChannelSpec
import de.perigon.companion.core.saf.ExternalDriveEjectBus
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
 *
 * Also owns the one process-lifetime ACTION_MEDIA_* receiver. It lives here,
 * not in an Activity, for two reasons:
 *  1. the bus must stay fed while the activity is paused or rotated — a
 *     worker writing to a thumb drive must learn the drive is going away
 *     regardless of what the UI is doing;
 *  2. registration is tied to the process, matching the bus's lifetime, so
 *     there is no register/unregister race with the worker.
 *
 * StorageManager.StorageVolumeCallback is registered alongside as the
 * complementary signal. It catches:
 *  - yanks that skip the eject handshake (no broadcast, only the state
 *    transition), AND
 *  - REMOUNTS. The broadcast ACTION_MEDIA_EJECT adds the UUID to the bus;
 *    without a symmetric signal on MOUNTED the drive would stay greyed out
 *    as "ejecting" for the rest of the process — that was the bug this file
 *    now guards against.
 */
@HiltAndroidApp
class CompanionApp : Application(), Configuration.Provider {

    @Inject lateinit var workerFactory: HiltWorkerFactory
    @Inject lateinit var channels: Set<@JvmSuppressWildcards ChannelSpec>
    @Inject lateinit var initializers: Set<@JvmSuppressWildcards AppInitializer>
    @Inject lateinit var ejectBus: ExternalDriveEjectBus
    @Inject @ApplicationScope lateinit var appScope: CoroutineScope

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()

    override fun onCreate() {
        super.onCreate()
        NotificationChannels.ensure(this, channels)
        registerDriveEjectSignals()
        runInitializers()
    }

    private fun registerDriveEjectSignals() {
        // Eject/mount handshake. ACTION_MEDIA_EJECT fires while the media is
        // still present; this is the window in which the platform expects
        // apps to close their streams. ACTION_MEDIA_MOUNTED is the symmetric
        // signal that clears the ejecting flag on reinsert.
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_MEDIA_EJECT)
            addAction(Intent.ACTION_MEDIA_UNMOUNTED)
            addAction(Intent.ACTION_MEDIA_BAD_REMOVAL)
            addAction(Intent.ACTION_MEDIA_REMOVED)
            addAction(Intent.ACTION_MEDIA_MOUNTED)
            addDataScheme("file")
        }
        // API 34+ requires an explicit export flag for context-registered
        // receivers. These are system broadcasts, so EXPORTED is correct.
        ContextCompat.registerReceiver(
            this,
            ejectBus.receiver,
            filter,
            ContextCompat.RECEIVER_EXPORTED,
        )

        // Fallback / complement. Also fires REMOUNTS — without that branch,
        // a reinserted drive stayed in the bus's ejected set forever.
        val storageManager = getSystemService(StorageManager::class.java) ?: return
        storageManager.registerStorageVolumeCallback(mainExecutor,
            object : StorageManager.StorageVolumeCallback() {
                override fun onStateChanged(volume: StorageVolume) {
                    val uuid = volume.uuid ?: return
                    when (volume.state) {
                        Environment.MEDIA_MOUNTED,
                        Environment.MEDIA_MOUNTED_READ_ONLY ->
                            ejectBus.reportMounted(uuid)
                        Environment.MEDIA_UNMOUNTED,
                        Environment.MEDIA_REMOVED,
                        Environment.MEDIA_BAD_REMOVAL,
                        Environment.MEDIA_UNMOUNTABLE ->
                            ejectBus.reportUnmounted(uuid)
                        else -> Unit
                    }
                }
            },
        )
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
