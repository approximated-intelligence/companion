package de.perigon.companion.archive.mirror.worker

import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequest
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.Operation
import androidx.work.WorkManager
import androidx.work.workDataOf

object MirrorRequests {
    fun sync(): OneTimeWorkRequest =
        OneTimeWorkRequestBuilder<MirrorWorker>()
            .setInputData(workDataOf(MirrorWorker.KEY_MODE to MirrorWorker.MODE_SYNC))
            .addTag(MirrorWorker.WORK_SYNC)
            .build()

    fun verify(): OneTimeWorkRequest =
        OneTimeWorkRequestBuilder<MirrorWorker>()
            .setInputData(workDataOf(MirrorWorker.KEY_MODE to MirrorWorker.MODE_VERIFY))
            .addTag(MirrorWorker.WORK_VERIFY)
            .build()
}

fun WorkManager.enqueueMirrorSync(): Operation =
    enqueueUniqueWork(MirrorWorker.WORK_SYNC, ExistingWorkPolicy.KEEP, MirrorRequests.sync())

fun WorkManager.enqueueMirrorVerify(): Operation =
    enqueueUniqueWork(MirrorWorker.WORK_VERIFY, ExistingWorkPolicy.KEEP, MirrorRequests.verify())
