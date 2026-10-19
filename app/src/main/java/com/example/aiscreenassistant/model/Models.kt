package com.example.aiscreenassistant.model

import kotlinx.serialization.Serializable

enum class TaskState { IDLE, CAPTURE, UNDERSTAND, PLAN, CONFIRM_IF_NEEDED, EXECUTE, VERIFY, AWAITING_FINISH, PAUSED }
enum class ActionType { TAP, INPUT_TEXT, SCROLL, SELECT_OPTION, NEXT_QUESTION, BACK, WAIT }

@Serializable
data class NodeSummary(val id: String? = null, val text: String? = null, val className: String? = null, val clickable: Boolean = false, val editable: Boolean = false, val checkable: Boolean = false, val checked: Boolean = false, val selected: Boolean = false)

@Serializable
data class ScreenContext(val packageName: String, val nodes: List<NodeSummary>, val taskHint: String = "")

@Serializable
data class PlannedAction(val type: ActionType, val target: String? = null, val text: String? = null, val x: Float? = null, val y: Float? = null, val amount: Int? = null, val reason: String = "", val confidence: Float = 0f, val requiresConfirmation: Boolean = false)

@Serializable
data class AiResponse(val explanation: String = "", val answer: String? = null, val confidence: Float = 0f, val actions: List<PlannedAction> = emptyList(), val taskComplete: Boolean = false)

fun PlannedAction.isDestructive(): Boolean = requiresConfirmation || (type == ActionType.BACK && target?.contains("退出|删除|提交|发送|支付".toRegex()) == true)
