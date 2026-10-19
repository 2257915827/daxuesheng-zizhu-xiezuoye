package com.example.aiscreenassistant

import android.app.Activity
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.example.aiscreenassistant.ai.OpenAiCompatibleClient
import com.example.aiscreenassistant.capture.CaptureSession
import com.example.aiscreenassistant.capture.CaptureState
import com.example.aiscreenassistant.capture.ScreenCaptureService
import com.example.aiscreenassistant.settings.ApiSettings
import com.example.aiscreenassistant.settings.ApiSettingsStore
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private var overlayAllowed by mutableStateOf(false)
    private val capture = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            ScreenCaptureService.start(this, result.resultCode, result.data!!)
        } else CaptureSession.update(CaptureState.STOPPED, "你取消了录屏授权")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        overlayAllowed = Settings.canDrawOverlays(this)
        setContent { AssistantScreen() }
    }

    override fun onResume() {
        super.onResume()
        overlayAllowed = Settings.canDrawOverlays(this)
    }

    private fun requestOverlayPermission() {
        startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
    }

    private fun requestCapture() {
        capture.launch(getSystemService(MediaProjectionManager::class.java).createScreenCaptureIntent())
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun AssistantScreen() {
        val apiStore = remember { ApiSettingsStore(this@MainActivity) }
        val initial = remember { apiStore.read() }
        var baseUrl by remember { mutableStateOf(initial.baseUrl) }
        var token by remember { mutableStateOf(initial.token) }
        var selectedModel by remember { mutableStateOf(initial.selectedModel) }
        var modelSearch by remember { mutableStateOf("") }
        var models by remember { mutableStateOf(emptyList<String>()) }
        var modelMenuExpanded by remember { mutableStateOf(false) }
        var busy by remember { mutableStateOf(false) }
        var message by remember { mutableStateOf("") }
        var showPrivacy by remember { mutableStateOf(false) }
        val scope = rememberCoroutineScope()
        val captureStatus by CaptureSession.status.collectAsState()
        val visibleModels = remember(models, modelSearch) { models.filter { it.contains(modelSearch, ignoreCase = true) } }

        fun client() = OpenAiCompatibleClient(baseUrl, token, selectedModel)
        fun validateApi(): Boolean {
            if (baseUrl.isBlank() || token.isBlank()) { message = "请填写 API 地址和 Token"; return false }
            return true
        }
        fun beginAssistant() {
            if (captureStatus.state == CaptureState.CAPTURING || captureStatus.state == CaptureState.STARTING) return
            if (!overlayAllowed) requestOverlayPermission() else requestCapture()
        }

        MaterialTheme {
            Scaffold(topBar = { TopAppBar(title = { Text("AI 悬浮答案助手") }) }) { padding ->
                Column(
                    Modifier.padding(padding).padding(20.dp).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text("进入答题页面后点悬浮 AI 气泡识题。应用只提供答案，不会点击选项、填写或提交。")
                    Text("其他应用上层权限：${if (overlayAllowed) "已允许" else "未允许"}", style = MaterialTheme.typography.titleMedium)
                    Text("录屏状态：${captureStatus.state} · ${captureStatus.message}")
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = ::beginAssistant, enabled = captureStatus.state != CaptureState.CAPTURING && captureStatus.state != CaptureState.STARTING) {
                            Text(if (!overlayAllowed) "允许悬浮窗并开启助手" else "开启悬浮答案助手")
                        }
                        OutlinedButton(onClick = { ScreenCaptureService.stop(this@MainActivity) }, enabled = captureStatus.state == CaptureState.CAPTURING || captureStatus.state == CaptureState.STARTING) {
                            Text("停止辅助")
                        }
                    }
                    TextButton(onClick = { showPrivacy = true }) { Text("截图与隐私说明") }
                    HorizontalDivider()
                    Text("OpenAI 兼容 API", style = MaterialTheme.typography.titleMedium)
                    Text("填写服务根地址或以 /v1 结尾的地址，例如 https://api.openai.com 或 https://example.com/v1。请选支持图像输入的模型。")
                    OutlinedTextField(value = baseUrl, onValueChange = { baseUrl = it }, label = { Text("API 地址") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(value = token, onValueChange = { token = it }, label = { Text("API 密钥 / Token") }, visualTransformation = PasswordVisualTransformation(), singleLine = true, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(value = selectedModel, onValueChange = { selectedModel = it }, label = { Text("已选模型") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(enabled = !busy, onClick = {
                            if (!validateApi()) return@Button
                            scope.launch {
                                busy = true; message = "正在拉取模型…"
                                runCatching { client().listModels() }
                                    .onSuccess { result -> models = result; message = if (result.isEmpty()) "服务未返回模型" else "已拉取 ${result.size} 个模型"; modelMenuExpanded = result.isNotEmpty() }
                                    .onFailure { message = "拉取失败：${it.message}" }
                                busy = false
                            }
                        }) { Text("拉取模型") }
                        OutlinedButton(enabled = !busy, onClick = {
                            if (!validateApi()) return@OutlinedButton
                            scope.launch {
                                busy = true; message = "正在测试连接…"
                                runCatching { client().testConnection(selectedModel) }
                                    .onSuccess { result -> message = "连接成功：${result.modelCount} 个模型" + if (!result.selectedModelAvailable) "；当前模型不在列表中" else "" }
                                    .onFailure { message = "连接失败：${it.message}" }
                                busy = false
                            }
                        }) { Text("测试连接") }
                        OutlinedButton(onClick = { apiStore.save(ApiSettings(baseUrl, token, selectedModel)); message = "API 配置已安全保存" }) { Text("保存") }
                    }
                    if (models.isNotEmpty()) {
                        OutlinedTextField(value = modelSearch, onValueChange = { modelSearch = it; modelMenuExpanded = true }, label = { Text("搜索模型") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                        ExposedDropdownMenuBox(expanded = modelMenuExpanded, onExpandedChange = { modelMenuExpanded = it }) {
                            OutlinedTextField(
                                value = if (selectedModel.isBlank()) "${visibleModels.size} 个可选模型" else selectedModel,
                                onValueChange = {}, readOnly = true, label = { Text("选择模型") },
                                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = modelMenuExpanded) },
                                modifier = Modifier.menuAnchor().fillMaxWidth()
                            )
                            ExposedDropdownMenu(expanded = modelMenuExpanded, onDismissRequest = { modelMenuExpanded = false }) {
                                visibleModels.take(100).forEach { model ->
                                    DropdownMenuItem(text = { Text(model) }, onClick = { selectedModel = model; modelSearch = model; modelMenuExpanded = false })
                                }
                            }
                        }
                    }
                    if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                    if (message.isNotBlank()) Text(message, color = MaterialTheme.colorScheme.primary)
                }
            }
        }
        if (showPrivacy) AlertDialog(
            onDismissRequest = { showPrivacy = false },
            title = { Text("屏幕截图与隐私") },
            text = { Text("每次点击悬浮气泡时，应用会临时截取当前屏幕并发送到你配置的 AI 服务以生成答案。截图和答案不会保存在本机；关闭辅助会停止录屏并移除悬浮窗。请勿在含密码、支付或其他敏感信息的页面使用。") },
            confirmButton = { TextButton(onClick = { showPrivacy = false }) { Text("知道了") } }
        )
    }
}
