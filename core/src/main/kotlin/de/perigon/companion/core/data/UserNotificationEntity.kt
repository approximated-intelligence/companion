package de.perigon.companion.core.data

import androidx.room.*
import kotlinx.coroutines.flow.Flow

enum class NotificationType {
    ERROR, INFO, SUCCESS;

    class Converter {
        @TypeConverter
        fun fromEnum(value: NotificationType): String = value.name
        @TypeConverter
        fun toEnum(value: String): NotificationType = valueOf(value)
    }
}

@Entity(
    tableName = "user_notifications",
    indices = [Index("readAt"), Index("createdAt")],
)
data class UserNotificationEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val message: String,
    val type: NotificationType = NotificationType.INFO,
    val source: String = "",
    val relatedPostId: Long? = null,
    val detail: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val readAt: Long? = null,
)

@Dao
interface UserNotificationDao {

    @Query("SELECT * FROM user_notifications WHERE readAt IS NULL ORDER BY createdAt DESC")
    fun observeUnread(): Flow<List<UserNotificationEntity>>

    @Query("SELECT COUNT(*) FROM user_notifications WHERE readAt IS NULL")
    fun countUnread(): Flow<Int>

    @Query("SELECT * FROM user_notifications ORDER BY createdAt DESC LIMIT :limit")
    fun observeRecent(limit: Int = 50): Flow<List<UserNotificationEntity>>

    @Insert
    suspend fun insert(notification: UserNotificationEntity): Long

    @Query("UPDATE user_notifications SET readAt = :now WHERE id = :id AND readAt IS NULL")
    suspend fun markRead(id: Long, now: Long = System.currentTimeMillis())

    @Query("UPDATE user_notifications SET readAt = :now WHERE readAt IS NULL")
    suspend fun markAllRead(now: Long = System.currentTimeMillis())

    @Query("DELETE FROM user_notifications WHERE createdAt < :before")
    suspend fun deleteOlderThan(before: Long)

    @Query("DELETE FROM user_notifications")
    suspend fun deleteAll()
}

/**
 * Convenience for workers to insert notifications without injecting the DAO directly.
 * Workers call this from any coroutine context.
 */
object UserNotifications {

    suspend fun error(dao: UserNotificationDao, source: String, message: String, detail: String? = null, relatedPostId: Long? = null) {
        dao.insert(UserNotificationEntity(
            message = message,
            type = NotificationType.ERROR,
            source = source,
            detail = detail,
            relatedPostId = relatedPostId,
        ))
    }

    suspend fun success(dao: UserNotificationDao, source: String, message: String, detail: String? = null) {
        dao.insert(UserNotificationEntity(
            message = message,
            type = NotificationType.SUCCESS,
            source = source,
            detail = detail,
        ))
    }

    suspend fun info(dao: UserNotificationDao, source: String, message: String, detail: String? = null) {
        dao.insert(UserNotificationEntity(
            message = message,
            type = NotificationType.INFO,
            source = source,
            detail = detail,
        ))
    }
}
