package de.perigon.companion.backup.worker

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

/**
 * Periodic, WLAN-gated trigger for scheduled backups. Owns NO backup logic —
 * it funnels into the single on-demand execution slot [BackupWorker.WORK_NAME]
 * with KEEP. Consequences of the funnel:
 *  - a scheduled tick and a manual "Back up now" can never run at once (they
 *    share one unique name; WorkManager serialises it),
 *  - cancelling a running backup (cancelUniqueWork(WORK_NAME)) never touches
 *    this schedule,
 *  - toggling auto-backup off (cancelUniqueWork(SCHEDULE_NAME)) never touches a
 *    running manual backup.
 *
 * The UNMETERED constraint lives on THIS periodic request (registered under
 * [SCHEDULE_NAME]); the execution it enqueues is intentionally unconstrained,
 * so a manual backup still runs off-WLAN.
 */
@HiltWorker
class BackupTriggerWorker @AssistedInject constructor(
    @Assisted ctx: Context,
    @Assisted params: WorkerParameters,
) : CoroutineWorker(ctx, params) {

    override suspend fun doWork(): Result {
        val req = OneTimeWorkRequestBuilder<BackupWorker>()
            .addTag(BackupWorker.WORK_NAME)
            .build()
        WorkManager.getInstance(applicationContext).enqueueUniqueWork(
            BackupWorker.WORK_NAME,
            ExistingWorkPolicy.KEEP,
            req,
        )
        return Result.success()
    }

    companion object {
        const val SCHEDULE_NAME = "backup_schedule"
    }
}
