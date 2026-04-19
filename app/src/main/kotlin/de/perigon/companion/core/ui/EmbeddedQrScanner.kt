package de.perigon.companion.core.ui

import android.Manifest
import android.content.pm.PackageManager
import android.util.Size
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.zxing.BinaryBitmap
import com.google.zxing.MultiFormatReader
import com.google.zxing.NotFoundException
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.common.HybridBinarizer
import java.util.concurrent.Executors

@Composable
fun EmbeddedQrScanner(
    prompt: String,
    onResult: (String) -> Unit,
    onCancel: () -> Unit,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    var hasCameraPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED
        )
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasCameraPermission = granted
    }

    LaunchedEffect(Unit) {
        if (!hasCameraPermission) {
            permissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    if (!hasCameraPermission) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.padding(32.dp),
            ) {
                Text("Camera permission required to scan QR codes.",
                    style = MaterialTheme.typography.bodyMedium)
                Button(onClick = { permissionLauncher.launch(Manifest.permission.CAMERA) }) {
                    Text("Grant permission")
                }
            }
        }
        return
    }

    var decoded by remember { mutableStateOf(false) }
    val executor = remember { Executors.newSingleThreadExecutor() }
    val reader = remember { MultiFormatReader() }

    Box(Modifier.fillMaxSize()) {
        AndroidView(
            factory = { ctx ->
                val previewView = PreviewView(ctx)

                val cameraProviderFuture = ProcessCameraProvider.getInstance(ctx)
                cameraProviderFuture.addListener({
                    val cameraProvider = cameraProviderFuture.get()

                    val preview = Preview.Builder().build().also {
                        it.surfaceProvider = previewView.surfaceProvider
                    }

                    val resolutionSelector = ResolutionSelector.Builder()
                        .setResolutionStrategy(
                            ResolutionStrategy(
                                Size(1280, 720),
                                ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER
                            )
                        )
                        .build()

                    val analysis = ImageAnalysis.Builder()
                        .setResolutionSelector(resolutionSelector)
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .build()

                    analysis.setAnalyzer(executor) { imageProxy ->
                        if (decoded) {
                            imageProxy.close()
                            return@setAnalyzer
                        }

                        val plane = imageProxy.planes[0]
                        val buffer = plane.buffer
                        val rowStride = plane.rowStride
                        val width = imageProxy.width
                        val height = imageProxy.height

                        val bytes = if (rowStride == width) {
                            ByteArray(buffer.remaining()).also { buffer.get(it) }
                        } else {
                            ByteArray(width * height).also { dest ->
                                val row = ByteArray(rowStride)
                                for (y in 0 until height) {
                                    val remaining = buffer.remaining()
                                    if (remaining <= 0) break
                                    val toCopy = minOf(rowStride, remaining)
                                    buffer.get(row, 0, toCopy)
                                    row.copyInto(dest, y * width, 0, minOf(width, toCopy))
                                }
                            }
                        }
                        // disabled // val bytes = if (rowStride == width) {
                        // disabled //     ByteArray(buffer.remaining()).also { buffer.get(it) }
                        // disabled // } else {
                        // disabled //     ByteArray(width * height).also { dest ->
                        // disabled //         val row = ByteArray(rowStride)
                        // disabled //         for (y in 0 until height) {
                        // disabled //             buffer.get(row)
                        // disabled //             row.copyInto(dest, y * width, 0, width)
                        // disabled //         }
                        // disabled //     }
                        // disabled // }

                        val source = PlanarYUVLuminanceSource(
                            bytes, width, height, 0, 0, width, height, false,
                        )
                        val bitmap = BinaryBitmap(HybridBinarizer(source))

                        try {
                            val result = reader.decodeWithState(bitmap)
                            decoded = true
                            onResult(result.text)
                        } catch (_: NotFoundException) {
                            // No QR found in this frame
                        } finally {
                            reader.reset()
                            imageProxy.close()
                        }
                    }
                    // disabled // analysis.setAnalyzer(executor) { imageProxy ->
                    // disabled //     if (decoded) {
                    // disabled //         imageProxy.close()
                    // disabled //         return@setAnalyzer
                    // disabled //     }

                    // disabled //     val plane = imageProxy.planes[0]
                    // disabled //     val buffer = plane.buffer
                    // disabled //     val bytes = ByteArray(buffer.remaining())
                    // disabled //     buffer.get(bytes)

                    // disabled //     val source = PlanarYUVLuminanceSource(
                    // disabled //         bytes,
                    // disabled //         imageProxy.width,
                    // disabled //         imageProxy.height,
                    // disabled //         0, 0,
                    // disabled //         imageProxy.width,
                    // disabled //         imageProxy.height,
                    // disabled //         false,
                    // disabled //     )
                    // disabled //     val bitmap = BinaryBitmap(HybridBinarizer(source))

                    // disabled //     try {
                    // disabled //         val result = reader.decodeWithState(bitmap)
                    // disabled //         decoded = true
                    // disabled //         onResult(result.text)
                    // disabled //     } catch (_: NotFoundException) {
                    // disabled //         // No QR found in this frame
                    // disabled //     } finally {
                    // disabled //         reader.reset()
                    // disabled //         imageProxy.close()
                    // disabled //     }
                    // disabled // }

                    cameraProvider.unbindAll()
                    cameraProvider.bindToLifecycle(
                        lifecycleOwner,
                        CameraSelector.DEFAULT_BACK_CAMERA,
                        preview,
                        analysis,
                    )
                }, ContextCompat.getMainExecutor(ctx))

                previewView
            },
            modifier = Modifier.fillMaxSize(),
        )

        Text(
            prompt,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onPrimary,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 48.dp)
                .fillMaxWidth()
                .padding(horizontal = 32.dp),
        )

        IconButton(
            onClick = onCancel,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(16.dp),
        ) {
            Icon(
                Icons.Default.Close,
                contentDescription = "Cancel scan",
                tint = MaterialTheme.colorScheme.onPrimary,
            )
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            executor.shutdown()
        }
    }
}
