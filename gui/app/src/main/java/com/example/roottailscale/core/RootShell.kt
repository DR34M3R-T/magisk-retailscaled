package com.example.roottailscale.core

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.BufferedReader
import java.io.InputStreamReader

data class ShellResult(
    val exitCode: Int,
    val stdout: String,
    val stderr: String,
    val timedOut: Boolean = false
) {
    val isSuccess: Boolean
        get() = exitCode == 0 && !timedOut

    val combinedOutput: String
        get() = buildString {
            if (stdout.isNotBlank()) append(stdout.trim())
            if (stderr.isNotBlank()) {
                if (isNotEmpty()) append('\n')
                append(stderr.trim())
            }
        }
}

object RootShell {

    private const val DEFAULT_PATH_EXPORT =
        "export PATH=\"\$PATH:/system/bin:/system/xbin:/sbin:/data/adb/magisk:/data/adb/ksu/bin:/data/adb/tailscale/bin:/data/adb/tailscale/scripts:/data/adb/modules/magisk-retailscaled/system/bin:/data/adb/modules/tailscaled/system/bin:/data/adb/modules/magisk-tailscaled/system/bin\"; "

    fun shellQuote(arg: String): String {
        return "'" + arg.replace("'", "'\\''") + "'"
    }

    suspend fun checkRoot(timeoutMs: Long = 5_000L): Boolean {
        val result = exec("id", timeoutMs = timeoutMs, includePathExport = false)
        return result.isSuccess && result.stdout.contains("uid=0")
    }

    suspend fun exec(
        command: String,
        timeoutMs: Long = 15_000L,
        includePathExport: Boolean = true
    ): ShellResult = withContext(Dispatchers.IO) {
        val fullCmd = if (includePathExport) DEFAULT_PATH_EXPORT + command else command
        val process = try {
            ProcessBuilder("su", "-c", fullCmd).start()
        } catch (e: Exception) {
            return@withContext ShellResult(
                exitCode = -1,
                stdout = "",
                stderr = e.message ?: "无法执行 su 命令"
            )
        }

        try {
            coroutineScope {
                val stdoutDeferred = async {
                    process.inputStream.bufferedReader().use(BufferedReader::readText)
                }
                val stderrDeferred = async {
                    process.errorStream.bufferedReader().use(BufferedReader::readText)
                }

                val finishedCode = withTimeoutOrNull(timeoutMs) {
                    process.waitFor()
                }

                if (finishedCode == null) {
                    process.destroyForcibly()
                    ShellResult(
                        exitCode = -1,
                        stdout = "",
                        stderr = "命令执行超时 (${timeoutMs / 1000}s)",
                        timedOut = true
                    )
                } else {
                    ShellResult(
                        exitCode = finishedCode,
                        stdout = stdoutDeferred.await(),
                        stderr = stderrDeferred.await()
                    )
                }
            }
        } catch (e: Exception) {
            process.destroyForcibly()
            ShellResult(
                exitCode = -1,
                stdout = "",
                stderr = e.message ?: "执行出错"
            )
        }
    }

    suspend fun execStreaming(
        command: String,
        timeoutMs: Long = 25_000L,
        onLine: suspend (String) -> Unit
    ): ShellResult = withContext(Dispatchers.IO) {
        val fullCmd = DEFAULT_PATH_EXPORT + command
        val process = try {
            ProcessBuilder("su", "-c", fullCmd)
                .redirectErrorStream(true)
                .start()
        } catch (e: Exception) {
            return@withContext ShellResult(
                exitCode = -1,
                stdout = "",
                stderr = e.message ?: "无法执行 su 命令"
            )
        }

        val collected = StringBuilder()
        try {
            val finishedCode = withTimeoutOrNull(timeoutMs) {
                BufferedReader(InputStreamReader(process.inputStream)).use { reader ->
                    var line = reader.readLine()
                    while (line != null) {
                        collected.appendLine(line)
                        onLine(line)
                        line = reader.readLine()
                    }
                }
                process.waitFor()
            }

            if (finishedCode == null) {
                process.destroyForcibly()
                ShellResult(
                    exitCode = -1,
                    stdout = collected.toString(),
                    stderr = "命令执行超时 (${timeoutMs / 1000}s)",
                    timedOut = true
                )
            } else {
                ShellResult(
                    exitCode = finishedCode,
                    stdout = collected.toString(),
                    stderr = ""
                )
            }
        } catch (e: Exception) {
            process.destroyForcibly()
            ShellResult(
                exitCode = -1,
                stdout = collected.toString(),
                stderr = e.message ?: "流式执行出错"
            )
        }
    }

    fun startBackground(command: String): Process? {
        val fullCmd = DEFAULT_PATH_EXPORT + command
        return try {
            ProcessBuilder("su", "-c", fullCmd)
                .redirectErrorStream(true)
                .start()
        } catch (_: Exception) {
            null
        }
    }
}
