package com.example.ntpsync

import android.app.Application
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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

/** 服务器列表中的一项：lastOk 为空表示尚未尝试过 */
data class ServerUiItem(
    val address: String,
    val lastOk: Boolean? = null,
    val lastError: String = "",
)

/** 界面状态：单页面所有展示数据 */
data class SyncUiState(
    val servers: List<ServerUiItem> = emptyList(),
    val newInput: String = "",
    val inputError: String? = null,
    val syncing: Boolean = false,
    val activeServer: String? = null,
    val ntpTime: Long? = null,
    val offsetMillis: Long? = null,
    val delayMillis: Long? = null,
    val statusKind: StatusKind = StatusKind.NONE,
    val statusText: String = "添加服务器后点同步",
    /** 当前系统 NTP 服务器（null = 尚未读取） */
    val systemNtp: String? = null,
    /** 是否已获得一次性 ADB 授权（null = 尚未检测） */
    val hasSecureSettings: Boolean? = null,
)

/**
 * 极简 ViewModel：不引入 Hilt 等框架，只负责“列表维护 + 存档 + 触发同步 + 状态更新”。
 */
class SyncViewModel(app: Application) : AndroidViewModel(app) {

    private val settings = SettingsStore(app)
    private val manager = TimeSyncManager(app)
    private val switcher = SystemNtpSwitcher(app)
    private val addressValidator = NtpClient()

    private val _ui = MutableStateFlow(SyncUiState())
    val ui: StateFlow<SyncUiState> = _ui.asStateFlow()

    private var started = false

    fun onNewInput(value: String) {
        _ui.update { it.copy(newInput = value, inputError = null) }
    }

    /** 界面首次展示时调用：读取存档列表并自动同步一次 */
    fun start() {
        if (started) return
        started = true
        viewModelScope.launch {
            val saved = try {
                settings.serversFlow.first()
            } catch (e: Exception) {
                SettingsStore.DEFAULT_SERVERS
            }
            _ui.update {
                it.copy(servers = saved.map { addr -> ServerUiItem(addr) })
            }
            refreshSystemNtp()
            doSync()
        }
    }

    /** 读取系统 NTP 当前值 + 授权状态（供“一键切换”卡片展示） */
    fun refreshSystemNtp() {
        viewModelScope.launch {
            val granted = try {
                switcher.hasPermission()
            } catch (e: Exception) {
                false
            }
            val current = if (granted) {
                try {
                    switcher.getSystemServer()
                } catch (e: Exception) {
                    null
                }
            } else {
                null
            }
            _ui.update { it.copy(hasSecureSettings = granted, systemNtp = current) }
        }
    }

    /**
     * 把系统 NTP 切换到指定服务器（取 host 部分，系统 NTP 固定 UDP 123）。
     * 只保证“设置已写入”，不谎报时间已同步。
     */
    fun applySystemNtp(address: String) {
        if (_ui.value.syncing) return
        viewModelScope.launch {
            val host = try {
                addressValidator.parseServerAddress(address).host
            } catch (e: IllegalArgumentException) {
                _ui.update {
                    it.copy(
                        statusKind = StatusKind.FAILED,
                        statusText = "✕ 地址无效：${e.message}",
                    )
                }
                return@launch
            }
            _ui.update {
                it.copy(statusKind = StatusKind.SYNCING, statusText = "正在切换系统 NTP...")
            }
            val result = switcher.apply(host)
            refreshSystemNtp()
            _ui.update { cur ->
                result.fold(
                    onSuccess = {
                        cur.copy(
                            statusKind = StatusKind.SUCCESS,
                            statusText = "✓ 系统 NTP 已切换为 $host\n" +
                                "系统正在后台同步（通常 1 分钟内生效），请稍后查看本机时间。",
                        )
                    },
                    onFailure = { e ->
                        cur.copy(
                            statusKind = StatusKind.FAILED,
                            statusText = "✕ ${e.message}",
                        )
                    },
                )
            }
        }
    }

    /** 添加服务器（格式校验，不允许重复） */
    fun addServer() {
        val input = _ui.value.newInput.trim()
        if (input.isEmpty()) {
            _ui.update { it.copy(inputError = "请输入服务器地址") }
            return
        }
        try {
            addressValidator.parseServerAddress(input)
        } catch (e: IllegalArgumentException) {
            _ui.update { it.copy(inputError = "地址无效：${e.message}") }
            return
        }
        if (_ui.value.servers.any { it.address == input }) {
            _ui.update { it.copy(inputError = "该服务器已在列表中") }
            return
        }
        viewModelScope.launch {
            val next = _ui.value.servers + ServerUiItem(input)
            persist(next)
            _ui.update { it.copy(servers = next, newInput = "", inputError = null) }
        }
    }

    /** 删除服务器 */
    fun removeServer(index: Int) {
        viewModelScope.launch {
            val next = _ui.value.servers.toMutableList()
            if (index in next.indices) {
                next.removeAt(index)
                persist(next)
                _ui.update { it.copy(servers = next) }
            }
        }
    }

    /** 上移 / 下移：顺序即同步优先级 */
    fun moveServer(index: Int, delta: Int) {
        viewModelScope.launch {
            val next = _ui.value.servers.toMutableList()
            val target = index + delta
            if (index in next.indices && target in next.indices) {
                val item = next.removeAt(index)
                next.add(target, item)
                persist(next)
                _ui.update { it.copy(servers = next) }
            }
        }
    }

    /** 手动点击“同步时间” */
    fun sync() {
        if (_ui.value.syncing) return
        viewModelScope.launch { doSync() }
    }

    private suspend fun persist(servers: List<ServerUiItem>) {
        try {
            settings.saveServers(servers.map { it.address })
        } catch (e: Exception) {
            // 存档失败不阻断操作
        }
    }

    private suspend fun doSync() {
        val servers = _ui.value.servers
        _ui.update {
            it.copy(syncing = true, statusKind = StatusKind.SYNCING, statusText = "正在同步...")
        }
        persist(servers)

        val outcome = manager.syncInOrder(servers.map { it.address })

        _ui.update { cur ->
            // 先把逐台尝试结果回填到列表行
            val attempts = when (outcome) {
                is TimeSyncManager.SyncOutcome.EmptyServers -> emptyList()
                is TimeSyncManager.SyncOutcome.AllNtpFailed -> outcome.attempts
                is TimeSyncManager.SyncOutcome.NoPermission -> outcome.attempts
                is TimeSyncManager.SyncOutcome.Success -> outcome.attempts
                is TimeSyncManager.SyncOutcome.SyncFailed -> outcome.attempts
            }
            val marked = cur.servers.map { item ->
                val hit = attempts.lastOrNull { it.server == item.address }
                if (hit == null) {
                    item.copy(lastOk = null, lastError = "")
                } else {
                    item.copy(lastOk = hit.ntp != null, lastError = hit.error ?: "")
                }
            }
            val base = cur.copy(servers = marked, syncing = false)
            when (outcome) {
                is TimeSyncManager.SyncOutcome.EmptyServers -> base.copy(
                    statusKind = StatusKind.FAILED,
                    statusText = "✕ ${outcome.message}",
                )
                is TimeSyncManager.SyncOutcome.AllNtpFailed -> base.copy(
                    statusKind = StatusKind.FAILED,
                    statusText = "✕ 所有服务器均同步失败\n" +
                        outcome.attempts.joinToString("\n") { "• ${it.server}：${it.error}" } +
                        "\n\n请检查：\n" +
                        "1. 设备是否接入对应网络\n" +
                        "2. 服务器地址是否正确\n" +
                        "3. UDP 123 是否开放",
                )
                is TimeSyncManager.SyncOutcome.NoPermission -> base.copy(
                    activeServer = outcome.server,
                    ntpTime = outcome.ntp.serverTimeMillis,
                    offsetMillis = outcome.ntp.offsetMillis,
                    delayMillis = outcome.ntp.delayMillis,
                    statusKind = StatusKind.NO_PERMISSION,
                    statusText = "⚠ ${outcome.server} 获取NTP时间成功，" +
                        "但没有修改系统时间的权限。\n${outcome.message}",
                )
                is TimeSyncManager.SyncOutcome.Success -> base.copy(
                    activeServer = outcome.server,
                    ntpTime = outcome.ntp.serverTimeMillis,
                    offsetMillis = outcome.ntp.offsetMillis,
                    delayMillis = outcome.ntp.delayMillis,
                    statusKind = StatusKind.SUCCESS,
                    statusText = "✓ 同步成功（${outcome.server}）",
                )
                is TimeSyncManager.SyncOutcome.SyncFailed -> {
                    val withNtp = if (outcome.ntp != null) {
                        base.copy(
                            ntpTime = outcome.ntp.serverTimeMillis,
                            offsetMillis = outcome.ntp.offsetMillis,
                            delayMillis = outcome.ntp.delayMillis,
                        )
                    } else {
                        base
                    }
                    withNtp.copy(
                        activeServer = outcome.server,
                        statusKind = StatusKind.FAILED,
                        statusText = "✕ 同步失败（${outcome.server}）\n${outcome.message}",
                    )
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
                    onNewInput = vm::onNewInput,
                    onAdd = vm::addServer,
                    onRemove = vm::removeServer,
                    onMove = vm::moveServer,
                    onSync = vm::sync,
                    onApplySystem = vm::applySystemNtp,
                    onRefreshSystem = vm::refreshSystemNtp,
                )
            }
        }
    }
}

/** 主界面 */
@Composable
fun SyncScreen(
    state: SyncUiState,
    onNewInput: (String) -> Unit,
    onAdd: () -> Unit,
    onRemove: (Int) -> Unit,
    onMove: (Int, Int) -> Unit,
    onSync: () -> Unit,
    onApplySystem: (String) -> Unit,
    onRefreshSystem: () -> Unit,
) {
    // 本机时间每秒刷新
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(1000)
            now = System.currentTimeMillis()
        }
    }
    // 技术员功能默认收起，普通用户只看三步向导
    var showAdvanced by remember { mutableStateOf(false) }

    Scaffold(modifier = Modifier.fillMaxSize()) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 20.dp, vertical = 16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Header()

            Button(
                onClick = onSync,
                enabled = !state.syncing,
                modifier = Modifier.fillMaxWidth(),
            ) {
                if (state.syncing) {
                    CircularProgressIndicator(
                        modifier = Modifier
                            .padding(end = 8.dp)
                            .size(18.dp),
                        strokeWidth = 2.dp,
                    )
                    Text("正在同步...")
                } else {
                    Text("获取准确时间", fontSize = 16.sp)
                }
            }

            TimeCards(now = now, state = state)

            OffsetHeroCard(state = state)

            // 普通用户三步：看准时间 → 跳系统设置 → 照着填
            GuideCard(ntpTime = state.ntpTime)

            StatusCard(state = state)

            // 技术员功能默认收起：多服务器管理、系统 NTP 一键切换
            TextButton(
                onClick = { showAdvanced = !showAdvanced },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(if (showAdvanced) "收起高级设置 ↑" else "高级设置（多服务器 / 一键切换） ↓")
            }
            if (showAdvanced) {
                ServerListCard(
                    state = state,
                    onNewInput = onNewInput,
                    onAdd = onAdd,
                    onRemove = onRemove,
                    onMove = onMove,
                    onApplySystem = onApplySystem,
                )

            SystemNtpCard(
                state = state,
                onRefresh = onRefreshSystem,
            )
            }
        }
    }
}

/** 顶部标题区 */
@Composable
fun Header() {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = "◷",
            fontSize = 40.sp,
            color = MaterialTheme.colorScheme.primary,
        )
        Column {
            Text(
                text = "NTP 校时",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = "多服务器按序优选 · 局域网 / 公网",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** 普通用户三步向导：普通手机上 App 改不了系统时间，照着填最靠谱 */
@Composable
fun GuideCard(ntpTime: Long?) {
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
    ElevatedCard(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = "3 步对好时间（不用电脑）",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = "1. 记住下面的准确时间\n" +
                    "2. 点按钮跳到系统设置，关掉「自动确定日期和时间」\n" +
                    "3. 照着准确时间手动填进去",
                style = MaterialTheme.typography.bodyMedium,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledTonalButton(
                    onClick = {
                        val text = TimeFormat.format(ntpTime)
                        clipboard.setText(AnnotatedString(text))
                        Toast.makeText(context, "准确时间已复制：$text", Toast.LENGTH_SHORT).show()
                    },
                    enabled = ntpTime != null,
                ) {
                    Text("复制准确时间")
                }
                Button(
                    onClick = {
                        try {
                            context.startActivity(
                                Intent(Settings.ACTION_DATE_SETTINGS)
                                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                            )
                        } catch (e: Exception) {
                            Toast.makeText(context, "打不开系统设置，请手动进入", Toast.LENGTH_SHORT)
                                .show()
                        }
                    },
                ) {
                    Text("去系统设置改时间")
                }
            }
        }
    }
}

/** 服务器列表卡片：增 / 删 / 排序，顺序即同步优先级 */
@Composable
fun ServerListCard(
    state: SyncUiState,
    onNewInput: (String) -> Unit,
    onAdd: () -> Unit,
    onRemove: (Int) -> Unit,
    onMove: (Int, Int) -> Unit,
    onApplySystem: (String) -> Unit,
) {
    ElevatedCard(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = "NTP 服务器（按顺序尝试）",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
            )
            if (state.servers.isEmpty()) {
                Text(
                    text = "暂无服务器，请在下方添加",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            state.servers.forEachIndexed { index, item ->
                ServerRow(
                    index = index,
                    item = item,
                    isActive = item.address == state.activeServer,
                    canApplySystem = state.hasSecureSettings == true,
                    syncing = state.syncing,
                    onRemove = { onRemove(index) },
                    onMoveUp = { onMove(index, -1) },
                    onMoveDown = { onMove(index, 1) },
                    onApplySystem = { onApplySystem(item.address) },
                )
                if (index < state.servers.lastIndex) {
                    HorizontalDivider()
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedTextField(
                    value = state.newInput,
                    onValueChange = onNewInput,
                    label = { Text("添加服务器") },
                    placeholder = { Text(SettingsStore.DEFAULT_SERVER) },
                    singleLine = true,
                    enabled = !state.syncing,
                    isError = state.inputError != null,
                    supportingText = state.inputError?.let { { Text(it) } },
                    modifier = Modifier.weight(1f),
                )
                FilledTonalButton(
                    onClick = onAdd,
                    enabled = !state.syncing,
                ) {
                    Text("添加")
                }
            }
        }
    }
}

/** 服务器列表中的一行：序号 + 地址 + 状态 + 设为系统/排序/删除 */
@Composable
fun ServerRow(
    index: Int,
    item: ServerUiItem,
    isActive: Boolean,
    canApplySystem: Boolean,
    syncing: Boolean,
    onRemove: () -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onApplySystem: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "${index + 1}",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(end = 8.dp),
        )
        Column(modifier = Modifier.weight(1f)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    text = statusGlyph(item.lastOk),
                    fontSize = 14.sp,
                    color = statusColor(item.lastOk),
                )
                Text(
                    text = item.address,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = if (isActive) FontWeight.Bold else FontWeight.Normal,
                )
            }
            if (isActive) {
                Text(
                    text = "本次使用",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            } else if (item.lastOk == false && item.lastError.isNotEmpty()) {
                Text(
                    text = item.lastError,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
        TextButton(onClick = onMoveUp, enabled = !syncing) { Text("↑") }
        TextButton(onClick = onMoveDown, enabled = !syncing) { Text("↓") }
        TextButton(onClick = onRemove, enabled = !syncing) { Text("删除") }
        if (canApplySystem) {
            FilledTonalButton(onClick = onApplySystem, enabled = !syncing) { Text("设为系统") }
        }
    }
}

/** 尝试状态图标：✓ 成功 / ✕ 失败 / • 未尝试 */
@Composable
fun statusGlyph(lastOk: Boolean?): String = when (lastOk) {
    true -> "✓"
    false -> "✕"
    null -> "•"
}

@Composable
fun statusColor(lastOk: Boolean?): Color {
    val colors = MaterialTheme.colorScheme
    return when (lastOk) {
        true -> colors.primary
        false -> colors.error
        null -> colors.onSurfaceVariant
    }
}

/** 系统 NTP 一键切换卡片：显示系统当前值 + 授权状态 + 设置向导 */
@Composable
fun SystemNtpCard(
    state: SyncUiState,
    onRefresh: () -> Unit,
) {
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
    val granted = state.hasSecureSettings == true

    ElevatedCard(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "系统 NTP（一键切换）",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = onRefresh) { Text("刷新") }
            }
            Text(
                text = if (granted) {
                    "当前系统：${state.systemNtp ?: "未知"}\n" +
                        "点服务器右侧「设为系统」，1 分钟内生效，在局域网/公网间随便切。"
                } else {
                    "未授权，无法切换。只需用电脑执行一次下面命令（无需恢复出厂、不删账号）："
                },
                style = MaterialTheme.typography.bodyMedium,
            )
            if (!granted) {
                Text(
                    text = SystemNtpSwitcher.GRANT_CMD,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilledTonalButton(
                        onClick = {
                            clipboard.setText(AnnotatedString(SystemNtpSwitcher.GRANT_CMD))
                            Toast.makeText(context, "授权命令已复制", Toast.LENGTH_SHORT).show()
                        },
                    ) {
                        Text("复制命令")
                    }
                }
                Text(
                    text = "执行成功后点右上角「刷新」，再点「设为系统」即可。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** 本机时间 / NTP 时间双卡片 */
@Composable
fun TimeCards(now: Long, state: SyncUiState) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        ElevatedCard(modifier = Modifier.weight(1f)) {
            Column(modifier = Modifier.padding(12.dp)) {
                Text(
                    text = "本机时间",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = TimeFormat.format(now),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                )
            }
        }
        ElevatedCard(modifier = Modifier.weight(1f)) {
            Column(modifier = Modifier.padding(12.dp)) {
                Text(
                    text = "NTP 时间",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = TimeFormat.format(state.ntpTime),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                )
            }
        }
    }
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        ElevatedCard(modifier = Modifier.weight(1f)) {
            Column(modifier = Modifier.padding(12.dp)) {
                Text(
                    text = "网络延迟",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = TimeFormat.formatDelay(state.delayMillis),
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                )
            }
        }
        ElevatedCard(modifier = Modifier.weight(1f)) {
            Column(modifier = Modifier.padding(12.dp)) {
                Text(
                    text = "授时服务",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = state.activeServer ?: "--",
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                )
            }
        }
    }
}

/** 时间偏差大字展示：核心数据一眼可见 */
@Composable
fun OffsetHeroCard(state: SyncUiState) {
    ElevatedCard(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = "时间偏差",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = TimeFormat.formatOffset(state.offsetMillis),
                fontSize = 40.sp,
                fontWeight = FontWeight.Bold,
                color = if (state.offsetMillis == null) {
                    MaterialTheme.colorScheme.onSurfaceVariant
                } else {
                    MaterialTheme.colorScheme.primary
                },
            )
        }
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
    val onContainer = when (state.statusKind) {
        StatusKind.SUCCESS -> colors.onPrimaryContainer
        StatusKind.NO_PERMISSION -> colors.onTertiaryContainer
        StatusKind.FAILED -> colors.onErrorContainer
        else -> colors.onSurfaceVariant
    }
    ElevatedCard(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.elevatedCardColors(containerColor = container),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = "状态",
                style = MaterialTheme.typography.labelMedium,
                color = onContainer,
            )
            Text(
                text = state.statusText,
                style = MaterialTheme.typography.bodyLarge,
                textAlign = TextAlign.Start,
                color = onContainer,
            )
        }
    }
}
