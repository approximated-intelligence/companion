package de.perigon.companion.media.network

import android.database.sqlite.SQLiteDatabase
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * The outcome of fetching one tile. A `Bitmap?` cannot distinguish "this
 * source has no tile here" (a coverage gap, normal for an offline map at the
 * edges) from "fetching failed" (network, IO, parse — actionable). The
 * renderer needs both counts separately: coverage gaps are silent, fetch
 * failures are not.
 */
sealed interface TileResult {
    data class Present(val bitmap: Bitmap) : TileResult

    /** Not in this source's coverage (e.g. SQLite row absent, out of zoom range). */
    data object Absent : TileResult

    /** Fetching failed — actionable (network flaky, IO error, parse error). */
    data class Failed(val reason: String, val cause: Throwable? = null) : TileResult
}

/**
 * Provides map tiles for track rendering.
 * Implementations: [SqliteTileSource] (local OsmAnd DB), [NetworkTileSource] (HTTP).
 */
interface TileSource : AutoCloseable {
    val minZoom: Int
    val maxZoom: Int
    fun getTile(z: Int, x: Int, y: Int): TileResult
}

/**
 * Reads tiles from an OsmAnd-style SQLiteDB file.
 * OsmAnd uses inverted zoom: osmandZ = 17 - z.
 */
class SqliteTileSource private constructor(
    private val db: SQLiteDatabase,
    override val minZoom: Int,
    override val maxZoom: Int,
) : TileSource {

    companion object {
        fun open(file: File): SqliteTileSource {
            val db = SQLiteDatabase.openDatabase(
                file.absolutePath, null,
                SQLiteDatabase.OPEN_READONLY or SQLiteDatabase.NO_LOCALIZED_COLLATORS,
            )
            val (minZ, maxZ) = readZoomRange(db)
            return SqliteTileSource(db, minZ, maxZ)
        }

        fun cacheFile(cacheDir: File): File = File(cacheDir, "tile_source.sqlitedb")

        private fun readZoomRange(db: SQLiteDatabase): Pair<Int, Int> {
            db.rawQuery("SELECT minzoom, maxzoom FROM info LIMIT 1", null).use { c ->
                if (c.moveToFirst()) {
                    val osmandMin = c.getInt(0)
                    val osmandMax = c.getInt(1)
                    return (17 - osmandMax) to (17 - osmandMin)
                }
            }
            return 0 to 17
        }
    }

    override fun getTile(z: Int, x: Int, y: Int): TileResult {
        val osmandZ = 17 - z
        try {
            db.rawQuery(
                "SELECT image FROM tiles WHERE z = ? AND x = ? AND y = ? AND s = 0 LIMIT 1",
                arrayOf(osmandZ.toString(), x.toString(), y.toString()),
            ).use { c ->
                if (!c.moveToFirst()) return TileResult.Absent
                val blob = c.getBlob(0) ?: return TileResult.Absent
                val bitmap = BitmapFactory.decodeByteArray(blob, 0, blob.size)
                    ?: return TileResult.Failed("decode returned null")
                return TileResult.Present(bitmap)
            }
        } catch (e: Exception) {
            return TileResult.Failed("SQLite read failed: ${e.message}", e)
        }
    }

    override fun close() {
        db.close()
    }
}

/**
 * Fetches tiles over HTTP. Supports two URL template styles:
 *
 * **XYZ** — contains `{z}`, `{x}`, `{y}` placeholders:
 *   `https://tile.openstreetmap.org/{z}/{x}/{y}.png`
 *
 * **Quadkey** — contains `{q}` placeholder:
 *   `https://ecn.t0.tiles.virtualearth.net/tiles/a{q}.jpeg?g=587`
 *
 * No caching — tiles are fetched on demand per render.
 * Called from Dispatchers.IO inside the renderer loop.
 */
class NetworkTileSource(
    private val urlTemplate: String,
    override val minZoom: Int = 0,
    override val maxZoom: Int = 19,
) : TileSource {

    private val isQuadkey: Boolean = "{q}" in urlTemplate

    override fun getTile(z: Int, x: Int, y: Int): TileResult {
        val url = buildUrl(z, x, y)
        return fetchBitmap(url)
    }

    override fun close() { /* nothing to release */ }

    private fun buildUrl(z: Int, x: Int, y: Int): String =
        if (isQuadkey) {
            urlTemplate.replace("{q}", toQuadkey(z, x, y))
        } else {
            urlTemplate
                .replace("{z}", z.toString())
                .replace("{x}", x.toString())
                .replace("{y}", y.toString())
        }

    companion object {
        /**
         * Convert tile coordinates to a quadkey string.
         * Each zoom level contributes one digit (0-3) based on
         * the binary interleaving of x and y bits.
         */
        fun toQuadkey(z: Int, x: Int, y: Int): String = buildString(z) {
            for (i in z downTo 1) {
                var digit = 0
                val mask = 1 shl (i - 1)
                if (x and mask != 0) digit += 1
                if (y and mask != 0) digit += 2
                append(digit)
            }
        }

        private fun fetchBitmap(url: String): TileResult {
            val conn = URL(url).openConnection() as HttpURLConnection
            return try {
                conn.connectTimeout = 10_000
                conn.readTimeout = 10_000
                conn.setRequestProperty("User-Agent", "PerigonCompanion/1.0")
                val code = conn.responseCode
                if (code != 200) return TileResult.Failed("HTTP $code")
                val bitmap = conn.inputStream.use { BitmapFactory.decodeStream(it) }
                    ?: return TileResult.Failed("decode returned null")
                TileResult.Present(bitmap)
            } catch (e: Exception) {
                // Previously swallowed into null, making network failures
                // indistinguishable from absent tiles: the rendered map
                // silently had holes and reported success.
                TileResult.Failed("${e.javaClass.simpleName}: ${e.message}", e)
            } finally {
                conn.disconnect()
            }
        }
    }
}
