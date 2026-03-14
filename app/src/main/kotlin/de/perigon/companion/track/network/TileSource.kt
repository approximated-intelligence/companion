package de.perigon.companion.track.network

import android.content.ContentResolver
import android.database.sqlite.SQLiteDatabase
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import java.io.File

class TileSource private constructor(
    private val db: SQLiteDatabase,
    private val pfd: android.os.ParcelFileDescriptor?, // keep open while db is open
    val minZoom: Int,
    val maxZoom: Int,
) : AutoCloseable {

    companion object {
        fun open(resolver: ContentResolver, uri: Uri): TileSource {
            val pfd = resolver.openFileDescriptor(uri, "r")
                ?: throw IllegalStateException("Cannot open tile source URI")
            val path = "/proc/self/fd/${pfd.fd}"
            val db = SQLiteDatabase.openDatabase(
                path, null, SQLiteDatabase.OPEN_READONLY)
            val (minZ, maxZ) = readZoomRange(db)
            return TileSource(db, pfd, minZ, maxZ)
        }

        private fun readZoomRange(db: SQLiteDatabase): Pair<Int, Int> {
            db.rawQuery("SELECT minzoom, maxzoom FROM info LIMIT 1", null).use { c ->
                if (c.moveToFirst()) {
                    val osmandMin = c.getInt(0)
                    val osmandMax = c.getInt(1)
                    return (17 - osmandMax) to (17 - osmandMin)
                }
            }
            return 0 to 17
        }
    }

    fun getTile(z: Int, x: Int, y: Int): Bitmap? {
        val osmandZ = 17 - z
        db.rawQuery(
            "SELECT image FROM tiles WHERE z = ? AND x = ? AND y = ? AND s = 0 LIMIT 1",
            arrayOf(osmandZ.toString(), x.toString(), y.toString()),
        ).use { c ->
            if (!c.moveToFirst()) return null
            val blob = c.getBlob(0) ?: return null
            return BitmapFactory.decodeByteArray(blob, 0, blob.size)
        }
    }

    override fun close() {
        db.close()
        pfd?.close()
    }
}
