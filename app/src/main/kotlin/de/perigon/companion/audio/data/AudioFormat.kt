package de.perigon.companion.audio.data

import android.media.MediaRecorder

/**
 * Supported audio recording formats. Each maps to an Android MediaRecorder
 * output-format + encoder pair that produces a file playable by the system
 * audio player.
 *
 * AMR-WB is sample-rate-fixed (16 kHz) — the UI hides sample-rate and
 * bitrate chips when AMR-WB is selected.
 */
enum class AudioFormat(
    val displayName: String,
    val extension: String,
    val mimeType: String,
    val outputFormat: Int,
    val encoder: Int,
    val supportsPause: Boolean,
    val fixedSampleRateHz: Int? = null,
    val fixedBitrateBps: Int? = null,
) {
    M4A_AAC(
        displayName  = "M4A / AAC",
        extension    = "m4a",
        mimeType     = "audio/mp4",
        outputFormat = MediaRecorder.OutputFormat.MPEG_4,
        encoder      = MediaRecorder.AudioEncoder.AAC,
        supportsPause = true,
    ),
    OGG_OPUS(
        displayName  = "OGG / Opus",
        extension    = "ogg",
        mimeType     = "audio/ogg",
        outputFormat = MediaRecorder.OutputFormat.OGG,
        encoder      = MediaRecorder.AudioEncoder.OPUS,
        supportsPause = true,
    ),
    AMR_WB(
        displayName  = "AMR-WB",
        extension    = "amr",
        mimeType     = "audio/amr-wb",
        outputFormat = MediaRecorder.OutputFormat.AMR_WB,
        encoder      = MediaRecorder.AudioEncoder.AMR_WB,
        supportsPause = false,
        fixedSampleRateHz = 16_000,
        fixedBitrateBps   = 23_850,
    );

    val isFixedFormat: Boolean get() = fixedSampleRateHz != null

    companion object {
        val ALL = values().toList()
    }
}

/**
 * Preset bundles that stamp format + quality + audio-processing defaults.
 * Touching any preset-bound field (format/rate/bitrate/NS/AGC) switches to
 * CUSTOM. Non-preset fields (showLevel) don't affect preset identity.
 */
enum class AudioPreset(val displayName: String) {
    DICTATION("Dictation"),
    VOICE("Voice"),
    INTERVIEW("Interview"),
    DIALOG("Dialog"),
    NATURE("Nature"),
    CUSTOM("Custom");

    companion object {
        val ALL = values().toList()
    }
}

val SAMPLE_RATE_OPTIONS_HZ = listOf(16_000, 22_050, 44_100, 48_000)
val BITRATE_OPTIONS_BPS    = listOf(32_000, 64_000, 96_000, 128_000, 192_000)

fun formatSampleRate(hz: Int): String = when {
    hz >= 1000 && hz % 1000 == 0 -> "${hz / 1000} kHz"
    hz >= 1000                   -> "%.1f kHz".format(hz / 1000.0)
    else                         -> "$hz Hz"
}

fun formatBitrate(bps: Int): String = "${bps / 1000} kbps"

// ---- Level meter helpers (inlined from former SilenceGate) ----

private const val MAX_AMPLITUDE = 32_767f

/**
 * Convert a MediaRecorder max-amplitude sample (0..32767) to dBFS.
 * Returns -100 for zero / near-zero amplitude to avoid -Infinity.
 */
fun amplitudeToDb(amplitude: Int): Int {
    if (amplitude <= 0) return -100
    val normalised = amplitude / MAX_AMPLITUDE
    return (20.0 * kotlin.math.log10(normalised)).toInt().coerceIn(-100, 0)
}

/** ASCII meter e.g. "████████    " scaled by dB in [-60, 0]. */
fun asciiMeter(db: Int, width: Int = 12): String {
    val clamped = db.coerceIn(-60, 0)
    val fullCells = ((clamped + 60) / 60f * width).toInt().coerceIn(0, width)
    return buildString {
        repeat(fullCells) { append('█') }
        repeat(width - fullCells) { append(' ') }
    }
}
