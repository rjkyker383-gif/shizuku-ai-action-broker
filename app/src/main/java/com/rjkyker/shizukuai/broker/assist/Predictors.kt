package com.rjkyker.shizukuai.broker.assist

import android.content.Context
import com.rjkyker.shizukuai.broker.autonomy.memory.MemoryStore
import java.io.File

/** Lazily loads the bundled offline word predictor; user learning persisted only while memory is enabled. */
object Predictors {
    @Volatile private var p: WordPredictor? = null
    private const val USER_FILE = "predict_user.tsv"

    fun get(ctx: Context): WordPredictor? {
        p?.let { return it }
        synchronized(this) {
            p?.let { return it }
            // aapt un-gzips *.gz assets at build time (stored as predict/en_model.tsv, zip-compressed); accept either form
            val loaded = runCatching { ctx.assets.open("predict/en_model.tsv").use { WordPredictor.load(it, gz = false) } }.getOrNull()
                ?: runCatching { ctx.assets.open("predict/en_model.tsv.gz").use { WordPredictor.load(it) } }.getOrNull() ?: return null
            if (MemoryStore.enabled(ctx)) runCatching { File(ctx.filesDir, USER_FILE).takeIf { it.exists() }?.readText()?.let(loaded::importUser) }
            p = loaded
            return loaded
        }
    }

    fun learnAccepted(ctx: Context, contextTail: String, accepted: String) {
        if (!MemoryStore.enabled(ctx)) return
        val w = get(ctx) ?: return
        w.learn(contextTail.takeLast(60) + accepted)
        runCatching { File(ctx.filesDir, USER_FILE).writeText(w.exportUser()) }
    }

    fun forgetUser(ctx: Context) { p?.clearUser(); runCatching { File(ctx.filesDir, USER_FILE).delete() } }
}
