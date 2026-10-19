package com.example.aiscreenassistant.capture

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.example.aiscreenassistant.model.QuestionAnswer
import com.example.aiscreenassistant.model.QuestionType
import kotlin.math.abs

class FloatingAssistantController(
    context: Context,
    private val onAsk: () -> Unit
) {
    private val appContext = context.applicationContext
    private val windowManager = context.getSystemService(WindowManager::class.java)
    private val handler = Handler(Looper.getMainLooper())
    private val root = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        background = rounded(0xEE20242C.toInt(), 20)
        setPadding(16, 12, 16, 12)
        elevation = 12f
    }
    private val params = WindowManager.LayoutParams(
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
        PixelFormat.TRANSLUCENT
    ).apply { gravity = Gravity.TOP or Gravity.LEFT; x = 20; y = 360 }
    private var added = false
    private var inFlight = false
    private var expanded = false
    private var lastAnswer: QuestionAnswer? = null
    private var lastError: String? = null
    private var revealToken = 0

    fun show() {
        if (!added) {
            windowManager.addView(root, params)
            added = true
            renderBubble("AI · 点我识题")
        }
    }

    fun hideForCapture() { if (added) root.visibility = View.INVISIBLE }
    fun showAfterCapture() { if (added) root.visibility = View.VISIBLE }

    fun setLoading() {
        inFlight = true
        lastError = null
        lastAnswer = null
        renderPanel("正在识别截图…", "请稍候，AI 正在分析当前题目。", showCopy = false, allowAsk = false)
    }

    fun showAnswer(answer: QuestionAnswer) {
        inFlight = false
        lastAnswer = answer
        lastError = null
        if (answer.questionType == QuestionType.MULTIPLE_CHOICE) {
            expanded = false
            val token = ++revealToken
            renderBubble(answer.answer)
            handler.postDelayed({
                if (added && !expanded && !inFlight && token == revealToken) renderBubble("AI · 点我看答案")
            }, 5_000)
        } else {
            expanded = true
            renderAnswerPanel(answer)
        }
    }

    fun showError(message: String) {
        inFlight = false
        lastError = message
        expanded = true
        renderPanel("识别失败", message, showCopy = false, allowAsk = true)
    }

    fun remove() {
        handler.removeCallbacksAndMessages(null)
        if (added) runCatching { windowManager.removeView(root) }
        added = false
        inFlight = false
        lastAnswer = null
        lastError = null
    }

    private fun renderBubble(label: String) {
        expanded = false
        root.minimumWidth = 0
        root.removeAllViews()
        val bubble = TextView(root.context).apply {
            text = label
            setTextColor(0xFFFFFFFF.toInt())
            textSize = 15f
            maxWidth = 260
            setPadding(10, 6, 10, 6)
            setOnTouchListener(dragTouchListener())
            setOnClickListener {
                if (inFlight) return@setOnClickListener
                when {
                    lastAnswer != null -> renderAnswerPanel(lastAnswer!!)
                    lastError != null -> renderPanel("识别失败", lastError!!, showCopy = false, allowAsk = true)
                    else -> onAsk()
                }
            }
        }
        root.addView(bubble)
        updateWindow()
    }

    private fun renderAnswerPanel(answer: QuestionAnswer) {
        expanded = true
        renderPanel(
            when (answer.questionType) {
                QuestionType.MULTIPLE_CHOICE -> "选择题答案"
                QuestionType.FILL_IN -> "填空题答案"
                QuestionType.SHORT_ANSWER -> "简答题答案"
                QuestionType.UNKNOWN -> "识别到的答案"
            },
            answer.answer,
            showCopy = true,
            allowAsk = true
        )
    }

    private fun renderPanel(title: String, body: String, showCopy: Boolean, allowAsk: Boolean) {
        expanded = true
        root.removeAllViews()
        root.minimumWidth = (appContext.resources.displayMetrics.widthPixels * .72f).toInt().coerceAtMost(920)
        val maxPanelWidth = (appContext.resources.displayMetrics.widthPixels * .72f).toInt().coerceAtMost(920)
        root.addView(TextView(root.context).apply {
            text = title
            setTextColor(0xFF8DD9FF.toInt())
            textSize = 15f
            maxWidth = maxPanelWidth
            setPadding(4, 0, 4, 8)
        })
        root.addView(TextView(root.context).apply {
            text = body
            setTextColor(0xFFFFFFFF.toInt())
            textSize = 17f
            maxWidth = maxPanelWidth
            maxHeight = (appContext.resources.displayMetrics.heightPixels * .48f).toInt()
            setPadding(4, 4, 4, 10)
        })
        val buttons = LinearLayout(root.context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.END }
        if (showCopy) buttons.addView(Button(root.context).apply {
            text = "复制答案"
            setOnClickListener {
                val clipboard = appContext.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("AI 答案", body))
                Toast.makeText(appContext, "答案已复制", Toast.LENGTH_SHORT).show()
            }
        })
        if (allowAsk) buttons.addView(Button(root.context).apply {
            text = if (inFlight) "识别中…" else "再拍一题"
            isEnabled = !inFlight
            setOnClickListener { onAsk() }
        })
        buttons.addView(Button(root.context).apply {
            text = "收起"
            setOnClickListener { renderBubble("AI · 点我看答案") }
        })
        root.addView(buttons)
        updateWindow()
    }

    private fun updateWindow() {
        if (added) runCatching { windowManager.updateViewLayout(root, params) }
    }

    private fun dragTouchListener() = View.OnTouchListener { view, event ->
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                view.tag = floatArrayOf(event.rawX, event.rawY, params.x.toFloat(), params.y.toFloat())
                true
            }
            MotionEvent.ACTION_MOVE -> {
                val origin = view.tag as? FloatArray ?: return@OnTouchListener false
                val dx = event.rawX - origin[0]
                val dy = event.rawY - origin[1]
                if (abs(dx) + abs(dy) > 8) {
                    val metrics = appContext.resources.displayMetrics
                    params.x = (origin[2] + dx).toInt().coerceIn(0, (metrics.widthPixels - root.width).coerceAtLeast(0))
                    params.y = (origin[3] + dy).toInt().coerceIn(0, (metrics.heightPixels - root.height).coerceAtLeast(0))
                    updateWindow()
                }
                true
            }
            MotionEvent.ACTION_UP -> {
                val origin = view.tag as? FloatArray ?: return@OnTouchListener false
                val moved = abs(event.rawX - origin[0]) + abs(event.rawY - origin[1]) > 8
                if (!moved) view.performClick()
                true
            }
            else -> true
        }
    }

    private fun rounded(color: Int, radius: Int) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = radius.toFloat()
        setStroke(1, 0xFF607080.toInt())
    }
}
