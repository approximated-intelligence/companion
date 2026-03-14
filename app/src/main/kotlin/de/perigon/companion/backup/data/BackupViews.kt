package de.perigon.companion.backup.data

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@DatabaseView(
    viewName = "backup_records_view",
    value = "SELECT sha256 FROM backup_files WHERE status = 'CONFIRMED'",
)
data class BackupRecordView(val sha256: String)

@Dao
interface BackupRecordViewDao {
    @Query("SELECT sha256 FROM backup_records_view WHERE sha256 = :sha256 LIMIT 1")
    suspend fun findSha256(sha256: String): String?

    suspend fun isConfirmed(sha256: String): Boolean = findSha256(sha256) != null
}

@DatabaseView(
    viewName = "backup_issues_view",
    value = "SELECT id, path, mtime, issueDetail, updatedAt FROM backup_files WHERE status = 'ISSUE'",
)
data class BackupIssueView(
    val id: Long,
    val path: String,
    val mtime: Long,
    val issueDetail: String?,
    val updatedAt: Long,
)

@Dao
interface BackupIssueViewDao {
    @Query("SELECT * FROM backup_issues_view ORDER BY updatedAt DESC")
    fun observeAll(): Flow<List<BackupIssueView>>

    @Query("SELECT COUNT(*) FROM backup_issues_view")
    fun count(): Flow<Int>
}
