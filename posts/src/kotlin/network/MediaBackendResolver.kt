package de.perigon.companion.posts.network

import de.perigon.companion.core.network.MediaBackend
import de.perigon.companion.core.network.S3BackendFactory
import de.perigon.companion.posts.data.PostsPrefs
import de.perigon.companion.posts.data.PostsSecrets
import javax.inject.Inject
import javax.inject.Singleton

/** An external media backend and the public base URL its objects are served from. */
data class ActiveBackend(val backend: MediaBackend, val staticUrl: String)

/**
 * Which external media backend is active: S3 first, then HTTP; null means
 * media goes to GitHub. One copy of the selection publish and unpublish
 * previously each implemented.
 */
@Singleton
class MediaBackendResolver @Inject constructor(
    private val prefs: PostsPrefs,
    private val secrets: PostsSecrets,
    private val s3Factory: S3BackendFactory,
    private val httpFactory: HttpMediaBackendFactory,
) {
    fun active(): ActiveBackend? {
        val s3 = prefs.s3MediaConfig()
        val s3Secret = secrets.s3SecretKey()
        if (s3 != null && s3Secret != null) {
            return ActiveBackend(
                s3Factory.create(s3.endpoint, s3.bucket, s3.keyId, s3Secret, s3.region),
                s3.staticUrl.trimEnd('/'),
            )
        }
        val http = prefs.httpMediaConfig()
        val password = secrets.httpMediaPassword()
        if (http != null && password != null) {
            return ActiveBackend(
                httpFactory.create(http.endpoint, http.user, password, http.authType),
                http.staticUrl.trimEnd('/'),
            )
        }
        return null
    }
}
