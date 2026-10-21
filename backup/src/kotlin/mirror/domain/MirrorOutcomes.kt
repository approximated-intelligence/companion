package de.perigon.companion.backup.mirror.domain

import de.perigon.companion.backup.data.HashFailure

/**
 * What a sync invocation did. Fields mirror the vocabulary the worker uses
 * to compose user-facing messages: "sourceFiles" (found by scan), "copied"
 * (wrote this run), "unchanged" (already matched), plus an explicit
 * failure list and the DCIM/source-config edge cases that make an empty
 * run legible.
 */
data class SyncOutcome(
    val sourceFiles: Int,
    val copied: Int,
    val unchanged: Int,
    val failures: List<HashFailure>,
    val unreadable: Int = 0,
    val enabledFolders: Int = 0,
    val missingDcimGrant: Boolean = false,
    /** Name of the SUMS file written this run, or null if nothing was written. */
    val sumsFile: String? = null,
)

/**
 * What a check invocation did. Splitting "ok from source" vs "ok from SUMS"
 * matters for the notification — the former proves bytes against a live
 * source, the latter against a previously-recorded claim.
 */
data class VerifyOutcome(
    val okFromSource: Int,
    val okFromShaSum: Int,
    val mismatch: Int,
    val unverifiable: Int,
    val notMirrored: Int,
    val sourceFiles: Int = 0,
    val sourceUnreadable: Int = 0,
    val enabledFolders: Int = 0,
    val missingDcimGrant: Boolean = false,
    val mirrorFolderMissing: Boolean = false,
    /** First few offending paths, for the notification detail and logcat. */
    val mismatchPaths: List<String> = emptyList(),
    /** reason → count. Lets the notification explain "why unverifiable". */
    val unverifiableReasons: Map<String, Int> = emptyMap(),
)

/**
 * Result of trying to read one drive file for verification. The dance
 * (openInputStream → setRequireOriginal) is authoritative; the raw read is
 * a fallback for files MediaProvider hasn't indexed yet. Both may be null
 * on failure; [danceError]/[rawError] carry the reason.
 */
data class DriveReadResult(
    val danceHash: String?,
    val rawHash: String?,
    val danceError: String?,
    val rawError: String?,
)
