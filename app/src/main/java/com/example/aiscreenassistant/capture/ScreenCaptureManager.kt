package com.example.aiscreenassistant.capture

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Handler
import android.os.Looper
import android.util.DisplayMetrics
import android.view.WindowManager

/** Captures one frame at a time. The caller owns the returned bitmap and must recycle it after use. */
class ScreenCaptureManager(private val context: Context, private val onProjectionStopped: () -> Unit = {}) : AutoCloseable {
    private var projection: MediaProjection? = null
    private var display: VirtualDisplay? = null
    private var reader: ImageReader? = null
    private val callback = object : MediaProjection.Callback() {
        override fun onStop() { projection = null; releaseResources(); onProjectionStopped() }
    }

    fun start(resultCode: Int, data: Intent) {
        close()
        val manager = context.getSystemService(MediaProjectionManager::class.java)
        projection = requireNotNull(manager.getMediaProjection(resultCode, data)) { "系统未返回录屏会话" }
        projection!!.registerCallback(callback, Handler(Looper.getMainLooper()))
        val metrics = DisplayMetrics().also { @Suppress("DEPRECATION") context.getSystemService(WindowManager::class.java).defaultDisplay.getRealMetrics(it) }
        val width = metrics.widthPixels.coerceAtLeast(1)
        val height = metrics.heightPixels.coerceAtLeast(1)
        reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
        display = projection!!.createVirtualDisplay("AiScreenAssistant", width, height, metrics.densityDpi, DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, reader!!.surface, null, null)
    }

    fun capture(): Bitmap? {
        val image = reader?.acquireLatestImage() ?: return null
        try {
            val plane = image.planes.firstOrNull() ?: return null
            val pixelStride = plane.pixelStride
            val rowPadding = plane.rowStride - pixelStride * image.width
            val padded = Bitmap.createBitmap(image.width + rowPadding / pixelStride, image.height, Bitmap.Config.ARGB_8888)
            padded.copyPixelsFromBuffer(plane.buffer)
            val bitmap = if (rowPadding == 0) padded else Bitmap.createBitmap(padded, 0, 0, image.width, image.height).also { padded.recycle() }
            return bitmap
        } finally { image.close() }
    }
    private fun releaseResources() { display?.release(); reader?.close(); display = null; reader = null }
    override fun close() {
        val activeProjection = projection
        projection = null
        releaseResources()
        activeProjection?.unregisterCallback(callback)
        activeProjection?.stop()
    }
}
