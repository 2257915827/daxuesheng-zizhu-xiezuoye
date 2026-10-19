package com.example.aiscreenassistant.capture

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
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
import android.widget.HorizontalScrollView
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.example.aiscreenassistant.model.QuestionAnswer
import com.example.aiscreenassistant.model.QuestionType
import kotlin.math.abs

class FloatingAssistantController(
    context: Context,
    private val onAsk: () -> Unit,
    private val onClose: () -> Unit
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
    private var tucked = false
    private var lastAnswer: QuestionAnswer? = null
    private var lastError: String? = null
    private var revealToken = 0
    private var touchMoved = false
    private var longPressed = false
    private var previousX = 20
    private val tuckRunnable = Runnable { tuckToEdge() }
    private val longPressRunnable = Runnable {
        if (!touchMoved && added && !tucked) {
            longPressed = true
            showPanel()
        }
    }

    fun show() {
        if (!added) {
            windowManager.addView(root, params)
            added = true
            renderBubble()
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
                if (added && !expanded && !inFlight && token == revealToken) renderBubble()
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

    private fun renderBubble(label: String = "✦") {
        expanded = false
        handler.removeCallbacks(tuckRunnable)
        root.removeAllViews()
        root.setPadding(0, 0, 0, 0)
        root.background = GradientDrawable(
            GradientDrawable.Orientation.TL_BR,
            intArrayOf(0xFF72D7FF.toInt(), 0xFF4675F5.toInt(), 0xFF7556DD.toInt())
        ).apply { shape = GradientDrawable.OVAL; setStroke(2, 0xCCFFFFFF.toInt()) }
        val bubble = TextView(root.context).apply {
            text = label
            setTextColor(0xFFFFFFFF.toInt())
            textSize = if (label == "✦") 25f else 12f
            gravity = Gravity.CENTER
            maxWidth = 190
            minWidth = if (label == "✦") dp(56) else dp(72)
            minHeight = dp(56)
            setPadding(dp(8), dp(4), dp(8), dp(4))
            setOnTouchListener(bubbleTouchListener())
            setOnClickListener {
                if (longPressed) { longPressed = false; return@setOnClickListener }
                if (tucked) { untuck(); return@setOnClickListener }
                if (inFlight) return@setOnClickListener
                when {
                    lastAnswer != null -> renderAnswerPanel(lastAnswer!!)
                    lastError != null -> renderPanel("识别失败", lastError!!, showCopy = false, allowAsk = true)
                    else -> onAsk()
                }
            }
        }
        root.addView(bubble, LinearLayout.LayoutParams(if (label == "✦") dp(56) else -2, if (label == "✦") dp(56) else dp(56)))
        updateWindow()
        if (!tucked) handler.postDelayed(tuckRunnable, 2_000)
    }

    private fun showPanel() {
        if (inFlight) {
            renderPanel("正在识别截图…", "请稍候，AI 正在分析当前题目。", showCopy = false, allowAsk = false)
        } else when {
            lastAnswer != null -> renderAnswerPanel(lastAnswer!!)
            lastError != null -> renderPanel("识别失败", lastError!!, showCopy = false, allowAsk = true)
            else -> renderPanel("AI 答案助手", "单点球形按钮截图识题；长按打开功能面板。", showCopy = false, allowAsk = true)
        }
    }

    private fun renderAnswerPanel(answer: QuestionAnswer) {
        expanded = true
        val cacheLine = if (answer.cacheHitTokens > 0) "\n缓存命中：${answer.cacheHitTokens} tokens" else ""
        renderPanel(
            when (answer.questionType) {
                QuestionType.MULTIPLE_CHOICE -> "选择题答案"
                QuestionType.FILL_IN -> "填空题答案"
                QuestionType.SHORT_ANSWER -> "简答题答案"
                QuestionType.UNKNOWN -> "识别到的答案"
            },
            answer.answer + cacheLine,
            showCopy = true,
            allowAsk = true
        )
    }

    private fun renderPanel(title: String, body: String, showCopy: Boolean, allowAsk: Boolean) {
        expanded = true
        handler.removeCallbacks(tuckRunnable)
        root.removeAllViews()
        root.setPadding(dp(16), dp(12), dp(16), dp(12))
        root.background = rounded(0xEE20242C.toInt(), 20)
        val maxPanelWidth = (appContext.resources.displayMetrics.widthPixels * .78f).toInt().coerceAtMost(dp(420))
        root.minimumWidth = maxPanelWidth
        root.addView(TextView(root.context).apply {
            text = title
            setTextColor(0xFF8DD9FF.toInt())
            textSize = 15f
            maxWidth = maxPanelWidth
            setPadding(dp(4), 0, dp(4), dp(8))
        })
        root.addView(TextView(root.context).apply {
            text = body
            setTextColor(0xFFFFFFFF.toInt())
            textSize = 17f
            maxWidth = maxPanelWidth
            maxHeight = (appContext.resources.displayMetrics.heightPixels * .42f).toInt()
            setPadding(dp(4), dp(4), dp(4), dp(10))
        })
        val buttons = LinearLayout(root.context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.END }
        if (showCopy) buttons.addView(Button(root.context).apply {
            text = "复制答案"
            setOnClickListener {
                val clipboard = appContext.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("AI 答案", lastAnswer?.answer.orEmpty()))
                Toast.makeText(appContext, "答案已复制", Toast.LENGTH_SHORT).show()
            }
        })
        if (allowAsk) buttons.addView(Button(root.context).apply {
            text = if (inFlight) "识别中…" else "再拍一题"
            isEnabled = !inFlight
            setOnClickListener { onAsk() }
        })
        buttons.addView(Button(root.context).apply {
            text = "跳转到应用"
            setOnClickListener { showAppPicker() }
        })
        buttons.addView(Button(root.context).apply {
            text = "关闭悬浮窗"
            setOnClickListener { onClose() }
        })
        if (lastAnswer != null || lastError != null) buttons.addView(Button(root.context).apply {
            text = "收起"
            setOnClickListener { renderBubble() }
        })
        root.addView(HorizontalScrollView(root.context).apply {
            isHorizontalScrollBarEnabled = true
            addView(buttons)
        })
        updateWindow()
    }

    private fun showAppPicker() {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        @Suppress("DEPRECATION")
        val apps = appContext.packageManager.queryIntentActivities(intent, PackageManager.MATCH_ALL)
            .filter { it.activityInfo.packageName != appContext.packageName }
            .distinctBy { it.activityInfo.packageName }
            .sortedBy { it.loadLabel(appContext.packageManager).toString().lowercase() }
        if (apps.isEmpty()) {
            Toast.makeText(appContext, "没有找到可打开的应用", Toast.LENGTH_SHORT).show()
            return
        }
        root.removeAllViews()
        root.setPadding(dp(12), dp(10), dp(12), dp(10))
        root.addView(TextView(root.context).apply {
            text = "选择要打开的应用"
            setTextColor(0xFF8DD9FF.toInt()); textSize = 16f
        })
        val list = LinearLayout(root.context).apply { orientation = LinearLayout.VERTICAL }
        apps.take(40).forEach { info ->
            val name = info.loadLabel(appContext.packageManager).toString()
            list.addView(Button(root.context).apply {
                text = name
                setOnClickListener {
                    val launch = appContext.packageManager.getLaunchIntentForPackage(info.activityInfo.packageName)
                    if (launch != null) {
                        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        appContext.startActivity(launch)
                        renderBubble()
                    }
                }
            })
        }
        root.addView(ScrollView(root.context).apply { addView(list) }, LinearLayout.LayoutParams(-1, dp(300)))
        root.addView(Button(root.context).apply { text = "返回面板"; setOnClickListener { showPanel() } })
        updateWindow()
    }

    private fun bubbleTouchListener() = View.OnTouchListener { view, event ->
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                touchMoved = false
                longPressed = false
                view.tag = floatArrayOf(event.rawX, event.rawY, params.x.toFloat(), params.y.toFloat())
                handler.postDelayed(longPressRunnable, android.view.ViewConfiguration.getLongPressTimeout().toLong())
                true
            }
            MotionEvent.ACTION_MOVE -> {
                val origin = view.tag as? FloatArray ?: return@OnTouchListener false
                val dx = event.rawX - origin[0]
                val dy = event.rawY - origin[1]
                if (abs(dx) + abs(dy) > dp(8)) {
                    touchMoved = true
                    handler.removeCallbacks(longPressRunnable)
                    if (tucked) untuck()
                    val metrics = appContext.resources.displayMetrics
                    params.x = (origin[2] + dx).toInt().coerceIn(0, (metrics.widthPixels - root.width).coerceAtLeast(0))
                    params.y = (origin[3] + dy).toInt().coerceIn(0, (metrics.heightPixels - root.height).coerceAtLeast(0))
                    updateWindow()
                }
                true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                handler.removeCallbacks(longPressRunnable)
                val origin = view.tag as? FloatArray
                val moved = touchMoved
                if (event.actionMasked == MotionEvent.ACTION_UP && moved) {
                    val width = appContext.resources.displayMetrics.widthPixels
                    previousX = params.x
                    params.x = if (params.x + root.width / 2 < width / 2) 0 else (width - root.width).coerceAtLeast(0)
                    updateWindow()
                    handler.removeCallbacks(tuckRunnable)
                    handler.postDelayed(tuckRunnable, 2_000)
                } else if (event.actionMasked == MotionEvent.ACTION_UP && !longPressed) {
                    if (tucked) { untuck() } else view.performClick()
                }
                true
            }
            else -> true
        }
    }

    private fun tuckToEdge() {
        if (!added || expanded || inFlight || tucked) return
        val width = appContext.resources.displayMetrics.widthPixels
        val atLeft = params.x + root.width / 2 < width / 2
        previousX = params.x
        params.x = if (atLeft) -(root.width - dp(14)) else width - dp(14)
        tucked = true
        updateWindow()
    }

    private fun untuck() {
        if (!tucked) return
        params.x = previousX.coerceIn(0, (appContext.resources.displayMetrics.widthPixels - root.width).coerceAtLeast(0))
        tucked = false
        updateWindow()
        handler.removeCallbacks(tuckRunnable)
        handler.postDelayed(tuckRunnable, 2_000)
    }

    private fun updateWindow() { if (added) runCatching { windowManager.updateViewLayout(root, params) } }
    private fun dp(value: Int) = (value * appContext.resources.displayMetrics.density).toInt()
    private fun rounded(color: Int, radius: Int) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(radius).toFloat()
        setStroke(1, 0xFF607080.toInt())
    }
}
