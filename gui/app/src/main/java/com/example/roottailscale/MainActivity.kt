package com.example.roottailscale

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import com.example.roottailscale.ui.HomeScreen
import com.example.roottailscale.ui.MainViewModel
import com.example.roottailscale.ui.SettingsScreen
import com.example.roottailscale.ui.WebUiScreen
import com.example.roottailscale.ui.theme.RootTailscaleTheme

private enum class AppScreen {
    HOME,
    SETTINGS,
    WEB_UI
}

class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            RootTailscaleTheme {
                val uiState by viewModel.uiState.collectAsState()
                var currentScreen by rememberSaveable { mutableStateOf(AppScreen.HOME) }

                BackHandler(enabled = currentScreen != AppScreen.HOME) {
                    currentScreen = AppScreen.HOME
                }

                when (currentScreen) {
                    AppScreen.HOME -> {
                        HomeScreen(
                            uiState = uiState,
                            onRefresh = { viewModel.refresh(it) },
                            onToggleConnection = { viewModel.toggleConnection(it) },
                            onBrowserLogin = { onOpenUrl -> viewModel.startBrowserLogin(onOpenUrl) },
                            onAuthKeyLogin = { key, server -> viewModel.loginWithAuthKey(key, server) },
                            onPingPeer = { viewModel.startPing(it) },
                            onDismissPing = { viewModel.dismissPingDialog() },
                            onOpenSettings = { currentScreen = AppScreen.SETTINGS },
                            onOpenWebUi = { currentScreen = AppScreen.WEB_UI },
                            onShowMessage = { viewModel.showMessage(it) },
                            onSnackbarConsumed = { viewModel.clearSnackbar() }
                        )
                    }

                    AppScreen.SETTINGS -> {
                        SettingsScreen(
                            uiState = uiState,
                            onSaveSettings = { viewModel.updateSettings(it) },
                            onSelectExitNode = { viewModel.setExitNode(it) },
                            onOpenWebUi = { currentScreen = AppScreen.WEB_UI },
                            onBack = { currentScreen = AppScreen.HOME }
                        )
                    }

                    AppScreen.WEB_UI -> {
                        WebUiScreen(
                            repository = viewModel.repository,
                            onBack = { currentScreen = AppScreen.HOME }
                        )
                    }
                }
            }
        }
    }
}
