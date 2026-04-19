package de.perigon.companion.util.network

import android.util.Base64
import io.ktor.client.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.serialization.json.*

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
     * Commit multiple file additions and deletions in a single atomic commit.
     *
     * Flow:
     *   1. GET ref/heads/:branch → HEAD SHA
     *   2. GET commits/:sha → base tree SHA
     *   3. GET trees/:sha?recursive=1 → full tree
     *   4. POST blobs for each addition
     *   5. Filter tree: remove deletions, replace/add additions
     *   6. POST trees (full, no base_tree)
     *   7. POST commits (parent = old HEAD)
     *   8. PATCH ref/heads/:branch → update to new commit
     *
     * Returns the new commit SHA.
     */
    suspend fun commitTree(
        branch: String = "main",
        message: String,
        additions: List<TreeFile> = emptyList(),
        deletions: Set<String> = emptySet(),
        // New: prune any blob under this prefix not in keepFilenames
        stalePrefix: String? = null,
        keepFilenames: Set<String> = emptySet(),
    ): String {
        require(additions.isNotEmpty() || deletions.isNotEmpty()) {
            "commitTree requires at least one addition or deletion"
        }

        // 1. Get HEAD ref
        val headSha = getRefSha(branch)

        // 2. Get commit to find base tree
        val baseTreeSha = getCommitTreeSha(headSha)

        // 3. Get full recursive tree
        val existingEntries = getFullTree(baseTreeSha)

        // 4. Create blobs for additions
        val additionBlobs = additions.map { file ->
            val blobSha = createBlob(file.content)
            TreeEntry(
                path = file.path,
                mode = "100644",
                type = "blob",
                sha = blobSha,
            )
        }
        val additionPaths = additions.map { it.path }.toSet()

        // Derive stale deletions from the same tree snapshot
        val staleDeletions = if (stalePrefix != null) {
            existingEntries
                .filter { it.type == "blob" && it.path.startsWith(stalePrefix) }
                .map { it.path }
                .filter { it.substringAfterLast('/') !in keepFilenames }
                .toSet()
        } else emptySet()

        // Step 5: merge, combining explicit deletions + stale deletions
        val allDeletions = deletions + staleDeletions
        val mergedEntries = existingEntries
            .filter { it.type == "blob" }
            .filter { it.path !in allDeletions }
            .filter { it.path !in additionPaths }
            .plus(additionBlobs)

        // 6. Create new tree (full replacement — no base_tree)
        val newTreeSha = createTree(mergedEntries)

        // 7. Create commit
        val newCommitSha = createCommit(message, newTreeSha, parentSha = headSha)

        // 8. Update branch ref
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

    private suspend fun getFullTree(treeSha: String): List<TreeEntry> {
        val response = get("$base/repos/$owner/$repo/git/trees/$treeSha?recursive=1")
        val json = Json.parseToJsonElement(response).jsonObject
        val tree = json["tree"]?.jsonArray ?: return emptyList()
        return tree.map { node ->
            val obj = node.jsonObject
            TreeEntry(
                path = obj["path"]!!.jsonPrimitive.content,
                mode = obj["mode"]!!.jsonPrimitive.content,
                type = obj["type"]!!.jsonPrimitive.content,
                sha = obj["sha"]!!.jsonPrimitive.content,
            )
        }
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

    private suspend fun createTree(entries: List<TreeEntry>): String {
        val body = buildJsonObject {
            putJsonArray("tree") {
                for (entry in entries) {
                    addJsonObject {
                        put("path", entry.path)
                        put("mode", entry.mode)
                        put("type", entry.type)
                        put("sha", entry.sha)
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