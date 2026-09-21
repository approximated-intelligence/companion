package de.perigon.companion.backup

import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.compose.composable
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.WorkManager
import de.perigon.companion.backup.consolidate.ui.ConsolidateScreen
import de.perigon.companion.backup.data.BackupSchedulePrefs
import de.perigon.companion.backup.ui.BackupScreen
import de.perigon.companion.backup.ui.restore.RestoreScreen
import de.perigon.companion.backup.worker.syncBackupSchedule
import de.perigon.companion.core.feature.AppInitializer
import de.perigon.companion.core.feature.CommonRequirements
import de.perigon.companion.core.feature.FeatureEntry
import de.perigon.companion.core.feature.MenuItem
import kotlinx.serialization.Serializable
import javax.inject.Inject

object BackupRoute {
    @Serializable data object Backup
    @Serializable data object Restore
    @Serializable data object Consolidate
}

object BackupChannels {
    const val BACKUP = "backup"
    const val CONSOLIDATE = "consolidate"
}

/** "navigate_to" values used by this module's notifications. */
object BackupTargets {
    const val BACKUP = "Backup"
    const val RESTORE = "Restore"
    const val CONSOLIDATE = "Consolidate"
}

class BackupFeature @Inject constructor() : FeatureEntry {
    override val id = "backup"
    override val order = 100
    override val startRoute: Any = BackupRoute.Consolidate

    override val menuItems = listOf(
        MenuItem("Backup", BackupRoute.Backup, order = 20),
        MenuItem("Restore", BackupRoute.Restore, order = 30),
        MenuItem("Consolidate", BackupRoute.Consolidate, order = 40),
    )

    override val requirements = listOf(CommonRequirements.dcim, CommonRequirements.notifications)

    override fun register(graph: NavGraphBuilder, nav: NavHostController) {
        graph.composable<BackupRoute.Backup> { BackupScreen(navController = nav) }
        graph.composable<BackupRoute.Restore> { RestoreScreen(navController = nav) }
        graph.composable<BackupRoute.Consolidate> { ConsolidateScreen(navController = nav) }
    }

    override fun titleFor(route: String): String? = when {
        route.endsWith("BackupRoute.Backup") -> "Backup"
        route.endsWith("BackupRoute.Restore") -> "Restore"
        route.endsWith("BackupRoute.Consolidate") -> "Consolidate"
        else -> null
    }

    override fun routeFor(navigateTo: String): Any? = when (navigateTo) {
        BackupTargets.BACKUP -> BackupRoute.Backup
        BackupTargets.RESTORE -> BackupRoute.Restore
        BackupTargets.CONSOLIDATE -> BackupRoute.Consolidate
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
