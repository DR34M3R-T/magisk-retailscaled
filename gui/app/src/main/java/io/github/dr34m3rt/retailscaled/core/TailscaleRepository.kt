package io.github.dr34m3rt.retailscaled.core

import android.content.Context
import android.os.Build
import kotlinx.serialization.json.Json

class TailscaleRepository(
    context: Context,
    private val settingsStore: SettingsStore
) {
    private val bridgeClient = BridgeClient(context)

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
    }

    private val urlRegex = Regex("""https?://\S+""")
    private val pingDirectRegex = Regex("""via\s+([^(\s]+)\s+in\s+([0-9.]+(?:ms|µs|us|s))""", RegexOption.IGNORE_CASE)
    private val pingDerpRegex = Regex("""via\s+DERP\(([^)]+)\)\s+in\s+([0-9.]+(?:ms|µs|us|s))""", RegexOption.IGNORE_CASE)

    fun currentSettings(): AppSettings = settingsStore.load()

    fun saveSettings(settings: AppSettings) = settingsStore.save(settings)

    private fun defaultDeviceHostname(): String {
        val brand = Build.BRAND.orEmpty().trim().lowercase()
        val model = Build.MODEL.orEmpty().trim().lowercase()
        val raw = when {
            model.isBlank() && brand.isBlank() -> "android-device"
            brand.isBlank() || model.startsWith(brand) -> model
            else -> "$brand-$model"
        }
        return raw.replace(Regex("[^a-z0-9-]+"), "-").trim('-').ifBlank { "android-device" }
    }

    suspend fun readBridgeHealth(): BridgeHealthResponse? {
        return bridgeClient.health()
    }

    suspend fun readModuleVersion(): String? {
        return bridgeClient.health()?.moduleVersion?.takeIf { it.isNotBlank() }
    }

    suspend fun setVpnBypass(enabled: Boolean): ShellResult {
        val res = bridgeClient.setVpnBypass(enabled)
            ?: return ShellResult(-1, "", "无法连接 REtailscaled 模块服务")
        return if (res.ok) {
            val msg = when {
                enabled && res.restarted -> "已开启绕过系统 VPN，tailscaled 已重启生效"
                !enabled && res.restarted -> "已关闭绕过系统 VPN，tailscaled 已重启生效"
                enabled -> "已开启绕过系统 VPN，将在下次启动时生效"
                else -> "已关闭绕过系统 VPN，将在下次启动时生效"
            }
            ShellResult(0, msg, "")
        } else {
            ShellResult(1, res.output, res.error.ifBlank { "更新绕过系统 VPN 配置失败" })
        }
    }

    suspend fun fetchStatus(): TailscaleLoadState {
        if (bridgeClient.health() == null) {
            return TailscaleLoadState.BridgeUnavailable
        }

        val raw = bridgeClient.fetchStatusRaw() ?: return TailscaleLoadState.BridgeUnavailable
        val (code, body) = raw
        val trimmed = body.trim()

        if (code == 200 && trimmed.startsWith("{")) {
            return try {
                TailscaleLoadState.Ready(json.decodeFromString<TailscaleStatus>(trimmed))
            } catch (e: Exception) {
                TailscaleLoadState.Error("解析 status JSON 失败: ${e.message}")
            }
        }
        if (code == 503 || body.contains("daemonStopped", ignoreCase = true)) {
            return TailscaleLoadState.DaemonStopped("tailscaled 守护进程未运行")
        }
        return TailscaleLoadState.Error(body.ifBlank { "模块控制桥请求失败 (HTTP $code)" })
    }

    suspend fun turnOn(): ShellResult {
        val settings = currentSettings()
        return bridgeClient.turnOn(
            acceptDnsFalse = settings.acceptDnsFalse,
            hostname = defaultDeviceHostname(),
            socks5Address = settings.socks5Address
        ) ?: ShellResult(-1, "", "无法连接 REtailscaled 模块服务")
    }

    suspend fun turnOff(): ShellResult {
        val settings = currentSettings()
        return bridgeClient.turnOff(
            deepPowerSave = settings.powerSaveMode == PowerSaveMode.DEEP
        ) ?: ShellResult(-1, "", "无法连接 REtailscaled 模块服务")
    }

    suspend fun startBrowserLogin(onAuthUrlFound: suspend (String) -> Unit): ShellResult {
        val settings = currentSettings()
        var foundUrl = false
        return bridgeClient.streamBrowserLogin(
            acceptDnsFalse = settings.acceptDnsFalse,
            hostname = defaultDeviceHostname(),
            socks5Address = settings.socks5Address
        ) { line ->
            if (!foundUrl) {
                val match = urlRegex.find(line)
                if (match != null) {
                    foundUrl = true
                    onAuthUrlFound(match.value.trimEnd('.', ',', ')', ']'))
                }
            }
        } ?: ShellResult(-1, "", "无法连接 REtailscaled 模块服务")
    }

    suspend fun loginWithAuthKey(authKey: String, loginServer: String): ShellResult {
        val settings = currentSettings()
        return bridgeClient.loginWithAuthKey(
            authKey = authKey,
            loginServer = loginServer,
            acceptDnsFalse = settings.acceptDnsFalse,
            hostname = defaultDeviceHostname()
        ) ?: ShellResult(-1, "", "无法连接 REtailscaled 模块服务")
    }

    suspend fun pingPeer(
        ip: String,
        count: Int = 3,
        onEntry: suspend (PingEntry) -> Unit
    ): ShellResult {
        return bridgeClient.streamPing(ip, count) { line ->
            if (line.isNotBlank()) onEntry(parsePingLine(line))
        } ?: ShellResult(-1, "", "无法连接 REtailscaled 模块服务")
    }

    private fun parsePingLine(line: String): PingEntry {
        val derpMatch = pingDerpRegex.find(line)
        if (derpMatch != null) {
            return PingEntry(
                rawLine = line,
                latencyMs = derpMatch.groupValues[2],
                isDirect = false,
                via = "DERP(${derpMatch.groupValues[1]})"
            )
        }
        val directMatch = pingDirectRegex.find(line)
        if (directMatch != null) {
            return PingEntry(
                rawLine = line,
                latencyMs = directMatch.groupValues[2],
                isDirect = true,
                via = directMatch.groupValues[1]
            )
        }
        return PingEntry(rawLine = line)
    }

    suspend fun setExitNode(exitNodeIpOrEmpty: String): ShellResult {
        return bridgeClient.setExitNode(exitNodeIpOrEmpty)
            ?: ShellResult(-1, "", "无法连接 REtailscaled 模块服务")
    }

    suspend fun startWebUi(): String? {
        return bridgeClient.startWebUi(currentSettings().webUiPort)
    }

    suspend fun stopWebUi() {
        bridgeClient.stopWebUi()
    }
}
