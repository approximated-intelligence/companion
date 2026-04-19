package de.perigon.companion.core.ui

import android.content.Context
import android.provider.Settings
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
    val hasOverlayApps: Boolean = false,
) {
    val isBlocked: Boolean get() = hasScreenReaders || hasOverlayApps

    val reason: String get() = buildString {
        if (hasScreenReaders) append("A screen-reading accessibility service is active.")
        if (hasScreenReaders && hasOverlayApps) append("\n\n")
        if (hasOverlayApps) append("An app with screen overlay permission is active.")
    }

    val remediation: String get() = buildString {
        if (hasScreenReaders) append("Disable accessibility services in Android Settings → Accessibility.")
        if (hasScreenReaders && hasOverlayApps) append("\n\n")
        if (hasOverlayApps) append("Revoke overlay permissions in Android Settings → Apps → Special access → Display over other apps.")
    }
}

private fun checkThreats(context: Context): ScreenSecurityThreat =
    ScreenSecurityThreat(
        hasScreenReaders = checkScreenReaders(context),
        hasOverlayApps = checkOverlayApps(context),
    )

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

private fun checkOverlayApps(context: Context): Boolean {
    val pm = context.packageManager
    val ownPackage = context.packageName

    val installedPackages = pm.getInstalledPackages(0)

    return installedPackages.any { pkgInfo ->
        val pkg = pkgInfo.packageName

        if (pkg == ownPackage) return@any false
        if (isSystemPackage(pkg)) return@any false

        try {
            Settings.canDrawOverlays(context.createPackageContext(pkg, 0))
        } catch (_: Exception) {
            false
        }
    }
}

private fun isSystemPackage(pkg: String): Boolean =
    pkg.startsWith("com.android.") ||
    pkg.startsWith("com.google.android.") ||
    pkg == "android" ||
    pkg.startsWith("com.samsung.") ||
    pkg.startsWith("com.sec.") ||
    pkg.startsWith("com.oneplus.") ||
    pkg.startsWith("com.xiaomi.") ||
    pkg.startsWith("com.miui.") ||
    pkg.startsWith("com.huawei.") ||
    pkg.startsWith("com.oppo.") ||
    pkg.startsWith("com.coloros.") ||
    pkg.startsWith("com.vivo.")

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
