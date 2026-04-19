package de.perigon.companion.track.service

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import de.perigon.companion.track.domain.FixStrategy

/**
 * Schedules the next fix cycle using the appropriate Android mechanism.
 *
 * Continuous: not used (service uses requestLocationUpdates directly).
 * Passive: not used (service uses requestLocationUpdates with PASSIVE_PROVIDER).
 * DelayedSingle: Handler.postDelayed.
 * AlarmSingle: AlarmManager.setExactAndAllowWhileIdle.
 */
class FixScheduler(
    private val context: Context,
    private val onFixDue: () -> Unit,
) {
    private val handler = Handler(Looper.getMainLooper())
    private val alarmManager = context.getSystemService(AlarmManager::class.java)

    private var handlerToken: Runnable? = null

    fun schedule(strategy: FixStrategy) {
        cancel()
        when (strategy) {
            is FixStrategy.Continuous -> {
                // Not handled here - service uses requestLocationUpdates
            }
            is FixStrategy.Passive -> {
                // Not handled here - service uses requestLocationUpdates with PASSIVE_PROVIDER
            }
            is FixStrategy.DelayedSingle -> {
                val runnable = Runnable { onFixDue() }
                handlerToken = runnable
                handler.postDelayed(runnable, strategy.delayMs)
            }
            is FixStrategy.AlarmSingle -> {
                val intent = Intent(context, FixAlarmReceiver::class.java)
                val pi = PendingIntent.getBroadcast(
                    context, REQUEST_CODE, intent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                )
                alarmManager.setExactAndAllowWhileIdle(
                    AlarmManager.ELAPSED_REALTIME_WAKEUP,
                    SystemClock.elapsedRealtime() + strategy.delayMs,
                    pi,
                )
            }
        }
    }

    fun cancel() {
        handlerToken?.let { handler.removeCallbacks(it) }
        handlerToken = null

        val intent = Intent(context, FixAlarmReceiver::class.java)
        val pi = PendingIntent.getBroadcast(
            context, REQUEST_CODE, intent,
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE,
        )
        pi?.let { alarmManager.cancel(it) }
    }

    companion object {
        private const val REQUEST_CODE = 9001
    }
}

class FixAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val serviceIntent = Intent(context, BackgroundService::class.java).apply {
            action = BackgroundService.ACTION_FIX_DUE
        }
        context.startForegroundService(serviceIntent)
    }
}
