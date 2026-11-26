package com.shidongliang.vinledger

import android.graphics.Color as AndroidColor
import androidx.camera.core.ImageAnalysis
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.viewinterop.AndroidView
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import java.util.concurrent.Executors

/** ML Kit 条码分析：识别到码就回调，1.2 秒内不重复回调 */
class BarcodeAnalyzer(
    private val gate: () -> Boolean,
    private val onResult: (String, String) -> Unit
) : ImageAnalysis.Analyzer {

    private val scanner = BarcodeScanning.getClient()
    private var lastAt = 0L
    private var busy = false

    override fun analyze(image: ImageProxy) {
        if (busy || !gate()) {
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
    onResult: (String, String) -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val controller = remember { LifecycleCameraController(context) }
    val gate = rememberUpdatedState(scanEnabled)
    val callback = rememberUpdatedState(onResult)

    DisposableEffect(Unit) {
        val executor = Executors.newSingleThreadExecutor()
        controller.setEnabledUseCases(CameraController.IMAGE_ANALYSIS)
        val analyzer = BarcodeAnalyzer({ gate.value }, { value, format -> callback.value(value, format) })
        controller.setImageAnalysisAnalyzer(executor, analyzer)
        onDispose {
            runCatching { controller.unbind() }
            executor.shutdown()
        }
    }

    LaunchedEffect(lifecycleOwner) {
        runCatching {
            controller.bindToLifecycle(lifecycleOwner)
            controller.isTapToFocusEnabled = true
            controller.isPinchToZoomEnabled = true
        }
    }

    LaunchedEffect(torchOn) {
        runCatching { controller.enableTorch(torchOn) }
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
