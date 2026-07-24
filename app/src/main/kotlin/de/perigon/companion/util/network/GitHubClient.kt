package de.perigon.companion.util.network

import android.util.Base64
import android.util.Log
import io.ktor.client.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.serialization.json.*

private const val TAG = "GitHubClient"

class TreeFile(val path: String, val content: ByteArray) {
    override fun equals(other: Any?): Boolean =
        other is TreeFile && path == other.path && content.contentEquals(other.content)

    override fun hashCode(): Int = 31 * path.hashCode() + content.contentHashCode()
}

/** A blob already uploaded via [GitHubClient.createBlob], referenced by sha. */
data class BlobAddition(val path: String, val sha: String)

/** Extract a required string field or fail with a diagnosable message. */
private fun JsonObject.requireString(key: String, context: String): String =
    this[key]?.jsonPrimitive?.content
        ?: error("GitHub response missing '$key' ($context)")

private fun JsonObject.requireObject(key: String, context: String): JsonObject =
    this[key]?.jsonObject
        ?: error("GitHub response missing '$key' object ($context)")

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
     * [additions] carry content (blobs are created here); [blobAdditions]
     * reference blobs already created via [createBlob].
     *
     * Deletion inputs, by strictness:
     *  - [deletions]: explicit paths. Paths absent from the repo are skipped
     *    (tolerant no-op), but ABSENCE MUST BE PROVEN: if GitHub truncates
     *    the tree listing, each unresolved path is checked individually via
     *    the Contents API rather than silently dropped — a missed explicit
     *    deletion would leave content live while the caller believes it gone.
     *  - [deleteWhere]: predicate over existing blob paths. Best-effort by
     *    nature (can only match what the listing shows); truncation is logged.
     *  - [stalePrefix]/[keepFilenames]: delete existing blobs under the prefix
     *    whose filename is not kept. Best-effort, same as deleteWhere.
     *
     * If nothing remains to add or delete, no commit is created and the
     * current HEAD sha is returned.
     *
     * Returns the new commit sha (or HEAD on no-op).
     */
    suspend fun commitTree(
        branch: String = "main",
        message: String,
        additions: List<TreeFile> = emptyList(),
        blobAdditions: List<BlobAddition> = emptyList(),
        deletions: Set<String> = emptySet(),
        deleteWhere: ((String) -> Boolean)? = null,
        stalePrefix: String? = null,
        keepFilenames: Set<String> = emptySet(),
    ): String {
        require(additions.isNotEmpty() || blobAdditions.isNotEmpty() ||
                deletions.isNotEmpty() || deleteWhere != null || stalePrefix != null) {
            "commitTree requires at least one addition, deletion, predicate, or a stalePrefix"
        }

        // Resolve HEAD and its tree
        val headSha = getRefSha(branch)
        val baseTreeSha = getCommitTreeSha(headSha)

        // Fetch the tree listing only when deletions must be resolved
        val additionPaths = (additions.map { it.path } + blobAdditions.map { it.path }).toSet()
        val resolvedDeletions: Set<String> =
            if (deletions.isNotEmpty() || deleteWhere != null || stalePrefix != null) {
                val (entries, truncated) = getFullTree(baseTreeSha)
                if (truncated) {
                    Log.w(TAG, "tree listing truncated by GitHub; predicate/prefix pruning may be incomplete")
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

                val predicated = if (deleteWhere != null) {
                    existingBlobs.filter(deleteWhere).toSet()
                } else emptySet()

                // Explicit deletions: resolve against the listing; when the
                // listing is truncated, prove presence/absence per path.
                val explicit = deletions.filter { path ->
                    when {
                        path in existingBlobs -> true
                        !truncated            -> false          // provably absent
                        else                  -> getFileSha(path) != null // truncated: ask directly
                    }
                }.toSet()

                (explicit + predicated + stale)
                    .filter { it !in additionPaths }  // re-added in this commit → keep
                    .toSet()
            } else emptySet()

        if (additions.isEmpty() && blobAdditions.isEmpty() && resolvedDeletions.isEmpty()) {
            return headSha // nothing to do; no empty commit
        }

        // Create blobs for content additions; merge with pre-created blobs
        val additionEntries =
            additions.map { file ->
                TreeEntry(path = file.path, mode = "100644", type = "blob", sha = createBlob(file.content))
            } + blobAdditions.map { blob ->
                TreeEntry(path = blob.path, mode = "100644", type = "blob", sha = blob.sha)
            }

        // Delta tree: base_tree + additions + deletions (sha = null)
        val newTreeSha = createDeltaTree(baseTreeSha, additionEntries, resolvedDeletions)

        // Commit and advance the branch
        val newCommitSha = createCommit(message, newTreeSha, parentSha = headSha)
        updateRef(branch, newCommitSha)

        return newCommitSha
    }

    /**
     * Upload one blob, returning its sha for later [commitTree] via
     * [BlobAddition]. Lets callers stream large files without holding all
     * content in memory until commit time.
     */
    suspend fun createBlob(content: ByteArray): String {
        val body = buildJsonObject {
            put("content", Base64.encodeToString(content, Base64.NO_WRAP))
            put("encoding", "base64")
        }.toString()

        val response = post("$base/repos/$owner/$repo/git/blobs", body)
        return Json.parseToJsonElement(response).jsonObject
            .requireString("sha", "createBlob")
    }

    // ---- Git Trees API internals ----

    private suspend fun getRefSha(branch: String): String {
        val response = get("$base/repos/$owner/$repo/git/ref/heads/$branch")
        return Json.parseToJsonElement(response).jsonObject
            .requireObject("object", "getRefSha $branch")
            .requireString("sha", "getRefSha $branch")
    }

    private suspend fun getCommitTreeSha(commitSha: String): String {
        val response = get("$base/repos/$owner/$repo/git/commits/$commitSha")
        return Json.parseToJsonElement(response).jsonObject
            .requireObject("tree", "getCommitTreeSha $commitSha")
            .requireString("sha", "getCommitTreeSha $commitSha")
    }

    private suspend fun getFullTree(treeSha: String): Pair<List<TreeEntry>, Boolean> {
        val response = get("$base/repos/$owner/$repo/git/trees/$treeSha?recursive=1")
        val json = Json.parseToJsonElement(response).jsonObject
        val truncated = json["truncated"]?.jsonPrimitive?.booleanOrNull ?: false
        val tree = json["tree"]?.jsonArray ?: return emptyList<TreeEntry>() to truncated
        val entries = tree.map { node ->
            val obj = node.jsonObject
            TreeEntry(
                path = obj.requireString("path", "tree entry"),
                mode = obj.requireString("mode", "tree entry"),
                type = obj.requireString("type", "tree entry"),
                sha = obj.requireString("sha", "tree entry"),
            )
        }
        return entries to truncated
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
        return Json.parseToJsonElement(response).jsonObject
            .requireString("sha", "createDeltaTree")
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
        return Json.parseToJsonElement(response).jsonObject
            .requireString("sha", "createCommit")
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

    /**
     * Recursive blob listing for a branch. Cached process-wide by the
     * branch's resolved commit sha: repeated syncs against an unchanged
     * branch cost one small ref lookup instead of a full tree transfer.
     */
    suspend fun getTree(branch: String = "main"): List<FileEntry> {
        val headSha = try {
            getRefSha(branch)
        } catch (_: Exception) {
            return emptyList() // branch/repo missing — match previous 404 behavior
        }

        val cacheKey = "$owner/$repo/$branch@$headSha"
        treeCache?.let { (key, entries) -> if (key == cacheKey) return entries }

        val (entries, truncated) = getFullTree(headSha.let { getCommitTreeSha(it) })
        if (truncated) {
            Log.w(TAG, "getTree: listing truncated by GitHub; results incomplete")
        }
        val files = entries
            .filter { it.type == "blob" }
            .map { FileEntry(it.path, it.sha) }
        treeCache = cacheKey to files
        return files
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

    companion object {
        // Process-wide single-slot tree cache: (owner/repo/branch@sha) → blobs.
        @Volatile
        private var treeCache: Pair<String, List<FileEntry>>? = null
    }
}
