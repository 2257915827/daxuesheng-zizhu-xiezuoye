package com.example.aiscreenassistant.capture

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.os.Binder
import android.graphics.Bitmap
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.example.aiscreenassistant.MainActivity
import com.example.aiscreenassistant.R

class ScreenCaptureService : Service() {
    companion object {
        private const val CHANNEL_ID = "screen_capture"
        private const val NOTIFICATION_ID = 1001
        private const val ACTION_START = "com.example.aiscreenassistant.capture.START"
        private const val ACTION_STOP = "com.example.aiscreenassistant.capture.STOP"
        private const val EXTRA_RESULT_CODE = "result_code"
        private const val EXTRA_RESULT_DATA = "result_data"
        @Volatile var instance: ScreenCaptureService? = null

        fun start(context: Context, resultCode: Int, data: Intent) {
            val intent = Intent(context, ScreenCaptureService::class.java).setAction(ACTION_START)
                .putExtra(EXTRA_RESULT_CODE, resultCode).putExtra(EXTRA_RESULT_DATA, data)
            ContextCompat.startForegroundService(context, intent)
        }
        fun stop(context: Context) { context.startService(Intent(context, ScreenCaptureService::class.java).setAction(ACTION_STOP)) }
    }

    private var manager: ScreenCaptureManager? = null
    private val localBinder = LocalBinder()
    inner class LocalBinder : Binder() { fun captureFrame(): Bitmap? = manager?.capture() }
    fun captureFrame(): Bitmap? = manager?.capture()

    override fun onCreate() { super.onCreate(); instance = this }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> stopCapture("录屏已停止")
            ACTION_START -> startCapture(intent)
        }
        return START_NOT_STICKY
    }

    private fun startCapture(intent: Intent) {
        val resultData = intent.getParcelableExtra<Intent>(EXTRA_RESULT_DATA)
        if (resultData == null) { stopCapture("录屏授权结果无效"); return }
        CaptureSession.update(CaptureState.STARTING, "正在启动录屏服务…")
        createChannel()
        startForeground(NOTIFICATION_ID, notification())
        try {
            manager?.close()
            val newManager = ScreenCaptureManager(this) { stopCapture("系统已停止录屏授权") }
            manager = newManager
            newManager.start(intent.getIntExtra(EXTRA_RESULT_CODE, 0), resultData)
            CaptureSession.update(CaptureState.CAPTURING, "正在捕获屏幕；可从通知栏停止")
        } catch (error: Exception) {
            stopCapture("无法启动录屏：${error.message ?: error.javaClass.simpleName}")
        }
    }

    private fun stopCapture(message: String) {
        manager?.close(); manager = null
        CaptureSession.update(CaptureState.STOPPED, message)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun createChannel() {
        val channel = NotificationChannel(CHANNEL_ID, "屏幕捕获", NotificationManager.IMPORTANCE_LOW)
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }
    private fun notification() = NotificationCompat.Builder(this, CHANNEL_ID)
        .setSmallIcon(android.R.drawable.ic_menu_view)
        .setContentTitle(getString(R.string.capture_notification_title))
        .setContentText(getString(R.string.capture_notification_text))
        .setOngoing(true)
        .setContentIntent(PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
        .addAction(0, getString(R.string.capture_stop), PendingIntent.getService(this, 1, Intent(this, ScreenCaptureService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
        .build()

    override fun onBind(intent: Intent?): IBinder = localBinder
    override fun onDestroy() { manager?.close(); manager = null; instance = null; super.onDestroy() }
}
