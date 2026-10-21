package de.perigon.companion.backup.mirror.domain

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

/** SUMS entries are 6-digit zero-padded: 000001, 000002, … */
val SUMS_NAME_REGEX = Regex("^\\d{6}$")

/** Standard `sha256sum` line: `<64 hex>  <path>`. */
val SHA256_LINE = Regex("^([0-9a-fA-F]{64})  (.+)$")

/**
 * True for files this pipeline writes itself (checksums, temp files). These
 * must be excluded from the "what is on the drive" diff, and from any user-
 * visible count.
 */
fun isSystemFile(path: String): Boolean {
    if (path.endsWith(TMP_SUFFIX)) return true
    if (path == SUMS_FOLDER || path.startsWith("$SUMS_FOLDER/")) return true
    if (path == LEGACY_SUMS_FILE) return true
    if (path.startsWith("$LEGACY_SUMS_FILE ")) return true
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
