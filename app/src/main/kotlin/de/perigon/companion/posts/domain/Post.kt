package de.perigon.companion.posts.domain

import android.content.Intent
import android.net.Uri

enum class PostPublishState {
    DRAFT, QUEUED, PUBLISHED, NEEDS_FIXING;

    class Converter {
        @androidx.room.TypeConverter
        fun fromEnum(value: PostPublishState): String = value.name

        @androidx.room.TypeConverter
        fun toEnum(value: String): PostPublishState = valueOf(value)
    }
}

data class Post(
    val id:            Long             = 0,
    val localDate:     String           = "",
    val motto:         String           = "",
    val title:         String           = "",
    val titleEdited:   Boolean          = false,
    val body:          String           = "",
    val teaser:        String           = "",
    val teaserEdited:  Boolean          = false,
    val tags:          List<String>     = emptyList(),
    val pinnedMediaId: Long?            = null,
    val slug:          String           = "",
    val slugEdited:    Boolean          = false,
    val publishState:  PostPublishState = PostPublishState.DRAFT,
    val publishedAt:   Long?            = null,
    val createdAt:     Long             = 0,
    val updatedAt:     Long             = 0,
)

data class PostMedia(
    val id:          Long   = 0,
    val postId:      Long   = 0,
    val contentHash: String = "",
    val mimeType:    String = "image/jpeg",
    val position:    Int    = 0,
    val addedAt:     Long   = 0,
)

fun String.slugify(): String =
    lowercase()
        .replace(Regex("\\s+"), "-")
        .replace(Regex("[^a-z0-9-]"), "")
        .replace(Regex("-+"), "-")
        .trim('-')

fun Post.computeSlug(): String =
    "${localDate.replace("-", "")}-${title.slugify()}"

fun computeDayNumber(startDate: String, postDate: String): Int? =
    runCatching {
        val start = java.time.LocalDate.parse(startDate)
        val post  = java.time.LocalDate.parse(postDate)
        val days  = java.time.temporal.ChronoUnit.DAYS.between(start, post).toInt() + 1
        if (days >= 1) days else null
    }.getOrNull()

fun prefillTitle(
    dayNumber:    Int?,
    motto:        String,
    journeyTitle: String,
    journeyTag:   String,
): String = buildString {
    if (dayNumber != null) append("Day $dayNumber: ")
    if (motto.isNotBlank()) {
        append(motto)
        if (journeyTitle.isNotBlank()) append(" - ")
    }
    if (journeyTitle.isNotBlank()) append(journeyTitle)
    if (journeyTag.isNotBlank()) append(" #$journeyTag")
}.trim()

fun autoTeaser(body: String, maxLen: Int = 160): String {
    if (body.isBlank()) return ""
    val trimmed = body.trim()
    if (trimmed.length <= maxLen) return trimmed
    val sentenceEnd = Regex("[.!?]\\s")
    var best = -1
    sentenceEnd.findAll(trimmed).forEach { match ->
        val end = match.range.first + 1
        if (end <= maxLen) best = end
    }
    return if (best > 0) trimmed.substring(0, best).trim()
    else trimmed.substring(0, maxLen).trim() + "…"
}

fun Post.publishedUrl(siteUrl: String?): String? {
    if (siteUrl.isNullOrBlank() || slug.isBlank() || localDate.isBlank()) return null
    val parts = localDate.split("-")
    if (parts.size != 3) return null
    val base = siteUrl.trimEnd('/')
    return "$base/${parts[0]}/${parts[1]}/${parts[2]}/$slug.html"
}

fun mediaRepoPath(slug: String, contentHash: String, mimeType: String): String {
    val ext = if (mimeType.startsWith("video")) "mp4" else "jpg"
    return "static/$slug/$contentHash.$ext"
}

/**
 * Build the media URL for frontmatter and share links.
 * Always uses the `static/slug/hash.ext` path structure.
 *
 * When [mediaBaseUrl] is set (S3), returns an absolute URL
 * like `https://static.example.com/static/slug/hash.jpg`.
 * Otherwise returns a relative path `/static/slug/hash.jpg`.
 */
fun mediaSiteUrl(slug: String, contentHash: String, mimeType: String, mediaBaseUrl: String? = null): String {
    val ext = if (mimeType.startsWith("video")) "mp4" else "jpg"
    val path = "static/$slug/$contentHash.$ext"
    return if (mediaBaseUrl != null) {
        "${mediaBaseUrl.trimEnd('/')}/$path"
    } else {
        "/$path"
    }
}

// --- Merged from MediaIngestor.kt ---

enum class MediaOrigin { POSTMEDIA, CONSOLIDATED, UNPROCESSED }

fun classifyMediaOrigin(relativePath: String?): MediaOrigin = when {
    relativePath == null -> MediaOrigin.UNPROCESSED
    relativePath.contains("DCIM/Consolidated", ignoreCase = true) -> MediaOrigin.CONSOLIDATED
    else -> MediaOrigin.UNPROCESSED
}

sealed class IngestResult {
    data class AlreadyInPostMedia(val uri: Uri, val contentHash: String) : IngestResult()
    data class CopiedToPostMedia(val uri: Uri, val contentHash: String) : IngestResult()
    data class NeedsTransform(val sourceUri: Uri) : IngestResult()
    data class Failed(val error: String) : IngestResult()
}


// --- Merged from PostShareIntentBuilder.kt ---

object PostShareIntentBuilder {

    data class ShareMedia(
        val uri: Uri,
        val mimeType: String,
    )

    fun build(
        title: String,
        teaser: String,
        tags: List<String>,
        slug: String,
        localDate: String,
        siteUrl: String?,
        media: List<ShareMedia>,
    ): Intent {
        val post = Post(localDate = localDate, title = title, slug = slug)
        val url = post.publishedUrl(siteUrl)

        val text = buildString {
            appendLine(title)
            if (url != null) { appendLine(); appendLine(url) }
        }.trim()

        val sendIntent = if (media.isNotEmpty()) {
            val uris = ArrayList(media.map { it.uri })
            val mimeType = if (media.all { it.mimeType.startsWith("image") }) "image/*"
                else if (media.all { it.mimeType.startsWith("video") }) "video/*"
                else "*/*"

            Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                type = mimeType
                putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
                putExtra(Intent.EXTRA_TEXT, text)
                putExtra(Intent.EXTRA_SUBJECT, title)
                clipData = android.content.ClipData(
                    title,
                    arrayOf(mimeType),
                    android.content.ClipData.Item(uris.first()),
                ).apply {
                    for (i in 1 until uris.size) {
                        addItem(android.content.ClipData.Item(uris[i]))
                    }
                }
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        } else {
            Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, text)
                putExtra(Intent.EXTRA_SUBJECT, title)
            }
        }

        return Intent.createChooser(sendIntent, "Share post").apply {
            if (media.isNotEmpty()) {
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        }
    }
}


// --- Merged from JekyllPostBuilder.kt ---

data class MediaEntry(
    val filename: String,
    val mimeType: String,
    val width: Int? = null,
    val height: Int? = null,
)

object JekyllPostBuilder {

    /**
     * Build Jekyll post markdown.
     *
     * Every media entry gets a `url:` field in the frontmatter.
     * - GitHub media: `/static/{slug}/{file}` (relative)
     * - S3 media: `{mediaBaseUrl}/static/{slug}/{file}` (absolute)
     *
     * The carousel template reads `item.url` directly and falls back
     * to constructing the path for legacy posts without `url:`.
     */
    fun build(
        post: Post,
        mediaEntries: List<MediaEntry>,
        siteUrl: String? = null,
        mediaBaseUrl: String? = null,
    ): String = buildString {
        appendLine("---")
        appendLine("layout: post")
        appendLine("title: \"${post.title.escapeYaml()}\"")
        appendLine("date: ${post.localDate}")
        appendLine("slug: ${post.slug}")
        if (post.motto.isNotBlank()) {
            appendLine("motto: \"${post.motto.escapeYaml()}\"")
        }
        if (post.teaser.isNotBlank()) {
            appendLine("teaser: \"${post.teaser.escapeYaml()}\"")
        }
        if (post.tags.isNotEmpty()) {
            appendLine("tags:")
            post.tags.forEach { tag ->
                appendLine("  - $tag")
            }
        }
        if (mediaEntries.isNotEmpty()) {
            val leadImage = mediaEntries.firstOrNull { it.mimeType.startsWith("image") }
            if (leadImage != null) {
                val imageUrl = mediaUrl(post.slug, leadImage.filename, mediaBaseUrl)
                if (mediaBaseUrl != null) {
                    appendLine("image: \"$imageUrl\"")
                } else if (siteUrl != null) {
                    appendLine("image: \"${siteUrl.trimEnd('/')}$imageUrl\"")
                } else {
                    appendLine("image: \"$imageUrl\"")
                }
            }
            appendLine("media:")
            mediaEntries.forEachIndexed { index, entry ->
                val type = if (entry.mimeType.startsWith("video")) "video" else "image"
                appendLine("  - file: ${entry.filename}")
                appendLine("    type: $type")
                appendLine("    url: ${mediaUrl(post.slug, entry.filename, mediaBaseUrl)}")
                if (entry.width != null && entry.height != null) {
                    appendLine("    width: ${entry.width}")
                    appendLine("    height: ${entry.height}")
                }
                if (index == 0 && post.motto.isNotBlank()) {
                    appendLine("    motto: true")
                }
            }
        }
        appendLine("---")
        appendLine()
        if (post.body.isNotBlank()) {
            appendLine(post.body)
        }
    }

    private fun mediaUrl(slug: String, filename: String, mediaBaseUrl: String?): String {
        val path = "/static/$slug/$filename"
        return if (mediaBaseUrl != null) {
            "${mediaBaseUrl.trimEnd('/')}$path"
        } else {
            path
        }
    }

    private fun String.escapeYaml(): String =
        replace("\\", "\\\\").replace("\"", "\\\"")
}
