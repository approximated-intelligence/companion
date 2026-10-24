package de.perigon.companion.archive

import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.compose.composable
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.WorkManager
import de.perigon.companion.archive.consolidate.ui.ConsolidateScreen
import de.perigon.companion.archive.backup.data.BackupSchedulePrefs
import de.perigon.companion.archive.mirror.ui.MirrorScreen
import de.perigon.companion.archive.restore.ui.RestoreScreen
import de.perigon.companion.archive.backup.worker.syncBackupSchedule
import de.perigon.companion.core.feature.AppInitializer
import de.perigon.companion.core.feature.CommonRequirements
import de.perigon.companion.core.feature.FeatureEntry
import de.perigon.companion.core.feature.MenuItem
import kotlinx.serialization.Serializable
import kotlin.reflect.KClass
import javax.inject.Inject

/**
 * The archive: consolidation, restore and mirror are tabs of one screen, one
 * menu item, one set of feature requirements. Each tab is its own route so
 * that a notification's `navigate_to` can deep-link straight to it — a
 * single route with a tab argument would not survive launchSingleTop.
 *
 * "Backup" (the run action) lives inside the Consolidate tab: consolidation
 * and backup share the destination picker and the progress surface, and
 * "back up after consolidating" is one tap. The former dedicated Backup
 * screen's per-run UI is gone; its set-once settings (schedule, pack size,
 * source folders) moved to the Settings screen and are deep-linked from the
 * sources card on Consolidate.
 */
object ArchiveRoute {
    @Serializable data object Consolidate
    @Serializable data object Restore
    @Serializable data object Mirror
}

/** Notification channel ids posted by this feature's workers. */
object ArchiveChannels {
    const val BACKUP = "backup"
    const val CONSOLIDATE = "consolidate"
    const val MIRROR = "mirror"
}

/**
 * The `navigate_to` strings embedded in notifications. [BACKUP] is retained
 * so that notifications posted before the Backup screen was folded into
 * Consolidate still navigate somewhere sensible; the value is fixed by
 * history, not by the current route names.
 */
object ArchiveTargets {
    const val BACKUP = "Backup"
    const val CONSOLIDATE = "Consolidate"
    const val RESTORE = "Restore"
    const val MIRROR = "Mirror"
}

class ArchiveFeature @Inject constructor() : FeatureEntry {
    override val id = "archive"
    override val order = 100
    override val startRoute: Any = ArchiveRoute.Consolidate

    override val menuItems = listOf(
        MenuItem("Archive", ArchiveRoute.Consolidate, order = 20),
    )

    override val requirements = listOf(
        CommonRequirements.dcim,
        CommonRequirements.mediaAccess,
        CommonRequirements.notifications,
    )

    override fun register(graph: NavGraphBuilder, nav: NavHostController) {
        graph.composable<ArchiveRoute.Consolidate> { ConsolidateScreen(navController = nav) }
        graph.composable<ArchiveRoute.Restore> { RestoreScreen(navController = nav) }
        graph.composable<ArchiveRoute.Mirror> { MirrorScreen(navController = nav) }
    }

    // All three tab roots report the feature's menu label ("Archive"), not
    // the tab name. The tab row indicates which sub-surface is selected; the
    // title bar matching the menu entry keeps "which feature am I in?"
    // answerable when the user is deep in a tab.
    override fun titleFor(route: String): String? = when {
        route.endsWith("ArchiveRoute.Consolidate") ||
        route.endsWith("ArchiveRoute.Restore") ||
        route.endsWith("ArchiveRoute.Mirror") -> "Archive"
        else -> null
    }

    override fun routeFor(navigateTo: String): Any? = when (navigateTo) {
        ArchiveTargets.CONSOLIDATE -> ArchiveRoute.Consolidate
        ArchiveTargets.RESTORE -> ArchiveRoute.Restore
        ArchiveTargets.MIRROR -> ArchiveRoute.Mirror
        // Legacy "Backup failed" notifications: the dedicated screen is gone,
        // but tapping them should still land in the archive. Consolidate is
        // where the backup was triggered from, so that is where it lands.
        ArchiveTargets.BACKUP -> ArchiveRoute.Consolidate
        else -> null
    }

    override fun defaultTabClassFor(route: Any): KClass<*>? = when (route) {
        is ArchiveRoute.Consolidate, is ArchiveRoute.Restore, is ArchiveRoute.Mirror ->
            ArchiveRoute.Consolidate::class
        else -> null
    }
}

/** Re-register the periodic trigger. KEEP: a launch must not reset the timer. */
class BackupScheduleInitializer @Inject constructor(
    private val workManager: WorkManager,
    private val schedulePrefs: BackupSchedulePrefs,
) : AppInitializer {
    override suspend fun run() {
        workManager.syncBackupSchedule(
            enabled = schedulePrefs.autoEnabled(),
            intervalHours = schedulePrefs.intervalHours(),
            policy = ExistingPeriodicWorkPolicy.KEEP,
        )
    }
}
