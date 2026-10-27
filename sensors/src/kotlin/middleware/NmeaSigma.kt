package de.perigon.companion.sensors.middleware

/**
 * Derive horizontal/vertical position uncertainty from the four NMEA
 * quantities that carry it.
 *
 * GST fields 6/7 are the receiver's own residual-derived σ. When they are
 * absent — many chipsets do not emit GST — fall back to the standard
 * standalone-GPS rule of thumb, DOP × UERE with UERE = 5 m. This is the
 * computation [NmeaGnssSource.buildFixFromSnapshot] has always performed;
 * it lives here so the NMEA debug tab can call the same code from its own
 * [de.perigon.companion.sensors.nmea.NmeaStream.ParsedSnapshot] rather
 * than duplicating the constant and risking drift.
 *
 * Both axes fall back independently: a receiver may emit GST but no VDOP,
 * in which case horizontal gets the GST value and vertical stays null.
 * Null is the honest answer, not zero.
 */
internal fun deriveNmeaSigma(
    gstHorizontalM: Double?,
    gstVerticalM: Double?,
    hdop: Double?,
    vdop: Double?,
): Sigma = Sigma(
    horizontalM = gstHorizontalM ?: hdop?.times(HDOP_UERE_M),
    verticalM = gstVerticalM ?: vdop?.times(HDOP_UERE_M),
)

/** Standard standalone-GPS UERE, metres, 1σ. */
private const val HDOP_UERE_M: Double = 5.0
