package de.perigon.companion.backup.worker

import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequest
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.Operation
import androidx.work.PeriodicWorkRequest
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

/**
 * Every backup WorkRequest in one place. Execution always runs under the one
 * unique slot [BackupWorker.WORK_NAME] with KEEP, so two backups never
 * overlap; the periodic trigger lives under [BackupTriggerWorker.SCHEDULE_NAME].
 */
object BackupRequests {

    private val unmetered: Constraints =
        Constraints.Builder().setRequiredNetworkType(NetworkType.UNMETERED).build()

    /** User-initiated ("Back up now"): runs on any network. */
    fun manual(): OneTimeWorkRequest =
        OneTimeWorkRequestBuilder<BackupWorker>()
            .addTag(BackupWorker.WORK_NAME)
            .build()

    /**
     * Enqueued by the periodic trigger. UNMETERED for the run's whole
     * lifetime: WorkManager stops it if WLAN drops and resumes it on WLAN,
     * instead of finishing a large upload over mobile data.
     */
    fun scheduled(): OneTimeWorkRequest =
        OneTimeWorkRequestBuilder<BackupWorker>()
            .addTag(BackupWorker.WORK_NAME)
            .setConstraints(unmetered)
            .build()

    /** The periodic trigger. Its constraint only gates when the trigger fires. */
    fun trigger(intervalHours: Int): PeriodicWorkRequest =
        PeriodicWorkRequestBuilder<BackupTriggerWorker>(intervalHours.toLong(), TimeUnit.HOURS)
            .setConstraints(unmetered)
            .build()
}

/**
 * Funnel into the single execution slot. KEEP: if a backup is queued or
 * running, this is a no-op. Known edge: a scheduled run parked waiting for
 * WLAN also absorbs a manual press until WLAN returns.
 */
fun WorkManager.enqueueBackup(request: OneTimeWorkRequest): Operation =
    enqueueUniqueWork(BackupWorker.WORK_NAME, ExistingWorkPolicy.KEEP, request)

/**
 * Register or cancel the periodic trigger. Never touches a running backup.
 * [policy]: KEEP at app start (don't reset the timer on every launch),
 * UPDATE when the user changes the interval.
 */
fun WorkManager.syncBackupSchedule(
    enabled: Boolean,
    intervalHours: Int,
    policy: ExistingPeriodicWorkPolicy,
): Operation =
    if (enabled) {
        enqueueUniquePeriodicWork(
            BackupTriggerWorker.SCHEDULE_NAME,
            policy,
            BackupRequests.trigger(intervalHours),
        )
    } else {
        cancelUniqueWork(BackupTriggerWorker.SCHEDULE_NAME)
    }
