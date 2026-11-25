package com.example.roottailscale.core

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class TailscaleStatus(
    @SerialName("Version") val version: String = "",
    @SerialName("BackendState") val backendState: String = "NoState",
    @SerialName("AuthURL") val authUrl: String = "",
    @SerialName("Self") val self: PeerStatus? = null,
    @SerialName("CurrentTailnet") val currentTailnet: TailnetInfo? = null,
    @SerialName("MagicDNSSuffix") val magicDnsSuffix: String = "",
    @SerialName("Peer") val peers: Map<String, PeerStatus>? = null,
    @SerialName("Health") val health: List<String>? = null
) {
    val isRunning: Boolean
        get() = backendState == "Running"

    val needsLogin: Boolean
        get() = backendState == "NeedsLogin" || backendState == "NeedsMachineAuth"

    val tailnetDisplayName: String
        get() = currentTailnet?.name?.takeIf { it.isNotBlank() }
            ?: magicDnsSuffix.takeIf { it.isNotBlank() }
            ?: "未连接 Tailnet"

    val sortedPeers: List<PeerStatus>
        get() = (peers?.values ?: emptyList())
            .sortedWith(
                compareByDescending<PeerStatus> { it.online }
                    .thenByDescending { it.exitNode }
                    .thenBy { it.displayName.lowercase() }
            )

    val exitNodeCandidates: List<PeerStatus>
        get() = (peers?.values ?: emptyList())
            .filter { it.exitNodeOption }
            .sortedByDescending { it.online }
}

@Serializable
data class TailnetInfo(
    @SerialName("Name") val name: String = "",
    @SerialName("MagicDNSSuffix") val magicDnsSuffix: String = ""
)

@Serializable
data class PeerStatus(
    @SerialName("ID") val id: String = "",
    @SerialName("HostName") val hostName: String = "",
    @SerialName("DNSName") val dnsName: String = "",
    @SerialName("OS") val os: String = "",
    @SerialName("TailscaleIPs") val tailscaleIps: List<String>? = null,
    @SerialName("Online") val online: Boolean = false,
    @SerialName("Active") val active: Boolean = false,
    @SerialName("CurAddr") val curAddr: String = "",
    @SerialName("Relay") val relay: String = "",
    @SerialName("ExitNode") val exitNode: Boolean = false,
    @SerialName("ExitNodeOption") val exitNodeOption: Boolean = false,
    @SerialName("RxBytes") val rxBytes: Long = 0L,
    @SerialName("TxBytes") val txBytes: Long = 0L
) {
    val ipv4: String
        get() = tailscaleIps?.firstOrNull { !it.contains(':') }
            ?: tailscaleIps?.firstOrNull()
            ?: ""

    val displayName: String
        get() = hostName.ifBlank {
            dnsName.trimEnd('.').substringBefore('.')
        }.ifBlank { ipv4.ifBlank { "未知设备" } }

    val connectionTypeLabel: String?
        get() = when {
            curAddr.isNotBlank() -> "Direct"
            relay.isNotBlank() -> "DERP($relay)"
            else -> null
        }

    val isDirectConnection: Boolean
        get() = curAddr.isNotBlank()
}

data class PingEntry(
    val rawLine: String,
    val latencyMs: String? = null,
    val isDirect: Boolean? = null,
    val via: String? = null
)

enum class PowerSaveMode {
    LIGHT,
    DEEP
}

data class AppSettings(
    val tailscalePath: String = "tailscale",
    val tailscaledPath: String = "tailscaled",
    val socketPath: String = "",
    val stateDir: String = "/data/adb/tailscale/state",
    val acceptDnsFalse: Boolean = true,
    val socks5Address: String = "127.0.0.1:1055",
    val powerSaveMode: PowerSaveMode = PowerSaveMode.LIGHT,
    val webUiPort: Int = 8088
)

sealed interface TailscaleLoadState {
    data object Loading : TailscaleLoadState
    data object NoRoot : TailscaleLoadState
    data class DaemonStopped(val detail: String) : TailscaleLoadState
    data class Ready(val status: TailscaleStatus) : TailscaleLoadState
    data class Error(val message: String) : TailscaleLoadState
}
