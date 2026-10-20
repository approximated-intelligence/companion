package de.perigon.companion.core.ui

import android.content.Context
import android.view.accessibility.AccessibilityManager
import android.accessibilityservice.AccessibilityServiceInfo
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

@Composable
fun rememberScreenSecurityThreat(): State<ScreenSecurityThreat> {
    val context = LocalContext.current
    val state = remember { mutableStateOf(checkThreats(context)) }

    DisposableEffect(Unit) {
        val am = context.getSystemService(AccessibilityManager::class.java)

        val stateListener = AccessibilityManager.AccessibilityStateChangeListener {
            state.value = checkThreats(context)
        }

        val servicesListener = AccessibilityManager.TouchExplorationStateChangeListener {
            state.value = checkThreats(context)
        }

        am.addAccessibilityStateChangeListener(stateListener)
        am.addTouchExplorationStateChangeListener(servicesListener)

        onDispose {
            am.removeAccessibilityStateChangeListener(stateListener)
            am.removeTouchExplorationStateChangeListener(servicesListener)
        }
    }

    return state
}

data class ScreenSecurityThreat(
    val hasScreenReaders: Boolean = false,
) {
    val isBlocked: Boolean get() = hasScreenReaders

    val reason: String get() =
        if (hasScreenReaders) "A screen-reading accessibility service is active." else ""

    val remediation: String get() =
        if (hasScreenReaders) "Disable accessibility services in Android Settings → Accessibility." else ""
}

// The installed-package "overlay app" sweep was removed: on API 30+ it needs
// QUERY_ALL_PACKAGES / a <queries> manifest block to see anything, so it was
// effectively blind; it ran full package-manager IPC + createPackageContext per
// package IN COMPOSITION on the main thread; and its OEM-prefix allowlist was
// trivially name-spoofable. The screen-reader check below is sound and cheap,
// and pairs with FLAG_SECURE (SecureWindow) which is the real screen-capture
// defense.
private fun checkThreats(context: Context): ScreenSecurityThreat =
    ScreenSecurityThreat(hasScreenReaders = checkScreenReaders(context))

private fun checkScreenReaders(context: Context): Boolean {
    val am = context.getSystemService(AccessibilityManager::class.java) ?: return false
    if (!am.isEnabled) return false

    val services = am.getEnabledAccessibilityServiceList(
        AccessibilityServiceInfo.FEEDBACK_ALL_MASK
    )

    return services.any { info ->
        val feedback = info.feedbackType
        val flags = info.flags

        val hasScreenReadFeedback = (feedback and (
            AccessibilityServiceInfo.FEEDBACK_SPOKEN or
            AccessibilityServiceInfo.FEEDBACK_BRAILLE
        )) != 0

        val canRetrieveWindows = (flags and
            AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS) != 0

        hasScreenReadFeedback || canRetrieveWindows
    }
}

@Composable
fun AccessibilityGate(content: @Composable () -> Unit) {
    val threat = rememberScreenSecurityThreat()

    if (threat.value.isBlocked) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center,
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp),
                modifier = Modifier.padding(32.dp),
            ) {
                Icon(
                    Icons.Default.Shield,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
                Text(
                    "Screen security check failed",
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.error,
                )
                Text(
                    threat.value.reason,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    threat.value.remediation,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    "This protects your encryption keys, API tokens, and B2 credentials "
                        + "from being captured by third-party apps.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline,
                )
            }
        }
    } else {
        content()
    }
}
