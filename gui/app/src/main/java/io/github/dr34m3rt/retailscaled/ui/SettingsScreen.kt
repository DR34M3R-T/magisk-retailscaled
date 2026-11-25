package io.github.dr34m3rt.retailscaled.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.Button
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import io.github.dr34m3rt.retailscaled.core.AppSettings
import io.github.dr34m3rt.retailscaled.core.PowerSaveMode
import io.github.dr34m3rt.retailscaled.core.TailscaleLoadState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    uiState: MainUiState,
    onSaveSettings: (AppSettings) -> Unit,
    onSelectExitNode: (String) -> Unit,
    onOpenWebUi: () -> Unit,
    onBack: () -> Unit
) {
    val current = uiState.settings
    var acceptDnsFalse by rememberSaveable(current.acceptDnsFalse) { mutableStateOf(current.acceptDnsFalse) }
    var socks5Address by rememberSaveable(current.socks5Address) { mutableStateOf(current.socks5Address) }
    var powerSaveModeName by rememberSaveable(current.powerSaveMode) { mutableStateOf(current.powerSaveMode.name) }
    var webUiPortText by rememberSaveable(current.webUiPort) { mutableStateOf(current.webUiPort.toString()) }

    val status = (uiState.loadState as? TailscaleLoadState.Ready)?.status
    val exitNodes = status?.exitNodeCandidates.orEmpty()
    val activeExitNodeIp = exitNodes.firstOrNull { it.exitNode }?.ipv4.orEmpty()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("进阶设置") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                }
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // 1. Official WebUI Fallback
            ElevatedCard(
                colors = CardDefaults.elevatedCardColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f)
                ),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "官方 Web 控制台（兜底入口）",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = "临时启动 tailscale web --listen 127.0.0.1:${current.webUiPort} 并在内置 WebView 中打开，退出页面时自动结束进程。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(10.dp))
                    FilledTonalButton(onClick = onOpenWebUi) {
                        Icon(Icons.Default.Language, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("打开官方 Web 控制台")
                    }
                }
            }

            // 2. Exit Node Selector
            ElevatedCard(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "Exit Node 选择器",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = "从当前 Tailnet 中筛选支持 Exit Node 的设备，点击即可切换。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(8.dp))

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(enabled = !uiState.isUpdatingExitNode) {
                                onSelectExitNode("")
                            }
                            .padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = activeExitNodeIp.isBlank(),
                            onClick = { onSelectExitNode("") },
                            enabled = !uiState.isUpdatingExitNode
                        )
                        Spacer(Modifier.width(8.dp))
                        Text("不使用 Exit Node（关闭）")
                    }

                    if (exitNodes.isEmpty()) {
                        Text(
                            text = "当前没有可用的 Exit Node 节点",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(start = 12.dp, top = 4.dp)
                        )
                    } else {
                        exitNodes.forEach { node ->
                            val ip = node.ipv4
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable(enabled = !uiState.isUpdatingExitNode && ip.isNotBlank()) {
                                        onSelectExitNode(ip)
                                    }
                                    .padding(vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                RadioButton(
                                    selected = node.exitNode,
                                    onClick = { if (ip.isNotBlank()) onSelectExitNode(ip) },
                                    enabled = !uiState.isUpdatingExitNode
                                )
                                Spacer(Modifier.width(8.dp))
                                Column {
                                    Text(
                                        text = "${node.displayName} ${if (node.online) "(在线)" else "(离线)"}",
                                        style = MaterialTheme.typography.bodyMedium
                                    )
                                    Text(
                                        text = ip,
                                        style = MaterialTheme.typography.bodySmall,
                                        fontFamily = FontFamily.Monospace,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // 3. Tile Power Save Mode
            ElevatedCard(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "磁贴与开关省电模式",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(Modifier.height(8.dp))

                    val selectedMode = runCatching { PowerSaveMode.valueOf(powerSaveModeName) }
                        .getOrDefault(PowerSaveMode.LIGHT)

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { powerSaveModeName = PowerSaveMode.LIGHT.name }
                            .padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = selectedMode == PowerSaveMode.LIGHT,
                            onClick = { powerSaveModeName = PowerSaveMode.LIGHT.name }
                        )
                        Spacer(Modifier.width(8.dp))
                        Column {
                            Text("轻量模式（默认）", fontWeight = FontWeight.Medium)
                            Text(
                                text = "关闭时执行 tailscale down，开启时执行 tailscale up，切换迅速。",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { powerSaveModeName = PowerSaveMode.DEEP.name }
                            .padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = selectedMode == PowerSaveMode.DEEP,
                            onClick = { powerSaveModeName = PowerSaveMode.DEEP.name }
                        )
                        Spacer(Modifier.width(8.dp))
                        Column {
                            Text("极致省电模式", fontWeight = FontWeight.Medium)
                            Text(
                                text = "关闭时直接停止 tailscaled 守护进程；开启时拉起 tailscaled 再执行 up。",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }

            // 4. Default Parameters
            ElevatedCard(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        text = "默认参数",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("启用 --accept-dns=false", fontWeight = FontWeight.Medium)
                            Text(
                                text = "默认开启，防止与 FlClash 等代理客户端的 DNS 冲突",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = acceptDnsFalse,
                            onCheckedChange = { acceptDnsFalse = it }
                        )
                    }

                    HorizontalDivider()

                    OutlinedTextField(
                        value = socks5Address,
                        onValueChange = { socks5Address = it },
                        label = { Text("本地 SOCKS5 监听地址") },
                        placeholder = { Text("127.0.0.1:1055") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )

                    OutlinedTextField(
                        value = webUiPortText,
                        onValueChange = { webUiPortText = it.filter(Char::isDigit) },
                        label = { Text("WebUI 本地端口") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )

                    Button(
                        onClick = {
                            val parsedPort = webUiPortText.toIntOrNull()?.coerceIn(1024, 65535) ?: 8088
                            val parsedMode = runCatching { PowerSaveMode.valueOf(powerSaveModeName) }
                                .getOrDefault(PowerSaveMode.LIGHT)
                            onSaveSettings(
                                AppSettings(
                                    acceptDnsFalse = acceptDnsFalse,
                                    socks5Address = socks5Address.trim().ifBlank { "127.0.0.1:1055" },
                                    powerSaveMode = parsedMode,
                                    webUiPort = parsedPort
                                )
                            )
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.Save, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("保存配置")
                    }
                }
            }
        }
    }
}
