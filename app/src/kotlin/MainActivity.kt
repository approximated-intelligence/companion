package de.perigon.companion

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.navigation.compose.rememberNavController
import dagger.hilt.android.AndroidEntryPoint
import de.perigon.companion.core.feature.FeatureEntry
import de.perigon.companion.core.ui.LaunchRequest
import de.perigon.companion.core.ui.LocalFeatures
import de.perigon.companion.core.ui.access.SetupGate
import de.perigon.companion.core.ui.deliver
import de.perigon.companion.core.ui.launchRequestOf
import de.perigon.companion.core.ui.theme.CompanionTheme
import kotlinx.coroutines.channels.Channel
import javax.inject.Inject

/**
 * launchMode="singleTask": one instance. Shares and notification taps while
 * running arrive in [onNewIntent].
 *
 * Knows no feature: the graph, menu and launch routing come from the
 * FeatureEntry set of this build.
 */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject lateinit var features: Set<@JvmSuppressWildcards FeatureEntry>

    /**
     * Every external launch goes through this one queue. A Channel, not a
     * StateFlow: requests are events and none may be conflated. Buffered, so
     * requests made while the setup gate is showing wait and are delivered
     * once the graph exists.
     */
    private val launchRequests = Channel<LaunchRequest>(Channel.UNLIMITED)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // On recreation the same intent is handed back — already delivered.
        if (savedInstanceState == null) launchRequestOf(intent)?.let { launchRequests.trySend(it) }

        val ordered = features.sortedBy { it.order }

        setContent {
            CompanionTheme {
                // Hoisted above the gate: a revoked grant closes and reopens
                // the gate; the back stack must survive that.
                val navController = rememberNavController()

                CompositionLocalProvider(LocalFeatures provides ordered) {
                    SetupGate {
                        LaunchedEffect(navController) {
                            for (request in launchRequests) navController.deliver(request, ordered)
                        }
                        NavGraph(navController, ordered)
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        launchRequestOf(intent)?.let { launchRequests.trySend(it) }
    }
}
