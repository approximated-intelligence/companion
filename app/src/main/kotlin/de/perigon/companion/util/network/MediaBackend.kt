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

    /**
     * Whether [key] is already present. Lets a retried publish skip media it
     * already uploaded instead of re-sending it — the difference between
     * "resumes" and "starts over" on a slow link.
     *
     * Throws (does NOT return false) when presence cannot be determined:
     * treating an unreachable server as "absent" would re-upload everything
     * on every flaky attempt.
     */
    suspend fun objectExists(key: String): Boolean
}
