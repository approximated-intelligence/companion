package de.perigon.companion.audio.data

/**
 * Recording configuration. Persisted via [AudioConfigPrefs].
 *
 * When [format] is a fixed-rate codec (AMR), the [sampleRateHz] and
 * [bitrateBps] fields are ignored at record time (MediaRecorder uses
 * codec-dictated values). The UI hides those chips in that case.
 */
data class AudioConfigEntity(
    val preset: AudioPreset            = AudioPreset.VOICE,
    val format: AudioFormat            = AudioFormat.M4A_AAC,
    val sampleRateHz: Int              = 22_050,
    val bitrateBps: Int                = 64_000,
    val skipSilence: Boolean           = true,
    val silenceThresholdDb: Int        = -45,
    val silenceGraceMs: Long           = 800L,
    val noiseSuppression: Boolean      = true,
    val autoGain: Boolean              = true,
    val showLevel: Boolean             = true,
) {
    val effectiveSampleRateHz: Int get() = format.fixedSampleRateHz ?: sampleRateHz
    val effectiveBitrateBps: Int   get() = format.fixedBitrateBps   ?: bitrateBps

    companion object {
        val DEFAULT = AudioConfigEntity()

        fun fromPreset(preset: AudioPreset): AudioConfigEntity = when (preset) {
            AudioPreset.VOICE_TINY -> AudioConfigEntity(
                preset = preset, format = AudioFormat.AMR_WB,
                sampleRateHz = 16_000, bitrateBps = 23_850,
                skipSilence = true, silenceThresholdDb = -45, silenceGraceMs = 800L,
                noiseSuppression = true, autoGain = true,
            )
            AudioPreset.VOICE -> AudioConfigEntity(
                preset = preset, format = AudioFormat.M4A_AAC,
                sampleRateHz = 22_050, bitrateBps = 64_000,
                skipSilence = true, silenceThresholdDb = -45, silenceGraceMs = 800L,
                noiseSuppression = true, autoGain = true,
            )
            AudioPreset.INTERVIEW -> AudioConfigEntity(
                preset = preset, format = AudioFormat.M4A_AAC,
                sampleRateHz = 44_100, bitrateBps = 96_000,
                skipSilence = false, silenceThresholdDb = -45, silenceGraceMs = 800L,
                noiseSuppression = true, autoGain = true,
            )
            AudioPreset.NATURE -> AudioConfigEntity(
                preset = preset, format = AudioFormat.OGG_OPUS,
                sampleRateHz = 48_000, bitrateBps = 192_000,
                skipSilence = false, silenceThresholdDb = -45, silenceGraceMs = 800L,
                noiseSuppression = false, autoGain = false,
            )
            AudioPreset.CUSTOM -> DEFAULT.copy(preset = AudioPreset.CUSTOM)
        }
    }
}
