package com.example.aiscreenassistant

import android.app.Activity
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.content.ComponentName
import android.provider.Settings.Secure
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
import com.example.aiscreenassistant.accessibility.AccessibilitySession
import com.example.aiscreenassistant.ai.OpenAiCompatibleClient
import com.example.aiscreenassistant.capture.CaptureSession
import com.example.aiscreenassistant.capture.CaptureState
import com.example.aiscreenassistant.capture.ScreenCaptureService
import com.example.aiscreenassistant.engine.TaskViewModel
import com.example.aiscreenassistant.settings.ApiSettings
import com.example.aiscreenassistant.settings.ApiSettingsStore
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay

private data class LaunchableApp(val label: String, val packageName: String)

class MainActivity : ComponentActivity() {
    private var autoCaptureAfterAccessibility = false
    private var accessibilityEnabledState by mutableStateOf(false)
    private val capture = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) ScreenCaptureService.start(this, result.resultCode, result.data!!)
        else CaptureSession.update(CaptureState.STOPPED, "你取消了录屏授权")
    }
    private lateinit var apiStore: ApiSettingsStore
    override fun onCreate(savedInstanceState: Bundle?) { super.onCreate(savedInstanceState); setContent { AssistantScreen() } }
    override fun onResume() {
        super.onResume()
        accessibilityEnabledState = isAssistantAccessibilityEnabled()
        if (autoCaptureAfterAccessibility) {
            autoCaptureAfterAccessibility = false
            if (accessibilityEnabledState) Handler(Looper.getMainLooper()).postDelayed({ requestCapture() }, 350)
        }
    }
    private fun requestCapture() { capture.launch(getSystemService(MediaProjectionManager::class.java).createScreenCaptureIntent()) }
    private fun requestAccessibility() { autoCaptureAfterAccessibility = true; startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
    private fun isAssistantAccessibilityEnabled(): Boolean {
        val expected = ComponentName(this, AssistantAccessibilityService::class.java).flattenToString()
        val enabledServices = Secure.getString(contentResolver, Secure.ENABLED_ACCESSIBILITY_SERVICES).orEmpty()
        return Secure.getInt(contentResolver, Secure.ACCESSIBILITY_ENABLED, 0) == 1 && enabledServices.split(':').any { it.equals(expected, ignoreCase = true) }
    }
    @Suppress("DEPRECATION")
    private fun launchableApps(): List<LaunchableApp> {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return packageManager.queryIntentActivities(intent, 0).mapNotNull { info ->
            val packageName = info.activityInfo?.packageName ?: return@mapNotNull null
            LaunchableApp(info.loadLabel(packageManager).toString(), packageName)
        }.filter { it.packageName != this.packageName }.distinctBy { it.packageName }.sortedBy { it.label.lowercase() }
    }
    private fun launchTarget(packageName: String): Boolean {
        val intent = packageManager.getLaunchIntentForPackage(packageName) ?: return false
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        startActivity(intent)
        return true
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable private fun AssistantScreen(vm: TaskViewModel = viewModel()) {
        if (!::apiStore.isInitialized) apiStore = ApiSettingsStore(this@MainActivity)
        val initial = remember { apiStore.read() }
        var baseUrl by remember { mutableStateOf(initial.baseUrl) }
        var token by remember { mutableStateOf(initial.token) }
        var selectedModel by remember { mutableStateOf(initial.selectedModel) }
        val appPrefs = remember { getSharedPreferences("task_settings", MODE_PRIVATE) }
        val apps = remember { launchableApps() }
        var selectedPackage by remember { mutableStateOf(appPrefs.getString("target_package", "") ?: "") }
        var selectedAppLabel by remember { mutableStateOf(apps.firstOrNull { it.packageName == selectedPackage }?.label.orEmpty()) }
        var appMenuExpanded by remember { mutableStateOf(false) }
        var appSearch by remember { mutableStateOf("") }
        var launchAfterCapture by remember { mutableStateOf(false) }
        var modelSearch by remember { mutableStateOf("") }
        var models by remember { mutableStateOf(emptyList<String>()) }
        var menuExpanded by remember { mutableStateOf(false) }
        var apiBusy by remember { mutableStateOf(false) }
        var apiMessage by remember { mutableStateOf("") }
        val scope = rememberCoroutineScope()
        val captureStatus by CaptureSession.status.collectAsState()
        val accessibilityConnected by AccessibilitySession.enabled.collectAsState()
        val ui by vm.ui.collectAsState()
        val filteredModels = remember(models, modelSearch) { models.filter { it.contains(modelSearch, ignoreCase = true) } }
        val filteredApps = remember(apps, appSearch) { apps.filter { it.label.contains(appSearch, ignoreCase = true) || it.packageName.contains(appSearch, ignoreCase = true) } }
        fun client() = OpenAiCompatibleClient(baseUrl, token, selectedModel)
        fun validateCredentials(): Boolean {
            if (baseUrl.isBlank() || token.isBlank()) { apiMessage = "请填写 API 地址和 Token"; return false }
            return true
        }

        MaterialTheme { Scaffold(topBar = { TopAppBar(title = { Text("AI 屏幕助手") }) }) { pad ->
            LaunchedEffect(captureStatus.state, selectedPackage) {
                accessibilityEnabledState = isAssistantAccessibilityEnabled()
                if (captureStatus.state == CaptureState.CAPTURING && launchAfterCapture && selectedPackage.isNotBlank()) {
                    launchAfterCapture = false
                    if (launchTarget(selectedPackage)) {
                        delay(1200)
                        vm.start(selectedPackage)
                    } else {
                        apiMessage = "无法打开所选应用，请重新选择"
                    }
                }
            }
            Column(Modifier.padding(pad).padding(20.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("仅在你明确授权后读取屏幕并执行辅助操作。默认不保存截图和作业内容。")
                Text("无障碍设置：${if (accessibilityEnabledState) "已启用" else "未启用"}；服务连接：${if (accessibilityConnected) "正常" else "未连接"}", style = MaterialTheme.typography.titleMedium)
                Text("录屏状态：${if (captureStatus.state == CaptureState.CAPTURING) "正在运行" else "未运行"}")
                Text("录屏：${captureStatus.state}", style = MaterialTheme.typography.titleMedium)
                Text(captureStatus.message)
                ExposedDropdownMenuBox(expanded = appMenuExpanded, onExpandedChange = { appMenuExpanded = it }) {
                    OutlinedTextField(value = selectedAppLabel, onValueChange = { appSearch = it; appMenuExpanded = true }, label = { Text("选择作业应用") }, placeholder = { Text("搜索手机上的应用") }, trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = appMenuExpanded) }, modifier = Modifier.menuAnchor().fillMaxWidth())
                    ExposedDropdownMenu(expanded = appMenuExpanded, onDismissRequest = { appMenuExpanded = false }) {
                        filteredApps.take(80).forEach { app -> DropdownMenuItem(text = { Text(app.label) }, onClick = { selectedPackage = app.packageName; selectedAppLabel = app.label; appPrefs.edit().putString("target_package", app.packageName).apply(); appMenuExpanded = false }) }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = {
                        if (selectedPackage.isBlank()) { apiMessage = "请先选择作业应用" }
                        else if (!accessibilityEnabledState) { requestAccessibility() }
                        else { launchAfterCapture = true; requestCapture() }
                    }) { Text(if (!accessibilityEnabledState) "启用无障碍并继续" else if (captureStatus.state == CaptureState.CAPTURING) "重新授权录屏" else "授权录屏并打开作业应用") }
                    OutlinedButton(onClick = { ScreenCaptureService.stop(this@MainActivity) }, enabled = captureStatus.state == CaptureState.CAPTURING) { Text("停止录屏") }
                    OutlinedButton(onClick = { requestAccessibility() }) { Text("无障碍设置") }
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
                Text("已自动选择题数：${ui.questionsAnswered}")
                Button(onClick = { if (selectedPackage.isNotBlank()) vm.start(selectedPackage) }, enabled = captureStatus.state == CaptureState.CAPTURING && accessibilityConnected && selectedPackage.isNotBlank()) { Text("开始 / 继续辅助") }
                OutlinedButton(onClick = { vm.pause() }) { Text("暂停辅助") }
                OutlinedButton(onClick = vm::stop) { Text("停止并清理") }
                if (ui.state == com.example.aiscreenassistant.model.TaskState.AWAITING_FINISH) {
                    AlertDialog(onDismissRequest = { vm.continueAfterCompletion(selectedPackage) }, title = { Text("当前作业已完成") }, text = { Text("AI 判断当前页面没有未完成题目，已自动选择 ${ui.questionsAnswered} 题。不会提交作业。要结束辅助吗？") }, confirmButton = { TextButton(onClick = vm::finishSupport) { Text("结束辅助") } }, dismissButton = { TextButton(onClick = { vm.continueAfterCompletion(selectedPackage) }) { Text("继续监测") } })
                }
                if (ui.state == com.example.aiscreenassistant.model.TaskState.PAUSED) {
                    AlertDialog(onDismissRequest = { }, title = { Text("辅助已暂停") }, text = { Text(ui.message) }, confirmButton = { TextButton(onClick = { if (selectedPackage.isNotBlank()) vm.continueAfterPause(selectedPackage) }) { Text("继续") } }, dismissButton = { TextButton(onClick = vm::stop) { Text("结束辅助") } })
                }
            }
        } }
    }
}
