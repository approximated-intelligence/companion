package de.perigon.companion.core.files

import android.content.Context
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import de.perigon.companion.core.feature.AppInitializer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject

private const val TAG = "CacheSweep"

/** Temp files every producer deletes after use; only a crash or kill leaves them. */
private val TEMP_PREFIXES = listOf("tq_", "pub_", "import_", "pull_")
private val TEMP_DIRS = setOf("frames", "gpx_share")
private const val MAX_AGE_MS = 24L * 60 * 60 * 1000

/**
 * Startup sweep of temp files a crash or kill left behind, so the app's
 * cache never grows unnoticed. Only known temp names, only older than a day:
 * a job finishing right now is never touched.
 */
class CacheSweep @Inject constructor(
    @param:ApplicationContext private val ctx: Context,
) : AppInitializer {

    override val order: Int = -100

    override suspend fun run() {
        withContext(Dispatchers.IO) {
            val now = System.currentTimeMillis()
            val stale = tempCandidates(ctx.cacheDir).filter { isStale(it.lastModified(), now) }
            val failed = stale.count { !it.delete() }
            if (stale.isNotEmpty()) Log.i(TAG, "swept ${stale.size - failed} stale temp file(s)")
            if (failed > 0) Log.w(TAG, "$failed stale temp file(s) could not be deleted")
        }
    }
}

private fun tempCandidates(cacheDir: File): List<File> =
    cacheDir.listFiles().orEmpty().flatMap { f ->
        when {
            f.isDirectory && f.name in TEMP_DIRS -> f.listFiles().orEmpty().filter { it.isFile }
            f.isFile && TEMP_PREFIXES.any { f.name.startsWith(it) } -> listOf(f)
            else -> emptyList()
        }
    }

/** Pure. */
internal fun isStale(lastModified: Long, now: Long, maxAgeMs: Long = MAX_AGE_MS): Boolean =
    now - lastModified > maxAgeMs
