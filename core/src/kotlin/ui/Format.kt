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

private val CARDINALS_8 = arrayOf("N", "NE", "E", "SE", "S", "SW", "W", "NW")

/** Compass bearing in degrees → 8-direction cardinal ("N", "NE", ..., "NW"). */
fun cardinal(deg: Float): String {
    val n = ((deg % 360f) + 360f) % 360f
    return CARDINALS_8[((n + 22.5f) / 45f).toInt() % 8]
}

/**
 * Short "how long ago" label — "just now", "3s ago", "5m ago", "2h ago",
 * "3d ago". Cutoffs chosen so each unit is the natural reading until it
 * gets unwieldy: seconds until 60, minutes until 60, hours until 24, days
 * beyond. Sub-second deltas collapse to "just now" so a fresh fix does
 * not flicker through "0s ago".
 */
fun formatAgeShort(ageMs: Long): String = when {
    ageMs < 1_000L      -> "just now"
    ageMs < 60_000L     -> "${ageMs / 1_000L}s ago"
    ageMs < 3_600_000L  -> "${ageMs / 60_000L}m ago"
    ageMs < 86_400_000L -> "${ageMs / 3_600_000L}h ago"
    else                -> "${ageMs / 86_400_000L}d ago"
}
