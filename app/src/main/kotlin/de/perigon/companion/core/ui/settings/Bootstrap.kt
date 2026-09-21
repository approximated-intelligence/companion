package de.perigon.companion.core.ui.settings

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

const val BOOTSTRAP_TYPE = "companion_bootstrap_v1"

/**
 * Parsing helpers shared by every SettingsSection. The bootstrap payload has
 * no central model any more: each section parses its own part in stage().
 */

fun JsonObject.str(key: String): String? = this[key]?.jsonPrimitive?.content

fun JsonObject.section(key: String): JsonObject? = this[key]?.jsonObject

/** Required field of a section; throws with a user-facing message. */
fun JsonObject.req(section: String, key: String): String =
    str(key) ?: error("QR missing $section.$key")

/** Pure: whether [raw] is a bootstrap payload (routes any scan to staging). */
fun isBootstrapPayload(raw: String): Boolean =
    runCatching { Json.parseToJsonElement(raw).jsonObject.str("type") == BOOTSTRAP_TYPE }
        .getOrDefault(false)

/** Pure: the bootstrap object, or throws. */
fun parseBootstrapEnvelope(raw: String): JsonObject {
    val obj = Json.parseToJsonElement(raw).jsonObject
    val type = obj.str("type")
    require(type == BOOTSTRAP_TYPE) { "Expected bootstrap QR, got: $type" }
    return obj
}

/**
 * Pure: a single-purpose QR (B2, GitHub, S3, …) as JSON. [expectedType] null
 * accepts payloads without a type field (the B2 QR has none).
 */
fun parseTypedQr(raw: String, expectedType: String?): JsonObject {
    val obj = Json.parseToJsonElement(raw).jsonObject
    if (expectedType != null) {
        val type = obj.str("type")
        require(type == expectedType) { "Expected $expectedType QR, got: $type" }
    }
    return obj
}
