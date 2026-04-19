package de.perigon.companion.posts.domain

import de.perigon.companion.posts.data.PostMediaFileStore
import de.perigon.companion.util.network.GitHubClient

/**
 * Pulls a post media file from GitHub into the local PostMedia store.
 * Composes [GitHubClient.getFileBinary] with [PostMediaFileStore] write
 * operations. Pure orchestration — no state, no Android context.
 *
 * Returns the new local URI string, or null on failure.
 */
suspend fun pullPostMediaFromGitHub(
    contentHash: String,
    mimeType: String,
    slug: String,
    github: GitHubClient,
    mediaFileStore: PostMediaFileStore,
): String? {
    val ext = if (mimeType.startsWith("video")) "mp4" else "jpg"
    val repoPath = "static/$slug/$contentHash.$ext"
    val bytes = github.getFileBinary(repoPath) ?: return null
    return mediaFileStore.writeFromBytes(contentHash, ext, bytes)
}
