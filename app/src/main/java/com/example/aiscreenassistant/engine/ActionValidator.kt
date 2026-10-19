package com.example.aiscreenassistant.engine

import com.example.aiscreenassistant.model.*

object ActionValidator {
    fun validate(actions: List<PlannedAction>): Result<Unit> {
        if (actions.any { it.confidence !in 0f..1f }) return Result.failure(IllegalArgumentException("置信度无效"))
        if (actions.any { it.confidence < .65f }) return Result.failure(IllegalArgumentException("识别置信度不足，任务已暂停"))
        return Result.success(Unit)
    }
}
