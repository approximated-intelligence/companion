package de.perigon.companion

import android.app.Application
import android.content.Context
import android.content.Intent
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.*
import de.perigon.companion.backup.data.BackupSchedulePrefs
import de.perigon.companion.backup.worker.BackupWorker
import de.perigon.companion.core.prefs.AppPrefs
import de.perigon.companion.core.ui.NotificationChannels
import de.perigon.companion.track.data.TrackConfigPrefs
import de.perigon.companion.track.service.AutoScheduler
import de.perigon.companion.track.service.BackgroundService
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
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

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

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
        if (!schedulePrefs.autoEnabled()) {
            WorkManager.getInstance(this)
                .cancelUniqueWork(BackupWorker.WORK_NAME)
            return
        }

        val req = PeriodicWorkRequestBuilder<BackupWorker>(
            schedulePrefs.intervalHours().toLong(), TimeUnit.HOURS
        )
            .addTag(BackupWorker.WORK_NAME)
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.UNMETERED)
                    .build()
            )
            .build()

        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            BackupWorker.WORK_NAME,
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
