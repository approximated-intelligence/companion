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
    runCatching { Json.parseToJsonElement(raw.trim()).jsonObject.str("type") == BOOTSTRAP_TYPE }
        .getOrDefault(false)

/**
 * Every QR shape setup.py emits. setup.py is the only legitimate producer, so
 * these shapes are authoritative: if a payload isn't one of these, it didn't
 * come from setup.py and the user should not expect it to work anywhere.
 *
 * Recognising the shapes is what lets us answer "you clicked the wrong button"
 * with the right button, instead of dumping the raw `type` value (which the
 * user never sees and cannot interpret) into a snackbar.
 */
sealed interface QrKind {
    data object Bootstrap : QrKind
    data object GitHub : QrKind
    data object CloudflareHmac : QrKind
    data object CloudflareApi : QrKind
    data object Nacl : QrKind
    data object B2 : QrKind
    data object S3 : QrKind
    data object Http : QrKind
    data class Unknown(val reason: String) : QrKind
}

private val HEX = "0123456789abcdef".toSet()

/**
 * Pure: which of setup.py's outputs is [raw]?
 *
 * Type-tagged QRs first — their `type` field is unambiguous. Then shape-based
 * recognition of the two tagless ones: the Cloudflare HMAC secret is a bare
 * 64-char hex string (not JSON), and the B2 credentials / Cloudflare API
 * config QRs are JSON without a `type`.
 */
fun classifyQr(raw: String): QrKind {
    val trimmed = raw.trim()
    if (trimmed.length == 64 && trimmed.all { it in HEX }) return QrKind.CloudflareHmac

    val obj = runCatching { Json.parseToJsonElement(trimmed).jsonObject }.getOrNull()
        ?: return QrKind.Unknown("not a JSON object")

    obj.str("type")?.let { type ->
        return when (type) {
            BOOTSTRAP_TYPE -> QrKind.Bootstrap
            "github" -> QrKind.GitHub
            "nacl_backup_pubkey" -> QrKind.Nacl
            "s3_media" -> QrKind.S3
            "http_media" -> QrKind.Http
            else -> QrKind.Unknown("unknown type '$type'")
        }
    }

    return when {
        obj.str("key_id") != null &&
            obj.str("application_key") != null &&
            obj.str("endpoint") != null &&
            obj.str("bucket") != null -> QrKind.B2
        obj.str("token") != null &&
            obj.str("zone_id") != null &&
            obj.str("ruleset_id") != null -> QrKind.CloudflareApi
        else -> QrKind.Unknown("unrecognised JSON shape")
    }
}

/**
 * Pure: one sentence naming what the user actually scanned, plus where to
 * scan it instead. The destination strings mirror `_dest(...)` in setup.py,
 * so they match the setup_report.pdf the user is reading from.
 */
fun wrongQrMessage(kind: QrKind, expected: String): String = when (kind) {
    QrKind.Bootstrap ->
        "That is the all-in-one bootstrap QR. Scan it from Settings → Import Full Config."
    QrKind.GitHub ->
        "That is the GitHub QR. Scan it from Settings → GitHub."
    QrKind.CloudflareHmac ->
        "That is the Cloudflare HMAC secret QR. Scan it from Settings → Cloudflare → HMAC Secret."
    QrKind.CloudflareApi ->
        "That is the Cloudflare API config QR. Scan it from Settings → Cloudflare → API Config."
    QrKind.Nacl ->
        "That is the backup encryption key QR. Scan it from Settings → Backup → Scan Encryption Key."
    QrKind.B2 ->
        "That is the B2 credentials QR. Scan it from Settings → Backup → B2 Credentials."
    QrKind.S3 ->
        "That is the MinIO S3 QR. Scan it from Settings → Media → S3 Credentials."
    QrKind.Http ->
        "That is the HTTP media QR. Scan it from Settings → Media → HTTP Credentials."
    is QrKind.Unknown ->
        "Not $expected — ${kind.reason}."
}

/**
 * Pure: the bootstrap object. On mismatch, names what the user scanned
 * instead of echoing the raw type value.
 */
fun parseBootstrapEnvelope(raw: String): JsonObject {
    val trimmed = raw.trim()
    val obj = runCatching { Json.parseToJsonElement(trimmed).jsonObject }.getOrNull()
    if (obj != null && obj.str("type") == BOOTSTRAP_TYPE) return obj
    error(wrongQrMessage(classifyQr(raw), expected = "the all-in-one bootstrap QR"))
}

/**
 * Pure: a single-purpose QR as JSON. [expectedType] is the `type` field
 * setup.py writes for that QR, or null for QRs that carry no type — the B2
 * credentials QR; that section uses [parseB2Qr] to enforce the shape.
 *
 * On mismatch, names what the user actually scanned. The message is designed
 * to be shown verbatim: callers should not re-prefix it with their own
 * "X QR not recognised:".
 */
fun parseTypedQr(raw: String, expectedType: String?): JsonObject {
    val trimmed = raw.trim()
    val obj = runCatching { Json.parseToJsonElement(trimmed).jsonObject }.getOrNull()
        ?: error(wrongQrMessage(classifyQr(raw), expected = expectedType?.let { "a $it QR" } ?: "a QR"))
    if (expectedType != null && obj.str("type") != expectedType) {
        error(wrongQrMessage(classifyQr(raw), expected = "a $expectedType QR"))
    }
    return obj
}
