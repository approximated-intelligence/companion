package de.perigon.companion.audio.data

import android.media.MediaRecorder

/**
 * Supported audio recording formats. Each maps to an Android MediaRecorder
 * output-format + encoder pair that produces a file playable by the system
 * audio player.
 *
 * Pause support per format: MediaRecorder.pause() is reliable for MP4/OGG
 * containers but unreliable for AMR framing, so AMR variants advertise
 * [supportsPause] = false and the UI disables the Pause control.
 *
 * AMR codecs are sample-rate-fixed (8 kHz NB, 16 kHz WB) — the UI hides
 * sample-rate and bitrate chips when an AMR variant is selected.
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
    ),
    AMR_NB(
        displayName  = "AMR-NB",
        extension    = "amr",
        mimeType     = "audio/amr",
        outputFormat = MediaRecorder.OutputFormat.AMR_NB,
        encoder      = MediaRecorder.AudioEncoder.AMR_NB,
        supportsPause = false,
        fixedSampleRateHz = 8_000,
        fixedBitrateBps   = 12_200,
    );

    val isFixedFormat: Boolean get() = fixedSampleRateHz != null

    companion object {
        val ALL = values().toList()
    }
}

/**
 * Preset bundles that stamp format + quality + gate defaults in one chip.
 * Touching any individual chip after selecting a preset switches to CUSTOM.
 */
enum class AudioPreset(val displayName: String) {
    VOICE_TINY("Voice (tiny)"),
    VOICE("Voice"),
    INTERVIEW("Interview"),
    NATURE("Nature"),
    CUSTOM("Custom");

    companion object {
        val ALL = values().toList()
    }
}

val SAMPLE_RATE_OPTIONS_HZ = listOf(16_000, 22_050, 44_100, 48_000)
val BITRATE_OPTIONS_BPS    = listOf(32_000, 64_000, 96_000, 128_000, 192_000)
val SILENCE_GRACE_OPTIONS_MS = listOf(400L, 800L, 1_500L, 3_000L)

fun formatSampleRate(hz: Int): String = when {
    hz >= 1000 && hz % 1000 == 0 -> "${hz / 1000} kHz"
    hz >= 1000                   -> "%.1f kHz".format(hz / 1000.0)
    else                         -> "$hz Hz"
}

fun formatBitrate(bps: Int): String = "${bps / 1000} kbps"
