package de.perigon.companion.posts.data

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import de.perigon.companion.posts.domain.PostPublishState
import de.perigon.companion.posts.site.data.AssetDao
import de.perigon.companion.posts.site.data.AssetEntity

@Database(
    entities = [PostEntity::class, PostMediaEntity::class, AssetEntity::class],
    version = 1,
    exportSchema = true,
)
@TypeConverters(PostPublishState.Converter::class)
abstract class PostsDatabase : RoomDatabase() {
    abstract fun postDao(): PostDao
    abstract fun postMediaDao(): PostMediaDao
    abstract fun assetDao(): AssetDao
}
