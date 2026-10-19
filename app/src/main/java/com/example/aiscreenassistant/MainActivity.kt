package com.example.aiscreenassistant

import android.app.Activity
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.aiscreenassistant.accessibility.AssistantAccessibilityService
import com.example.aiscreenassistant.ai.OpenAiCompatibleClient
import com.example.aiscreenassistant.capture.CaptureSession
import com.example.aiscreenassistant.capture.CaptureState
import com.example.aiscreenassistant.capture.ScreenCaptureService
import com.example.aiscreenassistant.engine.TaskViewModel
import com.example.aiscreenassistant.settings.ApiSettings
import com.example.aiscreenassistant.settings.ApiSettingsStore
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val capture = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) ScreenCaptureService.start(this, result.resultCode, result.data!!)
        else CaptureSession.update(CaptureState.STOPPED, "你取消了录屏授权")
    }
    private lateinit var apiStore: ApiSettingsStore
    override fun onCreate(savedInstanceState: Bundle?) { super.onCreate(savedInstanceState); setContent { AssistantScreen() } }
    private fun requestCapture() { capture.launch(getSystemService(MediaProjectionManager::class.java).createScreenCaptureIntent()) }
    private fun requestAccessibility() { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable private fun AssistantScreen(vm: TaskViewModel = viewModel()) {
        if (!::apiStore.isInitialized) apiStore = ApiSettingsStore(this@MainActivity)
        val initial = remember { apiStore.read() }
        var baseUrl by remember { mutableStateOf(initial.baseUrl) }
        var token by remember { mutableStateOf(initial.token) }
        var selectedModel by remember { mutableStateOf(initial.selectedModel) }
        var modelSearch by remember { mutableStateOf("") }
        var models by remember { mutableStateOf(emptyList<String>()) }
        var menuExpanded by remember { mutableStateOf(false) }
        var apiBusy by remember { mutableStateOf(false) }
        var apiMessage by remember { mutableStateOf("") }
        val scope = rememberCoroutineScope()
        val captureStatus by CaptureSession.status.collectAsState()
        val ui by vm.ui.collectAsState()
        val filteredModels = remember(models, modelSearch) { models.filter { it.contains(modelSearch, ignoreCase = true) } }
        fun client() = OpenAiCompatibleClient(baseUrl, token, selectedModel)
        fun validateCredentials(): Boolean {
            if (baseUrl.isBlank() || token.isBlank()) { apiMessage = "请填写 API 地址和 Token"; return false }
            return true
        }

        MaterialTheme { Scaffold(topBar = { TopAppBar(title = { Text("AI 屏幕助手") }) }) { pad ->
            Column(Modifier.padding(pad).padding(20.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("仅在你明确授权后读取屏幕并执行辅助操作。默认不保存截图和作业内容。")
                Text("录屏：${captureStatus.state}", style = MaterialTheme.typography.titleMedium)
                Text(captureStatus.message)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = ::requestCapture) { Text(if (captureStatus.state == CaptureState.CAPTURING) "重新授权录屏" else "授权录屏") }
                    OutlinedButton(onClick = { ScreenCaptureService.stop(this@MainActivity) }, enabled = captureStatus.state == CaptureState.CAPTURING) { Text("停止录屏") }
                    OutlinedButton(onClick = ::requestAccessibility) { Text(if (AssistantAccessibilityService.enabled) "无障碍已启用" else "启用无障碍") }
                }
                HorizontalDivider()
                Text("OpenAI 兼容 API", style = MaterialTheme.typography.titleMedium)
                Text("填写服务根地址或以 /v1 结尾的地址，例如 https://api.openai.com 或 https://example.com/v1。")
                OutlinedTextField(value = baseUrl, onValueChange = { baseUrl = it }, label = { Text("API 地址") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = token, onValueChange = { token = it }, label = { Text("API 密钥 / Token") }, visualTransformation = PasswordVisualTransformation(), singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = selectedModel, onValueChange = { selectedModel = it }, label = { Text("已选模型") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(enabled = !apiBusy, onClick = {
                        if (!validateCredentials()) return@Button
                        scope.launch {
                            apiBusy = true; apiMessage = "正在拉取模型…"
                            runCatching { client().listModels() }.onSuccess { result -> models = result; apiMessage = if (result.isEmpty()) "服务未返回模型" else "已拉取 ${result.size} 个模型"; menuExpanded = result.isNotEmpty() }.onFailure { apiMessage = "拉取失败：${it.message}" }
                            apiBusy = false
                        }
                    }) { Text("拉取模型") }
                    OutlinedButton(enabled = !apiBusy, onClick = {
                        if (!validateCredentials()) return@OutlinedButton
                        scope.launch {
                            apiBusy = true; apiMessage = "正在测试连接…"
                            runCatching { client().testConnection(selectedModel) }.onSuccess { result -> apiMessage = "连接成功：返回 ${result.modelCount} 个模型" + if (selectedModel.isNotBlank() && !result.selectedModelAvailable) "；当前模型不在列表中" else "" }.onFailure { apiMessage = "连接失败：${it.message}" }
                            apiBusy = false
                        }
                    }) { Text("测试连接") }
                    OutlinedButton(onClick = { apiStore.save(ApiSettings(baseUrl, token, selectedModel)); apiMessage = "API 配置已保存" }) { Text("保存") }
                }
                if (models.isNotEmpty()) {
                    OutlinedTextField(value = modelSearch, onValueChange = { modelSearch = it; menuExpanded = true }, label = { Text("搜索已拉取模型") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    ExposedDropdownMenuBox(expanded = menuExpanded, onExpandedChange = { menuExpanded = it }) {
                        OutlinedTextField(value = "${filteredModels.size} 个匹配模型", onValueChange = {}, readOnly = true, label = { Text("选择模型") }, trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = menuExpanded) }, modifier = Modifier.menuAnchor().fillMaxWidth())
                        ExposedDropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                            filteredModels.take(100).forEach { model -> DropdownMenuItem(text = { Text(model) }, onClick = { selectedModel = model; modelSearch = model; menuExpanded = false }) }
                        }
                    }
                }
                if (apiBusy) LinearProgressIndicator(Modifier.fillMaxWidth())
                if (apiMessage.isNotEmpty()) Text(apiMessage, color = MaterialTheme.colorScheme.primary)
                HorizontalDivider()
                Text("任务状态：${ui.state}", style = MaterialTheme.typography.titleMedium)
                Text(ui.message)
                Button(onClick = vm::start, enabled = captureStatus.state == CaptureState.CAPTURING && AssistantAccessibilityService.enabled) { Text("开始辅助") }
                OutlinedButton(onClick = { vm.pause() }) { Text("暂停") }
                OutlinedButton(onClick = vm::stop) { Text("停止并清理") }
                ui.pending?.let { pending -> AlertDialog(onDismissRequest = { vm.pause("用户取消了操作") }, title = { Text("确认操作") }, text = { Text("AI 请求：${pending.type}\n${pending.reason}") }, confirmButton = { TextButton(onClick = { vm.confirm(pending) }) { Text("确认") } }, dismissButton = { TextButton(onClick = { vm.pause("用户取消了操作") }) { Text("取消") } }) }
            }
        } }
    }
}
