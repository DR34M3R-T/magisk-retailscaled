package com.example.roottailscale.ui

import android.app.Application
import android.content.ComponentName
import android.service.quicksettings.TileService
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.roottailscale.core.AppSettings
import com.example.roottailscale.core.PeerStatus
import com.example.roottailscale.core.PingEntry
import com.example.roottailscale.core.SettingsStore
import com.example.roottailscale.core.TailscaleLoadState
import com.example.roottailscale.core.TailscaleRepository
import com.example.roottailscale.tile.TailscaleTileService
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class PingDialogState(
    val peer: PeerStatus,
    val entries: List<PingEntry> = emptyList(),
    val isRunning: Boolean = false,
    val error: String? = null
)

data class MainUiState(
    val loadState: TailscaleLoadState = TailscaleLoadState.Loading,
    val settings: AppSettings = AppSettings(),
    val moduleVersion: String? = null,
    val isUsingBridge: Boolean = false,
    val isRefreshing: Boolean = false,
    val isToggling: Boolean = false,
    val isLoggingIn: Boolean = false,
    val capturedAuthUrl: String? = null,
    val isUpdatingExitNode: Boolean = false,
    val pingDialog: PingDialogState? = null,
    val snackbarMessage: String? = null
)

class MainViewModel(application: Application) : AndroidViewModel(application) {

    val repository = TailscaleRepository(application, SettingsStore(application))

    private val _uiState = MutableStateFlow(
        MainUiState(settings = repository.currentSettings())
    )
    val uiState: StateFlow<MainUiState> = _uiState.asStateFlow()

    private var pingJob: Job? = null

    init {
        refresh()
    }

    fun refresh(showPullIndicator: Boolean = false) {
        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    isRefreshing = showPullIndicator,
                    loadState = if (it.loadState is TailscaleLoadState.Loading) {
                        TailscaleLoadState.Loading
                    } else {
                        it.loadState
                    }
                )
            }
            val newState = repository.fetchStatus()
            val modVer = if (newState !is TailscaleLoadState.NoRoot) {
                repository.readModuleVersion()
            } else {
                null
            }
            _uiState.update {
                it.copy(
                    loadState = newState,
                    moduleVersion = modVer ?: it.moduleVersion,
                    isUsingBridge = repository.isUsingBridge,
                    isRefreshing = false,
                    settings = repository.currentSettings()
                )
            }
            requestTileSync()
        }
    }

    fun toggleConnection(turnOn: Boolean) {
        if (_uiState.value.isToggling) return
        viewModelScope.launch {
            _uiState.update { it.copy(isToggling = true) }
            val result = if (turnOn) repository.turnOn() else repository.turnOff()
            val message = if (result.isSuccess) {
                if (turnOn) "Tailscale 已启动" else "Tailscale 已停止"
            } else {
                result.combinedOutput.ifBlank { "操作失败 (exit=${result.exitCode})" }
            }
            val newState = repository.fetchStatus()
            _uiState.update {
                it.copy(
                    loadState = newState,
                    isToggling = false,
                    snackbarMessage = message
                )
            }
            requestTileSync()
        }
    }

    fun startBrowserLogin(onOpenUrl: (String) -> Unit) {
        if (_uiState.value.isLoggingIn) return
        viewModelScope.launch {
            _uiState.update { it.copy(isLoggingIn = true, capturedAuthUrl = null) }
            var opened = false
            val result = repository.startBrowserLogin { url ->
                opened = true
                _uiState.update { it.copy(capturedAuthUrl = url) }
                onOpenUrl(url)
            }
            val newState = repository.fetchStatus()
            _uiState.update {
                it.copy(
                    loadState = newState,
                    isLoggingIn = false,
                    snackbarMessage = when {
                        opened -> "已捕获登录链接并尝试打开浏览器"
                        result.isSuccess -> "登录命令执行完成"
                        else -> result.combinedOutput.ifBlank { "获取登录链接失败" }
                    }
                )
            }
            requestTileSync()
        }
    }

    fun loginWithAuthKey(authKey: String, loginServer: String) {
        if (authKey.isBlank()) {
            showMessage("请输入 Auth Key")
            return
        }
        if (_uiState.value.isLoggingIn) return
        viewModelScope.launch {
            _uiState.update { it.copy(isLoggingIn = true) }
            val result = repository.loginWithAuthKey(authKey, loginServer)
            val newState = repository.fetchStatus()
            _uiState.update {
                it.copy(
                    loadState = newState,
                    isLoggingIn = false,
                    snackbarMessage = if (result.isSuccess) {
                        "Auth Key 登录成功"
                    } else {
                        result.combinedOutput.ifBlank { "登录失败" }
                    }
                )
            }
            requestTileSync()
        }
    }

    fun startPing(peer: PeerStatus) {
        val ip = peer.ipv4
        if (ip.isBlank()) {
            showMessage("该设备没有可用的 Tailscale IP")
            return
        }
        pingJob?.cancel()
        _uiState.update {
            it.copy(
                pingDialog = PingDialogState(
                    peer = peer,
                    entries = emptyList(),
                    isRunning = true,
                    error = null
                )
            )
        }
        pingJob = viewModelScope.launch {
            val result = repository.pingPeer(ip, count = 3) { entry ->
                _uiState.update { state ->
                    val dialog = state.pingDialog ?: return@update state
                    state.copy(pingDialog = dialog.copy(entries = dialog.entries + entry))
                }
            }
            _uiState.update { state ->
                val dialog = state.pingDialog ?: return@update state
                val err = if (!result.isSuccess && dialog.entries.isEmpty()) {
                    result.combinedOutput.ifBlank { "Ping 测速失败" }
                } else {
                    null
                }
                state.copy(
                    pingDialog = dialog.copy(
                        isRunning = false,
                        error = err
                    )
                )
            }
        }
    }

    fun dismissPingDialog() {
        pingJob?.cancel()
        pingJob = null
        _uiState.update { it.copy(pingDialog = null) }
    }

    fun updateSettings(newSettings: AppSettings) {
        repository.saveSettings(newSettings)
        _uiState.update {
            it.copy(
                settings = newSettings,
                snackbarMessage = "设置已保存"
            )
        }
        refresh()
    }

    fun setExitNode(exitNodeIpOrEmpty: String) {
        if (_uiState.value.isUpdatingExitNode) return
        viewModelScope.launch {
            _uiState.update { it.copy(isUpdatingExitNode = true) }
            val result = repository.setExitNode(exitNodeIpOrEmpty)
            val newState = repository.fetchStatus()
            _uiState.update {
                it.copy(
                    loadState = newState,
                    isUpdatingExitNode = false,
                    snackbarMessage = if (result.isSuccess) {
                        if (exitNodeIpOrEmpty.isBlank()) "已关闭 Exit Node" else "已切换 Exit Node: $exitNodeIpOrEmpty"
                    } else {
                        result.combinedOutput.ifBlank { "切换 Exit Node 失败" }
                    }
                )
            }
        }
    }

    fun showMessage(message: String) {
        _uiState.update { it.copy(snackbarMessage = message) }
    }

    fun clearSnackbar() {
        _uiState.update { it.copy(snackbarMessage = null) }
    }

    private fun requestTileSync() {
        runCatching {
            val app = getApplication<Application>()
            TileService.requestListeningState(
                app,
                ComponentName(app, TailscaleTileService::class.java)
            )
        }
    }
}
