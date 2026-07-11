package de.perigon.companion.util.network

import android.util.Base64
import android.util.Log
import io.ktor.client.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.serialization.json.*

private const val TAG = "GitHubClient"

data class TreeFile(val path: String, val content: ByteArray)

/**
 * Minimal GitHub Contents API + Git Trees API client.
 *
 * Single-file operations (putFile, deleteFile) for asset sync.
 * Atomic tree commits (commitTree) for post publish/unpublish.
 */
class GitHubClient(
    private val http:  HttpClient,
    private val token: String,
    private val owner: String,
    private val repo:  String,
) {
    private val base = "https://api.github.com"

    // ---- Atomic tree commit ----

    /**
     * Commit file additions and deletions in a single atomic commit, as a
     * DELTA against the current tree (base_tree + changed entries only).
     *
     * Never rewrites the whole tree: unrelated blobs, submodules (gitlink
     * entries) and anything past a truncated tree listing are untouched by
     * construction.
     *
     * [stalePrefix]: additionally delete every existing blob under this
     * prefix whose filename is not in [keepFilenames] (stale media pruning).
     *
     * Deletions are resolved against the current tree; paths not present are
     * silently skipped (GitHub 422s on deleting unknown paths). If nothing
     * remains to add or delete, no commit is created and the current HEAD
     * sha is returned.
     *
     * Flow:
     *   1. GET ref/heads/:branch → HEAD sha
     *   2. GET commits/:sha → base tree sha
     *   3. GET trees/:sha?recursive=1 → tree listing (only if deletions or
     *      stalePrefix require resolving paths)
     *   4. POST blobs for each addition
     *   5. POST trees with base_tree + delta entries (sha null = delete)
     *   6. POST commits (parent = old HEAD)
     *   7. PATCH ref/heads/:branch
     *
     * Returns the new commit sha (or HEAD on no-op).
     */
    suspend fun commitTree(
        branch: String = "main",
        message: String,
        additions: List<TreeFile> = emptyList(),
        deletions: Set<String> = emptySet(),
        stalePrefix: String? = null,
        keepFilenames: Set<String> = emptySet(),
    ): String {
        require(additions.isNotEmpty() || deletions.isNotEmpty() || stalePrefix != null) {
            "commitTree requires at least one addition, deletion, or a stalePrefix"
        }

        // 1. + 2. Resolve HEAD and its tree
        val headSha = getRefSha(branch)
        val baseTreeSha = getCommitTreeSha(headSha)

        // 3. Fetch the tree listing only when deletions must be resolved
        val additionPaths = additions.map { it.path }.toSet()
        val resolvedDeletions: Set<String> =
            if (deletions.isNotEmpty() || stalePrefix != null) {
                val (entries, truncated) = getFullTree(baseTreeSha)
                if (truncated) {
                    // Additions are unaffected; deletions can only be missed,
                    // never wrongly applied — log and proceed.
                    Log.w(TAG, "tree listing truncated by GitHub; stale pruning may be incomplete")
                }
                val existingBlobs = entries
                    .asSequence()
                    .filter { it.type == "blob" }
                    .map { it.path }
                    .toSet()

                val stale = if (stalePrefix != null) {
                    existingBlobs
                        .filter { it.startsWith(stalePrefix) }
                        .filter { it.substringAfterLast('/') !in keepFilenames }
                        .toSet()
                } else emptySet()

                (deletions + stale)
                    .filter { it in existingBlobs }   // unknown path → 422; skip
                    .filter { it !in additionPaths }  // re-added in this commit → keep
                    .toSet()
            } else emptySet()

        if (additions.isEmpty() && resolvedDeletions.isEmpty()) {
            return headSha // nothing to do; no empty commit
        }

        // 4. Create blobs for additions
        val additionEntries = additions.map { file ->
            TreeEntry(
                path = file.path,
                mode = "100644",
                type = "blob",
                sha = createBlob(file.content),
            )
        }

        // 5. Delta tree: base_tree + additions + deletions (sha = null)
        val newTreeSha = createDeltaTree(baseTreeSha, additionEntries, resolvedDeletions)

        // 6. + 7. Commit and advance the branch
        val newCommitSha = createCommit(message, newTreeSha, parentSha = headSha)
        updateRef(branch, newCommitSha)

        return newCommitSha
    }

    // ---- Git Trees API internals ----

    private suspend fun getRefSha(branch: String): String {
        val response = get("$base/repos/$owner/$repo/git/ref/heads/$branch")
        val json = Json.parseToJsonElement(response).jsonObject
        return json["object"]!!.jsonObject["sha"]!!.jsonPrimitive.content
    }

    private suspend fun getCommitTreeSha(commitSha: String): String {
        val response = get("$base/repos/$owner/$repo/git/commits/$commitSha")
        val json = Json.parseToJsonElement(response).jsonObject
        return json["tree"]!!.jsonObject["sha"]!!.jsonPrimitive.content
    }

    private suspend fun getFullTree(treeSha: String): Pair<List<TreeEntry>, Boolean> {
        val response = get("$base/repos/$owner/$repo/git/trees/$treeSha?recursive=1")
        val json = Json.parseToJsonElement(response).jsonObject
        val truncated = json["truncated"]?.jsonPrimitive?.booleanOrNull ?: false
        val tree = json["tree"]?.jsonArray ?: return emptyList<TreeEntry>() to truncated
        val entries = tree.map { node ->
            val obj = node.jsonObject
            TreeEntry(
                path = obj["path"]!!.jsonPrimitive.content,
                mode = obj["mode"]!!.jsonPrimitive.content,
                type = obj["type"]!!.jsonPrimitive.content,
                sha = obj["sha"]!!.jsonPrimitive.content,
            )
        }
        return entries to truncated
    }

    private suspend fun createBlob(content: ByteArray): String {
        val body = buildJsonObject {
            put("content", Base64.encodeToString(content, Base64.NO_WRAP))
            put("encoding", "base64")
        }.toString()

        val response = post("$base/repos/$owner/$repo/git/blobs", body)
        val json = Json.parseToJsonElement(response).jsonObject
        return json["sha"]!!.jsonPrimitive.content
    }

    private suspend fun createDeltaTree(
        baseTreeSha: String,
        additions: List<TreeEntry>,
        deletions: Set<String>,
    ): String {
        val body = buildJsonObject {
            put("base_tree", baseTreeSha)
            putJsonArray("tree") {
                for (entry in additions) {
                    addJsonObject {
                        put("path", entry.path)
                        put("mode", entry.mode)
                        put("type", entry.type)
                        put("sha", entry.sha)
                    }
                }
                for (path in deletions) {
                    addJsonObject {
                        put("path", path)
                        put("mode", "100644")
                        put("type", "blob")
                        put("sha", JsonNull) // null sha = delete from base_tree
                    }
                }
            }
        }.toString()

        val response = post("$base/repos/$owner/$repo/git/trees", body)
        val json = Json.parseToJsonElement(response).jsonObject
        return json["sha"]!!.jsonPrimitive.content
    }

    private suspend fun createCommit(
        message: String,
        treeSha: String,
        parentSha: String,
    ): String {
        val body = buildJsonObject {
            put("message", message)
            put("tree", treeSha)
            putJsonArray("parents") { add(parentSha) }
        }.toString()

        val response = post("$base/repos/$owner/$repo/git/commits", body)
        val json = Json.parseToJsonElement(response).jsonObject
        return json["sha"]!!.jsonPrimitive.content
    }

    private suspend fun updateRef(branch: String, commitSha: String) {
        val body = buildJsonObject {
            put("sha", commitSha)
        }.toString()

        val response = http.patch("$base/repos/$owner/$repo/git/refs/heads/$branch") {
            header(HttpHeaders.Authorization, "Bearer $token")
            header(HttpHeaders.Accept, "application/vnd.github+json")
            header("X-GitHub-Api-Version", "2022-11-28")
            contentType(ContentType.Application.Json)
            setBody(body)
        }
        check(response.status.isSuccess()) {
            "GitHub PATCH ref/heads/$branch failed: ${response.status} - ${response.bodyAsText()}"
        }
    }

    private data class TreeEntry(
        val path: String,
        val mode: String,
        val type: String,
        val sha: String,
    )

    // ---- Single-file Contents API (assets) ----

    suspend fun putFile(
        path:    String,
        content: ByteArray,
        message: String,
        sha:     String? = null,
    ) {
        val body = buildJsonObject {
            put("message", message)
            put("content", Base64.encodeToString(content, Base64.NO_WRAP))
            if (sha != null) put("sha", sha)
        }.toString()

        val response = http.put("$base/repos/$owner/$repo/contents/${path.trimStart('/')}") {
            header(HttpHeaders.Authorization, "Bearer $token")
            header(HttpHeaders.Accept, "application/vnd.github+json")
            header("X-GitHub-Api-Version", "2022-11-28")
            contentType(ContentType.Application.Json)
            setBody(body)
        }
        check(response.status.isSuccess()) {
            "GitHub PUT $path failed: ${response.status} - ${response.bodyAsText()}"
        }
    }

    suspend fun getFileSha(path: String): String? {
        val response = http.get("$base/repos/$owner/$repo/contents/${path.trimStart('/')}") {
            header(HttpHeaders.Authorization, "Bearer $token")
            header(HttpHeaders.Accept, "application/vnd.github+json")
            header("X-GitHub-Api-Version", "2022-11-28")
        }
        if (response.status == HttpStatusCode.NotFound) return null
        check(response.status.isSuccess()) {
            "GitHub GET $path failed: ${response.status}"
        }
        val json = Json.parseToJsonElement(response.bodyAsText()).jsonObject
        return json["sha"]?.jsonPrimitive?.content
    }

    suspend fun getFileContent(path: String): String? {
        val response = http.get("$base/repos/$owner/$repo/contents/${path.trimStart('/')}") {
            header(HttpHeaders.Authorization, "Bearer $token")
            header(HttpHeaders.Accept, "application/vnd.github+json")
            header("X-GitHub-Api-Version", "2022-11-28")
        }
        if (response.status == HttpStatusCode.NotFound) return null
        check(response.status.isSuccess()) {
            "GitHub GET $path failed: ${response.status}"
        }
        val json    = Json.parseToJsonElement(response.bodyAsText()).jsonObject
        val encoded = json["content"]?.jsonPrimitive?.content ?: return null
        val clean   = encoded.replace("\n", "").replace("\r", "")
        return String(Base64.decode(clean, Base64.DEFAULT), Charsets.UTF_8)
    }

    suspend fun getFileBinary(path: String): ByteArray? {
        val response = http.get("$base/repos/$owner/$repo/contents/${path.trimStart('/')}") {
            header(HttpHeaders.Authorization, "Bearer $token")
            header(HttpHeaders.Accept, "application/vnd.github+json")
            header("X-GitHub-Api-Version", "2022-11-28")
        }
        if (response.status == HttpStatusCode.NotFound) return null
        check(response.status.isSuccess()) {
            "GitHub GET $path failed: ${response.status}"
        }
        val json    = Json.parseToJsonElement(response.bodyAsText()).jsonObject
        val encoded = json["content"]?.jsonPrimitive?.content ?: return null
        val clean   = encoded.replace("\n", "").replace("\r", "")
        return Base64.decode(clean, Base64.DEFAULT)
    }

    suspend fun listDir(path: String): List<FileEntry> {
        val response = http.get("$base/repos/$owner/$repo/contents/${path.trimStart('/')}") {
            header(HttpHeaders.Authorization, "Bearer $token")
            header(HttpHeaders.Accept, "application/vnd.github+json")
            header("X-GitHub-Api-Version", "2022-11-28")
        }
        if (response.status == HttpStatusCode.NotFound) return emptyList()
        check(response.status.isSuccess()) {
            "GitHub GET dir $path failed: ${response.status}"
        }
        val json = Json.parseToJsonElement(response.bodyAsText())
        if (json !is JsonArray) return emptyList()
        return json.mapNotNull { entry ->
            val obj  = entry.jsonObject
            val type = obj["type"]?.jsonPrimitive?.content
            if (type != "file") return@mapNotNull null
            FileEntry(
                path = obj["path"]?.jsonPrimitive?.content ?: return@mapNotNull null,
                sha  = obj["sha"]?.jsonPrimitive?.content  ?: return@mapNotNull null,
            )
        }
    }

    suspend fun getTree(branch: String = "main"): List<FileEntry> {
        val response = http.get("$base/repos/$owner/$repo/git/trees/$branch?recursive=1") {
            header(HttpHeaders.Authorization, "Bearer $token")
            header(HttpHeaders.Accept, "application/vnd.github+json")
            header("X-GitHub-Api-Version", "2022-11-28")
        }
        if (response.status == HttpStatusCode.NotFound) return emptyList()
        check(response.status.isSuccess()) {
            "GitHub GET tree failed: ${response.status}"
        }
        val json = Json.parseToJsonElement(response.bodyAsText()).jsonObject
        if (json["truncated"]?.jsonPrimitive?.booleanOrNull == true) {
            Log.w(TAG, "getTree: listing truncated by GitHub; results incomplete")
        }
        val tree = json["tree"]?.jsonArray ?: return emptyList()
        return tree.mapNotNull { node ->
            val obj  = node.jsonObject
            val type = obj["type"]?.jsonPrimitive?.content
            if (type != "blob") return@mapNotNull null
            FileEntry(
                path = obj["path"]?.jsonPrimitive?.content ?: return@mapNotNull null,
                sha  = obj["sha"]?.jsonPrimitive?.content  ?: return@mapNotNull null,
            )
        }
    }

    suspend fun deleteFile(path: String, sha: String, message: String) {
        val body = buildJsonObject {
            put("message", message)
            put("sha", sha)
        }.toString()

        val response = http.request("$base/repos/$owner/$repo/contents/${path.trimStart('/')}") {
            method = HttpMethod.Delete
            header(HttpHeaders.Authorization, "Bearer $token")
            header(HttpHeaders.Accept, "application/vnd.github+json")
            header("X-GitHub-Api-Version", "2022-11-28")
            contentType(ContentType.Application.Json)
            setBody(body)
        }
        check(response.status.isSuccess()) {
            "GitHub DELETE $path failed: ${response.status} - ${response.bodyAsText()}"
        }
    }

    // ---- Shared HTTP helpers ----

    private suspend fun get(url: String): String {
        val response = http.get(url) {
            header(HttpHeaders.Authorization, "Bearer $token")
            header(HttpHeaders.Accept, "application/vnd.github+json")
            header("X-GitHub-Api-Version", "2022-11-28")
        }
        check(response.status.isSuccess()) {
            "GitHub GET $url failed: ${response.status} - ${response.bodyAsText()}"
        }
        return response.bodyAsText()
    }

    private suspend fun post(url: String, body: String): String {
        val response = http.post(url) {
            header(HttpHeaders.Authorization, "Bearer $token")
            header(HttpHeaders.Accept, "application/vnd.github+json")
            header("X-GitHub-Api-Version", "2022-11-28")
            contentType(ContentType.Application.Json)
            setBody(body)
        }
        check(response.status.isSuccess()) {
            "GitHub POST $url failed: ${response.status} - ${response.bodyAsText()}"
        }
        return response.bodyAsText()
    }

    data class FileEntry(val path: String, val sha: String)
}
