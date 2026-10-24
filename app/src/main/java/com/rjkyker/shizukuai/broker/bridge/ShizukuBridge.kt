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
/** Result of [ShizukuBridge.execSeparate]: stdout and stderr kept apart. */
data class ShellResult(
    val exitCode: Int,
    val stdout: String,
    val stderr: String,
    val via: String,
    val timedOut: Boolean = false
)

object ShizukuBridge {

    private const val TAG = "ShizukuBridge"
    private const val TIMEOUT_SEC = 30L
    private const val MAX_CAPTURE_CHARS = 512 * 1024

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
     * Like [exec] but keeps stdout/stderr separate (no redirectErrorStream) and reports which
     * backend ran the command: shizuku_api (uid 2000 ADB shell, or uid 0 if Shizuku runs as root),
     * rish, or sh (app uid, unprivileged).
     */
    fun execSeparate(cmd: String, timeoutSec: Long = TIMEOUT_SEC): ShellResult {
        val t = timeoutSec.coerceIn(1L, 300L)
        if (ShizukuGateway.isReady()) {
            try {
                return readProcessSeparate(newShizukuProcess(cmd), "shizuku_api", t)
            } catch (e: Exception) {
                Log.w(TAG, "Shizuku API execSeparate failed, falling back to rish/sh: ${e.message}")
            }
        }
        var last: ShellResult? = null
        for (argv in listOf(listOf("rish", "-c", cmd), listOf("sh", "-c", cmd))) {
            try {
                val r = readProcessSeparate(ProcessBuilder(argv).start(), argv[0], t)
                if (argv[0] == "rish" && (r.exitCode == 127 ||
                        (r.exitCode != 0 && (r.stderr + r.stdout).contains("not found", ignoreCase = true)))
                ) {
                    last = r
                    continue
                }
                return r
            } catch (e: Exception) {
                Log.w(TAG, "execSeparate via ${argv[0]} failed: ${e.message}")
                last = ShellResult(-1, "", e.message ?: "exec failed", argv[0])
            }
        }
        return last ?: ShellResult(-1, "", "no shell available", "none")
    }

    private fun newShizukuProcess(cmd: String): Process {
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
        return ctor.newInstance(remote) as Process
    }

    /** Drains stdout and stderr on separate threads so neither pipe can block the child. */
    private fun readProcessSeparate(proc: Process, via: String, timeoutSec: Long): ShellResult {
        val out = StringBuilder()
        val err = StringBuilder()
        val tOut = Thread({ drain(proc.inputStream, out) }, "sh-stdout").apply { isDaemon = true; start() }
        val tErr = Thread({ drain(proc.errorStream, err) }, "sh-stderr").apply { isDaemon = true; start() }
        try { proc.outputStream.close() } catch (_: Exception) { }
        val finished = waitWithTimeout(proc, timeoutSec)
        if (!finished) {
            try { proc.destroyForcibly() } catch (_: Exception) { proc.destroy() }
        }
        tOut.join(2000)
        tErr.join(2000)
        val code = if (finished) {
            try { proc.exitValue() } catch (_: Exception) { -1 }
        } else -1
        Log.d(TAG, "execSeparate via=$via code=$code timedOut=${!finished}")
        val stderr = synchronized(err) { err.toString() }
        return ShellResult(
            exitCode = code,
            stdout = synchronized(out) { out.toString() },
            stderr = if (finished) stderr else "timeout after ${timeoutSec}s via=$via\n$stderr",
            via = via,
            timedOut = !finished
        )
    }

    private fun drain(stream: java.io.InputStream, sink: StringBuilder) {
        try {
            BufferedReader(InputStreamReader(stream)).use { r ->
                val buf = CharArray(4096)
                while (true) {
                    val n = r.read(buf)
                    if (n < 0) break
                    synchronized(sink) {
                        if (sink.length < MAX_CAPTURE_CHARS) sink.append(buf, 0, minOf(n, MAX_CAPTURE_CHARS - sink.length))
                    }
                }
            }
        } catch (_: Exception) {
        }
    }

    private fun waitWithTimeout(proc: Process, timeoutSec: Long): Boolean {
        return try {
            proc.waitFor(timeoutSec, TimeUnit.SECONDS)
        } catch (_: Exception) {
            // ShizukuRemoteProcess may not implement waitFor(timeout); try its own variant, else poll.
            try {
                val m = proc.javaClass.getMethod("waitForTimeout", Long::class.javaPrimitiveType, TimeUnit::class.java)
                m.invoke(proc, timeoutSec, TimeUnit.SECONDS) as Boolean
            } catch (_: Exception) {
                val deadline = System.currentTimeMillis() + timeoutSec * 1000
                while (System.currentTimeMillis() < deadline) {
                    try { proc.exitValue(); return true } catch (_: IllegalThreadStateException) { }
                    Thread.sleep(100)
                }
                false
            }
        }
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
