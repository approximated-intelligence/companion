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
val MIGRATION_18_1 = object : Migration(18, 1) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE posts DROP COLUMN mediaMigrated")
    }
}
