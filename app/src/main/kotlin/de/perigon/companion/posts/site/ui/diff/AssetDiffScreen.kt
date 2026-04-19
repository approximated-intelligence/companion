package de.perigon.companion.posts.site.ui.diff

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController

private val AddedBg       = Color(0x3300C853)
private val RemovedBg     = Color(0x33D50000)
private val AddedWordBg   = Color(0x6600C853)
private val RemovedWordBg = Color(0x66D50000)
private val GutterBg      = Color(0xFFF5F5F5)
private val GutterText    = Color(0xFF999999)

@Composable
fun AssetDiffScreen(
    navController: NavController,
    assetId:       Long,
    vm:            AssetDiffViewModel = hiltViewModel(),
) {
    val state by vm.state.collectAsStateWithLifecycle()

    LaunchedEffect(assetId) { vm.load(assetId) }

    Scaffold(
        topBar = {
            DiffTopBar(
                path   = state.path,
                onBack = { navController.popBackStack() },
            )
        },
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            when {
                state.isLoading -> {
                    CircularProgressIndicator(Modifier.align(Alignment.Center))
                }
                state.error != null -> {
                    Column(
                        Modifier.align(Alignment.Center).padding(32.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text("Error", style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.error)
                        Text(state.error!!, style = MaterialTheme.typography.bodyMedium)
                    }
                }
                state.lines.isEmpty() -> {
                    Text("No differences",
                        Modifier.align(Alignment.Center),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                else -> {
                    DiffContent(state.lines)
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DiffTopBar(path: String, onBack: () -> Unit) {
    TopAppBar(
        title = {
            Column {
                Text(
                    path.substringAfterLast('/').ifEmpty { path },
                    style    = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                )
                Text("Server vs Local",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        navigationIcon = {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
            }
        },
    )
}

@Composable
private fun DiffContent(lines: List<DiffLine>) {
    val horizScroll = rememberScrollState()

    Box(Modifier.horizontalScroll(horizScroll)) {
        LazyColumn(
            modifier = Modifier
                .fillMaxHeight()
                .widthIn(min = 1200.dp),
        ) {
            itemsIndexed(lines) { _, line ->
                DiffLineRow(line)
            }
        }
    }
}

@Composable
private fun DiffLineRow(line: DiffLine, modifier: Modifier = Modifier) {
    val lineBg = when (line.tag) {
        DiffLineTag.DELETE -> RemovedBg
        DiffLineTag.INSERT -> AddedBg
        DiffLineTag.EQUAL  -> Color.Transparent
    }

    val prefix = when (line.tag) {
        DiffLineTag.DELETE -> "−"
        DiffLineTag.INSERT -> "+"
        DiffLineTag.EQUAL  -> " "
    }

    Row(
        modifier
            .fillMaxWidth()
            .background(lineBg)
            .padding(vertical = 1.dp),
    ) {
        Box(
            Modifier
                .width(72.dp)
                .background(GutterBg)
                .padding(horizontal = 4.dp, vertical = 2.dp),
        ) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(
                    line.oldLineNo?.toString() ?: "",
                    style    = MaterialTheme.typography.labelSmall.copy(
                        fontFamily = FontFamily.Monospace, fontSize = 11.sp),
                    color    = GutterText,
                    modifier = Modifier.width(28.dp),
                )
                Text(
                    line.newLineNo?.toString() ?: "",
                    style    = MaterialTheme.typography.labelSmall.copy(
                        fontFamily = FontFamily.Monospace, fontSize = 11.sp),
                    color    = GutterText,
                    modifier = Modifier.width(28.dp),
                )
            }
        }

        Text(
            prefix,
            style    = MaterialTheme.typography.bodySmall.copy(
                fontFamily = FontFamily.Monospace, fontSize = 13.sp,
                fontWeight = FontWeight.Bold),
            color    = when (line.tag) {
                DiffLineTag.DELETE -> Color(0xFFD50000)
                DiffLineTag.INSERT -> Color(0xFF00C853)
                DiffLineTag.EQUAL  -> GutterText
            },
            modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
        )

        Text(
            text     = buildSegmentAnnotatedString(line.segments),
            style    = MaterialTheme.typography.bodySmall.copy(
                fontFamily = FontFamily.Monospace, fontSize = 13.sp, lineHeight = 18.sp),
            modifier = Modifier.padding(vertical = 2.dp, horizontal = 4.dp),
            softWrap = false,
        )
    }
}

private fun buildSegmentAnnotatedString(segments: List<DiffSegment>): AnnotatedString =
    buildAnnotatedString {
        for (segment in segments) {
            when (segment) {
                is DiffSegment.Equal -> append(segment.text)
                is DiffSegment.Added -> {
                    withStyle(SpanStyle(background = AddedWordBg, fontWeight = FontWeight.Bold)) {
                        append(segment.text)
                    }
                }
                is DiffSegment.Removed -> {
                    withStyle(SpanStyle(background = RemovedWordBg, fontWeight = FontWeight.Bold)) {
                        append(segment.text)
                    }
                }
            }
        }
    }
