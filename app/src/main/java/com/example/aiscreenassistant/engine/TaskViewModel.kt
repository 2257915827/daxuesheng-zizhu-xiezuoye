package com.example.aiscreenassistant.engine

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.aiscreenassistant.ai.*
import com.example.aiscreenassistant.model.*
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class TaskUiState(val state: TaskState = TaskState.IDLE, val message: String = "准备就绪", val answer: String? = null, val confidence: Float = 0f, val pending: PlannedAction? = null)

class TaskViewModel(app: Application) : AndroidViewModel(app) {
    private val _ui = MutableStateFlow(TaskUiState())
    val ui = _ui.asStateFlow()
    private var job: Job? = null
    private val ai: AiClient = UnconfiguredAiClient()

    fun start() {
        if (job?.isActive == true) return
        job = viewModelScope.launch {
            _ui.value = TaskUiState(TaskState.CAPTURE, "等待屏幕捕获授权。")
            _ui.value = _ui.value.copy(state = TaskState.PAUSED, message = "请先授权录屏和无障碍服务，然后再次开始。")
        }
    }
    fun pause(message: String = "任务已暂停") { job?.cancel(); _ui.value = _ui.value.copy(state = TaskState.PAUSED, message = message, pending = null) }
    fun stop() { job?.cancel(); _ui.value = TaskUiState(message = "任务已停止，临时数据已清理") }
    fun confirm(action: PlannedAction) { _ui.value = _ui.value.copy(state = TaskState.EXECUTE, message = "已确认：${action.type}", pending = null) }
}
