package de.perigon.companion.media.ui.prep

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.RotateLeft
import androidx.compose.material.icons.automirrored.filled.RotateRight
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCut
import androidx.compose.material.icons.filled.Flip
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.exifinterface.media.ExifInterface
import androidx.media3.common.MediaItem as ExoMediaItem
import androidx.media3.common.Effect
import androidx.media3.common.VideoSize
import androidx.media3.common.Player
import androidx.media3.effect.ScaleAndRotateTransformation
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import de.perigon.companion.media.domain.CropHandle
import de.perigon.companion.media.domain.CropRect
import de.perigon.companion.media.domain.MediaType
import de.perigon.companion.media.domain.OrientationTransform
import de.perigon.companion.media.domain.PixelCrop
import de.perigon.companion.media.domain.TransformIntent
import de.perigon.companion.media.domain.centeredToCanvas
import de.perigon.companion.media.domain.computeImageRect
import de.perigon.companion.media.domain.drawDimWithQuadCutout
import de.perigon.companion.media.domain.drawOrientationArrow
import de.perigon.companion.media.domain.drawQuadCornerHandles
import de.perigon.companion.media.domain.drawQuadGuideLines
import de.perigon.companion.media.domain.drawQuadrilateral
import de.perigon.companion.media.domain.formatTime
import de.perigon.companion.media.domain.fullFramePixelCrop
import de.perigon.companion.media.domain.handleCropDrag
import de.perigon.companion.media.domain.handleCropDragStart
import de.perigon.companion.media.domain.projectCropCorners
import de.perigon.companion.media.domain.rotatedDimensions
import de.perigon.companion.media.domain.toPixelCrop
import de.perigon.companion.media.domain.toNormalized
import de.perigon.companion.util.applyExifOrientation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.roundToInt

private const val HANDLE_HIT_SLOP_DP = 40f
private const val MIN_CROP_PX = 48f
private const val CORNER_LINE_LEN_DP = 24f
private const val CORNER_LINE_WIDTH_DP = 3f
private const val CORNER_DOT_RADIUS_DP = 6f
private const val TRIM_HANDLE_WIDTH_DP = 14f
private const val TRIM_HIT_SLOP_DP = 30f
private const val FRAME_COUNT = 12
private const val FINE_ROTATION_RANGE = 15f
private const val FINE_ROTATION_SNAP_ZONE = 0.5f
private const val MEDIA_PADDING_DP = 24f

// =============================================================================
// Main screen
// =============================================================================

@Composable
fun MediaEditScreen(
    uri: Uri,
    mediaType: MediaType,
    initialIntent: TransformIntent = TransformIntent(),
    onConfirm: (TransformIntent) -> Unit,
    onCancel: () -> Unit,
) {
    BackHandler { onCancel() }

    val context = LocalContext.current

    var orientation by remember { mutableStateOf(initialIntent.orientation) }
    var fineRotation by remember { mutableFloatStateOf(initialIntent.fineRotationDegrees) }
    var trimStartFraction by remember { mutableFloatStateOf(0f) }
    var trimEndFraction by remember { mutableFloatStateOf(1f) }
    var durationMs by remember { mutableLongStateOf(0L) }
    var trimInitialApplied by remember { mutableStateOf(false) }

    var rawBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var exifOrientation by remember { mutableIntStateOf(ExifInterface.ORIENTATION_NORMAL) }

    var frames by remember { mutableStateOf<List<Bitmap>>(emptyList()) }
    var isPlaying by remember { mutableStateOf(false) }
    var playbackFraction by remember { mutableFloatStateOf(0f) }

    // ExoPlayer reports display-oriented dimensions (native rotation already applied).
    // We track these as the "base" display size. User orientation rotations are applied on top.
    var displayVideoSize by remember { mutableStateOf(IntSize.Zero) }

    var pixelCrop by remember { mutableStateOf<PixelCrop?>(null) }

    /**
     * Compute the effective display dimensions after applying the user's
     * coarse orientation on top of the ExoPlayer display size.
     * ExoPlayer already accounts for native rotation, so we only swap
     * for the user's additional rotation.
     */
    fun effectiveVideoDimensions(): Pair<Float, Float> {
        if (displayVideoSize == IntSize.Zero) return 0f to 0f
        val userCoarse = orientation.rotationDegrees % 360
        return if (userCoarse % 180 != 0) {
            displayVideoSize.height.toFloat() to displayVideoSize.width.toFloat()
        } else {
            displayVideoSize.width.toFloat() to displayVideoSize.height.toFloat()
        }
    }

    fun initPixelCrop(unrotW: Float, unrotH: Float) {
        val (rotW, rotH) = rotatedDimensions(unrotW, unrotH, fineRotation)
        pixelCrop = initialIntent.cropRect.toPixelCrop(rotW, rotH)
    }

    fun applyOrientation(newOrientation: OrientationTransform) {
        orientation = newOrientation
        pixelCrop = null
    }

    fun applyFineRotation(value: Float): Float =
        if (abs(value) < FINE_ROTATION_SNAP_ZONE) 0f else value

    val player = remember(mediaType) {
        if (mediaType == MediaType.VIDEO) {
            ExoPlayer.Builder(context).build().apply {
                setMediaItem(ExoMediaItem.fromUri(uri))
                prepare()
            }
        } else null
    }

    DisposableEffect(player) {
        val listener = player?.let {
            object : Player.Listener {
                override fun onVideoSizeChanged(size: VideoSize) {
                    if (size.width > 0 && size.height > 0) {
                        displayVideoSize = IntSize(size.width, size.height)
                    }
                }
            }
        }
        if (listener != null) player.addListener(listener)
        onDispose {
            if (listener != null) player.removeListener(listener)
            player?.release()
        }
    }

    LaunchedEffect(orientation) {
        if (player == null) return@LaunchedEffect
        val effects = buildVideoPreviewEffects(orientation)
        player.setVideoEffects(effects)
        player.prepare()
    }

    LaunchedEffect(uri, mediaType) {
        if (mediaType == MediaType.IMAGE) {
            withContext(Dispatchers.IO) {
                try {
                    val resolver = context.contentResolver
                    val exif = resolver.openInputStream(uri)?.use { stream ->
                        ExifInterface(stream).getAttributeInt(
                            ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL
                        )
                    } ?: ExifInterface.ORIENTATION_NORMAL
                    exifOrientation = exif
                    rawBitmap = resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) }
                } catch (_: Exception) {}
            }
        }
    }

    LaunchedEffect(uri, mediaType) {
        if (mediaType == MediaType.VIDEO) {
            withContext(Dispatchers.IO) {
                val retriever = MediaMetadataRetriever()
                try {
                    retriever.setDataSource(context, uri)
                    val dur = retriever.extractMetadata(
                        MediaMetadataRetriever.METADATA_KEY_DURATION
                    )?.toLongOrNull() ?: 0L
                    durationMs = dur
                    val extracted = mutableListOf<Bitmap>()
                    for (i in 0 until FRAME_COUNT) {
                        val timeUs = (dur * 1000L * i) / FRAME_COUNT
                        val frame = retriever.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                        if (frame != null) extracted += frame
                    }
                    frames = extracted
                } catch (_: Exception) {
                } finally { retriever.release() }
            }
        }
    }

    LaunchedEffect(durationMs) {
        if (durationMs > 0 && !trimInitialApplied) {
            trimInitialApplied = true
            if (initialIntent.trimStartMs > 0L)
                trimStartFraction = (initialIntent.trimStartMs.toFloat() / durationMs).coerceIn(0f, 0.98f)
            if (initialIntent.trimEndMs != Long.MAX_VALUE && initialIntent.trimEndMs <= durationMs)
                trimEndFraction = (initialIntent.trimEndMs.toFloat() / durationMs).coerceIn(0.02f, 1f)
        }
    }

    LaunchedEffect(isPlaying) {
        if (!isPlaying || player == null) return@LaunchedEffect
        while (isActive && isPlaying) {
            val pos = player.currentPosition
            val currentEndMs = (trimEndFraction * durationMs).toLong()
            if (durationMs > 0) playbackFraction = pos.toFloat() / durationMs
            if (pos >= currentEndMs) { player.pause(); isPlaying = false }
            delay(50)
        }
    }

    val trimStartMs = (trimStartFraction * durationMs).toLong()
    val trimEndMs = (trimEndFraction * durationMs).toLong()

    fun confirm() {
        val crop = pixelCrop
        val normCrop = if (crop != null && rawBitmap != null) {
            val bmp = applyCoarseTransform(rawBitmap!!, exifOrientation, orientation)
            val (rotW, rotH) = rotatedDimensions(bmp.width.toFloat(), bmp.height.toFloat(), fineRotation)
            crop.toNormalized(rotW, rotH)
        } else if (crop != null && displayVideoSize != IntSize.Zero) {
            // Use effective dimensions (ExoPlayer display size + user orientation)
            val (uw, uh) = effectiveVideoDimensions()
            val (rotW, rotH) = rotatedDimensions(uw, uh, fineRotation)
            crop.toNormalized(rotW, rotH)
        } else {
            CropRect()
        }

        onConfirm(TransformIntent(
            cropRect = normCrop,
            orientation = orientation,
            fineRotationDegrees = fineRotation,
            trimStartMs = if (mediaType == MediaType.VIDEO) trimStartMs else 0L,
            trimEndMs = if (mediaType == MediaType.VIDEO) trimEndMs else Long.MAX_VALUE,
        ))
    }

    Box(
        modifier = Modifier.fillMaxSize().background(Color.Black).systemBarsPadding(),
    ) {
        // Top bar
        Row(
            modifier = Modifier.fillMaxWidth().align(Alignment.TopCenter)
                .background(Color.Black.copy(alpha = 0.4f)).padding(8.dp).statusBarsPadding(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onCancel) { Icon(Icons.Default.Close, "Cancel", tint = Color.White) }
            Text(
                if (mediaType == MediaType.VIDEO) "Edit video" else "Edit image",
                style = MaterialTheme.typography.titleMedium, color = Color.White,
            )
            IconButton(onClick = ::confirm) { Icon(Icons.Default.Check, "Apply", tint = Color.White) }
        }

        // Media content area
        when (mediaType) {
            MediaType.IMAGE -> {
                if (rawBitmap != null) {
                    ImageCropOverlay(
                        rawBitmap = rawBitmap!!,
                        exifOrientation = exifOrientation,
                        orientation = orientation,
                        fineRotation = fineRotation,
                        pixelCrop = pixelCrop,
                        onPixelCropChanged = { pixelCrop = it },
                        onInitPixelCrop = { w, h -> initPixelCrop(w, h) },
                        modifier = Modifier.fillMaxSize().padding(
                            top = 64.dp, bottom = 200.dp,
                            start = MEDIA_PADDING_DP.dp, end = MEDIA_PADDING_DP.dp,
                        ),
                    )
                } else {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = Color.White)
                    }
                }
            }
            MediaType.VIDEO -> {
                Box(
                    modifier = Modifier.fillMaxSize().padding(
                        top = 64.dp, bottom = 280.dp,
                        start = MEDIA_PADDING_DP.dp, end = MEDIA_PADDING_DP.dp,
                    ),
                ) {
                    var containerSize by remember { mutableStateOf(IntSize.Zero) }
                    Box(
                        modifier = Modifier.fillMaxSize().onSizeChanged { containerSize = it },
                        contentAlignment = Alignment.Center,
                    ) {
                        AndroidView(
                            factory = { ctx ->
                                PlayerView(ctx).apply {
                                    this.player = player
                                    useController = false
                                    setBackgroundColor(android.graphics.Color.BLACK)
                                }
                            },
                            modifier = Modifier.fillMaxSize(),
                        )

                        // Compute frame rect using display size + user orientation only
                        val videoFrameRect = remember(containerSize, displayVideoSize, orientation) {
                            computeVideoFrameRect(containerSize, displayVideoSize, orientation)
                        }

                        if (videoFrameRect != Rect.Zero && displayVideoSize != IntSize.Zero) {
                            val (unrotW, unrotH) = effectiveVideoDimensions()

                            if (pixelCrop == null) {
                                val (rotW, rotH) = rotatedDimensions(unrotW, unrotH, fineRotation)
                                pixelCrop = initialIntent.cropRect.toPixelCrop(rotW, rotH)
                            }

                            VideoCropOverlay(
                                videoFrameRect = videoFrameRect,
                                fineRotation = fineRotation,
                                unrotW = unrotW, unrotH = unrotH,
                                pixelCrop = pixelCrop,
                                onPixelCropChanged = { pixelCrop = it },
                                orientation = orientation,
                                modifier = Modifier.fillMaxSize(),
                            )
                        }
                    }
                }
            }
        }

        // Bottom controls
        Column(
            modifier = Modifier.fillMaxWidth().align(Alignment.BottomCenter)
                .background(Color.Black.copy(alpha = 0.7f))
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // Rotation & flip controls
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = { applyOrientation(orientation.rotate90Ccw()) }) {
                    Icon(Icons.AutoMirrored.Filled.RotateLeft, "Rotate left", tint = Color.White)
                }
                IconButton(onClick = { applyOrientation(orientation.rotate90Cw()) }) {
                    Icon(Icons.AutoMirrored.Filled.RotateRight, "Rotate right", tint = Color.White)
                }
                if (mediaType == MediaType.VIDEO) {
                    IconButton(onClick = {
                        if (isPlaying) { player?.pause(); isPlaying = false }
                        else { player?.seekTo(trimStartMs); player?.play(); isPlaying = true }
                    }) {
                        Icon(
                            if (isPlaying) Icons.Default.Stop else Icons.Default.PlayArrow,
                            "Play", tint = Color.White,
                        )
                    }
                } else {
                    IconButton(onClick = {}, enabled = false) { Spacer(Modifier) }
                }
                IconButton(onClick = { applyOrientation(orientation.toggleFlipHorizontal()) }) {
                    Icon(Icons.Default.Flip, "Flip horizontal",
                        tint = if (orientation.flipHorizontal) MaterialTheme.colorScheme.primary else Color.White)
                }
                IconButton(onClick = { applyOrientation(orientation.toggleFlipVertical()) }) {
                    Icon(Icons.Default.Flip, "Flip vertical", tint = Color.White,
                        modifier = Modifier.graphicsLayer { rotationZ = 90f })
                }
            }

            // Fine rotation slider
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text("%.1f°".format(fineRotation),
                    style = MaterialTheme.typography.labelSmall, color = Color.White,
                    modifier = Modifier.width(40.dp))
                Slider(
                    value = fineRotation,
                    onValueChange = { fineRotation = applyFineRotation(it) },
                    valueRange = -FINE_ROTATION_RANGE..FINE_ROTATION_RANGE,
                    modifier = Modifier.weight(1f),
                    colors = SliderDefaults.colors(
                        thumbColor = Color.White,
                        activeTrackColor = MaterialTheme.colorScheme.primary,
                        inactiveTrackColor = Color.White.copy(alpha = 0.3f),
                    ),
                )
                IconButton(
                    onClick = { fineRotation = 0f }, modifier = Modifier.size(32.dp),
                    enabled = fineRotation != 0f,
                ) {
                    Icon(Icons.Default.Refresh, "Reset rotation",
                        tint = if (fineRotation != 0f) Color.White else Color.White.copy(alpha = 0.3f),
                        modifier = Modifier.size(16.dp))
                }
            }

            // Trim strip (video only)
            if (mediaType == MediaType.VIDEO && frames.isNotEmpty()) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(formatTime(trimStartMs), color = Color.White, style = MaterialTheme.typography.labelSmall)
                    Text("Duration: ${formatTime(trimEndMs - trimStartMs)}",
                        color = Color.White.copy(alpha = 0.7f), style = MaterialTheme.typography.labelSmall)
                    Text(formatTime(trimEndMs), color = Color.White, style = MaterialTheme.typography.labelSmall)
                }
                TrimStrip(
                    frames = frames,
                    startFraction = trimStartFraction, endFraction = trimEndFraction,
                    playbackFraction = if (isPlaying) playbackFraction else null,
                    onStartChanged = { f ->
                        trimStartFraction = f.coerceIn(0f, trimEndFraction - 0.02f)
                        if (!isPlaying) player?.seekTo((trimStartFraction * durationMs).toLong())
                    },
                    onEndChanged = { f -> trimEndFraction = f.coerceIn(trimStartFraction + 0.02f, 1f) },
                )
            }

            // Action buttons
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(onClick = onCancel, modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White),
                ) { Text("Cancel") }
                Button(onClick = ::confirm, modifier = Modifier.weight(1f)) { Text("Apply") }
            }
        }
    }
}

// ===== Video preview effects (coarse only) =====

private fun buildVideoPreviewEffects(orientation: OrientationTransform): List<Effect> {
    if (orientation.isIdentity) return emptyList()
    val builder = ScaleAndRotateTransformation.Builder()
    if (orientation.rotationDegrees != 0) builder.setRotationDegrees(-orientation.rotationDegrees.toFloat())
    if (orientation.flipHorizontal) builder.setScale(-1f, 1f)
    return listOf(builder.build())
}

/**
 * Compute the video frame rectangle within the container.
 * [displayVideoSize] is from ExoPlayer and already accounts for native rotation.
 * We only apply the user's orientation rotation on top.
 */
private fun computeVideoFrameRect(
    containerSize: IntSize,
    displayVideoSize: IntSize,
    orientation: OrientationTransform,
): Rect {
    if (containerSize.width <= 0 || containerSize.height <= 0) return Rect.Zero
    if (displayVideoSize == IntSize.Zero) return Rect.Zero
    val userCoarse = orientation.rotationDegrees % 360
    val (vw, vh) = if (userCoarse % 180 != 0) {
        displayVideoSize.height to displayVideoSize.width
    } else {
        displayVideoSize.width to displayVideoSize.height
    }
    return computeImageRect(vw, vh, containerSize)
}

// ===== Image crop overlay =====

@Composable
private fun ImageCropOverlay(
    rawBitmap: Bitmap, exifOrientation: Int,
    orientation: OrientationTransform,
    fineRotation: Float,
    pixelCrop: PixelCrop?,
    onPixelCropChanged: (PixelCrop) -> Unit,
    onInitPixelCrop: (Float, Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val hitSlop = with(density) { HANDLE_HIT_SLOP_DP.dp.toPx() }
    val cornerLen = with(density) { CORNER_LINE_LEN_DP.dp.toPx() }
    val cornerWidth = with(density) { CORNER_LINE_WIDTH_DP.dp.toPx() }
    val dotRadius = with(density) { CORNER_DOT_RADIUS_DP.dp.toPx() }
    val minCropPx = with(density) { MIN_CROP_PX.dp.toPx() }

    var canvasSize by remember { mutableStateOf(IntSize.Zero) }

    val displayBitmap = remember(rawBitmap, exifOrientation, orientation) {
        applyCoarseTransform(rawBitmap, exifOrientation, orientation)
    }
    val imageBmp = remember(displayBitmap) { displayBitmap.asImageBitmap() }
    val unrotW = displayBitmap.width.toFloat()
    val unrotH = displayBitmap.height.toFloat()
    val (rotW, rotH) = remember(unrotW, unrotH, fineRotation) {
        rotatedDimensions(unrotW, unrotH, fineRotation)
    }

    LaunchedEffect(unrotW, unrotH) {
        if (pixelCrop == null) onInitPixelCrop(unrotW, unrotH)
    }

    val imageRect = remember(canvasSize, displayBitmap.width, displayBitmap.height) {
        computeImageRect(displayBitmap.width, displayBitmap.height, canvasSize)
    }

    val crop = pixelCrop ?: fullFramePixelCrop(rotW, rotH)
    var activeHandle by remember { mutableStateOf(CropHandle.NONE) }

    val currentCrop by rememberUpdatedState(crop)
    val currentOnPixelCropChanged by rememberUpdatedState(onPixelCropChanged)
    val currentImageRect by rememberUpdatedState(imageRect)
    val currentFineRotation by rememberUpdatedState(fineRotation)
    val currentRotW by rememberUpdatedState(rotW)
    val currentRotH by rememberUpdatedState(rotH)

    val dimColor = Color.Black.copy(alpha = 0.5f)
    val guideColor = Color.White.copy(alpha = 0.4f)
    val accentColor = Color.White
    val arrowColor = Color.Yellow

    Box(
        modifier = modifier
            .onSizeChanged { canvasSize = it }
            .pointerInput(Unit) {
                detectDragGestures(
                    onDragStart = { canvasOffset ->
                        activeHandle = handleCropDragStart(
                            canvasOffset, currentImageRect, unrotW, unrotH,
                            currentFineRotation, currentCrop, hitSlop,
                        )
                    },
                    onDrag = { change, dragAmount ->
                        if (activeHandle == CropHandle.NONE) return@detectDragGestures
                        change.consume()
                        currentOnPixelCropChanged(handleCropDrag(
                            dragAmount, currentImageRect, unrotW, unrotH,
                            currentRotW, currentRotH,
                            currentFineRotation, currentCrop, activeHandle, minCropPx,
                        ))
                    },
                    onDragEnd = { activeHandle = CropHandle.NONE },
                    onDragCancel = { activeHandle = CropHandle.NONE },
                )
            },
    ) {
        Canvas(Modifier.fillMaxSize()) {
            if (imageRect == Rect.Zero) return@Canvas

            drawImage(
                image = imageBmp,
                srcSize = androidx.compose.ui.unit.IntSize(displayBitmap.width, displayBitmap.height),
                dstOffset = androidx.compose.ui.unit.IntOffset(
                    imageRect.left.roundToInt(), imageRect.top.roundToInt()),
                dstSize = androidx.compose.ui.unit.IntSize(
                    imageRect.width.roundToInt(), imageRect.height.roundToInt()),
            )

            val imgCorners = projectCropCorners(crop, fineRotation)
            val canvasCorners = imgCorners.map { centeredToCanvas(it, imageRect, unrotW, unrotH) }

            drawDimWithQuadCutout(imageRect, canvasCorners, dimColor)
            drawQuadrilateral(canvasCorners, accentColor, cornerWidth)
            drawQuadGuideLines(canvasCorners, guideColor)
            drawQuadCornerHandles(canvasCorners, accentColor, cornerLen, cornerWidth, dotRadius)
            drawOrientationArrow(canvasCorners, arrowColor, cornerWidth, orientation.rotationDegrees)
        }
    }
}

// ===== Video crop overlay =====

@Composable
private fun VideoCropOverlay(
    videoFrameRect: Rect,
    orientation: OrientationTransform,
    fineRotation: Float,
    unrotW: Float, unrotH: Float,
    pixelCrop: PixelCrop?,
    onPixelCropChanged: (PixelCrop) -> Unit,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val hitSlop = with(density) { HANDLE_HIT_SLOP_DP.dp.toPx() }
    val cornerLen = with(density) { CORNER_LINE_LEN_DP.dp.toPx() }
    val cornerWidth = with(density) { CORNER_LINE_WIDTH_DP.dp.toPx() }
    val dotRadius = with(density) { CORNER_DOT_RADIUS_DP.dp.toPx() }
    val minCropPx = with(density) { MIN_CROP_PX.dp.toPx() }

    val (rotW, rotH) = remember(unrotW, unrotH, fineRotation) {
        rotatedDimensions(unrotW, unrotH, fineRotation)
    }

    val crop = pixelCrop ?: fullFramePixelCrop(rotW, rotH)
    var activeHandle by remember { mutableStateOf(CropHandle.NONE) }

    val currentCrop by rememberUpdatedState(crop)
    val currentOnPixelCropChanged by rememberUpdatedState(onPixelCropChanged)
    val currentVideoFrameRect by rememberUpdatedState(videoFrameRect)
    val currentFineRotation by rememberUpdatedState(fineRotation)
    val currentRotW by rememberUpdatedState(rotW)
    val currentRotH by rememberUpdatedState(rotH)

    val dimColor = Color.Black.copy(alpha = 0.4f)
    val guideColor = Color.White.copy(alpha = 0.3f)
    val accentColor = Color.White
    val arrowColor = Color.Yellow

    Box(
        modifier = modifier
            .pointerInput(Unit) {
                detectDragGestures(
                    onDragStart = { canvasOffset ->
                        activeHandle = handleCropDragStart(
                            canvasOffset, currentVideoFrameRect, unrotW, unrotH,
                            currentFineRotation, currentCrop, hitSlop,
                        )
                    },
                    onDrag = { change, dragAmount ->
                        if (activeHandle == CropHandle.NONE) return@detectDragGestures
                        change.consume()
                        currentOnPixelCropChanged(handleCropDrag(
                            dragAmount, currentVideoFrameRect, unrotW, unrotH,
                            currentRotW, currentRotH,
                            currentFineRotation, currentCrop, activeHandle, minCropPx,
                        ))
                    },
                    onDragEnd = { activeHandle = CropHandle.NONE },
                    onDragCancel = { activeHandle = CropHandle.NONE },
                )
            },
    ) {
        Canvas(Modifier.fillMaxSize()) {
            if (videoFrameRect == Rect.Zero) return@Canvas

            val imgCorners = projectCropCorners(crop, fineRotation)
            val canvasCorners = imgCorners.map { centeredToCanvas(it, videoFrameRect, unrotW, unrotH) }

            drawDimWithQuadCutout(videoFrameRect, canvasCorners, dimColor)
            drawQuadrilateral(canvasCorners, accentColor, cornerWidth)
            drawQuadGuideLines(canvasCorners, guideColor)
            drawQuadCornerHandles(canvasCorners, accentColor, cornerLen, cornerWidth, dotRadius)
            drawOrientationArrow(canvasCorners, arrowColor, cornerWidth, orientation.rotationDegrees)
        }
    }
}

// ===== Trim strip =====

private enum class TrimDragTarget { NONE, START, END }

@Composable
private fun TrimStrip(
    frames: List<Bitmap>, startFraction: Float, endFraction: Float,
    playbackFraction: Float?,
    onStartChanged: (Float) -> Unit, onEndChanged: (Float) -> Unit,
) {
    val density = LocalDensity.current
    val handleWidthPx = with(density) { TRIM_HANDLE_WIDTH_DP.dp.toPx() }
    val hitSlopPx = with(density) { TRIM_HIT_SLOP_DP.dp.toPx() }
    var stripSize by remember { mutableStateOf(IntSize.Zero) }
    val selectedColor = MaterialTheme.colorScheme.primary
    val dimColor = Color.Black.copy(alpha = 0.6f)
    val playheadColor = Color.White
    val currentStart by rememberUpdatedState(startFraction)
    val currentEnd by rememberUpdatedState(endFraction)
    val currentOnStart by rememberUpdatedState(onStartChanged)
    val currentOnEnd by rememberUpdatedState(onEndChanged)
    var dragTarget by remember { mutableStateOf(TrimDragTarget.NONE) }

    Box(
        modifier = Modifier.fillMaxWidth().height(48.dp).clip(RoundedCornerShape(4.dp))
            .onSizeChanged { stripSize = it }
            .pointerInput(Unit) {
                detectDragGestures(
                    onDragStart = { offset ->
                        if (stripSize.width <= 0) return@detectDragGestures
                        val startPx = currentStart * stripSize.width
                        val endPx = currentEnd * stripSize.width
                        val dStart = abs(offset.x - startPx)
                        val dEnd = abs(offset.x - endPx)
                        dragTarget = when {
                            dStart <= hitSlopPx && dEnd <= hitSlopPx ->
                                if (dStart <= dEnd) TrimDragTarget.START else TrimDragTarget.END
                            dStart <= hitSlopPx -> TrimDragTarget.START
                            dEnd <= hitSlopPx -> TrimDragTarget.END
                            else -> TrimDragTarget.NONE
                        }
                    },
                    onDrag = { change, dragAmount ->
                        if (stripSize.width <= 0 || dragTarget == TrimDragTarget.NONE) return@detectDragGestures
                        change.consume()
                        val delta = dragAmount.x / stripSize.width
                        when (dragTarget) {
                            TrimDragTarget.START -> currentOnStart(currentStart + delta)
                            TrimDragTarget.END -> currentOnEnd(currentEnd + delta)
                            TrimDragTarget.NONE -> Unit
                        }
                    },
                    onDragEnd = { dragTarget = TrimDragTarget.NONE },
                    onDragCancel = { dragTarget = TrimDragTarget.NONE },
                )
            },
    ) {
        Canvas(Modifier.fillMaxSize()) {
            if (frames.isEmpty() || stripSize.width == 0) return@Canvas
            val frameWidth = size.width / frames.size
            frames.forEachIndexed { i, frame ->
                drawImage(
                    image = frame.asImageBitmap(),
                    srcSize = androidx.compose.ui.unit.IntSize(frame.width, frame.height),
                    dstOffset = androidx.compose.ui.unit.IntOffset((i * frameWidth).toInt(), 0),
                    dstSize = androidx.compose.ui.unit.IntSize(frameWidth.toInt(), size.height.toInt()),
                )
            }
            drawRect(dimColor, androidx.compose.ui.geometry.Offset.Zero, Size(size.width * startFraction, size.height))
            drawRect(dimColor, androidx.compose.ui.geometry.Offset(size.width * endFraction, 0f),
                Size(size.width * (1f - endFraction), size.height))
            val left = size.width * startFraction
            val right = size.width * endFraction
            drawRect(selectedColor, androidx.compose.ui.geometry.Offset(left, 0f), Size(right - left, 3f))
            drawRect(selectedColor, androidx.compose.ui.geometry.Offset(left, size.height - 3f), Size(right - left, 3f))
            drawRect(selectedColor, androidx.compose.ui.geometry.Offset(left, 0f), Size(handleWidthPx, size.height))
            drawRect(selectedColor, androidx.compose.ui.geometry.Offset(right - handleWidthPx, 0f), Size(handleWidthPx, size.height))
            if (playbackFraction != null) {
                val px = size.width * playbackFraction
                drawLine(playheadColor, androidx.compose.ui.geometry.Offset(px, 0f),
                    androidx.compose.ui.geometry.Offset(px, size.height), strokeWidth = 2f)
            }
        }
    }
}

// ===== Coarse transform for display bitmap =====

private fun applyCoarseTransform(raw: Bitmap, exifOrientation: Int, orientation: OrientationTransform): Bitmap {
    var bmp = applyExifOrientation(raw, exifOrientation)
    if (!orientation.isIdentity) {
        val m = android.graphics.Matrix()
        if (orientation.flipHorizontal) m.postScale(-1f, 1f)
        if (orientation.rotationDegrees != 0) m.postRotate(orientation.rotationDegrees.toFloat())
        val oriented = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
        if (oriented !== bmp && bmp !== raw) bmp.recycle()
        bmp = oriented
    }
    return bmp
}
