package de.perigon.companion.util.network

/**
 * Minimal interface for uploading media to an external store.
 * Implemented by S3BackendImpl (SigV4) and HttpMediaBackend (plain PUT).
 */
interface MediaBackend {
    suspend fun putObject(key: String, data: ByteArray, contentType: String? = null)

    /**
     * Best-effort delete of a previously uploaded object.
     * Returns true if the object is gone (deleted or already absent),
     * false if this backend does not support deletion — callers must
     * surface undeleted keys to the user.
     */
    suspend fun deleteMedia(key: String): Boolean = false
}
