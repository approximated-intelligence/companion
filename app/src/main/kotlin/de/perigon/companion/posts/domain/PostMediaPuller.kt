package de.perigon.companion.posts.domain

import de.perigon.companion.posts.data.PostMediaFileStore
import de.perigon.companion.util.network.GitHubClient

/**
 * Pulls a post media file from GitHub into the local PostMedia store.
 * Composes [GitHubClient.getFileBinary] with [PostMediaFileStore] write
 * operations. Pure orchestration — no state, no Android context.
 *
 * Server files are named by [processedHash] when available; the
 * [contentHash] location covers repos not yet renamed by the media
 * migration. The local copy is written under whichever hash the file was
 * found by, keeping local and server names mirrored.
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
    val ext = if (mimeType.startsWith("video")) "mp4" else "jpg"

    processedHash?.let { hash ->
        github.getFileBinary("static/$slug/$hash.$ext")?.let { bytes ->
            return mediaFileStore.writeFromBytes(hash, ext, bytes)
        }
    }

    // TODO(media-migration): remove fallback after all repos are migrated.
    val bytes = github.getFileBinary("static/$slug/$contentHash.$ext") ?: return null
    return mediaFileStore.writeFromBytes(contentHash, ext, bytes)
}
