package com.rjkyker.shizukuai.broker.cache

import android.content.Context
import com.rjkyker.shizukuai.broker.shell.ShellExecutor
import org.json.JSONObject
import java.io.File

/**
 * 1.10.0 cache hygiene. Clears ONLY the broker's own cache directories, or (with confirm) runs
 * `pm trim-caches <size>` via Shizuku to free other apps' cache files.
 * Never uses `pm clear`, never deletes RJ's files / Downloads / DCIM / databases / shared_prefs.
 */
object CacheCleaner {
    data class DirStat(val path: String, val bytesBefore: Long, val bytesAfter: Long, val deleted: Int)

    private fun du(f: File): Long = when {
        !f.exists() -> 0L
        f.isFile -> f.length()
        else -> f.walkTopDown().filter { it.isFile }.map { it.length() }.sum()
    }

    private fun wipeContents(dir: File): Pair<Int, Long> {
        if (!dir.exists()) return 0 to 0L
        var n = 0; var freed = 0L
        dir.listFiles()?.forEach { child ->
            val size = du(child)
            if (child.deleteRecursively()) { n++; freed += size }
        }
        return n to freed
    }

    /** Clear context.cacheDir + externalCacheDir (+ code_cache if present). Leaves filesDir/databases/prefs alone. */
    fun clearOwn(ctx: Context): JSONObject {
        val app = ctx.applicationContext
        val targets = listOfNotNull(app.cacheDir, app.externalCacheDir, app.codeCacheDir)
        val stats = JSONObject()
        var freed = 0L; var deleted = 0
        for (d in targets) {
            val before = du(d)
            val (n, f) = wipeContents(d)
            deleted += n; freed += f
            stats.put(d.absolutePath, JSONObject().put("before_bytes", before).put("freed_bytes", f).put("entries_removed", n))
        }
        return JSONObject().put("ok", true).put("own_cache", true).put("freed_bytes", freed).put("entries_removed", deleted)
            .put("dirs", stats).put("note", "broker cache only; databases, shared_prefs and filesDir untouched")
    }

    /** Optional: `pm trim-caches <size>` (e.g. "2G"). Requires Shizuku. Confirm must already be checked by caller. */
    fun trimSystem(size: String = "2G"): JSONObject {
        val s = size.trim()
        if (!Regex("""^\d+[KMG]?$""", RegexOption.IGNORE_CASE).matches(s))
            return JSONObject().put("ok", false).put("error", "size must look like 500M or 2G")
        val cmd = "pm trim-caches $s"
        // ShellDenylist allows trim-caches (it only blocks pm uninstall/clear/disable-user/hide).
        val r = ShellExecutor.execSync(cmd, 60)
        return JSONObject().put("ok", r.exitCode == 0 && !r.timedOut)
            .put("via", r.via).put("exit_code", r.exitCode).put("stdout", r.stdout.take(500))
            .put("stderr", r.stderr.take(500)).put("command", cmd)
            .put("note", "trims other apps' cache files only; never pm clear; never deletes user media")
    }

    fun status(ctx: Context): JSONObject {
        val app = ctx.applicationContext
        fun one(d: File?) = if (d == null) JSONObject.NULL else JSONObject().put("path", d.absolutePath).put("bytes", du(d))
        return JSONObject().put("ok", true)
            .put("cacheDir", one(app.cacheDir)).put("externalCacheDir", one(app.externalCacheDir))
            .put("codeCacheDir", one(app.codeCacheDir))
            .put("filesDir_bytes", du(app.filesDir)) // reported, NOT cleared
            .put("note", "clear_own only touches cacheDir/externalCacheDir/codeCacheDir")
    }
}
