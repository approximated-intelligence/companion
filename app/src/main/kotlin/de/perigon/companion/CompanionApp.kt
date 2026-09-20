package de.perigon.companion

import android.app.Application
import android.content.Context
import android.content.Intent
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.*
import de.perigon.companion.backup.data.BackupSchedulePrefs
import de.perigon.companion.backup.worker.BackupTriggerWorker
import de.perigon.companion.core.di.ApplicationScope
import de.perigon.companion.core.prefs.AppPrefs
import de.perigon.companion.core.ui.NotificationChannels
import de.perigon.companion.track.data.TrackConfigPrefs
import de.perigon.companion.track.service.AutoScheduler
import de.perigon.companion.track.service.BackgroundService
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.io.File
import java.util.concurrent.TimeUnit
import javax.inject.Inject

@HiltAndroidApp
class CompanionApp : Application(), Configuration.Provider {

    @Inject lateinit var workerFactory: HiltWorkerFactory
    @Inject lateinit var schedulePrefs: BackupSchedulePrefs
    @Inject lateinit var trackConfigPrefs: TrackConfigPrefs
    @Inject lateinit var appPrefs: AppPrefs

    // One application scope. Previously a second private
    // CoroutineScope(SupervisorJob() + Dispatchers.IO) shadowed the DI one.
    @Inject @ApplicationScope lateinit var appScope: CoroutineScope

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()

    override fun onCreate() {
        super.onCreate()
        NotificationChannels.ensureAll(this)
        applyBackupSchedule()
        resumeBackground()
        migrateBackgroundPrefs()
    }

    private fun applyBackupSchedule() {
        val wm = WorkManager.getInstance(this)

        // Evict the pre-funnel periodic that shared the execution slot
        // "backup". Now that execution lives under BackupWorker.WORK_NAME
        // ("backup_run") and the schedule under SCHEDULE_NAME, nothing else
        // uses "backup" — so this is a one-time cleanup and a harmless no-op
        // forever after.
        wm.cancelUniqueWork("backup")

        if (!schedulePrefs.autoEnabled()) {
            wm.cancelUniqueWork(BackupTriggerWorker.SCHEDULE_NAME)
            return
        }

        val req = PeriodicWorkRequestBuilder<BackupTriggerWorker>(
            schedulePrefs.intervalHours().toLong(), TimeUnit.HOURS
        )
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.UNMETERED)
                    .build()
            )
            .build()

        wm.enqueueUniquePeriodicWork(
            BackupTriggerWorker.SCHEDULE_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            req,
        )
    }

    /**
     * Resume track recording if auto-schedule is enabled and we're in the active window.
     * Covers: reinstall, force-stop, process death, boot (belt-and-suspenders with AutoTrackReceiver).
     * Also reschedules alarms that may have been lost.
     */
    private fun resumeBackground() {
        appScope.launch {
            val config = trackConfigPrefs.get()
            val backgroundGps = appPrefs.isBackgroundGpsEnabled()
            if (!config.autoScheduleEnabled || !backgroundGps) return@launch

            val scheduler = AutoScheduler(this@CompanionApp, config, backgroundGps)
            scheduler.apply()

            if (scheduler.isInActiveWindow() && !BackgroundService.status.value.isRecording) {
                val intent = Intent(this@CompanionApp, BackgroundService::class.java).apply {
                    action = BackgroundService.ACTION_START
                }
                startForegroundService(intent)
            }
        }
    }

    private fun migrateBackgroundPrefs() {
        val prefsFile = File(applicationInfo.dataDir, "shared_prefs/track_recorder.xml")
        if (!prefsFile.exists()) return

        val old = getSharedPreferences("track_recorder", Context.MODE_PRIVATE)

        appScope.launch {
            trackConfigPrefs.update { current ->
                current.copy(
                    intervalMs = old.getLong("interval_ms", current.intervalMs),
                    fixTimeoutMs = old.getLong("fix_timeout_ms", current.fixTimeoutMs),
                    maxInaccuracyM = old.getFloat("max_inaccuracy_m", current.maxInaccuracyM),
                    autoScheduleEnabled = old.getBoolean("auto_schedule_enabled", current.autoScheduleEnabled),
                    autoStartSeconds = old.getLong("auto_start_seconds", current.autoStartSeconds),
                    autoStopSeconds = old.getLong("auto_stop_seconds", current.autoStopSeconds),
                )
            }

            old.edit().clear().apply()
            prefsFile.delete()
        }
    }
}
