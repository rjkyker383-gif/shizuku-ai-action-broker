package com.rjkyker.shizukuai.broker.llm

import android.content.Context
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * App-private model storage: noBackupFilesDir/models (never backed up, removed with the app).
 * Download = HTTPS from a pinned HF revision -> <file>.part (resumable) -> size + SHA-256 check -> atomic rename.
 * A file is only "present" when its verified marker matches the pinned sha256.
 */
object ModelStore {
    fun interface Source {
        /** Open a stream starting at [offset]; returns the stream and whether the server honored the offset. */
        fun open(offset: Long): Pair<InputStream, Boolean>
    }

    data class Progress(val bytes: Long, val total: Long)

    class VerifyException(msg: String) : Exception(msg)

    fun modelsDir(ctx: Context): File = File(ctx.noBackupFilesDir, "models").apply { mkdirs() }
    fun fileFor(ctx: Context, spec: ModelSpec): File = File(modelsDir(ctx), spec.file)
    private fun marker(f: File) = File(f.parentFile, f.name + ".sha256")

    fun isVerifiedPresent(ctx: Context, spec: ModelSpec): Boolean = isVerifiedPresent(fileFor(ctx, spec), spec)

    fun isVerifiedPresent(f: File, spec: ModelSpec): Boolean =
        f.isFile && f.length() == spec.sizeBytes &&
            runCatching { marker(f).readText().trim() == spec.sha256 }.getOrDefault(false)

    fun httpSource(url: String): Source = Source { offset ->
        var u = URL(url)
        var redirects = 0
        while (true) {
            val c = (u.openConnection() as HttpURLConnection).apply {
                connectTimeout = 20_000; readTimeout = 60_000; instanceFollowRedirects = false
                setRequestProperty("User-Agent", "ShizukuAiBroker/1.9.0")
                if (offset > 0) setRequestProperty("Range", "bytes=$offset-")
            }
            val code = c.responseCode
            if (code in 300..399 && redirects < 6) {
                val loc = c.getHeaderField("Location") ?: throw VerifyException("redirect without Location")
                u = URL(u, loc); redirects++; c.disconnect()
                require(u.protocol == "https") { "refusing non-https redirect" }
                continue
            }
            if (code != 200 && code != 206) throw VerifyException("HTTP $code")
            return@Source c.inputStream to (code == 206)
        }
        @Suppress("UNREACHABLE_CODE") throw IllegalStateException()
    }

    /**
     * Download into [dest] via [source]. Resumes from an existing .part. Verifies exact size and SHA-256
     * before the atomic rename; on mismatch the .part is deleted and VerifyException thrown.
     */
    fun download(
        dest: File, spec: ModelSpec, source: Source,
        cancelled: () -> Boolean = { false }, onProgress: (Progress) -> Unit = {},
    ): File {
        if (isVerifiedPresent(dest, spec)) return dest
        dest.parentFile?.mkdirs()
        val part = File(dest.parentFile, dest.name + ".part")
        var offset = if (part.isFile) part.length() else 0L
        if (offset > spec.sizeBytes) { part.delete(); offset = 0 }
        if (offset < spec.sizeBytes) {
            val (input, resumed) = source.open(offset)
            if (!resumed && offset > 0) { part.delete(); offset = 0 }
            input.use { ins ->
                FileOutputStream(part, offset > 0).use { out ->
                    val buf = ByteArray(256 * 1024)
                    var got = offset
                    var lastReport = 0L
                    while (true) {
                        if (cancelled()) throw InterruptedException("download cancelled")
                        val n = ins.read(buf)
                        if (n < 0) break
                        if (got + n > spec.sizeBytes) throw VerifyException("server sent more than ${spec.sizeBytes} bytes")
                        out.write(buf, 0, n); got += n
                        if (got - lastReport >= 4L * 1024 * 1024) { lastReport = got; onProgress(Progress(got, spec.sizeBytes)) }
                    }
                    out.fd.sync()
                }
            }
        }
        if (part.length() != spec.sizeBytes) throw VerifyException("size ${part.length()} != ${spec.sizeBytes} (partial kept for resume)")
        val hash = sha256(part)
        if (!hash.equals(spec.sha256, ignoreCase = true)) {
            part.delete()
            throw VerifyException("sha256 mismatch: got $hash")
        }
        if (dest.exists()) dest.delete()
        if (!part.renameTo(dest)) throw VerifyException("rename failed")
        marker(dest).writeText(spec.sha256)
        onProgress(Progress(spec.sizeBytes, spec.sizeBytes))
        return dest
    }

    fun sha256(f: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        RandomAccessFile(f, "r").use { raf ->
            val buf = ByteArray(1 shl 20)
            while (true) { val n = raf.read(buf); if (n < 0) break; md.update(buf, 0, n) }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    /** Removes the model, its marker and any partial download. Only touches our own models dir. */
    fun delete(ctx: Context, spec: ModelSpec): Boolean {
        val f = fileFor(ctx, spec)
        val p = File(f.parentFile, f.name + ".part")
        val r = listOf(f, p, marker(f)).map { !it.exists() || it.delete() }
        return r.all { it }
    }

    fun partialBytes(ctx: Context, spec: ModelSpec): Long =
        File(modelsDir(ctx), spec.file + ".part").let { if (it.isFile) it.length() else 0L }
}
