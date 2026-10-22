package de.perigon.companion.archive.backup.worker

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.await
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

/**
 * Periodic, WLAN-gated trigger for scheduled backups. Owns NO backup logic —
 * it funnels into the single execution slot [BackupWorker.WORK_NAME] via
 * [enqueueBackup]. Consequences of the funnel:
 *  - a scheduled tick and a manual "Back up now" can never run at once,
 *  - cancelling a running backup (cancelUniqueWork(WORK_NAME)) never touches
 *    this schedule,
 *  - toggling auto-backup off (cancelUniqueWork(SCHEDULE_NAME)) never touches
 *    a running backup.
 *
 * Both the trigger AND the run it enqueues ([BackupRequests.scheduled]) are
 * UNMETERED — a scheduled backup never continues over mobile data. Manual
 * runs ([BackupRequests.manual]) are unconstrained.
 */
@HiltWorker
class BackupTriggerWorker @AssistedInject constructor(
    @Assisted ctx: Context,
    @Assisted params: WorkerParameters,
) : CoroutineWorker(ctx, params) {

    override suspend fun doWork(): Result {
        WorkManager.getInstance(applicationContext)
            .enqueueBackup(BackupRequests.scheduled())
            .await()
        return Result.success()
    }

    companion object {
        const val SCHEDULE_NAME = "backup_schedule"
    }
}
