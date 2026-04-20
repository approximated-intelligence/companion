package de.perigon.companion.audio.domain

import kotlin.math.log10

/**
 * Pure decision logic for the silence-skip feature.
 *
 * Fed one amplitude sample at a time (from MediaRecorder.getMaxAmplitude()),
 * along with the current timestamp and whether the recorder is currently
 * capturing. Emits one of [Decision.Stay] / [Decision.Pause] / [Decision.Resume].
 *
 * No timers, no threads, no I/O. The caller drives sampling and applies
 * the resulting decision to MediaRecorder.
 */
class SilenceGate(
    private val thresholdDb: Int,
    private val graceMs: Long,
) {
    sealed class Decision {
        data object Stay   : Decision()
        data object Pause  : Decision()
        data object Resume : Decision()
    }

    private var silenceStartMs: Long? = null

    fun evaluate(amplitude: Int, nowMs: Long, currentlyRecording: Boolean): Decision {
        val db = amplitudeToDb(amplitude)
        val isSilent = db < thresholdDb

        if (currentlyRecording) {
            if (isSilent) {
                val start = silenceStartMs ?: run { silenceStartMs = nowMs; nowMs }
                return if (nowMs - start >= graceMs) {
                    silenceStartMs = null
                    Decision.Pause
                } else Decision.Stay
            } else {
                silenceStartMs = null
                return Decision.Stay
            }
        } else {
            silenceStartMs = null
            return if (isSilent) Decision.Stay else Decision.Resume
        }
    }

    fun reset() { silenceStartMs = null }

    companion object {
        private const val MAX_AMPLITUDE = 32_767f

        /**
         * Convert a MediaRecorder max-amplitude sample (0..32767) to dBFS.
         * Returns -100 for zero / near-zero amplitude to avoid -Infinity.
         */
        fun amplitudeToDb(amplitude: Int): Int {
            if (amplitude <= 0) return -100
            val normalised = amplitude / MAX_AMPLITUDE
            return (20.0 * log10(normalised)).toInt().coerceIn(-100, 0)
        }

        /** ASCII meter e.g. "▁▂▃▄▅▆▇█" scaled by dB in [-60, 0]. */
        fun asciiMeter(db: Int, width: Int = 12): String {
            val blocks = "▁▂▃▄▅▆▇█"
            val clamped = db.coerceIn(-60, 0)
            val fullCells = ((clamped + 60) / 60f * width).toInt().coerceIn(0, width)
            val bar = buildString {
                repeat(fullCells) { append(blocks.last()) }
                repeat(width - fullCells) { append(' ') }
            }
            return bar
        }
    }
}
