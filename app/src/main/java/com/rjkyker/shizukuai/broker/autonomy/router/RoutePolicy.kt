package com.rjkyker.shizukuai.broker.autonomy.router

/**
 * U5 model routing (pure). Order: best local model -> smaller local model -> hosted (opt-in, off by default).
 * Offline is the normal case: hosted is never required and never chosen when disabled.
 */
object RoutePolicy {
    data class Backend(val id: String, val local: Boolean, val available: Boolean, val why: String = "")

    data class Route(val order: List<String>, val skipped: Map<String, String>)

    /** Errors that mean "try the next backend" rather than "give up". */
    val FALLBACK_CODES = setOf("preflight", "load_failed", "model_missing", "thermal_local", "oom")

    fun route(backends: List<Backend>, llmEnabled: Boolean, hostedEnabled: Boolean, online: Boolean): Route {
        val skipped = linkedMapOf<String, String>()
        val order = mutableListOf<String>()
        for (b in backends) {
            when {
                b.local && !llmEnabled -> skipped[b.id] = "llm_enabled=false"
                !b.local && !hostedEnabled -> skipped[b.id] = "hosted_enabled=false"
                !b.local && !online -> skipped[b.id] = "offline"
                !b.available -> skipped[b.id] = b.why.ifBlank { "unavailable" }
                else -> order += b.id
            }
        }
        // locals always before hosted
        val sorted = order.sortedBy { id -> if (backends.first { it.id == id }.local) 0 else 1 }
        return Route(sorted, skipped)
    }

    fun shouldFallback(code: String?): Boolean = code != null && code in FALLBACK_CODES
}
