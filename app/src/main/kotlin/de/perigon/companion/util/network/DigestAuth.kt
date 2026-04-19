package de.perigon.companion.util.network

import java.security.MessageDigest
import java.security.SecureRandom

/**
 * Pure HTTP Digest Auth (RFC 7616) computation. No I/O, no state.
 * Supports MD5 and SHA-256 algorithms. Always sends qop=auth.
 */
object DigestAuth {

    data class Challenge(
        val realm: String,
        val nonce: String,
        val opaque: String?,
        val algorithm: String, // "MD5" or "SHA-256"
        val qop: String?,      // typically "auth"
    )

    data class Credentials(
        val username: String,
        val password: String,
    )

    /**
     * Parse a WWW-Authenticate: Digest header value into a [Challenge].
     */
    fun parseChallenge(header: String): Challenge {
        val params = parseParams(header.removePrefix("Digest").trim())
        return Challenge(
            realm     = params["realm"] ?: error("Digest challenge missing realm"),
            nonce     = params["nonce"] ?: error("Digest challenge missing nonce"),
            opaque    = params["opaque"],
            algorithm = normalizeAlgorithm(params["algorithm"] ?: "MD5"),
            qop       = params["qop"],
        )
    }

    /**
     * Compute the Authorization: Digest header value.
     */
    fun authorize(
        method:   String,
        uri:      String,
        challenge: Challenge,
        creds:    Credentials,
        nc:       Int = 1,
        cnonce:   String = generateCnonce(),
    ): String {
        val ncHex = "%08x".format(nc)
        val ha1   = h(challenge.algorithm, "${creds.username}:${challenge.realm}:${creds.password}")
        val ha2   = h(challenge.algorithm, "$method:$uri")

        val response = if (challenge.qop != null) {
            h(challenge.algorithm, "$ha1:${challenge.nonce}:$ncHex:$cnonce:auth:$ha2")
        } else {
            h(challenge.algorithm, "$ha1:${challenge.nonce}:$ha2")
        }

        return buildString {
            append("Digest ")
            append("username=\"${creds.username}\", ")
            append("realm=\"${challenge.realm}\", ")
            append("nonce=\"${challenge.nonce}\", ")
            append("uri=\"$uri\", ")
            if (challenge.algorithm != "MD5") append("algorithm=${challenge.algorithm}, ")
            if (challenge.qop != null) {
                append("qop=auth, ")
                append("nc=$ncHex, ")
                append("cnonce=\"$cnonce\", ")
            }
            append("response=\"$response\"")
            if (challenge.opaque != null) append(", opaque=\"${challenge.opaque}\"")
        }
    }

    fun generateCnonce(): String {
        val bytes = ByteArray(16)
        SecureRandom().nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it) }
    }

    private fun h(algorithm: String, data: String): String {
        val digest = when (algorithm) {
            "MD5"     -> MessageDigest.getInstance("MD5")
            "SHA-256" -> MessageDigest.getInstance("SHA-256")
            else      -> error("Unsupported digest algorithm: $algorithm")
        }
        return digest.digest(data.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }

    private fun normalizeAlgorithm(raw: String): String = when (raw.uppercase()) {
        "MD5"                -> "MD5"
        "SHA-256", "SHA256"  -> "SHA-256"
        else                 -> raw.uppercase()
    }

    private fun parseParams(header: String): Map<String, String> {
        val result = mutableMapOf<String, String>()
        val regex = Regex("""(\w+)\s*=\s*(?:"([^"]*)"|([\w-]+))""")
        for (match in regex.findAll(header)) {
            val key   = match.groupValues[1]
            val value = match.groupValues[2].ifEmpty { match.groupValues[3] }
            result[key] = value
        }
        return result
    }
}
