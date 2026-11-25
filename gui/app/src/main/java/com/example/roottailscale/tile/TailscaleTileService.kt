package com.example.roottailscale.tile

import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.example.roottailscale.MainActivity
import com.example.roottailscale.core.SettingsStore
import com.example.roottailscale.core.TailscaleLoadState
import com.example.roottailscale.core.TailscaleRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class TailscaleTileService : TileService() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var repository: TailscaleRepository

    @Volatile
    private var lastState: TailscaleLoadState = TailscaleLoadState.Loading

    override fun onCreate() {
        super.onCreate()
        repository = TailscaleRepository(applicationContext, SettingsStore(applicationContext))
    }

    override fun onStartListening() {
        super.onStartListening()
        refreshTileState()
    }

    override fun onClick() {
        super.onClick()
        val current = lastState

        if (current is TailscaleLoadState.Ready && current.status.needsLogin) {
            openMainApp()
            return
        }
        if (current is TailscaleLoadState.BridgeUnavailable) {
            openMainApp()
            return
        }

        // Optimistically show transitional subtitle
        updateTile(
            state = qsTile?.state ?: Tile.STATE_INACTIVE,
            subtitle = "切换中..."
        )

        serviceScope.launch {
            val isCurrentlyRunning =
                (current as? TailscaleLoadState.Ready)?.status?.isRunning == true
            if (isCurrentlyRunning) {
                repository.turnOff()
            } else {
                repository.turnOn()
            }
            refreshTileStateSync()
        }
    }

    private fun refreshTileState() {
        serviceScope.launch {
            refreshTileStateSync()
        }
    }

    private suspend fun refreshTileStateSync() {
        val state = repository.fetchStatus()
        lastState = state

        when (state) {
            is TailscaleLoadState.BridgeUnavailable -> {
                updateTile(Tile.STATE_UNAVAILABLE, "模块未就绪")
            }

            is TailscaleLoadState.DaemonStopped -> {
                updateTile(Tile.STATE_INACTIVE, "已停止")
            }

            is TailscaleLoadState.Error -> {
                updateTile(Tile.STATE_INACTIVE, "状态异常")
            }

            is TailscaleLoadState.Loading -> {
                updateTile(Tile.STATE_INACTIVE, "读取中...")
            }

            is TailscaleLoadState.Ready -> {
                val status = state.status
                when {
                    status.isRunning -> {
                        val ip = status.self?.ipv4?.ifBlank { "运行中" } ?: "运行中"
                        updateTile(Tile.STATE_ACTIVE, ip)
                    }

                    status.needsLogin -> {
                        updateTile(Tile.STATE_INACTIVE, "未登录")
                    }

                    else -> {
                        updateTile(Tile.STATE_INACTIVE, "已停止")
                    }
                }
            }
        }
    }

    private fun updateTile(state: Int, subtitle: String) {
        val tile = qsTile ?: return
        tile.state = state
        tile.label = "REtailscaled"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            tile.subtitle = subtitle
        }
        tile.updateTile()
    }

    private fun openMainApp() {
        val intent = Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val pendingIntent = PendingIntent.getActivity(
                this,
                0,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            startActivityAndCollapse(pendingIntent)
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
    }
}
