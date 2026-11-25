package com.example.roottailscale.core

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

@Serializable
data class BridgeAuthConfig(
    val port: Int = 41688,
    val token: String = ""
)

@Serializable
data class BridgeHealthResponse(
    val ok: Boolean = false,
    val moduleVersion: String = ""
)

@Serializable
data class BridgeExecResponse(
    val exitCode: Int = 1,
    val output: String = ""
)

@Serializable
private data class BridgeUpRequest(
    val acceptDnsFalse: Boolean,
    val hostname: String,
    val socks5Address: String
)

@Serializable
private data class BridgeDownRequest(
    val deepPowerSave: Boolean
)

@Serializable
private data class BridgeBrowserLoginRequest(
    val hostname: String
)

@Serializable
private data class BridgeAuthKeyRequest(
    val authKey: String,
    val loginServer: String,
    val acceptDnsFalse: Boolean,
    val hostname: String
)

@Serializable
private data class BridgePingRequest(
    val ip: String,
    val count: Int
)

@Serializable
private data class BridgeExitNodeRequest(
    val exitNode: String
)

@Serializable
private data class BridgeWebUiStartRequest(
    val port: Int
)

@Serializable
data class BridgeWebUiStartResponse(
    val ok: Boolean = false,
    val url: String = "",
    val error: String = ""
)

class BridgeClient(context: Context) {

    private val authFile = File(context.applicationContext.filesDir, "bridge_auth.json")
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
    }

    private fun readAuth(): BridgeAuthConfig? {
        if (!authFile.exists() || !authFile.canRead()) return null
        return runCatching {
            val parsed = json.decodeFromString<BridgeAuthConfig>(authFile.readText())
            parsed.takeIf { it.token.isNotBlank() && it.port in 1024..65535 }
        }.getOrNull()
    }

    private fun openConnection(
        auth: BridgeAuthConfig,
        path: String,
        method: String,
        connectTimeoutMs: Int = 1_000,
        readTimeoutMs: Int = 15_000
    ): HttpURLConnection {
        val url = URL("http://127.0.0.1:${auth.port}$path")
        return (url.openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = connectTimeoutMs
            readTimeout = readTimeoutMs
            setRequestProperty("Authorization", "Bearer ${auth.token}")
            if (method == "POST") {
                doOutput = true
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
            }
        }
    }

    suspend fun health(): BridgeHealthResponse? = withContext(Dispatchers.IO) {
        val auth = readAuth() ?: return@withContext null
        runCatching {
            val conn = openConnection(auth, "/api/health", "GET", connectTimeoutMs = 800, readTimeoutMs = 1_500)
            try {
                if (conn.responseCode == 200) {
                    val body = conn.inputStream.bufferedReader().use(BufferedReader::readText)
                    json.decodeFromString<BridgeHealthResponse>(body).takeIf { it.ok }
                } else {
                    null
                }
            } finally {
                conn.disconnect()
            }
        }.getOrNull()
    }

    suspend fun fetchStatusRaw(): Pair<Int, String>? = withContext(Dispatchers.IO) {
        val auth = readAuth() ?: return@withContext null
        runCatching {
            val conn = openConnection(auth, "/api/status", "GET", connectTimeoutMs = 1_000, readTimeoutMs = 8_000)
            try {
                val code = conn.responseCode
                val stream = if (code in 200..299) conn.inputStream else conn.errorStream
                val body = stream?.bufferedReader()?.use(BufferedReader::readText).orEmpty()
                code to body
            } finally {
                conn.disconnect()
            }
        }.getOrNull()
    }

    suspend fun turnOn(
        acceptDnsFalse: Boolean,
        hostname: String,
        socks5Address: String
    ): ShellResult? = postExec(
        path = "/api/up",
        payload = json.encodeToString(BridgeUpRequest(acceptDnsFalse, hostname, socks5Address)),
        readTimeoutMs = 25_000
    )

    suspend fun turnOff(deepPowerSave: Boolean): ShellResult? = postExec(
        path = "/api/down",
        payload = json.encodeToString(BridgeDownRequest(deepPowerSave)),
        readTimeoutMs = 20_000
    )

    suspend fun loginWithAuthKey(
        authKey: String,
        loginServer: String,
        acceptDnsFalse: Boolean,
        hostname: String
    ): ShellResult? = postExec(
        path = "/api/login/authkey",
        payload = json.encodeToString(BridgeAuthKeyRequest(authKey, loginServer, acceptDnsFalse, hostname)),
        readTimeoutMs = 35_000
    )

    suspend fun setExitNode(exitNode: String): ShellResult? = postExec(
        path = "/api/exit-node",
        payload = json.encodeToString(BridgeExitNodeRequest(exitNode)),
        readTimeoutMs = 15_000
    )

    suspend fun streamBrowserLogin(
        hostname: String,
        onLine: suspend (String) -> Unit
    ): ShellResult? = postStreaming(
        path = "/api/login/browser",
        payload = json.encodeToString(BridgeBrowserLoginRequest(hostname)),
        readTimeoutMs = 20_000,
        onLine = onLine
    )

    suspend fun streamPing(
        ip: String,
        count: Int,
        onLine: suspend (String) -> Unit
    ): ShellResult? = postStreaming(
        path = "/api/ping",
        payload = json.encodeToString(BridgePingRequest(ip, count)),
        readTimeoutMs = 25_000,
        onLine = onLine
    )

    suspend fun startWebUi(port: Int): String? = withContext(Dispatchers.IO) {
        val auth = readAuth() ?: return@withContext null
        runCatching {
            val conn = openConnection(auth, "/api/webui/start", "POST", readTimeoutMs = 10_000)
            try {
                conn.outputStream.use { it.write(json.encodeToString(BridgeWebUiStartRequest(port)).toByteArray()) }
                if (conn.responseCode == 200) {
                    val body = conn.inputStream.bufferedReader().use(BufferedReader::readText)
                    val parsed = json.decodeFromString<BridgeWebUiStartResponse>(body)
                    parsed.url.takeIf { parsed.ok && it.isNotBlank() }
                } else {
                    null
                }
            } finally {
                conn.disconnect()
            }
        }.getOrNull()
    }

    suspend fun stopWebUi(): Boolean = withContext(Dispatchers.IO) {
        val auth = readAuth() ?: return@withContext false
        runCatching {
            val conn = openConnection(auth, "/api/webui/stop", "POST", readTimeoutMs = 5_000)
            try {
                conn.outputStream.use { it.write("{}".toByteArray()) }
                conn.responseCode == 200
            } finally {
                conn.disconnect()
            }
        }.getOrDefault(false)
    }

    private suspend fun postExec(
        path: String,
        payload: String,
        readTimeoutMs: Int
    ): ShellResult? = withContext(Dispatchers.IO) {
        val auth = readAuth() ?: return@withContext null
        runCatching {
            val conn = openConnection(auth, path, "POST", readTimeoutMs = readTimeoutMs)
            try {
                conn.outputStream.use { it.write(payload.toByteArray()) }
                val code = conn.responseCode
                val stream = if (code in 200..299) conn.inputStream else conn.errorStream
                val body = stream?.bufferedReader()?.use(BufferedReader::readText).orEmpty()
                if (code == 200) {
                    val res = json.decodeFromString<BridgeExecResponse>(body)
                    ShellResult(res.exitCode, res.output, "")
                } else {
                    ShellResult(code, "", body)
                }
            } finally {
                conn.disconnect()
            }
        }.getOrNull()
    }

    private suspend fun postStreaming(
        path: String,
        payload: String,
        readTimeoutMs: Int,
        onLine: suspend (String) -> Unit
    ): ShellResult? = withContext(Dispatchers.IO) {
        val auth = readAuth() ?: return@withContext null
        runCatching {
            val conn = openConnection(auth, path, "POST", readTimeoutMs = readTimeoutMs)
            val collected = StringBuilder()
            try {
                conn.outputStream.use { it.write(payload.toByteArray()) }
                val code = conn.responseCode
                val stream = if (code in 200..299) conn.inputStream else conn.errorStream
                if (stream != null) {
                    BufferedReader(InputStreamReader(stream)).use { reader ->
                        var line = reader.readLine()
                        while (line != null) {
                            collected.appendLine(line)
                            onLine(line)
                            line = reader.readLine()
                        }
                    }
                }
                if (code in 200..299) {
                    ShellResult(0, collected.toString(), "")
                } else {
                    ShellResult(code, "", collected.toString())
                }
            } finally {
                conn.disconnect()
            }
        }.getOrNull()
    }
}
