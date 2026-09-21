package de.perigon.companion.posts.network

import de.perigon.companion.posts.data.PostsPrefs
import de.perigon.companion.posts.data.PostsSecrets
import io.ktor.client.HttpClient
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The configured GitHub client, or null when token/owner/repo is missing.
 * Replaces seven private copies of the same null-check construction.
 */
@Singleton
class GitHubProvider @Inject constructor(
    private val http: HttpClient,
    private val prefs: PostsPrefs,
    private val secrets: PostsSecrets,
) {
    fun client(): GitHubClient? {
        val token = secrets.githubToken() ?: return null
        val owner = prefs.githubOwner() ?: return null
        val repo = prefs.githubRepo() ?: return null
        return GitHubClient(http, token, owner, repo)
    }
}
