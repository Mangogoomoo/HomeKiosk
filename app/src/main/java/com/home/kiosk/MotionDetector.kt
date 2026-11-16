package com.home.kiosk

import android.content.Context
import android.os.SystemClock
import android.util.Log
import android.util.Size
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.pow

/**
 * Watches the front camera at low resolution and calls [onMotion] when something moves.
 *
 * Each frame is reduced to a 32x24 grid of average brightness. Each grid is normalised by
 * its own mean so that overall light changes (the screen dimming, a lamp turning on, camera
 * auto-exposure) don't count as movement; only local changes do.
 */
class MotionDetector(
    private val context: Context,
    private val owner: LifecycleOwner,
    private val onMotion: () -> Unit,
) {
    @Volatile var sensitivity: Int = 6

    @Volatile private var previous: FloatArray? = null
    private var lastAnalyzed = 0L
    private val executor = Executors.newSingleThreadExecutor()
    private var provider: ProcessCameraProvider? = null
    private var running = false

    fun start() {
        if (running) return
        running = true
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            try {
                val p = future.get()
                provider = p
                if (!running) return@addListener

                val resolution = ResolutionSelector.Builder()
                    .setResolutionStrategy(
                        ResolutionStrategy(
                            Size(320, 240),
                            ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER,
                        )
                    )
                    .build()
                val analysis = ImageAnalysis.Builder()
                    .setResolutionSelector(resolution)
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                analysis.setAnalyzer(executor) { image ->
                    try {
                        analyze(image)
                    } catch (e: Exception) {
                        Log.w(TAG, "analyze failed", e)
                    } finally {
                        image.close()
                    }
                }

                val selector = try {
                    if (p.hasCamera(CameraSelector.DEFAULT_FRONT_CAMERA)) CameraSelector.DEFAULT_FRONT_CAMERA
                    else CameraSelector.DEFAULT_BACK_CAMERA
                } catch (e: Exception) {
                    CameraSelector.DEFAULT_FRONT_CAMERA
                }

                p.unbindAll()
                p.bindToLifecycle(owner, selector, analysis)
            } catch (e: Exception) {
                Log.e(TAG, "Could not start camera", e)
                running = false
            }
        }, ContextCompat.getMainExecutor(context))
    }

    fun stop() {
        running = false
        provider?.unbindAll()
        previous = null
    }

    /** Forget the last frame, e.g. right after the screen brightness changed. */
    fun resetBaseline() {
        previous = null
    }

    private fun analyze(image: ImageProxy) {
        val now = SystemClock.elapsedRealtime()
        if (now - lastAnalyzed < 250) return // ~4 checks per second is plenty
        lastAnalyzed = now

        val plane = image.planes[0] // Y (luminance) plane
        val buf = plane.buffer
        val rowStride = plane.rowStride
        val pixelStride = plane.pixelStride
        val w = image.width
        val h = image.height
        val cellW = w / GRID_W
        val cellH = h / GRID_H
        if (cellW < 1 || cellH < 1) return

        val grid = FloatArray(GRID_W * GRID_H)
        var total = 0f
        for (gy in 0 until GRID_H) {
            for (gx in 0 until GRID_W) {
                var sum = 0
                var n = 0
                var y = gy * cellH
                val yEnd = y + cellH
                while (y < yEnd) {
                    var x = gx * cellW
                    val xEnd = x + cellW
                    val rowBase = y * rowStride
                    while (x < xEnd) {
                        sum += buf.get(rowBase + x * pixelStride).toInt() and 0xFF
                        n++
                        x += 2
                    }
                    y += 2
                }
                val v = if (n > 0) sum.toFloat() / n else 0f
                grid[gy * GRID_W + gx] = v
                total += v
            }
        }
        val mean = total / grid.size
        for (i in grid.indices) grid[i] -= mean

        val prev = previous
        previous = grid
        if (prev == null) return

        var changed = 0
        for (i in grid.indices) {
            if (abs(grid[i] - prev[i]) > CELL_THRESHOLD) changed++
        }
        val fraction = changed.toFloat() / grid.size
        // sensitivity 1 -> 20% of the picture must change, 10 -> ~0.8%
        val needed = 0.20f * 0.70f.pow(sensitivity - 1)
        if (fraction >= needed) onMotion()
    }

    companion object {
        private const val TAG = "MotionDetector"
        private const val GRID_W = 32
        private const val GRID_H = 24
        private const val CELL_THRESHOLD = 18f
    }
}
