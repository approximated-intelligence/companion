# Companion

Android journaling app. Write posts with photos and videos, publish to your own Jekyll site on GitHub Pages.

## What it does

**Posts** - Markdown editor with media. Photos and videos get cropped, rotated, trimmed, and compressed on-device. Published atomically to a GitHub repo as Jekyll-compatible markdown.

**Media prep** - Drag-to-reorder, per-item crop/rotate/trim editor with live preview. Fine rotation slider, video trim strip with frame thumbnails.

**Site assets** - Edit Jekyll templates, layouts, and config on-device. Side-by-side diff against server. Push/pull individual files.

**Media consolidation** - Batch-compress camera roll into DCIM/Consolidated for reduced storage.

**Backup** - Media files are packed, encrypted, and uploaded to B2-compatible object storage. Restore available on-device.

## Architecture

Multi-module Android app. Kotlin, Jetpack Compose, Hilt, Room, WorkManager, Media3 Transformer.

```
app/         App shell: activity, nav graph, feature wiring
core/        DI, preferences, SAF, networking, navigation, UI
posts/       Post editor, media prep, Jekyll publishing, site assets
media/       Transform queue, image/video editing, EXIF, track rendering
backup/      Pack-based backup to B2, restore, consolidation
scaffold/    `companion.library` convention plugin for feature modules
```

**Transform queue** - All media processing flows through a Room-backed job queue. Jobs run sequentially on a dedicated HandlerThread. Media3 Transformer handles video; bitmap pipeline handles images.

**Atomic publishing** - Posts commit atomically via GitHub's Git Trees API: markdown always, media blobs only when media is stored on GitHub.

## Building

```bash
./gradlew assembleDebug
```

Requires JDK 21+ (developed against JDK 25). On JDK 24+, Gradle's launcher prints a restricted native-access warning; silence it with `export GRADLE_OPTS="--enable-native-access=ALL-UNNAMED"`. Dependencies managed via version catalog (`gradle/libs.versions.toml`).

## Updating dependencies

The project uses [version-catalog-update](https://github.com/littlerobots/version-catalog-update-plugin) to keep dependencies current. It's configured to only suggest stable releases.

```bash
# Check for available updates
./gradlew versionCatalogUpdate

# Review the proposed changes in gradle/libs.versions.toml, then build and test
./gradlew assembleDebug
```

The plugin modifies `gradle/libs.versions.toml` in place. Review the diff before committing - major version bumps (Room, Media3, Compose BOM) occasionally require migration steps.

## Configuration

Done on-device in Settings. Credentials (B2, GitHub) are imported by scanning a QR code; preferences are entered manually.

- **B2 storage** - S3-compatible endpoint, bucket, credentials
- **GitHub** - Token, owner, repo. Site URL read from the repo's CNAME when present
- **Journey** - Start date, title, tag for auto-generating post titles ("Day 42: ...")

## Requirements

- Android 14+ (minSdk 34)
- Camera permission for QR scanning (optional)
- SAF folder access for DCIM
- Media permissions for photos and videos, including original EXIF/GPS
