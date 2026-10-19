package com.example.aiscreenassistant.accessibility

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.os.Bundle
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.example.aiscreenassistant.model.*
import kotlinx.coroutines.flow.asStateFlow

class AssistantAccessibilityService : AccessibilityService() {
    companion object { @Volatile var instance: AssistantAccessibilityService? = null; @Volatile var enabled = false }
    override fun onServiceConnected() { super.onServiceConnected(); instance = this; enabled = true; AccessibilitySession.update(true) }
    override fun onDestroy() { enabled = false; instance = null; AccessibilitySession.update(false); super.onDestroy() }
    override fun onAccessibilityEvent(event: AccessibilityEvent?) { AccessibilitySession.update(true) }
    override fun onInterrupt() { AccessibilitySession.update(false) }

    fun snapshot(): AccessibilitySnapshot? = rootInActiveWindow?.let { root -> AccessibilitySnapshot(root.packageName?.toString().orEmpty(), buildList { walk(root, this) }) }
    private fun walk(node: AccessibilityNodeInfo, out: MutableList<NodeSummary>) {
        out += NodeSummary(node.viewIdResourceName, if (node.isPassword) null else (node.text ?: node.contentDescription)?.toString(), node.className?.toString(), node.isClickable, node.isEditable, node.isCheckable, node.isChecked, node.isSelected)
        for (i in 0 until node.childCount) node.getChild(i)?.let { walk(it, out) }
    }
    fun executeSafeAction(action: PlannedAction): Boolean = when (action.type) {
        ActionType.SELECT_OPTION -> selectOption(action.target)
        ActionType.NEXT_QUESTION -> clickNextQuestion()
        ActionType.SCROLL -> rootInActiveWindow?.performAction(if ((action.amount ?: 1) > 0) AccessibilityNodeInfo.ACTION_SCROLL_FORWARD else AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD) == true
        ActionType.WAIT -> true
        else -> false
    }

    private fun selectOption(target: String?): Boolean {
        if (target.isNullOrBlank()) return false
        val root = rootInActiveWindow ?: return false
        val matches = root.findAccessibilityNodeInfosByViewId(target).orEmpty().filter { it.viewIdResourceName == target } +
            root.findAccessibilityNodeInfosByText(target).orEmpty().filter { it.text?.toString()?.trim()?.equals(target.trim(), ignoreCase = true) == true }
        val candidate = matches.firstOrNull { it.isCheckable || it.isClickable } ?: matches.firstOrNull()?.let { node ->
            var parent = node.parent
            var depth = 0
            while (parent != null && depth++ < 5 && !parent.isClickable && !parent.isCheckable) parent = parent.parent
            parent
        }
        if (candidate == null || candidate.isChecked || candidate.isSelected) return candidate != null
        return candidate.performAction(AccessibilityNodeInfo.ACTION_CLICK)
    }

    private fun clickNextQuestion(): Boolean {
        val root = rootInActiveWindow ?: return false
        val safeLabels = setOf("下一题", "下一道", "继续答题", "继续作答", "next question")
        val node = findSafeNextNode(root, safeLabels) ?: return false
        var clickable: AccessibilityNodeInfo? = node
        var depth = 0
        while (clickable != null && depth++ < 5 && !clickable.isClickable) clickable = clickable.parent
        return clickable?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true
    }

    private fun findSafeNextNode(node: AccessibilityNodeInfo, safeLabels: Set<String>): AccessibilityNodeInfo? {
        val label = (node.text?.toString() ?: node.contentDescription?.toString()).orEmpty().trim().lowercase()
        if (label in safeLabels) return node
        for (index in 0 until node.childCount) {
            val child = node.getChild(index) ?: continue
            findSafeNextNode(child, safeLabels)?.let { return it }
        }
        return null
    }
}

data class AccessibilitySnapshot(val packageName: String, val nodes: List<NodeSummary>)

object AccessibilitySession {
    private val mutableEnabled = kotlinx.coroutines.flow.MutableStateFlow(false)
    val enabled = mutableEnabled.asStateFlow()
    fun update(value: Boolean) { mutableEnabled.value = value }
}
