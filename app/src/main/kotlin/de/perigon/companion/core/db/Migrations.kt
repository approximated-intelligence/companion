package de.perigon.companion.core.db

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

// Baseline collapse. Every shipped client is at schema 18, so the 1→18 chain
// is dead and has been removed. The new baseline is version 1; this single
// DOWNGRADE migration carries an existing v18 database to it WITHOUT data loss
// (Room runs it because a matching Migration is provided). Fresh installs get
// v1 directly and never run it.
//
// Only schema delta vs v18: posts.mediaMigrated is gone (the media-name
// migration was removed). minSdk is 34 ⇒ SQLite ≥ 3.35, so this is a plain
// one-line DROP COLUMN — no table rebuild.
//
// Convention: migrations are always single-line execSQL statements.

// Measured pixel size of the PROCESSED bytes, recorded alongside
// processedHash at publish time. NOT NULL DEFAULT 0 rather than nullable:
// 0 is already an impossible size for real media, so it carries "not
// measured" without a nullable column — and nothing is published yet, so no
// existing row loses information.
//
// Why store at all: a retried publish skips media already on the server, and
// without these it would have to re-download the file just to put width and
// height into the markdown.
val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE post_media ADD COLUMN processedWidth INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE post_media ADD COLUMN processedHeight INTEGER NOT NULL DEFAULT 0")
    }
}
