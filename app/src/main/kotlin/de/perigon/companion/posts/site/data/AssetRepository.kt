package de.perigon.companion.posts.site.data

import android.content.Context
import de.perigon.companion.util.network.GitHubClient
import de.perigon.companion.util.gitBlobSha1
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

data class BundledAsset(
    val assetPath: String,
    val isBinary: Boolean,
)

/**
 * Single map of repo path → bundled asset info.
 * Repo paths with leading _ are stored without the underscore prefix
 * because Android's asset pipeline ignores _-prefixed files.
 */
val BUNDLED_ASSETS: Map<String, BundledAsset> = mapOf(
    // Files where the repo path has a leading _ that Android ignores
    "_config.yml"                to BundledAsset("config.yml", false),
    "_includes/carousel.html"   to BundledAsset("includes/carousel.html", false),
    "_layouts/post.html"        to BundledAsset("layouts/post.html", false),
    "_layouts/home.html"        to BundledAsset("layouts/home.html", false),
    // Files where repo path = asset path
    "index.md"                  to BundledAsset("index.md", false),
    "tags.html"                 to BundledAsset("tags.html", false),
    "assets/js/carousel.js"     to BundledAsset("assets/js/carousel.js", false),
    "assets/main.scss"          to BundledAsset("assets/main.scss", false),
    // Binary font files
    "static/fonts/lmmono10-italic.otf"      to BundledAsset("static/fonts/lmmono10-italic.otf", true),
    "static/fonts/lmmono10-italic.ttf"      to BundledAsset("static/fonts/lmmono10-italic.ttf", true),
    "static/fonts/lmmono10-italic.woff2"    to BundledAsset("static/fonts/lmmono10-italic.woff2", true),
    "static/fonts/lmmono10-regular.otf"     to BundledAsset("static/fonts/lmmono10-regular.otf", true),
    "static/fonts/lmmono10-regular.ttf"     to BundledAsset("static/fonts/lmmono10-regular.ttf", true),
    "static/fonts/lmmono10-regular.woff2"   to BundledAsset("static/fonts/lmmono10-regular.woff2", true),
)

private val EXCLUDED_PREFIXES = listOf("_posts/")

private val RESEED_PROTECTED_PATHS = setOf("CNAME")

@Singleton
class AssetRepository @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val assetDao: AssetDao,
) {

    private fun onDiskDir(): File {
        val dir = File(context.getExternalFilesDir(null), "site_assets")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    private fun onDiskFile(path: String): File = File(onDiskDir(), path)

    suspend fun seedDefaultsIfEmpty() = withContext(Dispatchers.IO) {
        if (assetDao.countTextAssets() == 0) {
            seedFromBundled()
        }
        seedBinariesFromBundled()
    }

    suspend fun reseedFromBundled() = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        for ((repoPath, bundled) in BUNDLED_ASSETS) {
            if (bundled.isBinary) continue
            if (repoPath in RESEED_PROTECTED_PATHS) continue
            val content = readBundledText(bundled.assetPath) ?: continue
            val hash = gitBlobSha1(content)
            val existing = assetDao.getByPath(repoPath)
            if (existing != null) {
                val syncState = if (existing.serverSha.isNotEmpty() && hash != existing.serverSha) {
                    AssetSyncState.LOCAL_AHEAD
                } else if (existing.serverSha.isNotEmpty() && hash == existing.serverSha) {
                    AssetSyncState.IN_SYNC
                } else {
                    AssetSyncState.LOCAL_ONLY
                }
                assetDao.updateContent(existing.id, content, hash, syncState, now)
            } else {
                assetDao.upsert(AssetEntity(
                    path      = repoPath,
                    content   = content,
                    localHash = hash,
                    syncState = AssetSyncState.LOCAL_ONLY,
                    isOnDisk  = false,
                    updatedAt = now,
                ))
            }
        }
        seedBinariesFromBundled()
    }

    private suspend fun seedFromBundled() {
        val now = System.currentTimeMillis()
        val entities = BUNDLED_ASSETS
            .filter { !it.value.isBinary }
            .mapNotNull { (repoPath, bundled) ->
                val content = readBundledText(bundled.assetPath) ?: return@mapNotNull null
                val hash = gitBlobSha1(content)
                AssetEntity(
                    path      = repoPath,
                    content   = content,
                    localHash = hash,
                    syncState = AssetSyncState.LOCAL_ONLY,
                    isOnDisk  = false,
                    updatedAt = now,
                )
            }
        assetDao.insertAll(entities)
    }

    private suspend fun seedBinariesFromBundled() {
        val now = System.currentTimeMillis()
        for ((repoPath, bundled) in BUNDLED_ASSETS) {
            if (!bundled.isBinary) continue
            val existing = assetDao.getByPath(repoPath)
            val bundledBytes = readBundledBinary(bundled.assetPath) ?: continue
            val diskFile = onDiskFile(repoPath)
            diskFile.parentFile?.mkdirs()
            if (!diskFile.exists()) {
                diskFile.writeBytes(bundledBytes)
            }
            val hash = gitBlobSha1(diskFile.readBytes())
            if (existing != null) {
                assetDao.updateLocalHash(existing.id, hash, existing.syncState, now)
            } else {
                assetDao.upsert(AssetEntity(
                    path      = repoPath,
                    localHash = hash,
                    syncState = AssetSyncState.LOCAL_ONLY,
                    isOnDisk  = true,
                    updatedAt = now,
                ))
            }
        }
    }

    suspend fun fetchServerShas(github: GitHubClient) = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        val allFiles = github.getTree()
        val serverFiles = allFiles
            .filter { entry -> EXCLUDED_PREFIXES.none { entry.path.startsWith(it) } }
            .associate { it.path to it.sha }

        val allPaths = (assetDao.getAllPaths() + serverFiles.keys).toSet()

        for (path in allPaths) {
            if (EXCLUDED_PREFIXES.any { path.startsWith(it) }) continue
            // Skip post static media - managed by PostMediaFileStore
            if (path.startsWith("static/") && !path.startsWith("static/fonts/")) continue

            val local  = assetDao.getByPath(path)
            val srvSha = serverFiles[path]

            when {
                local == null && srvSha != null -> {
                    val isBinary = BUNDLED_ASSETS[path]?.isBinary
                        ?: path.startsWith("static/fonts/")
                    assetDao.upsert(AssetEntity(
                        path      = path,
                        content   = "",
                        serverSha = srvSha,
                        syncState = AssetSyncState.SERVER_ONLY,
                        isOnDisk  = isBinary,
                        updatedAt = now,
                    ))
                }
                local != null && srvSha == null -> {
                    if (local.serverSha.isNotEmpty()) {
                        assetDao.updateServerSha(local.id, "", AssetSyncState.LOCAL_ONLY, now)
                    }
                }
                local != null && srvSha != null -> {
                    val state = computeSyncState(local.localHash, local.serverSha, srvSha)
                    assetDao.updateServerSha(local.id, srvSha, state, now)
                }
            }
        }
    }

    suspend fun pullFromServer(assetId: Long, github: GitHubClient) = withContext(Dispatchers.IO) {
        val asset = assetDao.getById(assetId) ?: return@withContext
        if (asset.isOnDisk) {
            pullBinaryFromServer(asset, github)
        } else {
            pullTextFromServer(asset, github)
        }
    }

    private suspend fun pullTextFromServer(asset: AssetEntity, github: GitHubClient) {
        val content = github.getFileContent(asset.path) ?: return
        val hash = gitBlobSha1(content)
        val sha = github.getFileSha(asset.path) ?: asset.serverSha
        assetDao.updateSynced(
            id        = asset.id,
            content   = content,
            localHash = hash,
            serverSha = sha,
        )
    }

    private suspend fun pullBinaryFromServer(asset: AssetEntity, github: GitHubClient) {
        val bytes = github.getFileBinary(asset.path) ?: return
        val diskFile = onDiskFile(asset.path)
        diskFile.parentFile?.mkdirs()
        diskFile.writeBytes(bytes)
        val hash = gitBlobSha1(bytes)
        val sha = github.getFileSha(asset.path) ?: asset.serverSha
        assetDao.updateSyncedOnDisk(
            id        = asset.id,
            localHash = hash,
            serverSha = sha,
        )
    }

    suspend fun pushBinaryToServer(assetId: Long, github: GitHubClient): String? = withContext(Dispatchers.IO) {
        val asset = assetDao.getById(assetId) ?: return@withContext null
        if (!asset.isOnDisk) return@withContext null
        val diskFile = onDiskFile(asset.path)
        if (!diskFile.exists()) return@withContext null
        val bytes = diskFile.readBytes()
        val existingSha = asset.serverSha.takeIf { it.isNotEmpty() }
            ?: github.getFileSha(asset.path)
        github.putFile(
            path    = asset.path,
            content = bytes,
            message = "Update ${asset.path}",
            sha     = existingSha,
        )
        val newSha = github.getFileSha(asset.path) ?: ""
        val hash = gitBlobSha1(bytes)
        assetDao.updateSyncedOnDisk(assetId, hash, newSha)
        newSha
    }

    suspend fun fetchServerContent(assetId: Long, github: GitHubClient): String? =
        withContext(Dispatchers.IO) {
            val asset = assetDao.getById(assetId) ?: return@withContext null
            github.getFileContent(asset.path)
        }

    suspend fun markPushed(assetId: Long, newServerSha: String) {
        val asset = assetDao.getById(assetId) ?: return
        if (asset.isOnDisk) {
            assetDao.updateSyncedOnDisk(
                id        = assetId,
                localHash = asset.localHash,
                serverSha = newServerSha,
            )
        } else {
            assetDao.updateSynced(
                id        = assetId,
                content   = asset.content,
                localHash = asset.localHash,
                serverSha = newServerSha,
            )
        }
    }

    /**
     * Read the on-disk file bytes for a binary asset.
     * Used by GitHubFileWorker for push operations.
     */
    fun readOnDiskBytes(path: String): ByteArray? {
        val file = onDiskFile(path)
        return if (file.exists()) file.readBytes() else null
    }

    private fun readBundledText(assetPath: String): String? = runCatching {
        context.assets.open("defaults/$assetPath").bufferedReader().readText()
    }.getOrNull()

    private fun readBundledBinary(assetPath: String): ByteArray? = runCatching {
        context.assets.open("defaults/$assetPath").readBytes()
    }.getOrNull()
}

private fun computeSyncState(
    localHash: String,
    previousServerSha: String,
    currentServerSha: String,
): AssetSyncState = when {
    localHash == currentServerSha -> AssetSyncState.IN_SYNC
    previousServerSha == currentServerSha -> AssetSyncState.LOCAL_AHEAD
    localHash == previousServerSha -> AssetSyncState.SERVER_AHEAD
    else -> AssetSyncState.CONFLICT
}
