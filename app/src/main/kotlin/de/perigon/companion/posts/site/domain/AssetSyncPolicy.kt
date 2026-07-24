package de.perigon.companion.posts.site.domain

import de.perigon.companion.posts.site.data.AssetEntity
import de.perigon.companion.posts.site.data.AssetSyncState

// Pure sync-action gating for a single asset row. Extracted from
// AssetListScreen (a Composable file) -- this logic has nothing to do with
// rendering; it is boolean conditions on AssetEntity/AssetSyncState only.

fun canPull(asset: AssetEntity): Boolean {
    if (asset.serverSha.isEmpty()) return false
    return asset.syncState in listOf(
        AssetSyncState.SERVER_AHEAD,
        AssetSyncState.SERVER_ONLY,
        AssetSyncState.CONFLICT,
    ) || (!asset.isOnDisk && asset.content.isEmpty())
}

fun canDiff(asset: AssetEntity): Boolean {
    if (asset.isOnDisk) return false
    if (asset.content.isEmpty()) return false
    return asset.syncState in listOf(
        AssetSyncState.LOCAL_AHEAD,
        AssetSyncState.SERVER_AHEAD,
        AssetSyncState.CONFLICT,
    )
}

fun canEdit(asset: AssetEntity): Boolean = !asset.isOnDisk

fun canPush(asset: AssetEntity): Boolean {
    if (asset.isOnDisk) {
        return asset.syncState in listOf(
            AssetSyncState.LOCAL_AHEAD,
            AssetSyncState.LOCAL_ONLY,
            AssetSyncState.CONFLICT,
        )
    }
    return when (asset.syncState) {
        AssetSyncState.LOCAL_AHEAD,
        AssetSyncState.LOCAL_ONLY -> asset.content.isNotEmpty()
        AssetSyncState.CONFLICT -> asset.content.isNotEmpty()
        else -> false
    }
}
