package de.perigon.companion.core.feature

import android.Manifest
import de.perigon.companion.core.prefs.FolderKeys
import de.perigon.companion.core.saf.dcimPickerInitialUri
import de.perigon.companion.core.saf.isDcimTree

/** Requirements several features share; distinctRequirements() merges them by key. */
object CommonRequirements {

    val dcim: AccessRequirement = AccessRequirement.Folder(
        key = FolderKeys.DCIM,
        title = "DCIM folder",
        rationale = "Post media, consolidated photos and restored files live under DCIM.",
        write = true,
        initialUri = dcimPickerInitialUri(),
        validate = { uri ->
            if (isDcimTree(uri)) null
            else "That's not the DCIM folder. Select the folder named DCIM at the top level of your storage."
        },
    )

    val mediaAccess: AccessRequirement = AccessRequirement.Permissions(
        key = "media_access",
        title = "Photo and video access",
        rationale = "Needed to read original file bytes (with GPS intact) via MediaStore.",
        permissions = listOf(
            Manifest.permission.READ_MEDIA_IMAGES,
            Manifest.permission.READ_MEDIA_VIDEO,
            Manifest.permission.ACCESS_MEDIA_LOCATION,
        ),
    )

    val notifications: AccessRequirement = AccessRequirement.Permissions(
        key = "notifications",
        title = "Notifications",
        rationale = "Progress of backups, uploads and recordings.",
        permissions = listOf(Manifest.permission.POST_NOTIFICATIONS),
        optional = true,
    )
}
