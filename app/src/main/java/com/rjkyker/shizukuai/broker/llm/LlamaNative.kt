package com.rjkyker.shizukuai.broker.llm

/** JNI surface of libbroker_llm.so (llama.cpp, CPU). Only used inside the ":llm" process. */
object LlamaNative {
    fun interface TokenCallback {
        /** Return false to stop generation. */
        fun onToken(piece: String): Boolean
    }

    @Volatile private var loaded: Throwable? = null
    @Volatile private var tried = false

    @Synchronized
    fun ensureLoaded(): Boolean {
        if (!tried) {
            tried = true
            loaded = runCatching { System.loadLibrary("broker_llm") }.exceptionOrNull()
        }
        return loaded == null
    }

    fun loadError(): String? = loaded?.let { "${it.javaClass.simpleName}: ${it.message}" }

    @JvmStatic external fun nativeSystemInfo(): String
    @JvmStatic external fun nativeLoad(path: String, nCtx: Int, nThreads: Int, nThreadsBatch: Int): Long
    @JvmStatic external fun nativeApplyTemplate(handle: Long, system: String, user: String): String
    @JvmStatic external fun nativeCancel(handle: Long)
    @JvmStatic external fun nativeGenerate(
        handle: Long, prompt: String, maxNew: Int, temp: Float, topP: Float, seed: Int,
        deadlineMs: Long, cb: TokenCallback?
    ): String
    @JvmStatic external fun nativeFree(handle: Long)
}
