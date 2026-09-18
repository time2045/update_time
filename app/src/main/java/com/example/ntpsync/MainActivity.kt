package com.example.ntpsync

import android.app.Application
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** 状态类型：决定状态卡片的颜色与文案 */
enum class StatusKind {
    NONE,
    SYNCING,
    SUCCESS,
    NO_PERMISSION,
    FAILED,
}

/** 界面状态：单页面所有展示数据 */
data class SyncUiState(
    val serverInput: String = SettingsStore.DEFAULT_SERVER,
    val syncing: Boolean = false,
    val ntpTime: Long? = null,
    val offsetMillis: Long? = null,
    val delayMillis: Long? = null,
    val statusKind: StatusKind = StatusKind.NONE,
    val statusText: String = "等待同步",
)

/**
 * 极简 ViewModel：不引入 Hilt 等框架，只负责“存档地址读写 + 触发同步 + 状态更新”。
 */
class SyncViewModel(app: Application) : AndroidViewModel(app) {

    private val settings = SettingsStore(app)
    private val manager = TimeSyncManager(app)

    private val _ui = MutableStateFlow(SyncUiState())
    val ui: StateFlow<SyncUiState> = _ui.asStateFlow()

    private var started = false

    fun onInput(value: String) {
        _ui.update { it.copy(serverInput = value) }
    }

    /** 界面首次展示时调用：读取上次保存的地址并自动同步一次 */
    fun start() {
        if (started) return
        started = true
        viewModelScope.launch {
            val saved = try {
                settings.serverFlow.first()
            } catch (e: Exception) {
                SettingsStore.DEFAULT_SERVER
            }
            _ui.update { it.copy(serverInput = saved) }
            doSync()
        }
    }

    /** 手动点击“同步时间” */
    fun sync() {
        if (_ui.value.syncing) return
        viewModelScope.launch { doSync() }
    }

    private suspend fun doSync() {
        val input = _ui.value.serverInput
        _ui.update {
            it.copy(syncing = true, statusKind = StatusKind.SYNCING, statusText = "正在同步...")
        }
        // 先保存用户输入的地址（保存失败不阻断同步）
        try {
            settings.saveServer(input)
        } catch (e: Exception) {
            // 忽略
        }

        val outcome = manager.sync(input)
        _ui.update { cur ->
            when (outcome) {
                is TimeSyncManager.SyncOutcome.Success -> cur.copy(
                    syncing = false,
                    ntpTime = outcome.ntp.serverTimeMillis,
                    offsetMillis = outcome.ntp.offsetMillis,
                    delayMillis = outcome.ntp.delayMillis,
                    statusKind = StatusKind.SUCCESS,
                    statusText = "✓ 同步成功",
                )
                is TimeSyncManager.SyncOutcome.NoPermission -> cur.copy(
                    syncing = false,
                    ntpTime = outcome.ntp.serverTimeMillis,
                    offsetMillis = outcome.ntp.offsetMillis,
                    delayMillis = outcome.ntp.delayMillis,
                    statusKind = StatusKind.NO_PERMISSION,
                    statusText = "⚠ 获取NTP时间成功，但没有修改系统时间的权限。\n${outcome.message}",
                )
                is TimeSyncManager.SyncOutcome.NtpFailed -> cur.copy(
                    syncing = false,
                    statusKind = StatusKind.FAILED,
                    statusText = "✕ 同步失败\n${outcome.message}\n\n请检查：\n" +
                        "1. Android 设备是否连接局域网\n" +
                        "2. NTP 服务器地址是否正确\n" +
                        "3. UDP 123 是否开放",
                )
                is TimeSyncManager.SyncOutcome.SyncFailed -> {
                    val base = cur.copy(
                        syncing = false,
                        statusKind = StatusKind.FAILED,
                        statusText = "✕ 同步失败\n${outcome.message}",
                    )
                    if (outcome.ntp != null) {
                        base.copy(
                            ntpTime = outcome.ntp.serverTimeMillis,
                            offsetMillis = outcome.ntp.offsetMillis,
                            delayMillis = outcome.ntp.delayMillis,
                        )
                    } else {
                        base
                    }
                }
            }
        }
    }
}

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MaterialTheme {
                val vm: SyncViewModel = viewModel()
                val ui by vm.ui.collectAsState()
                // 每次打开 App 自动执行一次同步
                LaunchedEffect(Unit) { vm.start() }
                SyncScreen(
                    state = ui,
                    onInput = vm::onInput,
                    onSync = vm::sync,
                )
            }
        }
    }
}

/** 主界面：标题 + 地址输入 + 同步按钮 + 时间/偏差/状态展示 */
@Composable
fun SyncScreen(
    state: SyncUiState,
    onInput: (String) -> Unit,
    onSync: () -> Unit,
) {
    // 本机时间每秒刷新
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(1000)
            now = System.currentTimeMillis()
        }
    }

    Scaffold(modifier = Modifier.fillMaxSize()) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(20.dp)
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = "局域网 NTP 校时",
                style = MaterialTheme.typography.headlineSmall,
            )

            OutlinedTextField(
                value = state.serverInput,
                onValueChange = onInput,
                label = { Text("NTP服务器") },
                placeholder = { Text(SettingsStore.DEFAULT_SERVER) },
                singleLine = true,
                enabled = !state.syncing,
                modifier = Modifier.fillMaxWidth(),
            )

            Button(
                onClick = onSync,
                enabled = !state.syncing,
            ) {
                Text(if (state.syncing) "正在同步..." else "同步时间")
            }

            InfoRow(label = "本机时间", value = TimeFormat.format(now))
            InfoRow(label = "NTP服务器时间", value = TimeFormat.format(state.ntpTime))
            InfoRow(label = "时间偏差", value = TimeFormat.formatOffset(state.offsetMillis))
            InfoRow(label = "网络延迟", value = TimeFormat.formatDelay(state.delayMillis))

            StatusCard(state = state)
        }
    }
}

/** 标签 + 数值两行展示 */
@Composable
fun InfoRow(label: String, value: String) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyLarge,
        )
    }
}

/** 同步结果卡片：按状态类型着色 */
@Composable
fun StatusCard(state: SyncUiState) {
    val colors = MaterialTheme.colorScheme
    val container = when (state.statusKind) {
        StatusKind.SUCCESS -> colors.primaryContainer
        StatusKind.NO_PERMISSION -> colors.tertiaryContainer
        StatusKind.FAILED -> colors.errorContainer
        else -> colors.surfaceVariant
    }
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = container),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "状态",
                style = MaterialTheme.typography.labelMedium,
            )
            Text(
                text = state.statusText,
                style = MaterialTheme.typography.bodyLarge,
                textAlign = TextAlign.Start,
            )
        }
    }
}
