package de.perigon.companion.core.db

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE track_points ADD COLUMN undulation REAL")
        db.execSQL("ALTER TABLE track_points ADD COLUMN speedMs REAL")
        db.execSQL("ALTER TABLE track_points ADD COLUMN bearing REAL")
    }
}

val MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS track_stats (trackId INTEGER NOT NULL PRIMARY KEY, durationMs INTEGER NOT NULL, recordingLengthMs INTEGER NOT NULL, distanceM REAL NOT NULL, timeMovingMs INTEGER NOT NULL, FOREIGN KEY (trackId) REFERENCES tracks(id) ON DELETE CASCADE)")
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_track_stats_trackId ON track_stats (trackId)")
        db.execSQL("CREATE TABLE IF NOT EXISTS current_track (id INTEGER NOT NULL PRIMARY KEY, trackId INTEGER NOT NULL, state TEXT NOT NULL, FOREIGN KEY (trackId) REFERENCES tracks(id) ON DELETE CASCADE)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_current_track_trackId ON current_track (trackId)")
    }
}

val MIGRATION_3_4 = object : Migration(3, 4) {
    override fun migrate(db: SupportSQLiteDatabase) {
        val cursor = db.query("PRAGMA table_info(track_points)")
        val hasProvider = generateSequence { if (cursor.moveToNext()) cursor else null }
            .any { it.getString(it.getColumnIndexOrThrow("name")) == "provider" }
        cursor.close()
        if (!hasProvider) {
            db.execSQL("ALTER TABLE track_points ADD COLUMN provider TEXT NOT NULL DEFAULT 'gps'")
        }
        db.execSQL("DROP TABLE IF EXISTS current_track")
        db.execSQL("CREATE TABLE current_track (id INTEGER NOT NULL PRIMARY KEY, trackId INTEGER NOT NULL, state TEXT NOT NULL, FOREIGN KEY (trackId) REFERENCES tracks(id) ON DELETE CASCADE)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_current_track_trackId ON current_track (trackId)")
    }
}

val MIGRATION_4_5 = object : Migration(4, 5) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS backup_file_locations (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, backupFileId INTEGER NOT NULL, sha256 TEXT NOT NULL, startPack INTEGER NOT NULL, startPart INTEGER NOT NULL, startPartOffset INTEGER NOT NULL, endPack INTEGER NOT NULL, endPart INTEGER NOT NULL, numParts INTEGER NOT NULL, createdAt INTEGER NOT NULL, updatedAt INTEGER NOT NULL, FOREIGN KEY (backupFileId) REFERENCES backup_files(id) ON DELETE CASCADE)")
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_backup_file_locations_backupFileId ON backup_file_locations (backupFileId)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_backup_file_locations_startPack ON backup_file_locations (startPack)")
        db.execSQL("INSERT INTO backup_file_locations (backupFileId, sha256, startPack, startPart, startPartOffset, endPack, endPart, numParts, createdAt, updatedAt) SELECT id, sha256, startPack, startPart, startPartOffset, endPack, 0, 0, updatedAt, updatedAt FROM backup_files WHERE status = 'CONFIRMED' AND sha256 != ''")
        db.execSQL("DROP TABLE IF EXISTS current_file")
        db.execSQL("CREATE TABLE IF NOT EXISTS backup_current_files (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, backupFileId INTEGER NOT NULL, sha256 TEXT NOT NULL, byteOffset INTEGER NOT NULL DEFAULT 0, startPack INTEGER NOT NULL, startPart INTEGER NOT NULL, startPartOffset INTEGER NOT NULL, endPack INTEGER NOT NULL DEFAULT 0, endPart INTEGER NOT NULL DEFAULT 0, FOREIGN KEY (backupFileId) REFERENCES backup_files(id) ON DELETE CASCADE)")
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_backup_current_files_backupFileId ON backup_current_files (backupFileId)")
        db.execSQL("CREATE TABLE IF NOT EXISTS backup_files_new (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, path TEXT NOT NULL, uri TEXT NOT NULL, mtime INTEGER NOT NULL, size INTEGER NOT NULL, createdAt INTEGER NOT NULL, updatedAt INTEGER NOT NULL)")
        db.execSQL("INSERT INTO backup_files_new (id, path, uri, mtime, size, createdAt, updatedAt) SELECT id, path, uri, mtime, size, createdAt, updatedAt FROM backup_files")
        db.execSQL("DROP TABLE backup_files")
        db.execSQL("ALTER TABLE backup_files_new RENAME TO backup_files")
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_backup_files_path_mtime_size ON backup_files (path, mtime, size)")
        db.execSQL("DROP VIEW IF EXISTS backup_records_view")
        db.execSQL("CREATE VIEW `backup_records_view` AS SELECT l.sha256 FROM backup_file_locations l")
        db.execSQL("DROP VIEW IF EXISTS backup_issues_view")
        db.execSQL("DROP VIEW IF EXISTS backup_restore_view")
        db.execSQL("CREATE VIEW `backup_restore_view` AS SELECT f.id, f.path, f.mtime, f.size, l.sha256, l.startPack, l.startPart, l.startPartOffset, l.endPack, l.endPart, l.numParts FROM backup_files f INNER JOIN backup_file_locations l ON l.backupFileId = f.id ORDER BY l.startPack, l.startPart")
        db.execSQL("DROP VIEW IF EXISTS backup_pending_view")
        db.execSQL("CREATE VIEW `backup_pending_view` AS SELECT f.id, f.path, f.mtime, f.size, f.uri FROM backup_files f LEFT JOIN backup_file_locations l ON l.backupFileId = f.id WHERE l.backupFileId IS NULL ORDER BY f.id ASC")
    }
}

val MIGRATION_5_6 = object : Migration(5, 6) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("DROP VIEW IF EXISTS backup_records_view")
        db.execSQL("DROP VIEW IF EXISTS backup_restore_view")
        db.execSQL("DROP VIEW IF EXISTS backup_pending_view")
        db.execSQL("DROP TABLE IF EXISTS backup_file_locations")
        db.execSQL("DROP TABLE IF EXISTS backup_current_files")
        db.execSQL("DROP TABLE IF EXISTS parts_uploaded")
        db.execSQL("DROP TABLE IF EXISTS open_pack")
        db.execSQL("CREATE TABLE IF NOT EXISTS backup_packs (packNumber INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, createdAt INTEGER NOT NULL)")
        db.execSQL("CREATE TABLE IF NOT EXISTS backup_pack_sealed (packNumber INTEGER NOT NULL PRIMARY KEY, sealedAt INTEGER NOT NULL, FOREIGN KEY (packNumber) REFERENCES backup_packs(packNumber) ON DELETE CASCADE)")
        db.execSQL("CREATE TABLE IF NOT EXISTS open_pack (id INTEGER NOT NULL PRIMARY KEY DEFAULT 1, packNumber INTEGER NOT NULL, b2UploadId TEXT NOT NULL, numPartsTarget INTEGER NOT NULL, createdAt INTEGER NOT NULL)")
        db.execSQL("CREATE TABLE IF NOT EXISTS backup_file_hashes (backupFileId INTEGER NOT NULL PRIMARY KEY, sha256 TEXT NOT NULL, FOREIGN KEY (backupFileId) REFERENCES backup_files(id) ON DELETE CASCADE)")
        db.execSQL("CREATE TABLE IF NOT EXISTS backup_parts (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, packNumber INTEGER NOT NULL, partNumber INTEGER NOT NULL, FOREIGN KEY (packNumber) REFERENCES backup_packs(packNumber) ON DELETE CASCADE, UNIQUE(packNumber, partNumber))")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_backup_parts_packNumber ON backup_parts (packNumber)")
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_backup_parts_packNumber_partNumber ON backup_parts (packNumber, partNumber)")
        db.execSQL("CREATE TABLE IF NOT EXISTS backup_part_etags (backupPartId INTEGER NOT NULL PRIMARY KEY, etag TEXT NOT NULL, FOREIGN KEY (backupPartId) REFERENCES backup_parts(id) ON DELETE CASCADE)")
        db.execSQL("CREATE TABLE IF NOT EXISTS backup_chunks (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, backupFileId INTEGER NOT NULL, backupPartId INTEGER NOT NULL, fileOffset INTEGER NOT NULL, chunkBytes INTEGER NOT NULL, UNIQUE(backupFileId, backupPartId), FOREIGN KEY (backupFileId) REFERENCES backup_files(id) ON DELETE CASCADE, FOREIGN KEY (backupPartId) REFERENCES backup_parts(id) ON DELETE CASCADE)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_backup_chunks_backupFileId ON backup_chunks (backupFileId)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_backup_chunks_backupPartId ON backup_chunks (backupPartId)")
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_backup_chunks_backupFileId_backupPartId ON backup_chunks (backupFileId, backupPartId)")
        db.execSQL("CREATE TABLE IF NOT EXISTS backup_files_new (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, path TEXT NOT NULL, uri TEXT NOT NULL, mtime INTEGER NOT NULL, size INTEGER NOT NULL, createdAt INTEGER NOT NULL, updatedAt INTEGER NOT NULL)")
        db.execSQL("INSERT INTO backup_files_new (id, path, uri, mtime, size, createdAt, updatedAt) SELECT id, path, uri, mtime, size, createdAt, updatedAt FROM backup_files")
        db.execSQL("DROP TABLE backup_files")
        db.execSQL("ALTER TABLE backup_files_new RENAME TO backup_files")
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_backup_files_path_mtime_size ON backup_files (path, mtime, size)")
        db.execSQL("CREATE VIEW `backup_file_status_view` AS SELECT f.id, f.path, f.uri, f.mtime, f.size, h.sha256, CASE WHEN COUNT(c.id) = 0 THEN 0 WHEN SUM(CASE WHEN s.packNumber IS NOT NULL THEN 1 ELSE 0 END) = COUNT(c.id) THEN 2 WHEN SUM(CASE WHEN e.backupPartId IS NOT NULL THEN 1 ELSE 0 END) > 0 THEN 1 ELSE 0 END as state FROM backup_files f LEFT JOIN backup_file_hashes h ON h.backupFileId = f.id LEFT JOIN backup_chunks c ON c.backupFileId = f.id LEFT JOIN backup_parts p ON p.id = c.backupPartId LEFT JOIN backup_part_etags e ON e.backupPartId = p.id LEFT JOIN backup_pack_sealed s ON s.packNumber = p.packNumber GROUP BY f.id ORDER BY f.id ASC")
        db.execSQL("CREATE VIEW `backup_restore_view` AS SELECT f.id, f.path, f.mtime, f.size, h.sha256, MIN(p.packNumber) as startPack, MIN(CASE WHEN p.packNumber = (SELECT MIN(p2.packNumber) FROM backup_parts p2 JOIN backup_chunks c2 ON c2.backupPartId = p2.id WHERE c2.backupFileId = f.id) THEN p.partNumber ELSE NULL END) as startPart, MIN(c.fileOffset) as startPartOffset, MAX(p.packNumber) as endPack, MAX(CASE WHEN p.packNumber = (SELECT MAX(p2.packNumber) FROM backup_parts p2 JOIN backup_chunks c2 ON c2.backupPartId = p2.id WHERE c2.backupFileId = f.id) THEN p.partNumber ELSE NULL END) as endPart, COUNT(c.id) as numParts FROM backup_files f JOIN backup_file_hashes h ON h.backupFileId = f.id JOIN backup_chunks c ON c.backupFileId = f.id JOIN backup_parts p ON p.id = c.backupPartId JOIN backup_pack_sealed s ON s.packNumber = p.packNumber GROUP BY f.id ORDER BY startPack, startPart")
    }
}

val MIGRATION_6_7 = object : Migration(6, 7) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS backup_packs_new (packNumber INTEGER NOT NULL PRIMARY KEY, createdAt INTEGER NOT NULL)")
        db.execSQL("INSERT INTO backup_packs_new SELECT * FROM backup_packs")
        db.execSQL("DROP TABLE backup_packs")
        db.execSQL("ALTER TABLE backup_packs_new RENAME TO backup_packs")
    }
}

val MIGRATION_7_8 = object : Migration(7, 8) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS backup_files_done (backupFileId INTEGER NOT NULL PRIMARY KEY, sealedAt INTEGER NOT NULL, FOREIGN KEY (backupFileId) REFERENCES backup_files(id) ON DELETE CASCADE)")
        db.execSQL("INSERT OR IGNORE INTO backup_files_done (backupFileId, sealedAt) SELECT bf.id, MAX(s.sealedAt) FROM backup_files bf JOIN backup_chunks c ON c.backupFileId = bf.id JOIN backup_parts p ON p.id = c.backupPartId LEFT JOIN backup_pack_sealed s ON s.packNumber = p.packNumber GROUP BY bf.id HAVING MIN(s.packNumber IS NOT NULL) = 1")
        db.execSQL("CREATE TABLE IF NOT EXISTS consolidate_files (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, path TEXT NOT NULL, uri TEXT NOT NULL, mtime INTEGER NOT NULL, size INTEGER NOT NULL, createdAt INTEGER NOT NULL)")
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_consolidate_files_path_mtime_size ON consolidate_files (path, mtime, size)")
        db.execSQL("CREATE TABLE IF NOT EXISTS consolidate_files_done (consolidateFileId INTEGER NOT NULL PRIMARY KEY, destinationName TEXT NOT NULL, completedAt INTEGER NOT NULL, FOREIGN KEY (consolidateFileId) REFERENCES consolidate_files(id) ON DELETE CASCADE)")
        db.execSQL("CREATE VIEW `safe_to_delete_view` AS SELECT cf.id, cf.path, cf.uri, cf.mtime, cf.size, d.destinationName, d.completedAt as consolidatedAt FROM consolidate_files cf JOIN consolidate_files_done d ON d.consolidateFileId = cf.id JOIN backup_files bf ON bf.path = cf.path JOIN backup_files_done bd ON bd.backupFileId = bf.id")
    }
}

val MIGRATION_8_9 = object : Migration(8, 9) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS consolidate_protected_files (consolidateFileId INTEGER NOT NULL PRIMARY KEY, protectedAt INTEGER NOT NULL, FOREIGN KEY (consolidateFileId) REFERENCES consolidate_files(id) ON DELETE CASCADE)")
        db.execSQL("DROP VIEW IF EXISTS safe_to_delete_view")
        db.execSQL("CREATE VIEW `safe_to_delete_view` AS SELECT cf.id, cf.path, cf.uri, cf.mtime, cf.size, d.destinationName, d.completedAt as consolidatedAt FROM consolidate_files cf JOIN consolidate_files_done d ON d.consolidateFileId = cf.id JOIN backup_files bf ON bf.path = cf.path JOIN backup_files_done bd ON bd.backupFileId = bf.id LEFT JOIN consolidate_protected_files p ON p.consolidateFileId = cf.id WHERE p.consolidateFileId IS NULL")
    }
}

val MIGRATION_9_10 = object : Migration(9, 10) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("DROP VIEW IF EXISTS safe_to_delete_view")
        db.execSQL("CREATE VIEW `safe_to_delete_view` AS SELECT cf.id, cf.path, cf.uri, cf.mtime, cf.size, d.destinationName, d.completedAt as consolidatedAt, CASE WHEN p.consolidateFileId IS NOT NULL THEN 1 ELSE 0 END as isProtected FROM consolidate_files cf JOIN consolidate_files_done d ON d.consolidateFileId = cf.id JOIN backup_files bf ON bf.path = cf.path JOIN backup_files_done bd ON bd.backupFileId = bf.id LEFT JOIN consolidate_protected_files p ON p.consolidateFileId = cf.id")
    }
}

val MIGRATION_10_11 = object : Migration(10, 11) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE backup_chunks ADD COLUMN partOffset INTEGER NOT NULL DEFAULT -1")
        db.execSQL("DROP VIEW IF EXISTS backup_restore_view")
        db.execSQL("CREATE VIEW `backup_restore_view` AS SELECT f.id, f.path, f.mtime, f.size, h.sha256, MIN(p.packNumber) as startPack, MIN(CASE WHEN p.packNumber = (SELECT MIN(p2.packNumber) FROM backup_parts p2 JOIN backup_chunks c2 ON c2.backupPartId = p2.id WHERE c2.backupFileId = f.id) THEN p.partNumber ELSE NULL END) as startPart, (SELECT c2.partOffset FROM backup_chunks c2 JOIN backup_parts p2 ON p2.id = c2.backupPartId WHERE c2.backupFileId = f.id AND c2.fileOffset = 0) as startPartOffset, MAX(p.packNumber) as endPack, MAX(CASE WHEN p.packNumber = (SELECT MAX(p2.packNumber) FROM backup_parts p2 JOIN backup_chunks c2 ON c2.backupPartId = p2.id WHERE c2.backupFileId = f.id) THEN p.partNumber ELSE NULL END) as endPart, COUNT(c.id) as numParts FROM backup_files f JOIN backup_file_hashes h ON h.backupFileId = f.id JOIN backup_chunks c ON c.backupFileId = f.id JOIN backup_parts p ON p.id = c.backupPartId JOIN backup_pack_sealed s ON s.packNumber = p.packNumber GROUP BY f.id ORDER BY startPack, startPart")
    }
}

val MIGRATION_11_12 = object : Migration(11, 12) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS restore_selections (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, path TEXT NOT NULL, sha256 TEXT NOT NULL, startPack INTEGER NOT NULL, startPart INTEGER NOT NULL, startPartOffset INTEGER NOT NULL, endPack INTEGER NOT NULL, endPart INTEGER NOT NULL, numParts INTEGER NOT NULL, size INTEGER NOT NULL, mtime INTEGER NOT NULL)")
    }
}

val MIGRATION_12_13 = object : Migration(12, 13) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("DROP VIEW IF EXISTS safe_to_delete_view")
        db.execSQL("CREATE VIEW `safe_to_delete_view` AS SELECT cf.id, cf.path, cf.uri, cf.mtime, cf.size, d.destinationName, d.completedAt as consolidatedAt, CASE WHEN p.consolidateFileId IS NOT NULL THEN 1 ELSE 0 END as isProtected FROM consolidate_files cf JOIN consolidate_files_done d ON d.consolidateFileId = cf.id JOIN backup_files bf ON bf.path = cf.path AND bf.mtime = cf.mtime AND bf.size = cf.size JOIN backup_files_done bd ON bd.backupFileId = bf.id LEFT JOIN consolidate_protected_files p ON p.consolidateFileId = cf.id")
    }
}

val MIGRATION_13_14 = object : Migration(13, 14) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("CREATE VIEW `track_point_rows` AS SELECT t.id AS trackId, t.date AS trackDate, t.name AS trackName, s.id AS segmentId, s.startedAt AS segmentStartedAt, p.id AS pointId, p.lat, p.lon, p.ele, p.undulation, p.accuracyM, p.speedMs, p.bearing, p.time, p.provider FROM track_points p JOIN track_segments s ON s.id = p.segmentId JOIN tracks t ON t.id = s.trackId ORDER BY t.id ASC, s.id ASC, p.id ASC")
    }
}

val MIGRATION_14_15 = object : Migration(14, 15) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE posts ADD COLUMN slugEdited INTEGER NOT NULL DEFAULT 0")
    }
}

val MIGRATION_15_16 = object : Migration(15, 16) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS audio_recordings (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, name TEXT NOT NULL, uri TEXT NOT NULL, format TEXT NOT NULL, sampleRateHz INTEGER NOT NULL, bitrateBps INTEGER NOT NULL, createdAt INTEGER NOT NULL, durationMs INTEGER, sizeBytes INTEGER)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_audio_recordings_createdAt ON audio_recordings (createdAt)")
    }
}
