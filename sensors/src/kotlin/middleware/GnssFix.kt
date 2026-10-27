package de.perigon.companion.sensors.middleware

import de.perigon.companion.sensors.gnss.FixDimension
import de.perigon.companion.sensors.gnss.FixQuality
import de.perigon.companion.sensors.gnss.Position
import de.perigon.companion.sensors.gnss.SatelliteObservation
import de.perigon.companion.sensors.gnss.UTCDateTime

/**
 * Unified GNSS fix. Every value comes from the active source's native
 * protocol — no cross-source field substitution. `source` tags which
 * protocol produced the fix; it is informational, not a discriminator
 * for per-fix consumer behaviour (consumers branch on
 * [GnssController.sourceType], which is session-stable).
 *
 * Fields that a source cannot natively provide are `null`. Nothing is
 * synthesized or borrowed from another source to make the fix "look
 * complete".
 */
data class GnssFix(
    val source: GnssSourceType,
    val timestamp: UTCDateTime,
    val monotonicNanos: Long,
    val position: Position,
    val altitudeMslM: Double?,
    val altitudeWgs84M: Double?,
    val speedMps: Double?,
    val courseDeg: Double?,
    val magVarDeg: Double?,
    val fixQuality: FixQuality?,
    val fixDimension: FixDimension?,
    val satellitesUsed: Int?,
    val satellites: List<SatelliteObservation>,
    val dop: Dop,
    val sigma: Sigma,
    val sbasActive: Boolean,
    /**
     * The platform `Location` this fix was built from, when the source
     * produced one. Null in NMEA mode — NMEA mode does not consult
     * `Location` data at all. For interop only; consumers should use the
     * typed fields above.
     */
    val sourceLocation: android.location.Location?,
    /**
     * Time from the moment this source started observing to its first
     * valid fix, in whole seconds. Null until the first fix of a
     * session, and stays null if the session never produced one.
     *
     * Semantics differ between sources because the measurement differs:
     * the Location source reports the platform's GnssStatus.onFirstFix —
     * the time from its own requestLocationUpdates call to the HAL's
     * first fix. The NMEA source records when it started observing and
     * reports the delta to the first observed cycle; this may be nearly
     * zero if the engine was already warm from another consumer (the
     * recorder, the Sensors dashboard's own NMEA session, or another
     * app). Both are honest for what they measure; consumers see one
     * number.
     */
    val ttffSeconds: Int? = null,
)

data class Dop(
    val hdop: Double?,
    val vdop: Double?,
    val pdop: Double?,
) {
    companion object { val NONE = Dop(null, null, null) }
}

/**
 * Position uncertainty. Values are whatever the active source natively
 * provides: `Location.getAccuracy()` / `getVerticalAccuracyMeters()` in
 * LOCATION mode; NMEA GST fields 6/7/8 in NMEA mode. Null when the source
 * has no value — consumers choose their own fallback.
 */
data class Sigma(
    val horizontalM: Double?,
    val verticalM: Double?,
) {
    companion object { val NONE = Sigma(null, null) }
}

enum class GnssSourceType(val label: String) {
    LOCATION("Android Location"),
    NMEA("NMEA"),
}
