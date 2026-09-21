package de.perigon.companion.audio

import android.Manifest
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.compose.composable
import de.perigon.companion.audio.service.AudioRecordingService
import de.perigon.companion.audio.ui.recording.AudioRecordingScreen
import de.perigon.companion.core.feature.AccessRequirement
import de.perigon.companion.core.feature.CommonRequirements
import de.perigon.companion.core.feature.FeatureEntry
import de.perigon.companion.core.feature.MenuItem
import de.perigon.companion.core.ui.access.RequireAccess
import kotlinx.serialization.Serializable
import javax.inject.Inject

object AudioRoute {
    @Serializable data object Audio
}

object AudioChannels {
    const val RECORDER = "audio_recorder"
}

/** The recording folder is a FolderGrants entry under this key. */
object AudioFolder {
    const val KEY = "audio"
}

object AudioRequirements {
    val mic: AccessRequirement = AccessRequirement.Permissions(
        key = "microphone",
        title = "Microphone",
        rationale = "For audio recording.",
        permissions = listOf(Manifest.permission.RECORD_AUDIO),
        optional = true,
    )
    val folder: AccessRequirement = AccessRequirement.Folder(
        key = AudioFolder.KEY,
        title = "Audio folder",
        rationale = "Where recordings are written.",
        write = true,
        optional = true,
    )
}

class AudioFeature @Inject constructor() : FeatureEntry {
    override val id = "audio"
    override val order = 400
    override val startRoute: Any = AudioRoute.Audio
    override val menuItems = listOf(MenuItem("Audio", AudioRoute.Audio, order = 80))
    override val requirements = listOf(AudioRequirements.mic, AudioRequirements.folder, CommonRequirements.notifications)

    override fun register(graph: NavGraphBuilder, nav: NavHostController) {
        graph.composable<AudioRoute.Audio> {
            RequireAccess(listOf(AudioRequirements.mic, AudioRequirements.folder)) {
                AudioRecordingScreen(navController = nav)
            }
        }
    }

    override fun titleFor(route: String): String? =
        if (route.endsWith("AudioRoute.Audio")) "Audio" else null

    override fun routeFor(navigateTo: String): Any? =
        if (navigateTo == AudioRecordingService.NAV_AUDIO) AudioRoute.Audio else null
}
