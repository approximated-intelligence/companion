package de.perigon.companion.core.ui

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Display formatting shared by every screen — one copy instead of per-screen
 * private formatSize/formatDate/formatTs/formatDuration/formatElapsed.
 * Pure apart from the device locale and time zone.
 */

/** Binary units: "512 B", "37 KB", "4.2 MB". */
fun formatBytes(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "${bytes / 1024} KB"
    else -> "%.1f MB".format(Locale.getDefault(), bytes / (1024.0 * 1024.0))
}

/** Epoch millis in local time, e.g. pattern "MMM d, yyyy HH:mm". */
fun formatDateTime(epochMs: Long, pattern: String): String =
    DateTimeFormatter.ofPattern(pattern, Locale.getDefault())
        .format(Instant.ofEpochMilli(epochMs).atZone(ZoneId.systemDefault()))

/** Media position/length: "3:07", or "1:02:03" past an hour. */
fun formatClock(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    val h = s / 3600
    val m = (s % 3600) / 60
    val sec = s % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, sec) else "%d:%02d".format(m, sec)
}

/** Compact span for stats: "1h02m", "3m05s", "12s". */
fun formatSpan(ms: Long): String {
    if (ms <= 0) return "0s"
    val s = ms / 1000
    val h = s / 3600
    val m = (s % 3600) / 60
    val sec = s % 60
    return when {
        h > 0 -> "%dh%02dm".format(h, m)
        m > 0 -> "%dm%02ds".format(m, sec)
        else -> "${sec}s"
    }
}
