package com.example.aiscreenassistant.capture

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.IBinder
import android.provider.Settings
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.example.aiscreenassistant.MainActivity
import com.example.aiscreenassistant.R
import com.example.aiscreenassistant.ai.OpenAiCompatibleClient
import com.example.aiscreenassistant.settings.ApiSettingsStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ScreenCaptureService : Service() {
    companion object {
        private const val CHANNEL_ID = "screen_capture"
        private const val NOTIFICATION_ID = 1001
        private const val ACTION_START = "com.example.aiscreenassistant.capture.START"
        private const val ACTION_STOP = "com.example.aiscreenassistant.capture.STOP"
        private const val EXTRA_RESULT_CODE = "result_code"
        private const val EXTRA_RESULT_DATA = "result_data"

        fun start(context: Context, resultCode: Int, data: Intent) {
            val intent = Intent(context, ScreenCaptureService::class.java).setAction(ACTION_START)
                .putExtra(EXTRA_RESULT_CODE, resultCode).putExtra(EXTRA_RESULT_DATA, data)
            ContextCompat.startForegroundService(context, intent)
        }
        fun stop(context: Context) { context.startService(Intent(context, ScreenCaptureService::class.java).setAction(ACTION_STOP)) }
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var manager: ScreenCaptureManager? = null
    private var overlay: FloatingAssistantController? = null
    private var answerJob: Job? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> stopCapture("辅助和录屏已停止")
            ACTION_START -> startCapture(intent)
        }
        return START_NOT_STICKY
    }

    private fun startCapture(intent: Intent) {
        val resultData = intent.getParcelableExtra<Intent>(EXTRA_RESULT_DATA)
        if (resultData == null) { stopCapture("录屏授权结果无效"); return }
        if (!Settings.canDrawOverlays(this)) { stopCapture("请先允许 AI 屏幕助手显示在其他应用上层"); return }
        CaptureSession.update(CaptureState.STARTING, "正在启动录屏和悬浮助手…")
        createChannel()
        startForeground(NOTIFICATION_ID, notification())
        try {
            overlay?.remove()
            manager?.close()
            val newManager = ScreenCaptureManager(this) { stopCapture("系统已撤销录屏授权") }
            manager = newManager
            newManager.start(intent.getIntExtra(EXTRA_RESULT_CODE, 0), resultData)
            overlay = FloatingAssistantController(this) { requestAnswer() }.also { it.show() }
            CaptureSession.update(CaptureState.CAPTURING, "悬浮助手已开启；进入题目后点气泡识题")
        } catch (error: Exception) {
            stopCapture("无法启动悬浮助手：${error.message ?: error.javaClass.simpleName}")
        }
    }

    private fun requestAnswer() {
        if (answerJob?.isActive == true) return
        val controller = overlay ?: return
        answerJob = serviceScope.launch {
            controller.setLoading()
            var bitmap: Bitmap? = null
            try {
                val settings = ApiSettingsStore(this@ScreenCaptureService).read()
                if (settings.baseUrl.isBlank() || settings.token.isBlank() || settings.selectedModel.isBlank()) {
                    throw IllegalStateException("请先回到助手页面配置并保存 API、Token 和模型")
                }
                controller.hideForCapture()
                delay(350)
                bitmap = captureFreshFrame()
                controller.showAfterCapture()
                val result = OpenAiCompatibleClient(settings.baseUrl, settings.token, settings.selectedModel).answerQuestion(bitmap)
                bitmap = null // answerQuestion owns and recycles the bitmap.
                controller.showAnswer(result)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                controller.showAfterCapture()
                controller.showError(error.message ?: "识别失败，请重试")
            } finally {
                bitmap?.recycle()
                controller.showAfterCapture()
            }
        }
    }

    private suspend fun captureFreshFrame(): Bitmap = withContext(Dispatchers.IO) {
        repeat(12) {
            manager?.capture()?.let { return@withContext it }
            delay(150)
        }
        throw IllegalStateException("暂时无法获取屏幕画面，请稍后重试")
    }

    private fun stopCapture(message: String) {
        answerJob?.cancel(); answerJob = null
        overlay?.remove(); overlay = null
        manager?.close(); manager = null
        CaptureSession.update(CaptureState.STOPPED, message)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun createChannel() {
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "AI 悬浮答题助手", NotificationManager.IMPORTANCE_LOW)
        )
    }

    private fun notification() = NotificationCompat.Builder(this, CHANNEL_ID)
        .setSmallIcon(android.R.drawable.ic_menu_view)
        .setContentTitle("AI 悬浮答题助手正在运行")
        .setContentText("点悬浮气泡识题；可随时停止屏幕捕获")
        .setOngoing(true)
        .setContentIntent(PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
        .addAction(0, "停止辅助", PendingIntent.getService(this, 1, Intent(this, ScreenCaptureService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
        .build()

    override fun onBind(intent: Intent?): IBinder? = null
    override fun onDestroy() {
        answerJob?.cancel()
        overlay?.remove(); overlay = null
        manager?.close(); manager = null
        serviceScope.cancel()
        super.onDestroy()
    }
}
