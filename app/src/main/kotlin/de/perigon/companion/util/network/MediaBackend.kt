package de.perigon.companion.util.network

/**
 * Minimal interface for uploading media to an external store.
 * Implemented by S3BackendImpl (SigV4) and HttpMediaBackend (plain PUT).
 */
interface MediaBackend {
    suspend fun putObject(key: String, data: ByteArray, contentType: String? = null)
}
