package de.perigon.companion.core.ui.access

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.perigon.companion.core.feature.AccessRequirement

/**
 * App-wide: until setup was completed once AND every required grant of every
 * feature in this build holds, show the setup screen instead of [content].
 * Everything is requested there in one pass; this gate only reappears if a
 * required grant is revoked later.
 */
@Composable
fun SetupGate(
    vm: AccessViewModel = hiltViewModel(),
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val setupDone by vm.setupDone.collectAsStateWithLifecycle()
    var epoch by remember { mutableIntStateOf(0) }

    LifecycleResumeEffect(Unit) {
        epoch++
        onPauseOrDispose { }
    }

    val requiredMissing = remember(epoch) {
        vm.all.filter { !it.optional && !vm.isSatisfied(context, it) }
    }

    if (setupDone && requiredMissing.isEmpty()) {
        content()
        return
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .systemBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("Set up", style = MaterialTheme.typography.headlineSmall)
        Text(
            "Grant everything once here and the app won't ask again while you navigate. " +
                "Optional items can be skipped.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        AccessPanel(
            requirements = vm.all,
            onStatusChanged = { epoch++ },
            vm = vm,
        )
        Button(
            onClick = vm::completeSetup,
            enabled = requiredMissing.isEmpty(),
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Continue") }
    }
}

/**
 * Destination-level: [content] only when [requirements] hold, otherwise the
 * missing ones with grant actions. For optional grants a destination depends
 * on (e.g. the built-in picker's photo access).
 */
@Composable
fun RequireAccess(
    requirements: List<AccessRequirement>,
    vm: AccessViewModel = hiltViewModel(),
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    var epoch by remember { mutableIntStateOf(0) }

    LifecycleResumeEffect(Unit) {
        epoch++
        onPauseOrDispose { }
    }

    val missing = remember(epoch, requirements) {
        requirements.filter { !vm.isSatisfied(context, it) }
    }

    if (missing.isEmpty()) {
        content()
        return
    }

    Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text("Access needed", style = MaterialTheme.typography.titleLarge)
            AccessPanel(
                requirements = missing,
                showSatisfied = false,
                onStatusChanged = { epoch++ },
                vm = vm,
            )
        }
    }
}
