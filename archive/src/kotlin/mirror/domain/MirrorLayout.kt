package de.perigon.companion.archive.mirror.domain

import java.net.URLConnection

/**
 * Folder and file naming conventions for the mirror, plus pure path helpers.
 * Everything here is stateless and side-effect-free — usable from repository,
 * engine, worker, or ui without pulling in Android types.
 */

/** Where each drive's mirror content lives, relative to the granted tree root. */
const val MIRROR_FOLDER = "Mirror"

/** Per-run checksum logs under <drive>/Mirror/SUMS/. */
const val SUMS_FOLDER = "SUMS"

/** Pre-SUMS-dir log file, kept only for backward compatibility reads. */
const val LEGACY_SUMS_FILE = "SHA256SUM"

/** Transient suffix for atomic-write temp files. */
const val TMP_SUFFIX = ".tmp"

/** Standard `sha256sum` line: `<64 hex>  <path>`. */
val SHA256_LINE = Regex("^([0-9a-fA-F]{64})  (.+)$")

/**
 * True for files this pipeline writes itself (checksums, temp files), or
 * device/OS bookkeeping markers (`.nomedia`, `.DS_Store`, `.thumbnails`,
 * `.trashed`), or anything inside a dot-prefixed folder.
 *
 * Applied on BOTH sides of every mirror diff (source and drive) so the two
 * use one definition of "user data". In particular:
 *  - the app's own checksum/atomic-write temp files never appear as "not
 *    mirrored" or "mismatched";
 *  - `.nomedia` — which Android accepts as a file in a media folder OR as a
 *    directory name on some OEM builds — never lands in the "unverifiable"
 *    bucket, because neither the source scan nor the drive walk surfaced it.
 *
 * Both checks below accept the two ways SAF mutates a display name:
 * provider-appended extension (`.txt` for text/plain) and collision suffix
 * (` (1)`, ` (2)`, …).
 */
fun isSystemFile(path: String): Boolean {
    if (path.endsWith(TMP_SUFFIX)) return true
    if (path == SUMS_FOLDER || path.startsWith("$SUMS_FOLDER/")) return true
    if (path == LEGACY_SUMS_FILE) return true
    if (path.startsWith("$LEGACY_SUMS_FILE ")) return true          // "SHA256SUM (1)"
    if (path.startsWith("$LEGACY_SUMS_FILE.")) return true          // "SHA256SUM.txt", "SHA256SUM (1).txt"
    // Dot-prefixed component anywhere: .nomedia (file or dir), .DS_Store,
    // .thumbnails, .trashed, and everything under a dot-prefixed folder.
    if (path.split('/').any { it.startsWith(".") }) return true
    return false
}

/** Parent directories of [path], in order; empty for a root-level file. */
fun parentDirs(path: String): List<String> {
    val parent = path.substringBeforeLast('/', "")
    return if (parent.isEmpty()) emptyList()
    else parent.split('/').filter { it.isNotEmpty() }
}

fun fileName(path: String): String = path.substringAfterLast('/')

fun guessMime(path: String): String =
    URLConnection.guessContentTypeFromName(path) ?: "application/octet-stream"
