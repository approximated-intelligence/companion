package de.perigon.companion.backup.mirror.domain

import android.net.Uri
import android.util.Log
import androidx.core.net.toUri
import de.perigon.companion.backup.data.BackupScannedFile
import de.perigon.companion.backup.data.BackupSourceScanner
import de.perigon.companion.backup.data.HashFailure
import de.perigon.companion.backup.data.HashKey
import de.perigon.companion.backup.mirror.data.MirrorRepository
import javax.inject.Inject
import kotlin.coroutines.cancellation.CancellationException

private const val TAG = "MirrorEngine"
private const val MISMATCH_LOG_CAP = 20

/**
 * The mirror's decision logic: what to copy, what to verify, how to
 * classify each result. Depends on [BackupSourceScanner] for the source side
 * (shared with the backup pipeline, including the EXIF dance and hashing)
 * and on [MirrorRepository] for the drive side.
 *
 * No notifications, no WorkManager. Those live in `worker/`. Pure orchestration
 * that can be tested with a fake repository and a fake scanner.
 */
class MirrorEngine @Inject constructor(
    private val scanner: BackupSourceScanner,
    private val repo: MirrorRepository,
) {

    suspend fun sync(
        driveRoot: Uri,
        onProgress: suspend (phase: String, current: String, done: Int, total: Int) -> Unit =
            { _, _, _, _ -> },
    ): SyncOutcome {
        onProgress("prepare", "", 0, 0)
        val mirrorDocId = repo.ensureMirrorFolder(driveRoot)
        repo.sweepTmp(driveRoot, mirrorDocId)

        onProgress("scan", "", 0, 0)
        val scan = scanner.scan(onProgress = { hashed, total, key ->
            onProgress("scan", key.path, hashed, total)
        })

        if (scan.missingDcimGrant) {
            Log.w(TAG, "sync: DCIM grant stale — aborting")
            return SyncOutcome(
                sourceFiles = 0, copied = 0, unchanged = 0,
                enabledFolders = scan.enabledFolders,
                missingDcimGrant = true,
                failures = listOf(HashFailure(
                    key = HashKey("(DCIM)", 0L, 0L),
                    cause = SecurityException(
                        "DCIM grant lost — re-grant in Settings (was " +
                            "${scan.dcimGrantUri ?: "missing"})"),
                )),
            )
        }
        if (scan.enabledFolders == 0) {
            return SyncOutcome(
                sourceFiles = 0, copied = 0, unchanged = 0, enabledFolders = 0,
                failures = listOf(HashFailure(
                    key = HashKey("(sources)", 0L, 0L),
                    cause = IllegalStateException("no enabled source folders"))),
            )
        }

        onProgress("diff", "", 0, 0)
        val driveFiles = repo.walkMirror(driveRoot, mirrorDocId)
            .filter { !isSystemFile(it.path) }
            .associateBy { it.path }
        val shaSums = repo.readAllSums(driveRoot, mirrorDocId)

        val toCopy = mutableListOf<BackupScannedFile>()
        val toAppend = mutableListOf<Pair<String, String>>()
        for (sf in scan.files) {
            val drive = driveFiles[sf.path]
            when {
                drive == null || drive.size != sf.size -> toCopy += sf
                shaSums[sf.path] == null -> toAppend += sf.path to sf.sha256
            }
        }

        val copyFailures = mutableListOf<HashFailure>()
        var copied = 0
        for ((i, sf) in toCopy.withIndex()) {
            onProgress("copy", sf.path, i, toCopy.size)
            try {
                repo.copyFromSource(driveRoot, sf.uri.toUri(), sf.path)
                toAppend += sf.path to sf.sha256
                copied++
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "copy failed: ${sf.path}", e)
                copyFailures += HashFailure(HashKey(sf.path, sf.mtime, sf.size), e)
            }
        }

        var sumsFile: String? = null
        if (toAppend.isNotEmpty()) {
            sumsFile = repo.writeSums(driveRoot, mirrorDocId, toAppend)
        }

        return SyncOutcome(
            sourceFiles = scan.files.size,
            copied = copied,
            unchanged = scan.files.size - toCopy.size,
            failures = scan.failures + copyFailures,
            unreadable = scan.unreadable,
            enabledFolders = scan.enabledFolders,
            sumsFile = sumsFile,
        )
    }

    suspend fun verify(
        driveRoot: Uri,
        onProgress: suspend (phase: String, current: String, done: Int, total: Int) -> Unit =
            { _, _, _, _ -> },
    ): VerifyOutcome {
        onProgress("prepare", "", 0, 0)
        val mirrorDocId = repo.findMirrorFolder(driveRoot)
            ?: return VerifyOutcome(0, 0, 0, 0, 0, mirrorFolderMissing = true)

        onProgress("scan", "", 0, 0)
        val scan = scanner.scan()

        if (scan.missingDcimGrant) {
            return VerifyOutcome(
                okFromSource = 0, okFromShaSum = 0, mismatch = 0, unverifiable = 0, notMirrored = 0,
                sourceFiles = 0, sourceUnreadable = 0, enabledFolders = scan.enabledFolders,
                missingDcimGrant = true,
            )
        }

        onProgress("diff", "", 0, 0)
        val sourceByPath = scan.files.associateBy { it.path }
        val driveByPath = repo.walkMirror(driveRoot, mirrorDocId)
            .filter { !isSystemFile(it.path) }
            .associateBy { it.path }
        val shaSums = repo.readAllSums(driveRoot, mirrorDocId)

        Log.i(TAG, "verify: source=${scan.files.size} (${scan.unreadable} unreadable), " +
            "drive=${driveByPath.size}, sumsEntries=${shaSums.size}")

        var okSource = 0
        var okSum = 0
        var mismatch = 0
        var unverifiable = 0
        val mismatchPaths = mutableListOf<String>()
        val unverifiableReasons = mutableMapOf<String, Int>()
        val toAppend = mutableListOf<Pair<String, String>>()

        val entries = driveByPath.entries.toList()
        for ((i, entry) in entries.withIndex()) {
            val (path, drv) = entry
            onProgress("verify", path, i, entries.size)

            val src = sourceByPath[path]
            val expected = src?.sha256 ?: shaSums[path]
            if (expected == null) {
                unverifiable++
                bump(unverifiableReasons, "no expected hash")
                Log.w(TAG, "UNVERIFIABLE (no ground truth) $path")
                continue
            }

            val read = repo.readDriveFile(drv)
            when {
                read.danceHash == expected -> {
                    if (src != null) okSource++ else okSum++
                    if (shaSums[path] == null && src != null) toAppend += path to expected
                }
                read.rawHash == expected -> {
                    if (src != null) okSource++ else okSum++
                    if (shaSums[path] == null) toAppend += path to expected
                }
                read.danceHash != null -> {
                    mismatch++
                    Log.w(TAG, "MISMATCH $path: expected=${expected.take(12)} " +
                        "dance=${read.danceHash.take(12)}")
                    if (mismatchPaths.size < MISMATCH_LOG_CAP) mismatchPaths += path
                }
                read.rawHash != null -> {
                    unverifiable++
                    bump(unverifiableReasons, "raw mismatch (may need MediaStore indexing)")
                    Log.w(TAG, "UNVERIFIABLE (raw mismatch) $path: " +
                        "expected=${expected.take(12)} raw=${read.rawHash.take(12)}")
                }
                else -> {
                    unverifiable++
                    bump(unverifiableReasons, "unreadable")
                    Log.w(TAG, "UNVERIFIABLE (unreadable) $path: " +
                        "dance=${read.danceError} raw=${read.rawError}")
                }
            }
        }

        val notMirrored = sourceByPath.keys.count { it !in driveByPath }

        if (toAppend.isNotEmpty()) {
            repo.writeSums(driveRoot, mirrorDocId, toAppend)
        }

        return VerifyOutcome(
            okFromSource = okSource,
            okFromShaSum = okSum,
            mismatch = mismatch,
            unverifiable = unverifiable,
            notMirrored = notMirrored,
            sourceFiles = scan.files.size,
            sourceUnreadable = scan.unreadable,
            enabledFolders = scan.enabledFolders,
            mismatchPaths = mismatchPaths,
            unverifiableReasons = unverifiableReasons,
        )
    }

    private fun bump(reasons: MutableMap<String, Int>, key: String) {
        reasons[key] = (reasons[key] ?: 0) + 1
    }
}
