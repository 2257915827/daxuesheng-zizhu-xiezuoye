package com.example.aiscreenassistant.engine

import android.app.Application
import android.graphics.Bitmap
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.aiscreenassistant.accessibility.AssistantAccessibilityService
import com.example.aiscreenassistant.ai.OpenAiCompatibleClient
import com.example.aiscreenassistant.capture.CaptureSession
import com.example.aiscreenassistant.capture.CaptureState
import com.example.aiscreenassistant.capture.ScreenCaptureService
import com.example.aiscreenassistant.model.*
import com.example.aiscreenassistant.settings.ApiSettingsStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.asStateFlow

data class TaskUiState(
    val state: TaskState = TaskState.IDLE,
    val message: String = "准备就绪",
    val answer: String? = null,
    val confidence: Float = 0f,
    val pending: PlannedAction? = null,
    val questionsAnswered: Int = 0
)

class TaskViewModel(app: Application) : AndroidViewModel(app) {
    private val _ui = kotlinx.coroutines.flow.MutableStateFlow(TaskUiState())
    val ui = _ui.asStateFlow()
    private var job: Job? = null

    fun start(targetPackage: String) {
        if (job?.isActive == true) return
        job = viewModelScope.launch {
            try {
                val settings = ApiSettingsStore(getApplication()).read()
                if (settings.baseUrl.isBlank() || settings.token.isBlank() || settings.selectedModel.isBlank()) {
                    pause("请先保存 API 地址、Token 和模型")
                    return@launch
                }
                val service = ScreenCaptureService.instance
                val accessibility = AssistantAccessibilityService.instance
                if (CaptureSession.status.value.state != CaptureState.CAPTURING || service == null) {
                    pause("录屏服务尚未运行")
                    return@launch
                }
                if (accessibility == null || !AssistantAccessibilityService.enabled) {
                    pause("无障碍服务尚未连接，请重新启用后再试")
                    return@launch
                }
                val ai = OpenAiCompatibleClient(settings.baseUrl, settings.token, settings.selectedModel)
                var previousActionKey: String? = null
                var unchangedCount = 0
                var wrongAppCount = 0
                var answered = _ui.value.questionsAnswered

                while (isActive) {
                    _ui.value = _ui.value.copy(state = TaskState.CAPTURE, message = "正在读取当前页面…", pending = null)
                    val snapshot = accessibility.snapshot()
                    if (snapshot == null || snapshot.packageName.isBlank()) {
                        delay(700)
                        continue
                    }
                    if (snapshot.packageName != targetPackage) {
                        wrongAppCount++
                        if (wrongAppCount >= 20) {
                            pause("当前前台应用与所选作业应用不同。请切回作业应用后点继续。")
                            return@launch
                        }
                        _ui.value = _ui.value.copy(message = "正在等待所选作业应用显示…")
                        delay(500)
                        continue
                    }
                    wrongAppCount = 0
                    val bitmap = nextFrame(service)
                    if (bitmap == null) {
                        pause("无法读取屏幕帧，请停止并重新授权录屏")
                        return@launch
                    }
                    val context = ScreenContext(snapshot.packageName, snapshot.nodes)
                    _ui.value = _ui.value.copy(state = TaskState.UNDERSTAND, message = "AI 正在识别题目和选项…")
                    val response = ai.analyzeScreen(bitmap, context)
                    if (response.confidence !in 0f..1f || response.confidence < .65f) {
                        pause("AI 判断置信度不足，请检查当前页面后重试。${response.explanation}")
                        return@launch
                    }
                    if (response.taskComplete) {
                        _ui.value = _ui.value.copy(state = TaskState.AWAITING_FINISH, message = "AI 认为当前作业已完成。不会替你提交。", answer = response.explanation, confidence = response.confidence, pending = null, questionsAnswered = answered)
                        return@launch
                    }
                    val action = response.actions.firstOrNull()
                    if (action == null) {
                        pause("没有识别到可安全执行的选择题操作。${response.explanation}")
                        return@launch
                    }
                    if (action.type !in setOf(ActionType.SELECT_OPTION, ActionType.NEXT_QUESTION, ActionType.SCROLL, ActionType.WAIT)) {
                        pause("AI 返回了不允许的动作（${action.type}），已停止以避免误操作")
                        return@launch
                    }
                    if (action.confidence < .65f || action.confidence > 1f) {
                        pause("选项识别置信度不足，未执行任何操作")
                        return@launch
                    }
                    if (action.type == ActionType.SELECT_OPTION && action.target.isNullOrBlank()) {
                        pause("AI 没有指定清晰的选项目标，未执行任何操作")
                        return@launch
                    }
                    val signature = snapshot.nodes.joinToString("|") { "${it.id}:${it.text}:${it.checked}:${it.selected}" }
                    val actionKey = "$signature:${action.type}:${action.target}"
                    if (actionKey == previousActionKey) {
                        unchangedCount++
                        if (unchangedCount >= 3) {
                            pause("操作后页面没有变化，已暂停以避免重复选择。请检查页面后继续。")
                            return@launch
                        }
                        delay(1200)
                        continue
                    }
                    _ui.value = _ui.value.copy(state = TaskState.EXECUTE, message = "正在选择答案：${response.answer ?: action.target ?: action.type}", answer = response.answer ?: response.explanation, confidence = action.confidence)
                    if (!accessibility.executeSafeAction(action)) {
                        pause("无法定位或选择目标选项，请确认作业页面对无障碍服务可见")
                        return@launch
                    }
                    if (action.type == ActionType.SELECT_OPTION) answered++
                    previousActionKey = actionKey
                    unchangedCount = 0
                    _ui.value = _ui.value.copy(state = TaskState.VERIFY, message = "已选择当前选项，正在检查下一题…", questionsAnswered = answered)
                    delay(1100)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                pause("辅助已暂停：${error.message ?: error.javaClass.simpleName}")
            }
        }
    }

    private suspend fun nextFrame(service: ScreenCaptureService): Bitmap? {
        repeat(12) {
            service.captureFrame()?.let { return it }
            delay(250)
        }
        return null
    }

    fun pause(message: String = "任务已暂停") { job?.cancel(); job = null; _ui.value = _ui.value.copy(state = TaskState.PAUSED, message = message, pending = null) }
    fun stop() {
        job?.cancel(); job = null
        ScreenCaptureService.stop(getApplication())
        _ui.value = TaskUiState(message = "辅助已结束，录屏已停止", questionsAnswered = 0)
    }
    fun continueAfterPause(targetPackage: String) { start(targetPackage) }
    fun continueAfterCompletion(targetPackage: String) { _ui.value = _ui.value.copy(state = TaskState.IDLE, message = "继续监测作业…"); start(targetPackage) }
    fun finishSupport() {
        job?.cancel(); job = null
        ScreenCaptureService.instance?.let { ScreenCaptureService.stop(getApplication()) }
        _ui.value = TaskUiState(message = "辅助已结束，录屏已停止")
    }
}
