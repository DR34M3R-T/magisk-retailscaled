package com.example.roottailscale.core

import android.os.Build
import kotlinx.coroutines.delay
import kotlinx.serialization.json.Json

class TailscaleRepository(
    private val settingsStore: SettingsStore
) {
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

    private fun tailscaleCmd(settings: AppSettings, vararg args: String): String {
        val parts = mutableListOf(RootShell.shellQuote(settings.tailscalePath))
        if (settings.socketPath.isNotBlank()) {
            parts += "--socket=${RootShell.shellQuote(settings.socketPath)}"
        }
        parts.addAll(args)
        return parts.joinToString(" ")
    }

    suspend fun checkRoot(): Boolean = RootShell.checkRoot()

    suspend fun readModuleVersion(): String? {
        val res = RootShell.exec(
            command = "grep '^version=' /data/adb/modules/magisk-retailscaled/module.prop /data/adb/modules/magisk-tailscaled/module.prop 2>/dev/null | head -n 1 | cut -d'=' -f2",
            timeoutMs = 3_000L
        )
        return res.stdout.trim().takeIf { res.isSuccess && it.isNotBlank() }
    }

    suspend fun fetchStatus(): TailscaleLoadState {
        if (!RootShell.checkRoot()) {
            return TailscaleLoadState.NoRoot
        }

        val settings = currentSettings()
        val result = RootShell.exec(
            command = tailscaleCmd(settings, "status", "--json"),
            timeoutMs = 10_000L
        )

        val trimmedOut = result.stdout.trim()
        if (trimmedOut.startsWith("{")) {
            return try {
                val parsed = json.decodeFromString<TailscaleStatus>(trimmedOut)
                TailscaleLoadState.Ready(parsed)
            } catch (e: Exception) {
                TailscaleLoadState.Error("解析 status --json 失败: ${e.message}")
            }
        }

        val err = result.combinedOutput
        val looksStopped = err.contains("failed to connect to local tailscaled", ignoreCase = true) ||
            err.contains("connect: no such file", ignoreCase = true) ||
            err.contains("connection refused", ignoreCase = true) ||
            err.contains("is tailscaled running", ignoreCase = true)

        return if (looksStopped) {
            TailscaleLoadState.DaemonStopped(err.ifBlank { "tailscaled 守护进程未运行" })
        } else {
            TailscaleLoadState.Error(
                err.ifBlank { "执行 tailscale status 失败 (exit=${result.exitCode})" }
            )
        }
    }

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

    suspend fun turnOn(): ShellResult {
        val settings = currentSettings()
        if (settings.powerSaveMode == PowerSaveMode.DEEP) {
            ensureDaemonRunning(settings)
        } else {
            // Even in light mode, if daemon isn't running yet, try starting it seamlessly
            val runningCheck = RootShell.exec("pidof tailscaled || pgrep -f tailscaled", timeoutMs = 3_000L)
            if (!runningCheck.isSuccess || runningCheck.stdout.isBlank()) {
                ensureDaemonRunning(settings)
            }
        }

        val args = mutableListOf("up", "--hostname=${RootShell.shellQuote(defaultDeviceHostname())}")
        if (settings.acceptDnsFalse) {
            args += "--accept-dns=false"
        }
        return RootShell.exec(
            command = tailscaleCmd(settings, *args.toTypedArray()),
            timeoutMs = 20_000L
        )
    }

    suspend fun turnOff(): ShellResult {
        val settings = currentSettings()
        val downResult = RootShell.exec(
            command = tailscaleCmd(settings, "down"),
            timeoutMs = 15_000L
        )

        if (settings.powerSaveMode == PowerSaveMode.DEEP) {
            val stopCmd = if (settings.tailscaledPath == "tailscaled") {
                "if [ -x /data/adb/tailscale/scripts/tailscaled.service ]; then /data/adb/tailscale/scripts/tailscaled.service stop; else pkill -f tailscaled || killall tailscaled || true; fi"
            } else {
                "pkill -f tailscaled || killall tailscaled || true"
            }
            val stopDaemon = RootShell.exec(
                command = stopCmd,
                timeoutMs = 10_000L
            )
            return if (downResult.isSuccess || stopDaemon.isSuccess) {
                ShellResult(0, "已停止 Tailscale 及 tailscaled 进程", "")
            } else {
                stopDaemon
            }
        }
        return downResult
    }

    private suspend fun ensureDaemonRunning(settings: AppSettings) {
        val check = RootShell.exec("pidof tailscaled || pgrep -f tailscaled", timeoutMs = 3_000L)
        if (check.isSuccess && check.stdout.isNotBlank()) return

        if (settings.tailscaledPath == "tailscaled" && settings.socketPath.isBlank()) {
            val svcCheck = RootShell.exec(
                command = "if [ -x /data/adb/tailscale/scripts/tailscaled.service ]; then TS_SOCKS5_ADDR=${RootShell.shellQuote(settings.socks5Address)} /data/adb/tailscale/scripts/tailscaled.service start; else exit 127; fi",
                timeoutMs = 10_000L
            )
            if (svcCheck.exitCode != 127) {
                repeat(8) {
                    delay(500L)
                    val probe = RootShell.exec(
                        command = tailscaleCmd(settings, "status", "--json"),
                        timeoutMs = 3_000L
                    )
                    if (probe.stdout.trim().startsWith("{")) return
                }
                return
            }
        }

        val socketArg = if (settings.socketPath.isNotBlank()) {
            "--socket=${RootShell.shellQuote(settings.socketPath)}"
        } else {
            ""
        }
        val stateDirArg = if (settings.stateDir.isNotBlank()) {
            "mkdir -p ${RootShell.shellQuote(settings.stateDir)} && "
        } else {
            ""
        }
        val stateFlag = if (settings.stateDir.isNotBlank()) {
            "--statedir=${RootShell.shellQuote(settings.stateDir)}"
        } else {
            ""
        }
        val socksFlag = if (settings.socks5Address.isNotBlank()) {
            "--socks5-server=${RootShell.shellQuote(settings.socks5Address)}"
        } else {
            ""
        }

        val startCmd = buildString {
            append(stateDirArg)
            append("nohup ")
            append(RootShell.shellQuote(settings.tailscaledPath))
            append(" --tun=userspace-networking ")
            if (socksFlag.isNotBlank()) append("$socksFlag ")
            if (socketArg.isNotBlank()) append("$socketArg ")
            if (stateFlag.isNotBlank()) append("$stateFlag ")
            append(">/dev/null 2>&1 &")
        }

        RootShell.exec(startCmd, timeoutMs = 5_000L)

        // Wait up to 4 seconds for tailscaled to become responsive
        repeat(8) {
            delay(500L)
            val probe = RootShell.exec(
                command = tailscaleCmd(settings, "status", "--json"),
                timeoutMs = 3_000L
            )
            if (probe.stdout.trim().startsWith("{")) return
        }
    }

    suspend fun startBrowserLogin(onAuthUrlFound: suspend (String) -> Unit): ShellResult {
        val settings = currentSettings()
        var foundUrl = false
        return RootShell.execStreaming(
            command = tailscaleCmd(settings, "login", "--hostname=${RootShell.shellQuote(defaultDeviceHostname())}"),
            timeoutMs = 15_000L
        ) { line ->
            if (!foundUrl) {
                val match = urlRegex.find(line)
                if (match != null) {
                    foundUrl = true
                    onAuthUrlFound(match.value.trimEnd('.', ',', ')', ']'))
                }
            }
        }
    }

    suspend fun loginWithAuthKey(authKey: String, loginServer: String): ShellResult {
        val settings = currentSettings()
        val args = mutableListOf(
            "up",
            "--hostname=${RootShell.shellQuote(defaultDeviceHostname())}",
            "--authkey=${RootShell.shellQuote(authKey.trim())}"
        )
        if (loginServer.isNotBlank()) {
            args += "--login-server=${RootShell.shellQuote(loginServer.trim())}"
        }
        if (settings.acceptDnsFalse) {
            args += "--accept-dns=false"
        }
        return RootShell.exec(
            command = tailscaleCmd(settings, *args.toTypedArray()),
            timeoutMs = 30_000L
        )
    }

    suspend fun pingPeer(
        ip: String,
        count: Int = 3,
        onEntry: suspend (PingEntry) -> Unit
    ): ShellResult {
        val settings = currentSettings()
        return RootShell.execStreaming(
            command = tailscaleCmd(settings, "ping", "-c", count.toString(), RootShell.shellQuote(ip)),
            timeoutMs = 20_000L
        ) { line ->
            if (line.isNotBlank()) {
                onEntry(parsePingLine(line))
            }
        }
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
        val settings = currentSettings()
        val arg = "--exit-node=${RootShell.shellQuote(exitNodeIpOrEmpty.trim())}"
        return RootShell.exec(
            command = tailscaleCmd(settings, "set", arg),
            timeoutMs = 15_000L
        )
    }

    fun startWebUi(): Pair<Process?, String> {
        val settings = currentSettings()
        val listenAddr = "127.0.0.1:${settings.webUiPort}"
        val cmd = tailscaleCmd(settings, "web", "--listen", listenAddr)
        val process = RootShell.startBackground(cmd)
        return process to "http://$listenAddr"
    }

    suspend fun stopWebUi(process: Process?) {
        runCatching { process?.destroyForcibly() }
        val settings = currentSettings()
        val listenAddr = "127.0.0.1:${settings.webUiPort}"
        RootShell.exec(
            command = "pkill -f 'web --listen $listenAddr' || true",
            timeoutMs = 5_000L
        )
    }
}
