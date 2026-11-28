package com.shidongliang.vinledger

import android.graphics.Color as AndroidColor
import android.graphics.Rect
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.view.CameraController
import androidx.camera.view.LifecycleCameraController
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.viewinterop.AndroidView
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * ML Kit 条码分析：
 * - 只识别取景框内的区域（cropRect），框外内容不再误触
 * - 每两帧取一帧，降低发热
 * - 识别成功后 1.2 秒内不重复回调
 */
class BarcodeAnalyzer(
    private val gate: () -> Boolean,
    private val roiFraction: () -> FloatArray,
    private val onResult: (String, String) -> Unit
) : ImageAnalysis.Analyzer {

    private val scanner = BarcodeScanning.getClient()
    private var lastAt = 0L
    private var busy = false
    private var frameIndex = 0

    override fun analyze(image: ImageProxy) {
        frameIndex++
        if (busy || !gate() || frameIndex % 2 != 0) {
            image.close()
            return
        }
        val media = image.image
        if (media == null) {
            image.close()
            return
        }
        busy = true
        val input = InputImage.fromMediaImage(media, image.imageInfo.rotationDegrees)
        runCatching {
            val f = roiFraction()
            if (f.size == 4) {
                val w = media.width
                val h = media.height
                input.cropRect = Rect(
                    (f[0] * w).toInt().coerceIn(0, w - 1),
                    (f[1] * h).toInt().coerceIn(0, h - 1),
                    (f[2] * w).toInt().coerceIn(1, w),
                    (f[3] * h).toInt().coerceIn(1, h)
                )
            }
        }
        scanner.process(input)
            .addOnSuccessListener { list ->
                val hit = list.firstOrNull { !it.rawValue.isNullOrEmpty() }
                if (hit != null) {
                    val now = System.currentTimeMillis()
                    if (now - lastAt >= 1200) {
                        lastAt = now
                        onResult(hit.rawValue.orEmpty(), formatName(hit.format))
                    }
                }
            }
            .addOnCompleteListener {
                image.close()
                busy = false
            }
    }

    companion object {
        fun formatName(format: Int): String = when (format) {
            Barcode.FORMAT_QR_CODE -> "QR Code"
            Barcode.FORMAT_CODE_128 -> "Code 128"
            Barcode.FORMAT_CODE_39 -> "Code 39"
            Barcode.FORMAT_CODE_93 -> "Code 93"
            Barcode.FORMAT_CODABAR -> "Codabar"
            Barcode.FORMAT_EAN_8 -> "EAN-8"
            Barcode.FORMAT_EAN_13 -> "EAN-13"
            Barcode.FORMAT_UPC_A -> "UPC-A"
            Barcode.FORMAT_UPC_E -> "UPC-E"
            Barcode.FORMAT_ITF -> "ITF"
            Barcode.FORMAT_PDF417 -> "PDF417"
            Barcode.FORMAT_AZTEC -> "Aztec"
            Barcode.FORMAT_DATA_MATRIX -> "Data Matrix"
            else -> "条形码"
        }
    }
}

@Composable
fun CameraPreview(
    scanEnabled: Boolean,
    torchOn: Boolean,
    captureTick: Int,
    boxWidthDp: Float,
    boxHeightDp: Float,
    onResult: (String, String) -> Unit,
    onCapture: (File?) -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val configuration = LocalConfiguration.current
    val controller = remember { LifecycleCameraController(context) }
    val executor: ExecutorService = remember { Executors.newSingleThreadExecutor() }
    val captureExecutor: ExecutorService = remember { Executors.newSingleThreadExecutor() }

    val gate = rememberUpdatedState(scanEnabled)
    val callback = rememberUpdatedState(onResult)
    val captureCallback = rememberUpdatedState(onCapture)

    // 取景框占画面的比例，略放大留余量，避免手抖把码切在边线上
    val roi = remember(boxWidthDp, boxHeightDp, configuration.screenWidthDp, configuration.screenHeightDp) {
        val wFrac = (boxWidthDp / configuration.screenWidthDp.toFloat()).coerceIn(0.3f, 1f) * 1.12f
        val hFrac = (boxHeightDp / configuration.screenHeightDp.toFloat()).coerceIn(0.15f, 1f) * 1.35f
        val left = ((1f - wFrac) / 2f).coerceIn(0f, 0.4f)
        val top = ((1f - hFrac) / 2f).coerceIn(0f, 0.45f)
        floatArrayOf(left, top, 1f - left, 1f - top)
    }
    val roiState = rememberUpdatedState(roi)

    DisposableEffect(Unit) {
        controller.setEnabledUseCases(CameraController.IMAGE_CAPTURE or CameraController.IMAGE_ANALYSIS)
        val analyzer = BarcodeAnalyzer({ gate.value }, { roiState.value }, { value, format ->
            callback.value(value, format)
        })
        controller.setImageAnalysisAnalyzer(executor, analyzer)
        onDispose {
            runCatching { controller.unbind() }
            executor.shutdown()
            captureExecutor.shutdown()
        }
    }

    LaunchedEffect(lifecycleOwner) {
        runCatching {
            controller.bindToLifecycle(lifecycleOwner)
            controller.isTapToFocusEnabled = true
            controller.isPinchToZoomEnabled = true
            controller.imageCaptureFlashMode = ImageCapture.FLASH_MODE_AUTO
        }
    }

    LaunchedEffect(torchOn) {
        runCatching { controller.enableTorch(torchOn) }
    }

    LaunchedEffect(captureTick) {
        if (captureTick <= 0) return@LaunchedEffect
        val file = File(context.cacheDir, "scan_${System.currentTimeMillis()}.jpg")
        val options = ImageCapture.OutputFileOptions.Builder(file).build()
        runCatching {
            controller.takePicture(options, captureExecutor, object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) {
                    captureCallback.value(file)
                }

                override fun onError(exception: ImageCaptureException) {
                    captureCallback.value(null)
                }
            })
        }.onFailure { captureCallback.value(null) }
    }

    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { ctx ->
            PreviewView(ctx).apply {
                this.controller = controller
                scaleType = PreviewView.ScaleType.FILL_CENTER
                setBackgroundColor(AndroidColor.BLACK)
            }
        }
    )
}
