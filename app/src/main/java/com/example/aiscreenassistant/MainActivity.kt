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
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.aiscreenassistant.accessibility.AssistantAccessibilityService
import com.example.aiscreenassistant.capture.ScreenCaptureManager
import com.example.aiscreenassistant.engine.TaskViewModel
import com.example.aiscreenassistant.settings.ApiSettings
import com.example.aiscreenassistant.settings.ApiSettingsStore

class MainActivity : ComponentActivity() {
    private var captureManager: ScreenCaptureManager? = null
    private val capture = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        captureGranted = result.resultCode == Activity.RESULT_OK
        if (captureGranted && result.data != null) captureManager = ScreenCaptureManager(this).also { it.start(result.resultCode, result.data!!) }
    }
    private var captureGranted by mutableStateOf(false)
    private lateinit var apiStore: ApiSettingsStore
    override fun onCreate(savedInstanceState: Bundle?) { super.onCreate(savedInstanceState); setContent { AssistantScreen() } }
    private fun requestCapture() { val mgr = getSystemService(MediaProjectionManager::class.java); capture.launch(mgr.createScreenCaptureIntent()) }
    private fun requestAccessibility() { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
    override fun onDestroy() { captureManager?.close(); super.onDestroy() }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable private fun AssistantScreen(vm: TaskViewModel = viewModel()) {
        if (!::apiStore.isInitialized) apiStore = ApiSettingsStore(this@MainActivity)
        var baseUrl by remember { mutableStateOf(apiStore.read().baseUrl) }
        var token by remember { mutableStateOf(apiStore.read().token) }
        var savedMessage by remember { mutableStateOf("") }
        val ui by vm.ui.collectAsState()
        MaterialTheme { Scaffold(topBar = { TopAppBar(title = { Text("AI 屏幕助手") }) }) { pad ->
            Column(Modifier.padding(pad).padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("仅在你明确授权后读取屏幕并执行辅助操作。默认不保存截图和作业内容。")
                Text("状态：${ui.state}", style = MaterialTheme.typography.titleMedium)
                Text(ui.message)
                Text("AI API 配置", style = MaterialTheme.typography.titleMedium)
                OutlinedTextField(value = baseUrl, onValueChange = { baseUrl = it }, label = { Text("API 地址") }, placeholder = { Text("https://example.com/api") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = token, onValueChange = { token = it }, label = { Text("API 密钥 / Token") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { apiStore.save(ApiSettings(baseUrl, token)); savedMessage = "API 配置已保存" }) { Text("保存配置") }
                    OutlinedButton(onClick = { apiStore.clear(); baseUrl = ""; token = ""; savedMessage = "配置已清除" }) { Text("清除") }
                }
                if (savedMessage.isNotEmpty()) Text(savedMessage, color = MaterialTheme.colorScheme.primary)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = ::requestCapture) { Text(if (captureGranted) "已授权录屏" else "授权录屏") }
                    OutlinedButton(onClick = ::requestAccessibility) { Text(if (AssistantAccessibilityService.enabled) "无障碍已启用" else "启用无障碍") }
                }
                Button(onClick = vm::start, enabled = captureGranted && AssistantAccessibilityService.enabled) { Text("开始辅助") }
                OutlinedButton(onClick = { vm.pause() }) { Text("暂停") }
                OutlinedButton(onClick = vm::stop) { Text("停止并清理") }
                ui.pending?.let { pending -> AlertDialog(onDismissRequest = { vm.pause("用户取消了操作") }, title = { Text("确认操作") }, text = { Text("AI 请求：${pending.type}\n${pending.reason}") }, confirmButton = { TextButton(onClick = { vm.confirm(pending) }) { Text("确认") } }, dismissButton = { TextButton(onClick = { vm.pause("用户取消了操作") }) { Text("取消") } }) }
            }
        } }
    }
}
