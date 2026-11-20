package com.liuyunli.proxy

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.common.HybridBinarizer
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 极简扫码页：CameraX 预览 + zxing 直接解 YUV 灰度平面。
 * 不引 native so、不联网、不走图片预览，单帧解不出就丢，只认 LYVPN1: 配置串。
 */
class ScanActivity : ComponentActivity() {

    private val reader: MultiFormatReader = MultiFormatReader().apply {
        setHints(
            mapOf(
                DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE),
                DecodeHintType.TRY_HARDER to true,
                DecodeHintType.CHARACTER_SET to "UTF-8",
            )
        )
    }
    private val busy = AtomicBoolean(false)
    private val hintState = mutableStateOf<String?>(null)
    private val ready = mutableStateOf(false)

    private val analyzerExecutor: ExecutorService by lazy { Executors.newSingleThreadExecutor() }

    private val permLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            ready.value = granted
            if (!granted) hintState.value = "没有相机权限，扫不了码。可改用「从剪贴板导入」，或去系统设置里给相机权限。"
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = Color.Black) {
                    Box(modifier = Modifier.fillMaxSize()) {
                        if (ready.value) {
                            CameraPane(onFrame = ::analyze)
                        } else {
                            Text(
                                text = "需要相机权限才能扫码",
                                color = Color.White,
                                modifier = Modifier.align(Alignment.Center)
                            )
                        }
                        Column(
                            modifier = Modifier.align(Alignment.TopCenter).padding(top = 40.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text("对准另一台机器上的二维码", color = Color.White)
                            hintState.value?.let {
                                Text(
                                    text = it,
                                    color = Color(0xFFFFB4AB),
                                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 6.dp)
                                )
                            }
                        }
                        TextButton(
                            onClick = { finishCancelled() },
                            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 40.dp)
                        ) {
                            Text("取消", color = Color.White)
                        }
                        if (!ready.value && hintState.value == null) {
                            CircularProgressIndicator(
                                modifier = Modifier.align(Alignment.Center).padding(top = 80.dp)
                            )
                        }
                    }
                }
                LaunchedEffect(Unit) {
                    val granted = ContextCompat.checkSelfPermission(
                        this@ScanActivity, Manifest.permission.CAMERA
                    ) == PackageManager.PERMISSION_GRANTED
                    if (granted) ready.value = true
                    else permLauncher.launch(Manifest.permission.CAMERA)
                }
            }
        }
    }

    private fun finishCancelled() {
        setResult(Activity.RESULT_CANCELED)
        finish()
    }

    private fun finishWith(text: String) {
        setResult(Activity.RESULT_OK, Intent().putExtra(EXTRA_TEXT, text))
        finish()
    }

    /** 逐帧解码：只取 Y 平面做灰度，命中即回传 */
    private fun analyze(proxy: ImageProxy) {
        if (!busy.compareAndSet(false, true)) {
            proxy.close()
            return
        }
        try {
            val w = proxy.width
            val h = proxy.height
            val plane = proxy.planes[0]
            val data = yPlaneCopy(plane, w, h) ?: return
            val source = PlanarYUVLuminanceSource(data, w, h, 0, 0, w, h, false)
            val result = runCatching {
                reader.decodeWithState(BinaryBitmap(HybridBinarizer(source)))
            }.getOrNull() ?: return
            val text = result.text ?: return
            if (ConfigTransfer.parse(text) != null) {
                runOnUiThread { finishWith(text) }
            }
        } finally {
            runCatching { reader.reset() }
            busy.set(false)
            proxy.close()
        }
    }

    /** YUV_420_888 的 Y 平面可能带行尾填充，按 rowStride 拉平成紧密数组 */
    private fun yPlaneCopy(plane: ImageProxy.PlaneProxy, w: Int, h: Int): ByteArray? {
        val buffer = plane.buffer
        val rowStride = plane.rowStride
        if (rowStride <= 0) return null
        buffer.rewind()
        if (rowStride == w) {
            if (buffer.remaining() < w * h) return null
            return ByteArray(w * h).also { buffer.get(it) }
        }
        val out = ByteArray(w * h)
        val row = ByteArray(rowStride)
        for (y in 0 until h) {
            val n = minOf(rowStride, buffer.remaining())
            if (n <= 0) break
            buffer.get(row, 0, n)
            System.arraycopy(row, 0, out, y * w, minOf(w, n))
        }
        return out
    }

    override fun onDestroy() {
        super.onDestroy()
        runCatching { analyzerExecutor.shutdownNow() }
    }

    companion object {
        const val EXTRA_TEXT = "text"
    }
}

@Composable
private fun CameraPane(onFrame: (ImageProxy) -> Unit) {
    val context = LocalContext.current
    val host = context as ComponentActivity
    val view = remember {
        PreviewView(context).apply {
            scaleType = PreviewView.ScaleType.FILL_CENTER
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        }
    }
    DisposableEffect(Unit) {
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            val provider = runCatching { future.get() }.getOrNull() ?: return@addListener
            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(view.surfaceProvider)
            }
            val analysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
            analysis.setAnalyzer(Executors.newSingleThreadExecutor()) { proxy -> onFrame(proxy) }
            runCatching {
                provider.unbindAll()
                provider.bindToLifecycle(host, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
            }
        }, ContextCompat.getMainExecutor(context))
        onDispose {
            runCatching { ProcessCameraProvider.getInstance(context).get().unbindAll() }
        }
    }
    AndroidView(factory = { view }, modifier = Modifier.fillMaxSize())
}
