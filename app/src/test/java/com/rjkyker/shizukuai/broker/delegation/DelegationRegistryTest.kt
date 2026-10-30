package com.rjkyker.shizukuai.broker.delegation

import com.rjkyker.shizukuai.broker.delegation.DelegationRegistry.State
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DelegationRegistryTest {
    private class Mem : DelegationRegistry.Store {
        val m = HashMap<String, String>()
        override fun get(key: String) = m[key]
        override fun put(key: String, value: String?) { if (value == null) m.remove(key) else m[key] = value }
        override fun keys() = m.keys.toSet()
    }

    private val sig = "a".repeat(64)

    @Test fun unknownClientIsNotAuthorized() {
        val r = DelegationRegistry(Mem())
        assertEquals(State.NONE, r.state("com.example", sig))
        assertFalse(r.authorize("com.example", sig, "hide").allowed)
    }

    @Test fun requestThenApproveOnDevice() {
        val r = DelegationRegistry(Mem())
        assertEquals(State.PENDING, r.request("com.example", sig, 1))
        assertFalse(r.authorize("com.example", sig, "hide").allowed)
        assertTrue(r.approve("com.example", 2))
        assertTrue(r.authorize("com.example", sig, "hide").allowed)
        assertTrue(r.authorize("com.example", sig, "probe").allowed)
    }

    @Test fun approvedClientStillCannotRunHighOrLockout() {
        val r = DelegationRegistry(Mem())
        r.request("com.example", sig, 1); r.approve("com.example", 2)
        listOf("camera", "block_uninstall", "reboot", "wipe", "lock_task_packages", "delegate_scopes", "lock_now").forEach {
            assertFalse(it, r.authorize("com.example", sig, it).allowed)
        }
    }

    @Test fun signatureChangeDropsApproval() {
        val r = DelegationRegistry(Mem())
        r.request("com.example", sig, 1); r.approve("com.example", 2)
        assertEquals(State.NONE, r.state("com.example", "b".repeat(64)))
        assertFalse(r.authorize("com.example", "b".repeat(64), "hide").allowed)
    }

    @Test fun deniedStaysDeniedOnReRequest() {
        val r = DelegationRegistry(Mem())
        r.request("com.example", sig, 1); r.deny("com.example", 2)
        assertEquals(State.DENIED, r.request("com.example", sig, 3))
        assertTrue(r.revoke("com.example"))
        assertEquals(State.PENDING, r.request("com.example", sig, 4))
    }

    @Test fun brokerAndUnsignedCannotRequest() {
        val r = DelegationRegistry(Mem())
        assertEquals(State.DENIED, r.request("com.rjkyker.shizukuai.broker", sig, 1))
        assertEquals(State.DENIED, r.request("com.example", "", 1))
    }

    @Test fun listAndJsonDoNotLeakFullSignature() {
        val r = DelegationRegistry(Mem())
        r.request("com.b", sig, 1); r.request("com.a", sig, 1)
        assertEquals(listOf("com.a", "com.b"), r.list().map { it.pkg })
        assertEquals(2, r.pending().size)
        assertFalse(r.toJson().toString().contains(sig))
    }
}
