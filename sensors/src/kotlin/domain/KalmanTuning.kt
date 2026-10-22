package de.perigon.companion.sensors.domain

/**
 * Advanced altitude-filter tuning overrides.
 *
 * Every field is nullable; null means "use the built-in default for the
 * active model", which the UI renders as "0.001 (default)". Storing a
 * concrete default here would silently desynchronize from the model's
 * constructor the first time either is tuned.
 *
 * PROCESS NOISE IS EXPRESSED AS PROCESS NOISE. Every slider labelled "Q"
 * below is the variance rate directly — the same quantity the filter adds
 * to P each step. Not σ, not a square root. This is deliberate: the two
 * models share `biasNoiseQ` (both model b as a random walk), and if the
 * slider expressed σ the 3-state's q_b = σ² would require the user to take
 * a square root to compare against 2-state's q_b. Now 0.001 means 0.001 in
 * both models.
 *
 * Shared (both models):
 *   [biasNoiseQ]       — b random-walk variance rate, m²/s. Semantics
 *                        identical in both models; slider value maps 1:1
 *                        to q_b in the filter.
 *   [baroSigmaM]       — barometric measurement noise, m. Read by the
 *                        caller when calling [AltitudeKalmanFilter.updateBaro].
 *   [gpsVerticalMinM]  — floor on GPS vertical sigma derived from
 *                        Location.getVerticalAccuracyMeters(). On some
 *                        devices (Pixels especially) the HAL smooths the
 *                        altitude solution during stationary plateaus, so
 *                        the reported vertical accuracy is optimistic.
 *
 * Model-specific:
 *   [accelNoiseQ]     — 3-state vertical acceleration variance rate, m²/s³
 *                       (q_v directly). Unused in 2-state (no velocity state).
 *   [qH]              — 2-state h random-walk variance rate, m²/s.
 *                       Unused in 3-state (h is CV, not RW).
 *   [derivedVelNoise] — 2-state cascade v_z random-walk variance rate, m²/s³.
 *                       Unused in 3-state (v_z is a state).
 *
 * Both models serve the same use case — out and about, car/bike/wild — so
 * the bias process noise should reflect the same physical reality: weather
 * drift when you're stationary or slow, aircon when you're in a car, thermal
 * cycles when the phone is exposed to sun. The pure-weather rate is ~3e-4
 * (notes §5.2: ~1 m over 30–60 min); the pure-aircon rate is ~1e-2 (several
 * metres in minutes). The default below picks a middle ground for the mix
 * that "out and about" is in practice. The Advanced slider covers the full
 * range; adjust empirically against the b vs b* overlay.
 *
 * Defaults for the other fields are the "interim tuning" values from
 * notes §5.3.
 */
data class KalmanTuning(
    val accelNoiseQ: Double? = null,
    val biasNoiseQ: Double? = null,
    val qH: Double? = null,
    val derivedVelNoise: Double? = null,
    val baroSigmaM: Float? = null,
    val gpsVerticalMinM: Float? = null,
) {
    val isDefault: Boolean
        get() = accelNoiseQ == null && biasNoiseQ == null &&
                qH == null && derivedVelNoise == null &&
                baroSigmaM == null && gpsVerticalMinM == null

    companion object {
        /** 3-state accel process noise q_v, m²/s³. Historical 0.2² = 0.04. */
        const val DEFAULT_ACCEL_NOISE_Q = 0.04
        /**
         * Shared bias process noise q_b, m²/s. Middle ground for mixed
         * car/bike/wild use: ~3× the pure-weather rate (3e-4, from notes
         * §5.2), ~10× lower than the pure-aircon rate (1e-2). Pure-weather
         * means b moves 1 m in ~1 hour; this default means b moves 1 m in
         * ~17 minutes; aircon means b moves 1 m in ~1.7 minutes. Between
         * weather and aircon, closer to weather. The Advanced slider ranges
         * over all three regimes; adjust against b vs b*.
         */
        const val DEFAULT_BIAS_NOISE_Q = 1e-3
        const val DEFAULT_QH = 0.5
        const val DEFAULT_DERIVED_VEL_NOISE = 0.005
        const val DEFAULT_BARO_SIGMA_M: Float = 1.0f
        /**
         * Floor on GPS vertical sigma. 10 m is empirically the right level
         * for Pixel HALs, whose smoothed altitude solution stays flat while
         * the phone moves a meter or two — anything below 10 m lets the
         * filter follow those false plateaus. Users on other devices can
         * lower it via the Advanced expander; 0 disables the floor entirely.
         */
        const val DEFAULT_GPS_VERTICAL_MIN_M: Float = 10f
    }
}
