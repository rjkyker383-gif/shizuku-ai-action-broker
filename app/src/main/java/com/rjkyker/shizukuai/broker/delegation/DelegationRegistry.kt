package com.rjkyker.shizukuai.broker.delegation

import com.rjkyker.shizukuai.broker.owner.OwnerPolicyCatalog
import org.json.JSONArray
import org.json.JSONObject

/**
 * Allow-list + per-app consent for Dhizuku-style delegation. Pure logic over a tiny key/value
 * [Store] so it is JVM-unit-testable. A client is bound to its signing-cert SHA-256: a different
 * signature (re-signed / impostor package) drops back to NONE and must be approved again.
 * Approval is only possible on-device (dashboard); MCP may list / deny / revoke but never approve.
 */
class DelegationRegistry(private val store: Store) {

    interface Store {
        fun get(key: String): String?
        fun put(key: String, value: String?)
        fun keys(): Set<String>
    }

    enum class State { NONE, PENDING, ALLOWED, DENIED }

    data class Client(val pkg: String, val sig: String, val state: State, val requestedAt: Long, val decidedAt: Long) {
        fun toJson(): JSONObject = JSONObject().put("package", pkg).put("sig_sha256", sig.take(16) + "…")
            .put("state", state.name).put("requested_at", requestedAt).put("decided_at", decidedAt)
    }

    private fun load(pkg: String): Client? = store.get(pkg)?.let {
        runCatching {
            val o = JSONObject(it)
            Client(pkg, o.getString("sig"), State.valueOf(o.getString("state")), o.optLong("req"), o.optLong("dec"))
        }.getOrNull()
    }

    private fun save(c: Client) = store.put(
        c.pkg, JSONObject().put("sig", c.sig).put("state", c.state.name).put("req", c.requestedAt).put("dec", c.decidedAt).toString()
    )

    fun state(pkg: String, sig: String): State {
        val c = load(pkg) ?: return State.NONE
        return if (c.sig != sig) State.NONE else c.state
    }

    fun request(pkg: String, sig: String, now: Long): State {
        if (pkg.startsWith(OwnerPolicyCatalog.BROKER_PREFIX)) return State.DENIED
        if (sig.isBlank()) return State.DENIED
        val c = load(pkg)
        if (c != null && c.sig == sig && (c.state == State.ALLOWED || c.state == State.DENIED || c.state == State.PENDING)) return c.state
        save(Client(pkg, sig, State.PENDING, now, 0L))
        return State.PENDING
    }

    /** Dashboard (human, on-device) only. */
    fun approve(pkg: String, now: Long): Boolean = decide(pkg, State.ALLOWED, now)
    fun deny(pkg: String, now: Long): Boolean = decide(pkg, State.DENIED, now)
    fun revoke(pkg: String): Boolean { val had = store.get(pkg) != null; store.put(pkg, null); return had }

    private fun decide(pkg: String, s: State, now: Long): Boolean {
        val c = load(pkg) ?: return false
        save(c.copy(state = s, decidedAt = now)); return true
    }

    fun list(): List<Client> = store.keys().mapNotNull { load(it) }.sortedBy { it.pkg }
    fun pending(): List<Client> = list().filter { it.state == State.PENDING }

    data class Auth(val allowed: Boolean, val reason: String)

    fun authorize(pkg: String, sig: String, op: String): Auth {
        val st = state(pkg, sig)
        if (st != State.ALLOWED) return Auth(false, "client not approved (state=$st); call requestConsent() and approve on-device")
        val d = OwnerPolicyCatalog.decide(op, OwnerPolicyCatalog.Caller.DELEGATED_CLIENT, false)
        return Auth(d.allowed, d.reason)
    }

    fun toJson(): JSONObject {
        val arr = JSONArray(); list().forEach { arr.put(it.toJson()) }
        return JSONObject().put("ok", true).put("clients", arr)
            .put("client_ops", JSONArray(OwnerPolicyCatalog.CLIENT_OPS.sorted()))
            .put("note", "approve only on-device in the broker dashboard")
    }
}
