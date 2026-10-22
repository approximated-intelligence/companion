package de.perigon.companion.core.feature

import androidx.compose.runtime.Composable
import kotlinx.serialization.json.JsonObject

/** Opens the shared QR scanner with [prompt]; [onResult] gets the raw payload. */
fun interface QrScan {
    fun request(prompt: String, onResult: (String) -> Unit)
}

/**
 * One feature's block on the settings screen plus its part of the bootstrap
 * QR. Bound with @IntoSet; the core settings host renders sections by [order].
 *
 * [id] is a stable string used for deep-link anchors: navigating to
 * `SettingsRoute(anchor = "backup_sources")` scrolls the settings screen to
 * the section with that id. Distinct from [order] because orders are dense
 * integers that can be reordered; the anchor string is a user-facing
 * contract.
 */
interface SettingsSection {
    val id: String
    val order: Int

    @Composable
    fun Content(scan: QrScan)

    /**
     * Parse and validate this section's part of a bootstrap payload WITHOUT
     * applying it. Null when the payload carries nothing for this section;
     * throws with a user-facing message when that part is malformed.
     */
    fun stage(bootstrap: JsonObject): StagedConfig? = null

    /** Remove this section's secrets, or everything it stores. */
    suspend fun clear(secretsOnly: Boolean)
}

/**
 * A parsed, not yet applied bootstrap part. The host shows every [summary],
 * and on confirmation runs [check] for ALL staged parts before ANY [apply]:
 * one refusal (e.g. a backup key change onto a non-empty bucket) aborts the
 * whole import.
 */
interface StagedConfig {
    val summary: List<String>
    suspend fun check(): String? = null
    suspend fun apply()
}
