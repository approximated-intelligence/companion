package de.perigon.companion.audio.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import javax.inject.Inject
import javax.inject.Singleton

private val Context.audioConfigDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "audio_config",
)

private object Keys {
    val PRESET                = stringPreferencesKey("preset")
    val FORMAT                = stringPreferencesKey("format")
    val SAMPLE_RATE_HZ        = intPreferencesKey("sample_rate_hz")
    val BITRATE_BPS           = intPreferencesKey("bitrate_bps")
    val NOISE_SUPPRESSION     = booleanPreferencesKey("noise_suppression")
    val AUTO_GAIN             = booleanPreferencesKey("auto_gain")
    val SHOW_LEVEL            = booleanPreferencesKey("show_level")
    val FOLDER_URI            = stringPreferencesKey("folder_uri")
    val FOLDER_LABEL          = stringPreferencesKey("folder_label")
}

private fun Preferences.toAudioConfig(): AudioConfigEntity {
    val d = AudioConfigEntity.DEFAULT
    // Guard against legacy formats (e.g. AMR_NB removed) → fall back to default.
    val format = this[Keys.FORMAT]?.let { name ->
        runCatching { AudioFormat.valueOf(name) }.getOrNull()
    } ?: d.format
    return AudioConfigEntity(
        preset             = this[Keys.PRESET]?.let { runCatching { AudioPreset.valueOf(it) }.getOrNull() } ?: d.preset,
        format             = format,
        sampleRateHz       = this[Keys.SAMPLE_RATE_HZ]    ?: d.sampleRateHz,
        bitrateBps         = this[Keys.BITRATE_BPS]       ?: d.bitrateBps,
        noiseSuppression   = this[Keys.NOISE_SUPPRESSION] ?: d.noiseSuppression,
        autoGain           = this[Keys.AUTO_GAIN]         ?: d.autoGain,
        showLevel          = this[Keys.SHOW_LEVEL]        ?: d.showLevel,
    )
}

@Singleton
class AudioConfigPrefs @Inject constructor(
    @param:ApplicationContext private val ctx: Context,
) {
    private val ds: DataStore<Preferences> = ctx.audioConfigDataStore

    @Volatile
    private var cache: Preferences = runBlocking { ds.data.first() }

    private suspend fun edit(block: MutablePreferences.() -> Unit) {
        cache = ds.edit(block)
    }

    fun get(): AudioConfigEntity = cache.toAudioConfig()

    fun observe(): Flow<AudioConfigEntity> = ds.data.map { it.toAudioConfig() }

    suspend fun update(transform: (AudioConfigEntity) -> AudioConfigEntity) {
        val updated = transform(get())
        edit {
            set(Keys.PRESET,               updated.preset.name)
            set(Keys.FORMAT,               updated.format.name)
            set(Keys.SAMPLE_RATE_HZ,       updated.sampleRateHz)
            set(Keys.BITRATE_BPS,          updated.bitrateBps)
            set(Keys.NOISE_SUPPRESSION,    updated.noiseSuppression)
            set(Keys.AUTO_GAIN,            updated.autoGain)
            set(Keys.SHOW_LEVEL,           updated.showLevel)
        }
    }

    fun folderUri(): String?   = cache[Keys.FOLDER_URI]
    fun folderLabel(): String? = cache[Keys.FOLDER_LABEL]

    suspend fun setFolder(uri: String, label: String) {
        edit {
            set(Keys.FOLDER_URI, uri)
            set(Keys.FOLDER_LABEL, label)
        }
    }

    suspend fun clearFolder() {
        edit {
            remove(Keys.FOLDER_URI)
            remove(Keys.FOLDER_LABEL)
        }
    }

    fun observeFolderUri(): Flow<String?> = ds.data.map { it[Keys.FOLDER_URI] }
}
