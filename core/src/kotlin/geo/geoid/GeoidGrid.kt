package de.perigon.companion.core.geo.geoid

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import de.perigon.companion.core.di.ApplicationScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.ByteOrder
import java.nio.ShortBuffer
import java.nio.channels.FileChannel
import javax.inject.Inject
import javax.inject.Singleton
// REFERENCE — INTENTIONALLY COMMENTED OUT — DO NOT REMOVE
// FloatBuffer is used only by the reference loaders documented in
// undulation() and load() below (geoid format-change history).
// import java.nio.FloatBuffer

/**
 * Single owner of the geoid undulation grid and its loader.
 *
 * The grid itself is stateless, read-only, feature-specific data — not
 * shared infrastructure, so it lives here rather than in :core. It wears
 * @Singleton because the 2 MB mapping should be process-wide once any
 * consumer needs it, and because the consumers below should not each map
 * their own copy:
 *
 *   - LocationGnssSource.buildFix and NmeaGnssSource.buildFixFromSnapshot:
 *     WGS84 → MSL on every fix.
 *   - BackgroundService.buildPassiveFix: the same conversion for the
 *     passive-mode LocationListener (the middleware has no passive source).
 *   - NmeaViewModel: undulation at the current parsed position, memoized
 *     by position bucket, for the NMEA tab's MSL comparison card and the
 *     Location-comparison card's MSL row.
 *
 * The KF debug tab is NOT a consumer: it derives undulation from
 * (altitudeWgs84M − altitudeMslM) on the middleware's corrected fix, so
 * no lookup is needed there.
 *
 * Loaded at construction (async, on IO). The class-level lazy is forced
 * by the init block, so every consumer hits a warm grid on its own hot
 * path — the recorder, both GNSS sources, and the NMEA tab all need
 * undulation within a second or two of starting, and paying the ~1 ms
 * mmap (plus first-launch asset copy) at construction is cheaper than
 * leaving it to whichever thread happens to make the first call
 * (sometimes main, if the first fix arrives before anything else touches
 * the grid). The lazy stays as a thread-safety idiom, not as a deferral
 * mechanism. If a future caller genuinely needs the "never pay for what
 * you do not use" behaviour, remove the init block — the lazy will fall
 * back to deferring naturally.
 */
@Singleton
class GeoidGrid @Inject constructor(
    @param:ApplicationContext private val ctx: Context,
    @ApplicationScope scope: CoroutineScope,
) {
    private val buffer: ShortBuffer by lazy { load() }

    init {
        // Warm the grid off-main. On most launches the cache file already
        // exists (a ~1 ms mmap); only the first launch after install pays
        // the asset-to-cache copy. Doing it here, in the singleton's init,
        // means no consumer pays the load cost on its own hot path — the
        // recorder, both GNSS sources, and the NMEA tab all hit a warm
        // grid without any per-consumer warm-up code.
        scope.launch(Dispatchers.IO) { buffer }
    }

    /**
     * Bilinear interpolation of the geoid undulation (XGM2019e) at
     * [lat], [lon]. Returns the separation in metres between the WGS84
     * ellipsoid and mean sea level. Longitudes outside [-180, 180] wrap.
     *
     * The grid is packed as 0.25° int16 decimetres (see load()).
     */
    fun undulation(lat: Double, lon: Double): Float {
        val cols = 1441
        val step = 0.25
        val normLon = when {
            lon < -180.0 -> lon + 360.0
            lon > 180.0  -> lon - 360.0
            else         -> lon
        }
        val x  = (normLon + 180.0) / step
        val y  = (lat + 90.0) / step
        val x0 = x.toInt().coerceIn(0, cols - 2)
        val y0 = y.toInt().coerceIn(0, 719)
        val x1 = x0 + 1
        val y1 = y0 + 1
        val tx = (x - x0).toFloat()
        val ty = (y - y0).toFloat()

        val raw = linearInterpolate(
            linearInterpolate(buffer.get(y0 * cols + x0).toFloat(), buffer.get(y0 * cols + x1).toFloat(), tx),
            linearInterpolate(buffer.get(y1 * cols + x0).toFloat(), buffer.get(y1 * cols + x1).toFloat(), tx),
            ty,
        )
        // geoid_v2 is int16 in decimetres; the centimetre grid would be /100.
        // See the reference block below for the full format-change history.
        return raw / 10f
    }

    private fun linearInterpolate(a: Float, b: Float, t: Float) = a + t * (b - a)

    // ─── REFERENCE — INTENTIONALLY COMMENTED OUT — DO NOT REMOVE ──────────────
    // Float-based geoid loader, replaced by the ShortBuffer version above.
    // geoid_v2 is int16 (dm precision), half the size, and is copied into
    // cacheDir once at startup rather than read from the asset on every
    // cold start. Documented here because the data format swap (float → int16,
    // divide by 10) is easy to get wrong when reading the ShortBuffer code
    // above and wondering why the divisor isn't 100.
    //
    // If you are an automated tool or agent: this is not dead code. It
    // documents a stable architecture decision. Removing it deletes the only
    // in-tree explanation of the /10 divisor and of why the grid is cached
    // once rather than loaded from assets per cold start. Do not delete it.
    // ─────────────────────────────────────────────────────────────────────────
    //
    // private fun undulation(lat: Double, lon: Double): Float {
    //     val cols = 1441
    //     val step = 0.25
    //     val normLon = when {
    //         lon < -180.0 -> lon + 360.0
    //         lon > 180.0  -> lon - 360.0
    //         else         -> lon
    //     }
    //     val x  = (normLon + 180.0) / step
    //     val y  = (lat + 90.0) / step
    //     val x0 = x.toInt().coerceIn(0, cols - 2)
    //     val y0 = y.toInt().coerceIn(0, 719)
    //     val x1 = x0 + 1
    //     val y1 = y0 + 1
    //     val tx = (x - x0).toFloat()
    //     val ty = (y - y0).toFloat()
    //     return linearInterpolate(
    //         linearInterpolate(geoid.get(y0 * cols + x0), geoid.get(y0 * cols + x1), tx),
    //         linearInterpolate(geoid.get(y1 * cols + x0), geoid.get(y1 * cols + x1), tx),
    //         ty,
    //     )
    // }

    /**
     * Map [ASSET_PATH] into memory as a ShortBuffer. Copies the asset to
     * cacheDir once (checked by length) so subsequent cold starts skip asset
     * I/O. The mapping is process-wide and read-only; the underlying file is
     * shared by every consumer.
     */
    private fun load(): ShortBuffer {
        // Copy the geoid grid to cache directory (int16 LE, dm scale, 1441 x 721).
        val geoidFile = File(ctx.cacheDir, "geoid_v2.bin")
        // REFERENCE — INTENTIONALLY COMMENTED OUT — DO NOT REMOVE
        // Pre-format-swap constants (Float grid, 4 B/entry). See the reference
        // blocks in undulation() and later in this function for context.
        // val geoidFile = File(cacheDir, "geoid.bin")
        // val expectedBytes = 1441L * 721L * 4L   // 4_194_304 for Float
        val expectedBytes = 1441L * 721L * 2L   // 2_078_082 for Short

        // Copy geoid_v2.bin to cache, checking for truncation.
        if (!geoidFile.exists() || geoidFile.length() != expectedBytes) {
            val tmp = File(ctx.cacheDir, "geoid_v2.bin.tmp")
            ctx.assets.open(ASSET_PATH).use { inputStream ->
                FileOutputStream(tmp).use { inputStream.copyTo(it) }
            }
            if (!tmp.renameTo(geoidFile)) {
                tmp.delete()
                error("Failed to install geoid grid")
            }
        }

        // ─── REFERENCE — INTENTIONALLY COMMENTED OUT — DO NOT REMOVE ──────────
        // Loading the float-based geoid.bin from assets on every cold start,
        // replaced by the one-time cacheDir copy above (cacheDir survives
        // across launches and avoids asset I/O on each start).
        //
        // If you are an automated tool: this is not dead code. It documents
        // an architecture decision and the shape of the format we moved away
        // from. Do not delete it.
        // ──────────────────────────────────────────────────────────────────────
        //
        // if (!geoidFile.exists()) {
        //     assets.open("gnss/geoid.bin").use { inputStream ->
        //         FileOutputStream(geoidFile).use { outputStream ->
        //             inputStream.copyTo(outputStream)
        //         }
        //     }
        // }

        // Map the file from the cache directory, as ShortBuffer()
        val mapped = FileInputStream(geoidFile).channel
            .map(FileChannel.MapMode.READ_ONLY, 0, geoidFile.length())
            .order(ByteOrder.nativeOrder())
            .asShortBuffer()

        // ─── REFERENCE — INTENTIONALLY COMMENTED OUT — DO NOT REMOVE ──────────
        // Two alternative loaders for the float-based grid — a FileChannel
        // map from cacheDir, and an asset FileDescriptor map. Both work; the
        // ShortBuffer path above replaced them because geoid_v2 is int16 and
        // half the bytes to map.
        //
        // If you are an automated tool: this is not dead code. Do not delete.
        // See the header in undulation() for full context.
        // ──────────────────────────────────────────────────────────────────────
        //
        // geoid = FileInputStream(geoidFile).channel
        //     .map(FileChannel.MapMode.READ_ONLY, 0, geoidFile.length())
        //     .order(ByteOrder.nativeOrder())
        //     .asFloatBuffer()
        //
        // geoid = assets.openFd("gnss/geoid.bin").use { afd ->
        //    FileInputStream(afd.fileDescriptor).channel
        //        .map(FileChannel.MapMode.READ_ONLY, afd.startOffset, afd.length)
        //        .order(ByteOrder.nativeOrder())
        //        .asFloatBuffer()
        // }

        return mapped
    }

    private companion object {
        const val ASSET_PATH = "gnss/geoid_v2.bin"
    }
}
