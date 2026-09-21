package de.perigon.companion.core.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import de.perigon.companion.core.data.NotificationType
import de.perigon.companion.core.data.UserNotificationDao
import de.perigon.companion.core.data.UserNotificationEntity

/**
 * Core's own tables only: the user-facing notification ledger every feature
 * writes to. Each feature module owns its own database; nothing here may
 * reference a feature type.
 */
@Database(
    entities = [UserNotificationEntity::class],
    version = 1,
    exportSchema = true,
)
@TypeConverters(NotificationType.Converter::class)
abstract class CoreDatabase : RoomDatabase() {
    abstract fun userNotificationDao(): UserNotificationDao
}
