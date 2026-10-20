package de.perigon.companion.audio.data

/**
 * Recording configuration. Persisted via [AudioConfigPrefs].
 *
 * Preset-bound fields (change → preset becomes CUSTOM):
 *   format, sampleRateHz, bitrateBps, noiseSuppression, autoGain
 *
 * Recording-only fields (free to change without affecting preset):
 *   showLevel
 *
 * AMR-WB ignores [sampleRateHz] and [bitrateBps] at record time
 * (codec-fixed). The UI hides those chips when AMR-WB is selected.
 */
data class AudioConfigEntity(
    val preset: AudioPreset            = AudioPreset.VOICE,
    val format: AudioFormat            = AudioFormat.M4A_AAC,
    val sampleRateHz: Int              = 22_050,
    val bitrateBps: Int                = 64_000,
    val noiseSuppression: Boolean      = true,
    val autoGain: Boolean              = false,
    val showLevel: Boolean             = true,
) {
    val effectiveSampleRateHz: Int get() = format.fixedSampleRateHz ?: sampleRateHz
    val effectiveBitrateBps: Int   get() = format.fixedBitrateBps   ?: bitrateBps

    /**
     * Apply a preset's preset-bound fields while keeping recording-only
     * fields (showLevel) untouched.
     */
    fun applyingPreset(preset: AudioPreset): AudioConfigEntity {
        val p = presetFields(preset)
        return copy(
            preset           = preset,
            format           = p.format,
            sampleRateHz     = p.sampleRateHz,
            bitrateBps       = p.bitrateBps,
            noiseSuppression = p.noiseSuppression,
            autoGain         = p.autoGain,
        )
    }

    companion object {
        val DEFAULT = AudioConfigEntity()

        private data class PresetFields(
            val format: AudioFormat,
            val sampleRateHz: Int,
            val bitrateBps: Int,
            val noiseSuppression: Boolean,
            val autoGain: Boolean,
        )

        private fun presetFields(preset: AudioPreset): PresetFields = when (preset) {
            AudioPreset.DICTATION -> PresetFields(AudioFormat.AMR_WB,   16_000, 23_850, noiseSuppression = true,  autoGain = false)
            AudioPreset.VOICE     -> PresetFields(AudioFormat.M4A_AAC,  22_050, 64_000, noiseSuppression = true,  autoGain = false)
            AudioPreset.INTERVIEW -> PresetFields(AudioFormat.M4A_AAC,  44_100, 96_000, noiseSuppression = true,  autoGain = false)
            AudioPreset.DIALOG    -> PresetFields(AudioFormat.OGG_OPUS, 48_000, 128_000, noiseSuppression = false, autoGain = false)
            AudioPreset.NATURE    -> PresetFields(AudioFormat.OGG_OPUS, 48_000, 192_000, noiseSuppression = false, autoGain = false)
            AudioPreset.CUSTOM    -> PresetFields(DEFAULT.format, DEFAULT.sampleRateHz, DEFAULT.bitrateBps, DEFAULT.noiseSuppression, DEFAULT.autoGain)
        }
    }
}
