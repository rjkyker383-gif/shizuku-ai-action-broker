package com.rjkyker.shizukuai.broker.bridge

import android.util.Log
import java.io.BufferedReader
import java.io.InputStreamReader
import java.lang.reflect.Method
import java.util.concurrent.TimeUnit

/**
 * Shell bridge: prefer Shizuku binder [newProcess] when binder+permission are OK;
 * otherwise fall back to process `rish -c` then `sh -c`.
 */
object ShizukuBridge {

    private const val TAG = "ShizukuBridge"
    private const val TIMEOUT_SEC = 30L

    fun exec(cmd: String): Pair<Int, String> {
        if (ShizukuGateway.isReady()) {
            try {
                return execViaShizukuApi(cmd)
            } catch (e: Exception) {
                Log.w(TAG, "Shizuku API exec failed, falling back to rish/sh: ${e.message}")
            }
        }
        return execViaProcess(cmd)
    }

    /**
     * Uses IShizukuService.newProcess via reflection (API surface is package-protected).
     */
    private fun execViaShizukuApi(cmd: String): Pair<Int, String> {
        val requireService: Method = Class.forName("rikka.shizuku.Shizuku")
            .getDeclaredMethod("requireService")
        requireService.isAccessible = true
        val service = requireService.invoke(null)
            ?: throw IllegalStateException("Shizuku requireService returned null")

        val newProcess: Method = service.javaClass.getMethod(
            "newProcess",
            Array<String>::class.java,
            Array<String>::class.java,
            String::class.java
        )
        val remote = newProcess.invoke(service, arrayOf("sh", "-c", cmd), null, null)
            ?: throw IllegalStateException("Shizuku newProcess returned null")

        val remoteClass = Class.forName("moe.shizuku.server.IRemoteProcess")
        val ctor = Class.forName("rikka.shizuku.ShizukuRemoteProcess")
            .getDeclaredConstructor(remoteClass)
        ctor.isAccessible = true
        val process = ctor.newInstance(remote) as Process

        return readProcess(process, via = "shizuku_api")
    }

    private fun execViaProcess(cmd: String): Pair<Int, String> {
        val attempts = listOf(
            listOf("rish", "-c", cmd),
            listOf("sh", "-c", cmd)
        )
        var lastError: Pair<Int, String>? = null
        for (argv in attempts) {
            try {
                val result = runProcess(argv)
                if (argv[0] == "rish" && result.first == 127) {
                    lastError = result
                    continue
                }
                if (argv[0] == "rish" && result.second.contains("not found", ignoreCase = true) &&
                    result.first != 0
                ) {
                    lastError = result
                    continue
                }
                return result
            } catch (e: Exception) {
                Log.w(TAG, "exec via ${argv[0]} failed: ${e.message}")
                lastError = -1 to (e.message ?: "exec failed")
            }
        }
        return lastError ?: (-1 to "no shell available")
    }

    private fun runProcess(argv: List<String>): Pair<Int, String> {
        val pb = ProcessBuilder(argv).redirectErrorStream(true)
        return readProcess(pb.start(), via = argv[0])
    }

    private fun readProcess(proc: Process, via: String): Pair<Int, String> {
        val stdout = BufferedReader(InputStreamReader(proc.inputStream)).use { it.readText() }
        val finished = try {
            proc.waitFor(TIMEOUT_SEC, TimeUnit.SECONDS)
        } catch (_: Exception) {
            // ShizukuRemoteProcess may not implement waitFor(timeout); fall back.
            try {
                val m = proc.javaClass.getMethod("waitForTimeout", Long::class.javaPrimitiveType, TimeUnit::class.java)
                m.invoke(proc, TIMEOUT_SEC, TimeUnit.SECONDS) as Boolean
            } catch (_: Exception) {
                proc.waitFor()
                true
            }
        }
        if (!finished) {
            proc.destroyForcibly()
            return -1 to "timeout after ${TIMEOUT_SEC}s via=$via\n$stdout"
        }
        val code = try {
            proc.exitValue()
        } catch (_: Exception) {
            proc.waitFor()
        }
        Log.d(TAG, "exec via=$via code=$code")
        return code to stdout
    }
}
