package de.perigon.companion.core.ui.settings

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

const val BOOTSTRAP_TYPE = "companion_bootstrap_v1"

/**
 * A fully validated bootstrap payload. Parsing is all-or-nothing: if any
 * present section is incomplete, parseBootstrap throws and nothing is
 * applied — no half-imported configuration.
 */
data class Bootstrap(
    val naclPkHex: String?,
    val b2:        B2?,
    val github:    GitHub?,
    val s3:        S3?,
    val http:      Http?,
) {
    data class B2(
        val keyId:    String,
        val appKey:   String,
        val endpoint: String?,
        val bucket:   String?,
        val region:   String?,
    )

    data class GitHub(
        val token: String,
        val owner: String,
        val repo:  String,
    )

    data class S3(
        val endpoint:  String,
        val staticUrl: String,
        val bucket:    String,
        val keyId:     String,
        val secretKey: String,
        val region:    String,
    )

    data class Http(
        val endpoint:  String,
        val staticUrl: String,
        val user:      String,
        val password:  String,
    )
}

/** Pure. Throws with a user-facing message on any malformed or missing field. */
fun parseBootstrap(raw: String): Bootstrap {
    val obj = Json.parseToJsonElement(raw).jsonObject
    val type = obj.str("type")
    require(type == BOOTSTRAP_TYPE) { "Expected bootstrap QR, got: $type" }

    return Bootstrap(
        naclPkHex = obj.str("nacl_public_key_hex")?.also { hex ->
            require(hex.length == 64) { "Expected 32-byte hex key" }
        },
        b2 = obj.section("b2")?.let { b ->
            Bootstrap.B2(
                keyId    = b.req("b2", "key_id"),
                appKey   = b.req("b2", "application_key"),
                endpoint = b.str("endpoint"),
                bucket   = b.str("bucket"),
                region   = b.str("region"),
            )
        },
        github = obj.section("github")?.let { g ->
            Bootstrap.GitHub(
                token = g.req("github", "token"),
                owner = g.req("github", "owner"),
                repo  = g.req("github", "repo"),
            )
        },
        s3 = obj.section("s3")?.let { s ->
            Bootstrap.S3(
                endpoint  = s.req("s3", "endpoint"),
                staticUrl = s.req("s3", "static_url"),
                bucket    = s.req("s3", "bucket"),
                keyId     = s.req("s3", "key_id"),
                secretKey = s.req("s3", "secret_key"),
                region    = s.req("s3", "region"),
            )
        },
        http = obj.section("http")?.let { h ->
            Bootstrap.Http(
                endpoint  = h.req("http", "endpoint"),
                staticUrl = h.req("http", "static_url"),
                user      = h.req("http", "user"),
                password  = h.req("http", "password"),
            )
        },
    )
}

private fun JsonObject.str(key: String): String? = this[key]?.jsonPrimitive?.content

private fun JsonObject.section(key: String): JsonObject? = this[key]?.jsonObject

private fun JsonObject.req(section: String, key: String): String =
    str(key) ?: error("Bootstrap missing $section.$key")
