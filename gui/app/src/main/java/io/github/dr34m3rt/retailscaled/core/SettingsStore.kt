package io.github.dr34m3rt.retailscaled.core

import android.content.Context
import android.content.SharedPreferences

class SettingsStore(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun load(): AppSettings {
        val modeName = prefs.getString(KEY_POWER_SAVE_MODE, PowerSaveMode.LIGHT.name)
        val mode = runCatching { PowerSaveMode.valueOf(modeName ?: PowerSaveMode.LIGHT.name) }
            .getOrDefault(PowerSaveMode.LIGHT)

        return AppSettings(
            acceptDnsFalse = prefs.getBoolean(KEY_ACCEPT_DNS_FALSE, true),
            socks5Address = prefs.getString(KEY_SOCKS5_ADDRESS, "127.0.0.1:1055")
                ?.trim().orEmpty().ifBlank { "127.0.0.1:1055" },
            powerSaveMode = mode,
            webUiPort = prefs.getInt(KEY_WEB_UI_PORT, 8088).coerceIn(1024, 65535)
        )
    }

    fun save(settings: AppSettings) {
        prefs.edit()
            .putBoolean(KEY_ACCEPT_DNS_FALSE, settings.acceptDnsFalse)
            .putString(KEY_SOCKS5_ADDRESS, settings.socks5Address.trim().ifBlank { "127.0.0.1:1055" })
            .putString(KEY_POWER_SAVE_MODE, settings.powerSaveMode.name)
            .putInt(KEY_WEB_UI_PORT, settings.webUiPort.coerceIn(1024, 65535))
            .apply()
    }

    companion object {
        private const val PREFS_NAME = "retailscaled_settings"
        private const val KEY_ACCEPT_DNS_FALSE = "accept_dns_false"
        private const val KEY_SOCKS5_ADDRESS = "socks5_address"
        private const val KEY_POWER_SAVE_MODE = "power_save_mode"
        private const val KEY_WEB_UI_PORT = "web_ui_port"
    }
}
