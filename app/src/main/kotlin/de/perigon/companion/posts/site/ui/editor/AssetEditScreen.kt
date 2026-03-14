package de.perigon.companion.posts.site.ui.editor

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.WrapText
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import kotlinx.coroutines.launch

private val HighlightBg       = Color(0xFFFFEB3B)
private val ActiveHighlightBg = Color(0xFFFF9800)
private val HighlightFg       = Color(0xFF000000)

@Composable
fun AssetEditScreen(
    navController: NavController,
    assetId:       Long,
    vm:            AssetEditViewModel = hiltViewModel(),
) {
    val state        by vm.state.collectAsStateWithLifecycle()
    val snackbarHost = remember { SnackbarHostState() }

    LaunchedEffect(assetId) { vm.load(assetId) }

    LaunchedEffect(Unit) {
        vm.snackbar.events.collect { snackbarHost.showSnackbar(it) }
    }

    Scaffold(
        topBar = {
            AssetEditTopBar(
                path           = state.asset?.path ?: "",
                isDirty        = state.isDirty,
                wrapLines      = state.wrapLines,
                isSearchOpen   = state.isSearchOpen,
                onBack         = { if (state.isDirty) vm.save(); navController.popBackStack() },
                onSave         = vm::save,
                onToggleWrap   = vm::toggleWrapLines,
                onToggleSearch = vm::toggleSearch,
            )
        },
        snackbarHost = { SnackbarHost(snackbarHost) },
    ) { padding ->
        Column(
            Modifier
                .padding(padding)
                .fillMaxSize()
        ) {
            if (state.isSearchOpen) {
                SearchReplaceBar(
                    searchQuery     = state.searchQuery,
                    replaceText     = state.replaceText,
                    matchCount      = state.matchRanges.size,
                    currentIndex    = state.currentMatchIndex,
                    isReplaceOpen   = state.isReplaceOpen,
                    onSearchChange  = vm::setSearchQuery,
                    onReplaceChange = vm::setReplaceText,
                    onNext          = vm::nextMatch,
                    onPrevious      = vm::previousMatch,
                    onReplaceAll    = vm::replaceAll,
                    onToggleReplace = vm::toggleReplace,
                    onClose         = vm::toggleSearch,
                )
            }

            if (state.asset == null) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            } else {
                Editor(
                    content           = state.editContent,
                    wrapLines         = state.wrapLines,
                    matchRanges       = state.matchRanges,
                    currentMatchIndex = state.currentMatchIndex,
                    onContent         = vm::setContent,
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AssetEditTopBar(
    path:           String,
    isDirty:        Boolean,
    wrapLines:      Boolean,
    isSearchOpen:   Boolean,
    onBack:         () -> Unit,
    onSave:         () -> Unit,
    onToggleWrap:   () -> Unit,
    onToggleSearch: () -> Unit,
) {
    TopAppBar(
        title = {
            Column {
                Text(
                    path.substringAfterLast('/').ifEmpty { path },
                    style    = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                )
                if (isDirty) {
                    Text(
                        "Unsaved changes",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.tertiary,
                    )
                }
            }
        },
        navigationIcon = {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
            }
        },
        actions = {
            IconButton(onClick = onToggleSearch) {
                Icon(
                    Icons.Default.Search,
                    "Search",
                    tint = if (isSearchOpen) MaterialTheme.colorScheme.primary
                           else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = onToggleWrap) {
                Icon(
                    Icons.AutoMirrored.Filled.WrapText,
                    "Toggle line wrap",
                    tint = if (wrapLines) MaterialTheme.colorScheme.primary
                           else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = onSave, enabled = isDirty) {
                Icon(Icons.Default.Save, "Save")
            }
        },
    )
}

@Composable
private fun SearchReplaceBar(
    searchQuery:     String,
    replaceText:     String,
    matchCount:      Int,
    currentIndex:    Int,
    isReplaceOpen:   Boolean,
    onSearchChange:  (String) -> Unit,
    onReplaceChange: (String) -> Unit,
    onNext:          () -> Unit,
    onPrevious:      () -> Unit,
    onReplaceAll:    () -> Unit,
    onToggleReplace: () -> Unit,
    onClose:         () -> Unit,
) {
    Surface(
        color           = MaterialTheme.colorScheme.surfaceContainerHigh,
        shadowElevation = 2.dp,
    ) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)) {
            Row(
                verticalAlignment     = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                IconButton(onClick = onToggleReplace, modifier = Modifier.size(32.dp)) {
                    Icon(
                        if (isReplaceOpen) Icons.Default.UnfoldLess else Icons.Default.UnfoldMore,
                        contentDescription = "Toggle replace",
                        modifier = Modifier.size(18.dp),
                    )
                }

                OutlinedTextField(
                    value         = searchQuery,
                    onValueChange = onSearchChange,
                    placeholder   = { Text("Search", style = MaterialTheme.typography.bodySmall) },
                    singleLine    = true,
                    textStyle     = MaterialTheme.typography.bodySmall.copy(
                        fontFamily = FontFamily.Monospace,
                    ),
                    modifier = Modifier.weight(1f),
                )

                Text(
                    if (matchCount > 0) "${currentIndex + 1}/$matchCount" else "0",
                    style = MaterialTheme.typography.labelMedium,
                    color = if (matchCount > 0) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                )

                IconButton(onClick = onPrevious, enabled = matchCount > 0, modifier = Modifier.size(32.dp)) {
                    Icon(Icons.Default.KeyboardArrowUp, "Previous match", modifier = Modifier.size(18.dp))
                }
                IconButton(onClick = onNext, enabled = matchCount > 0, modifier = Modifier.size(32.dp)) {
                    Icon(Icons.Default.KeyboardArrowDown, "Next match", modifier = Modifier.size(18.dp))
                }

                IconButton(onClick = onClose, modifier = Modifier.size(32.dp)) {
                    Icon(Icons.Default.Close, "Close search", modifier = Modifier.size(18.dp))
                }
            }

            if (isReplaceOpen) {
                Spacer(Modifier.height(6.dp))
                Row(
                    verticalAlignment     = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Spacer(Modifier.width(32.dp))

                    OutlinedTextField(
                        value         = replaceText,
                        onValueChange = onReplaceChange,
                        placeholder   = { Text("Replace", style = MaterialTheme.typography.bodySmall) },
                        singleLine    = true,
                        textStyle     = MaterialTheme.typography.bodySmall.copy(
                            fontFamily = FontFamily.Monospace,
                        ),
                        modifier = Modifier.weight(1f),
                    )

                    FilledTonalButton(
                        onClick        = onReplaceAll,
                        enabled        = matchCount > 0,
                        contentPadding = PaddingValues(horizontal = 12.dp),
                    ) {
                        Text("All", style = MaterialTheme.typography.labelMedium)
                    }

                    Spacer(Modifier.width(32.dp))
                }
            }
        }
    }
}

@Composable
private fun Editor(
    content:           String,
    wrapLines:         Boolean,
    matchRanges:       List<IntRange>,
    currentMatchIndex: Int,
    onContent:         (String) -> Unit,
) {
    val textStyle = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontSize   = 13.sp,
        lineHeight = 20.sp,
        color      = MaterialTheme.colorScheme.onSurface,
    )

    var textFieldValue by remember { mutableStateOf(TextFieldValue(content)) }

    LaunchedEffect(content) {
        if (content != textFieldValue.text) {
            textFieldValue = textFieldValue.copy(text = content)
        }
    }

    val annotatedText = remember(textFieldValue.text, matchRanges, currentMatchIndex) {
        buildHighlightedText(textFieldValue.text, matchRanges, currentMatchIndex)
    }

    var textLayoutResult by remember { mutableStateOf<TextLayoutResult?>(null) }
    val vertScrollState  = rememberScrollState()
    val horizScrollState = rememberScrollState()
    val coroutineScope   = rememberCoroutineScope()

    LaunchedEffect(currentMatchIndex, matchRanges, textLayoutResult) {
        val layout = textLayoutResult ?: return@LaunchedEffect
        if (currentMatchIndex < 0 || currentMatchIndex >= matchRanges.size) return@LaunchedEffect
        val range = matchRanges[currentMatchIndex]
        if (range.first >= layout.layoutInput.text.length) return@LaunchedEffect

        val rect = layout.getBoundingBox(range.first)
        val targetY = (rect.top - 100f).coerceAtLeast(0f).toInt()

        coroutineScope.launch {
            vertScrollState.animateScrollTo(targetY)
        }
        if (!wrapLines) {
            val targetX = (rect.left - 100f).coerceAtLeast(0f).toInt()
            coroutineScope.launch {
                horizScrollState.animateScrollTo(targetX)
            }
        }
    }

    val fieldModifier = if (wrapLines) {
        Modifier
            .fillMaxSize()
            .padding(horizontal = 12.dp, vertical = 8.dp)
    } else {
        Modifier
            .fillMaxHeight()
            .horizontalScroll(horizScrollState)
            .padding(horizontal = 12.dp, vertical = 8.dp)
            .widthIn(min = 2000.dp)
    }

    Box(
        Modifier
            .fillMaxSize()
            .verticalScroll(vertScrollState)
    ) {
        BasicTextField(
            value         = textFieldValue,
            onValueChange = { newValue ->
                textFieldValue = newValue
                if (newValue.text != content) {
                    onContent(newValue.text)
                }
            },
            textStyle     = textStyle,
            cursorBrush   = SolidColor(MaterialTheme.colorScheme.primary),
            onTextLayout  = { textLayoutResult = it },
            decorationBox = { innerTextField ->
                Box {
                    androidx.compose.foundation.text.BasicText(
                        text     = annotatedText,
                        style    = textStyle,
                    )
                    Box(modifier = Modifier.matchParentSize()) {
                        innerTextField()
                    }
                }
            },
            modifier = fieldModifier.fillMaxWidth(),
        )
    }
}

private fun buildHighlightedText(
    text:              String,
    matchRanges:       List<IntRange>,
    currentMatchIndex: Int,
): AnnotatedString {
    if (matchRanges.isEmpty()) return AnnotatedString(text)

    val passiveStyle = SpanStyle(background = HighlightBg, color = HighlightFg)
    val activeStyle  = SpanStyle(background = ActiveHighlightBg, color = HighlightFg)

    return buildAnnotatedString {
        append(text)
        matchRanges.forEachIndexed { index, range ->
            if (range.first >= 0 && range.last < text.length) {
                addStyle(
                    if (index == currentMatchIndex) activeStyle else passiveStyle,
                    range.first,
                    range.last + 1,
                )
            }
        }
    }
}
