package de.perigon.companion.core.feature

import android.Manifest
import android.app.AlarmManager
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import androidx.core.content.ContextCompat
import de.perigon.companion.core.saf.hasPersistedReadGrant
import de.perigon.companion.core.saf.hasPersistedWriteGrant

/**
 * Something a destination needs before it can work. Features declare these;
 * the setup screen requests the union of all present features in one pass,
 * and destination gates only reappear when a grant is later revoked.
 */
sealed interface AccessRequirement {
    val key: String
    val title: String
    val rationale: String
    val optional: Boolean

    /** Runtime permissions. Satisfied by all of [permissions] or any of [alternatives]. */
    data class Permissions(
        override val key: String,
        override val title: String,
        override val rationale: String,
        val permissions: List<String>,
        val alternatives: List<String> = emptyList(),
        override val optional: Boolean = false,
    ) : AccessRequirement

    /** Android requires this as its own request, after foreground location. */
    data class BackgroundLocation(
        override val rationale: String,
        override val optional: Boolean = true,
    ) : AccessRequirement {
        override val key: String get() = "background_location"
        override val title: String get() = "Background location"
    }

    /** A special app op granted in system settings, not by a dialog. */
    data class ExactAlarms(
        override val rationale: String,
        override val optional: Boolean = true,
    ) : AccessRequirement {
        override val key: String get() = "exact_alarms"
        override val title: String get() = "Exact alarms"
    }

    /**
     * A persisted SAF tree grant, stored in FolderGrants under [key].
     * [validate] returns a user-facing error for an unacceptable pick, or null.
     */
    class Folder(
        override val key: String,
        override val title: String,
        override val rationale: String,
        val write: Boolean,
        val initialUri: Uri? = null,
        val validate: (Uri) -> String? = { null },
        override val optional: Boolean = false,
    ) : AccessRequirement
}

private fun Context.granted(permission: String): Boolean =
    ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

/** Current status. [folderUri] resolves a Folder key to its stored tree URI. */
fun AccessRequirement.isSatisfied(ctx: Context, folderUri: (String) -> String?): Boolean = when (this) {
    is AccessRequirement.Permissions ->
        permissions.all(ctx::granted) || alternatives.any(ctx::granted)
    is AccessRequirement.BackgroundLocation ->
        ctx.granted(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
    is AccessRequirement.ExactAlarms ->
        ctx.getSystemService(AlarmManager::class.java).canScheduleExactAlarms()
    is AccessRequirement.Folder ->
        folderUri(key)?.let(Uri::parse)?.let {
            if (write) hasPersistedWriteGrant(ctx, it) else hasPersistedReadGrant(ctx, it)
        } ?: false
}

/** Pure: one entry per key (two features may both need DCIM). */
fun Collection<AccessRequirement>.distinctRequirements(): List<AccessRequirement> =
    distinctBy { it.key }

/** Pure: every runtime permission, for ONE system request. */
fun Collection<AccessRequirement>.runtimePermissions(): List<String> =
    filterIsInstance<AccessRequirement.Permissions>()
        .flatMap { it.permissions + it.alternatives }
        .distinct()
