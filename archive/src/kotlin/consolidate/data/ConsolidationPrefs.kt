package de.perigon.companion.archive.consolidate.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import de.perigon.companion.archive.consolidate.domain.ConsolidationMode
import de.perigon.companion.archive.consolidate.domain.ConsolidationSettings
import de.perigon.companion.archive.consolidate.domain.MIN_BBOX
import de.perigon.companion.core.di.ApplicationScope
import de.perigon.companion.core.prefs.SnapshotStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.consolidationDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "consolidation_prefs",
)

private object Keys {
    val MODE        = stringPreferencesKey("mode")
    val BBOX_PHOTOS = intPreferencesKey("bbox_photos")
    val BBOX_PNGS   = intPreferencesKey("bbox_pngs")
    val BBOX_VIDEOS = intPreferencesKey("bbox_videos")
}

private fun Preferences.toSettings(): ConsolidationSettings {
    val d = ConsolidationSettings()
    return ConsolidationSettings(
        mode       = this[Keys.MODE]?.let { runCatching { ConsolidationMode.valueOf(it) }.getOrNull() } ?: d.mode,
        bboxPhotos = (this[Keys.BBOX_PHOTOS] ?: d.bboxPhotos).coerceAtLeast(MIN_BBOX),
        bboxPngs   = (this[Keys.BBOX_PNGS]   ?: d.bboxPngs)  .coerceAtLeast(MIN_BBOX),
        bboxVideos = (this[Keys.BBOX_VIDEOS] ?: d.bboxVideos).coerceAtLeast(MIN_BBOX),
    )
}

@Singleton
class ConsolidationPrefs @Inject constructor(
    @ApplicationContext ctx: Context,
    @ApplicationScope scope: CoroutineScope,
) {
    private val store = SnapshotStore(ctx.consolidationDataStore, scope)

    fun get(): ConsolidationSettings = store.snapshot().toSettings()

    fun observe(): Flow<ConsolidationSettings> = store.data.map { it.toSettings() }

    suspend fun update(transform: (ConsolidationSettings) -> ConsolidationSettings) {
        val updated = transform(store.current().toSettings())
        store.edit {
            set(Keys.MODE,        updated.mode.name)
            set(Keys.BBOX_PHOTOS, updated.bboxPhotos)
            set(Keys.BBOX_PNGS,   updated.bboxPngs)
            set(Keys.BBOX_VIDEOS, updated.bboxVideos)
        }
    }
}
