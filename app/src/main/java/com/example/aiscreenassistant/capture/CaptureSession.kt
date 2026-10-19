package com.example.aiscreenassistant.capture

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class CaptureState { IDLE, STARTING, CAPTURING, STOPPED, ERROR }
data class CaptureStatus(val state: CaptureState = CaptureState.IDLE, val message: String = "尚未授权录屏")

object CaptureSession {
    private val mutableStatus = MutableStateFlow(CaptureStatus())
    val status = mutableStatus.asStateFlow()
    fun update(state: CaptureState, message: String) { mutableStatus.value = CaptureStatus(state, message) }
}
