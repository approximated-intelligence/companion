package de.perigon.companion.posts.domain

import de.perigon.companion.posts.data.PostMediaFileStore
import de.perigon.companion.util.network.GitHubClient

/**
 * Pulls a post media file from GitHub into the local PostMedia store.
 * Composes [GitHubClient.getFileBinary] with [PostMediaFileStore] write
 * operations. Pure orchestration — no state, no Android context.
 *
 * Server files are named by [processedHash] — the media's published identity.
 * The local copy is written under the same hash, keeping local and server
 * names mirrored. A media with no [processedHash] was never uploaded, so
 * there is nothing to pull. [contentHash] is retained for call-site
 * compatibility.
 *
 * Returns the new local URI string, or null on failure.
 */
suspend fun pullPostMediaFromGitHub(
    contentHash: String,
    mimeType: String,
    slug: String,
    github: GitHubClient,
    mediaFileStore: PostMediaFileStore,
    processedHash: String? = null,
): String? {
    val ext  = if (mimeType.startsWith("video")) "mp4" else "jpg"
    val hash = processedHash ?: return null
    val bytes = github.getFileBinary("static/$slug/$hash.$ext") ?: return null
    return mediaFileStore.writeFromBytes(hash, ext, bytes)
}
