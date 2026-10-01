package com.voltcu.robot

import android.content.Context
import android.graphics.RectF
import android.os.SystemClock
import android.util.Size
import androidx.camera.core.CameraSelector
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import java.util.concurrent.Executors

/**
 * Ön kamerayla yüz bulur. Her şey telefonda, internetsiz çalışır.
 * Koordinatlar ayna (selfie) görünümündedir, yani ekrana bakan kişinin gördüğü gibi:
 * nx: -1 (ekranın solu = robotun SAĞI) .. +1 (ekranın sağı = robotun SOLU)
 * ny: -1 (yukarı) .. +1 (aşağı), size: yüzün görüntü genişliğine oranı
 */
data class FaceInfo(val nx: Float, val ny: Float, val size: Float, val box: RectF, val smile: Float?, val count: Int)

class FaceTracker(
    private val ctx: Context,
    private val owner: LifecycleOwner,
    private val onFace: (FaceInfo?) -> Unit,
    private val onStatus: (String) -> Unit
) {
    private val executor = Executors.newSingleThreadExecutor()
    private val detector = FaceDetection.getClient(
        FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
            .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_ALL)
            .setMinFaceSize(0.10f)
            .build()
    )
    private var lastFrame = 0L
    var fps = 0f
        private set
    private var provider: ProcessCameraProvider? = null

    fun start() {
        val future = ProcessCameraProvider.getInstance(ctx)
        future.addListener({
            try {
                val p = future.get()
                provider = p
                @Suppress("DEPRECATION")
                val analysis = ImageAnalysis.Builder()
                    .setTargetResolution(Size(640, 480))
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                analysis.setAnalyzer(executor) { proxy -> analyze(proxy) }
                p.unbindAll()
                p.bindToLifecycle(owner, CameraSelector.DEFAULT_FRONT_CAMERA, analysis)
                onStatus("Kamera açık")
            } catch (e: Exception) {
                onStatus("Kamera HATA: ${e.message}")
            }
        }, ContextCompat.getMainExecutor(ctx))
    }

    fun stop() { try { provider?.unbindAll() } catch (_: Exception) {} }

    @androidx.annotation.OptIn(ExperimentalGetImage::class)
    private fun analyze(proxy: ImageProxy) {
        val now = SystemClock.uptimeMillis()
        // Saniyede ~10 kare yeter, telefon ısınmasın
        if (now - lastFrame < 90) { proxy.close(); return }
        if (lastFrame > 0) fps = fps * 0.8f + 0.2f * (1000f / (now - lastFrame))
        lastFrame = now

        val media = proxy.image
        if (media == null) { proxy.close(); return }
        val rot = proxy.imageInfo.rotationDegrees
        val iw: Float; val ih: Float
        if (rot == 90 || rot == 270) { iw = proxy.height.toFloat(); ih = proxy.width.toFloat() }
        else { iw = proxy.width.toFloat(); ih = proxy.height.toFloat() }

        val input = InputImage.fromMediaImage(media, rot)
        detector.process(input)
            .addOnSuccessListener { faces ->
                val f = faces.maxByOrNull { it.boundingBox.width() * it.boundingBox.height() }
                if (f == null) { onFace(null); return@addOnSuccessListener }
                val bb = f.boundingBox
                // Ön kamera görüntüsünü robotun bakış açısına çevir (ayna)
                val l = 1f - bb.right / iw
                val r = 1f - bb.left / iw
                val t = bb.top / ih
                val b = bb.bottom / ih
                val cx = (l + r) / 2f
                val cy = (t + b) / 2f
                onFace(
                    FaceInfo(
                        nx = (cx * 2f - 1f).coerceIn(-1f, 1f),
                        ny = (cy * 2f - 1f).coerceIn(-1f, 1f),
                        size = (r - l).coerceIn(0f, 1f),
                        box = RectF(l, t, r, b),
                        smile = f.smilingProbability,
                        count = faces.size
                    )
                )
            }
            .addOnFailureListener { e -> onStatus("Yüz algılama HATA: ${e.message}") }
            .addOnCompleteListener { proxy.close() }
    }

    fun release() { stop(); detector.close(); executor.shutdown() }
}
