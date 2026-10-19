package com.example.aiscreenassistant.accessibility

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.os.Bundle
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.example.aiscreenassistant.model.*

class AssistantAccessibilityService : AccessibilityService() {
    companion object { var instance: AssistantAccessibilityService? = null; var enabled = false }
    override fun onServiceConnected() { super.onServiceConnected(); instance = this; enabled = true }
    override fun onDestroy() { enabled = false; instance = null; super.onDestroy() }
    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit
    override fun onInterrupt() = Unit

    fun nodes(): List<NodeSummary> = rootInActiveWindow?.let { root -> buildList { walk(root, this) } } ?: emptyList()
    private fun walk(node: AccessibilityNodeInfo, out: MutableList<NodeSummary>) {
        out += NodeSummary(node.viewIdResourceName, node.text?.toString(), node.className?.toString(), node.isClickable, node.isEditable)
        for (i in 0 until node.childCount) node.getChild(i)?.let { walk(it, out) }
    }
    fun execute(action: PlannedAction): Boolean = when (action.type) {
        ActionType.TAP, ActionType.SELECT_OPTION -> find(action.target)?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true
        ActionType.INPUT_TEXT -> find(action.target)?.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, action.text ?: "") }) == true
        ActionType.BACK -> performGlobalAction(GLOBAL_ACTION_BACK)
        ActionType.SCROLL -> find(action.target)?.performAction(if ((action.amount ?: 1) > 0) AccessibilityNodeInfo.ACTION_SCROLL_FORWARD else AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD) == true
        ActionType.WAIT -> true
    }
    fun tap(x: Float, y: Float): Boolean {
        val path = Path().apply { moveTo(x, y) }
        return dispatchGesture(GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(path, 0, 80)).build(), null, null)
    }
    private fun find(target: String?): AccessibilityNodeInfo? = target?.let { rootInActiveWindow?.findAccessibilityNodeInfosByViewId(it)?.firstOrNull() ?: rootInActiveWindow?.findAccessibilityNodeInfosByText(it)?.firstOrNull() }
}
