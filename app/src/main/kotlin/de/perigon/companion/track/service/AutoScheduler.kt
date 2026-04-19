package de.perigon.companion.track.service

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import de.perigon.companion.core.prefs.AppPrefs
import de.perigon.companion.track.data.TrackConfigEntity
import de.perigon.companion.track.data.TrackConfigPrefs
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import javax.inject.Inject

class AutoScheduler(
    private val context: Context,
    private val config: TrackConfigEntity,
    private val backgroundGps: Boolean = true,
) {

    private val alarmManager = context.getSystemService(AlarmManager::class.java)

    fun apply() {
        cancelAll()
        if (!backgroundGps || !config.autoScheduleEnabled) return

        scheduleAlarm(ACTION_AUTO_START, config.autoStartTime, REQUEST_START)
        scheduleAlarm(ACTION_AUTO_STOP, config.autoStopTime, REQUEST_STOP)
    }

    fun isInActiveWindow(): Boolean {
        val now = LocalTime.now()
        val start = config.autoStartTime
        val stop = config.autoStopTime

        return if (stop > start) {
            now in start..stop
        } else {
            now >= start || now <= stop
        }
    }

    private fun scheduleAlarm(action: String, time: LocalTime, requestCode: Int) {
        if (android.os.Build.VERSION.SDK_INT >= 31 && !alarmManager.canScheduleExactAlarms()) {
            Log.w("AutoScheduler", "Exact alarm permission not granted, skipping")
            return
        }

        val now = java.time.ZonedDateTime.now()
        var target = LocalDate.now()
            .atTime(time)
            .atZone(ZoneId.systemDefault())

        if (target.isBefore(now)) {
            target = target.plusDays(1)
        }

        val intent = Intent(context, AutoTrackReceiver::class.java).apply {
            this.action = action
        }
        val pi = PendingIntent.getBroadcast(
            context, requestCode, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        alarmManager.setExactAndAllowWhileIdle(
            AlarmManager.RTC_WAKEUP,
            target.toInstant().toEpochMilli(),
            pi,
        )
    }

    private fun cancelAll() {
        cancelAlarm(ACTION_AUTO_START, REQUEST_START)
        cancelAlarm(ACTION_AUTO_STOP, REQUEST_STOP)
    }

    private fun cancelAlarm(action: String, requestCode: Int) {
        val intent = Intent(context, AutoTrackReceiver::class.java).apply {
            this.action = action
        }
        val pi = PendingIntent.getBroadcast(
            context, requestCode, intent,
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE,
        )
        pi?.let { alarmManager.cancel(it) }
    }

    companion object {
        const val ACTION_AUTO_START = "de.perigon.companion.track.AUTO_START"
        const val ACTION_AUTO_STOP = "de.perigon.companion.track.AUTO_STOP"
        private const val REQUEST_START = 9010
        private const val REQUEST_STOP = 9011
    }
}

@AndroidEntryPoint
class AutoTrackReceiver : BroadcastReceiver() {

    @Inject lateinit var configPrefs: TrackConfigPrefs
    @Inject lateinit var appPrefs: AppPrefs

    override fun onReceive(context: Context, intent: Intent?) {
        val pending = goAsync()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        scope.launch {
            try {
                when (intent?.action) {
                    Intent.ACTION_BOOT_COMPLETED,
                    Intent.ACTION_MY_PACKAGE_REPLACED -> onBoot(context)
                    ACTION_AUTO_START -> {
                        startService(context)
                        reschedule(context)
                    }
                    ACTION_AUTO_STOP -> {
                        stopService(context)
                        reschedule(context)
                    }
                }
            } finally {
                pending.finish()
            }
        }
    }

    private suspend fun onBoot(context: Context) {
        val config = configPrefs.get()
        val backgroundGps = appPrefs.isBackgroundGpsEnabled()
        val scheduler = AutoScheduler(context, config, backgroundGps)
        scheduler.apply()

        if (config.autoScheduleEnabled && scheduler.isInActiveWindow() && backgroundGps) {
            startService(context)
        }
    }

    private suspend fun reschedule(context: Context) {
        val config = configPrefs.get()
        val backgroundGps = appPrefs.isBackgroundGpsEnabled()
        AutoScheduler(context, config, backgroundGps).apply()
    }

    private fun startService(context: Context) {
        context.startForegroundService(
            Intent(context, BackgroundService::class.java).apply {
                action = BackgroundService.ACTION_START
            }
        )
    }

    private fun stopService(context: Context) {
        context.startService(
            Intent(context, BackgroundService::class.java).apply {
                action = BackgroundService.ACTION_STOP
            }
        )
    }

    companion object {
        const val ACTION_AUTO_START = AutoScheduler.ACTION_AUTO_START
        const val ACTION_AUTO_STOP = AutoScheduler.ACTION_AUTO_STOP
    }
}
