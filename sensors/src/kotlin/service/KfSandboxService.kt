package de.perigon.companion.sensors.service

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import de.perigon.companion.core.feature.NotificationIds
import de.perigon.companion.core.feature.RequestCodes
import de.perigon.companion.core.ui.CoreIcons
import de.perigon.companion.core.ui.navigatePendingIntent
import de.perigon.companion.sensors.SensorsChannels
import de.perigon.companion.sensors.SensorsTargets
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Foreground service that keeps the KF diagnostic sandbox alive with the
 * screen off. Not a recorder: it does not touch the database, does not own
 * any listeners, and does not process measurements. Its only job is to hold
 * the process in the FOREGROUND_SERVICE_LOCATION state so the ViewModel's
 * location and sensor listeners keep firing, plus an optional wake-lock if
 * the user has toggled it on.
 *
 * Why a foreground service and not just a wake-lock: a bare PARTIAL_WAKE_LOCK
 * keeps the CPU awake but does not lift the background execution limits. Once
 * the Activity is no longer foreground, location and non-wake-up sensor
 * delivery are throttled or dropped regardless of CPU state. FGS with type
 * "location" is the Android-sanctioned way to keep a location-consuming
 * process in the foreground state; the wake-lock is a secondary reinforcement
 * for CPU during screen-off.
 *
 * The ongoing notification is mandatory — no FGS without one. It is silent
 * (channel importance LOW) because the sandbox is diagnostic, not a user-
 * facing feature.
 *
 * Content intent deep-links to the KF tab: SensorsTargets.KF_SANDBOX routes
 * through SensorsFeature.routeFor() to SensorsRoute.Recording(initialTab="Kf"),
 * so tapping the notification lands the user directly on the KF sandbox,
 * not the default (Track) tab. If the Sensors screen is already on the back
 * stack on a different tab, the launchSingleTop in LaunchRequest.deliver()
 * re-delivers the args to the existing entry, causing the tab selector to
 * re-read initialTab from the route.
 *
 * Lifecycle: START_NOT_STICKY. If the system kills the process, the KF tab's
 * ViewModel is also gone, and on the tab's next composition it will call
 * start() again and restart the service. Sticky restart would leave a
 * background service without the foreground contract, which is worse.
 *
 * Wakelock pattern mirrors [BackgroundService]'s session wake-lock: a
 * renewed lease, not one long hold. The renew job keeps the lock held for
 * the whole session without lapses; if teardown is missed, the lock self-
 * expires within one lease.
 */
class KfSandboxService : Service() {

    companion object {
        const val ACTION_START         = "de.perigon.companion.sensors.KF_SANDBOX_START"
        const val ACTION_STOP          = "de.perigon.companion.sensors.KF_SANDBOX_STOP"
        const val ACTION_SET_WAKELOCK  = "de.perigon.companion.sensors.KF_SANDBOX_SET_WAKELOCK"
        const val EXTRA_WAKELOCK       = "wakelock"

        private const val TAG                  = "KfSandboxService"
        private const val CHANNEL_ID           = SensorsChannels.KF_SANDBOX
        private const val NOTIFICATION_ID      = NotificationIds.TRACK + 6
        private const val CONTENT_REQUEST_CODE = RequestCodes.TRACK + 6
        private const val STOP_REQUEST_CODE    = RequestCodes.TRACK + 7

        private const val WAKELOCK_LEASE_MS = 60 * 60 * 1000L
        private const val WAKELOCK_RENEW_MS = WAKELOCK_LEASE_MS / 2
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var wakeLock: PowerManager.WakeLock? = null
    private var wakeLockJob: Job? = null
    private var wakeLockHeld = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startForeground(
                NOTIFICATION_ID,
                buildNotification(),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION,
            )
            ACTION_STOP -> stopSandbox()
            ACTION_SET_WAKELOCK -> {
                val enabled = intent.getBooleanExtra(EXTRA_WAKELOCK, false)
                if (enabled) acquireWakeLock() else releaseWakeLock()
            }
        }
        return START_NOT_STICKY
    }

    private fun stopSandbox() {
        releaseWakeLock()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun buildNotification(): Notification {
        // Deep-link target: SensorsTargets.KF_SANDBOX resolves through
        // SensorsFeature.routeFor() to SensorsRoute.Recording(initialTab="Kf"),
        // landing the user directly on the KF sandbox instead of the default
        // (Track) tab. When the Sensors screen is already on the back stack
        // on another tab, LaunchRequest.deliver() uses launchSingleTop,
        // which re-delivers the args to the existing entry — the tab
        // selector in SensorsScreen re-reads initialTab from the route.
        val contentPi = navigatePendingIntent(this, SensorsTargets.KF_SANDBOX, CONTENT_REQUEST_CODE)
        val stopPi = PendingIntent.getService(this, STOP_REQUEST_CODE,
            Intent(this, KfSandboxService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(CoreIcons.status)
            .setContentTitle("KF Sandbox")
            .setContentText("Diagnostic filter running")
            .setContentIntent(contentPi)
            .setOngoing(true)
            .addAction(0, "Stop", stopPi)
            .build()
    }

    private fun ensureWakeLock(): PowerManager.WakeLock {
        if (wakeLock == null) {
            wakeLock = getSystemService(PowerManager::class.java)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "PerigonCompanion:KfSandbox")
                // Non-counted: the renew job extends one hold rather than
                // stacking counts.
                .apply { setReferenceCounted(false) }
        }
        return wakeLock!!
    }

    private fun acquireWakeLock() {
        if (wakeLockHeld) return
        wakeLockHeld = true
        val lock = ensureWakeLock()
        wakeLockJob = scope.launch {
            while (isActive) {
                lock.acquire(WAKELOCK_LEASE_MS)
                delay(WAKELOCK_RENEW_MS)
            }
        }
    }

    private fun releaseWakeLock() {
        if (!wakeLockHeld) return
        wakeLockJob?.cancel()
        wakeLockJob = null
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLockHeld = false
    }

    override fun onDestroy() {
        releaseWakeLock()
        scope.cancel()
        super.onDestroy()
    }
}
