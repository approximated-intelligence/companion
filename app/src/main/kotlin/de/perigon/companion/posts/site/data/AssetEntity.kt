package de.perigon.companion.posts.site.data

import androidx.room.*
import kotlinx.coroutines.flow.Flow

enum class AssetSyncState {
    IN_SYNC,
    LOCAL_AHEAD,
    SERVER_AHEAD,
    CONFLICT,
    LOCAL_ONLY,
    SERVER_ONLY,
}

@Entity(tableName = "assets", indices = [Index("path", unique = true)])
data class AssetEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val path: String,
    val content: String = "",
    val serverSha: String = "",
    val localHash: String = "",
    val syncState: AssetSyncState = AssetSyncState.LOCAL_ONLY,
    val isOnDisk: Boolean = false,
    val updatedAt: Long,
)

@Dao
interface AssetDao {
    @Query("SELECT * FROM assets ORDER BY path ASC")
    fun observeAll(): Flow<List<AssetEntity>>

    @Query("SELECT path FROM assets")
    suspend fun getAllPaths(): List<String>

    @Query("SELECT * FROM assets WHERE id = :id")
    suspend fun getById(id: Long): AssetEntity?

    @Query("SELECT * FROM assets WHERE path = :path")
    suspend fun getByPath(path: String): AssetEntity?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(assets: List<AssetEntity>): List<Long>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(asset: AssetEntity): Long

    @Query("UPDATE assets SET content = :content, localHash = :localHash, syncState = :syncState, updatedAt = :now WHERE id = :id")
    suspend fun updateContent(
        id: Long,
        content: String,
        localHash: String,
        syncState: AssetSyncState,
        now: Long = System.currentTimeMillis(),
    )

    @Query("UPDATE assets SET localHash = :localHash, syncState = :syncState, updatedAt = :now WHERE id = :id")
    suspend fun updateLocalHash(
        id: Long,
        localHash: String,
        syncState: AssetSyncState,
        now: Long = System.currentTimeMillis(),
    )

    @Query("UPDATE assets SET serverSha = :serverSha, syncState = :syncState, updatedAt = :now WHERE id = :id")
    suspend fun updateServerSha(
        id: Long,
        serverSha: String,
        syncState: AssetSyncState,
        now: Long = System.currentTimeMillis(),
    )

    @Query("UPDATE assets SET content = :content, localHash = :localHash, serverSha = :serverSha, syncState = :syncState, updatedAt = :now WHERE id = :id")
    suspend fun updateSynced(
        id: Long,
        content: String,
        localHash: String,
        serverSha: String,
        syncState: AssetSyncState = AssetSyncState.IN_SYNC,
        now: Long = System.currentTimeMillis(),
    )

    @Query("UPDATE assets SET localHash = :localHash, serverSha = :serverSha, syncState = :syncState, updatedAt = :now WHERE id = :id")
    suspend fun updateSyncedOnDisk(
        id: Long,
        localHash: String,
        serverSha: String,
        syncState: AssetSyncState = AssetSyncState.IN_SYNC,
        now: Long = System.currentTimeMillis(),
    )

    @Query("SELECT COUNT(*) FROM assets")
    suspend fun count(): Int

    @Query("SELECT COUNT(*) FROM assets WHERE isOnDisk = 0")
    suspend fun countTextAssets(): Int

    @Query("DELETE FROM assets")
    suspend fun deleteAll()
}
