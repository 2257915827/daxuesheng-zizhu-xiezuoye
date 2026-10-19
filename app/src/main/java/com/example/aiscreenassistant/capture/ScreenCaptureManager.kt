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
import android.util.DisplayMetrics
import android.view.WindowManager

/** Captures one frame at a time. The caller owns the returned bitmap and must recycle it after use. */
class ScreenCaptureManager(private val context: Context) : AutoCloseable {
    private var projection: MediaProjection? = null
    private var display: VirtualDisplay? = null
    private var reader: ImageReader? = null

    fun start(resultCode: Int, data: Intent) {
        close()
        val manager = context.getSystemService(MediaProjectionManager::class.java)
        projection = manager.getMediaProjection(resultCode, data)
        val metrics = DisplayMetrics().also { @Suppress("DEPRECATION") context.getSystemService(WindowManager::class.java).defaultDisplay.getRealMetrics(it) }
        val width = metrics.widthPixels.coerceAtLeast(1)
        val height = metrics.heightPixels.coerceAtLeast(1)
        reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
        display = projection?.createVirtualDisplay("AiScreenAssistant", width, height, metrics.densityDpi, DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, reader!!.surface, null, null)
    }

    fun capture(): Bitmap? {
        val image = reader?.acquireLatestImage() ?: return null
        try {
            val plane = image.planes.firstOrNull() ?: return null
            val bitmap = Bitmap.createBitmap(image.width, image.height, Bitmap.Config.ARGB_8888)
            bitmap.copyPixelsFromBuffer(plane.buffer)
            return bitmap
        } finally { image.close() }
    }
    override fun close() { display?.release(); reader?.close(); projection?.stop(); display = null; reader = null; projection = null }
}
