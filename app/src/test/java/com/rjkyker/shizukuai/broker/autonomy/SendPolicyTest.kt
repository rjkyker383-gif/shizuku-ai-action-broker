package com.rjkyker.shizukuai.broker.autonomy

import com.rjkyker.shizukuai.broker.autonomy.delivery.SendPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SendPolicyTest {
    private val now = 1_800_000_000_000L
    private val pkg = "org.thoughtcrime.securesms"
    private fun input(enabled: Boolean = true, apps: Set<String> = setOf(pkg), rec: Set<String> = setOf("$pkg|Mom"),
                      body: String = "Running 10 minutes late, see you soon", global: List<Long> = emptyList(), per: List<Long> = emptyList(),
                      recipient: String = "Mom") =
        SendPolicy.Input(enabled, apps, rec, pkg, recipient, body, now, global, per)

    @Test fun defaultsRejectEverything() {
        val d = SendPolicy.evaluate(input(enabled = false, apps = emptySet(), rec = emptySet()))
        assertEquals(SendPolicy.REJECTED, d.state); assertTrue(d.reason.contains("kill switch"))
        assertEquals(SendPolicy.REJECTED, SendPolicy.evaluate(input(apps = emptySet())).state)
        assertEquals(SendPolicy.REJECTED, SendPolicy.evaluate(input(rec = emptySet())).state)
    }

    @Test fun recipientMustMatchExactly() {
        assertEquals(SendPolicy.REJECTED, SendPolicy.evaluate(input(recipient = "mom")).state)
        assertEquals(SendPolicy.REJECTED, SendPolicy.evaluate(input(recipient = "Mom2")).state)
    }

    @Test fun passingAllGatesStillOnlyQueuesForApproval() {
        val d = SendPolicy.evaluate(input())
        assertEquals(SendPolicy.PENDING_CONFIRM, d.state)
    }

    @Test fun contentRules() {
        for (b in listOf("", "code 483920", "my password is x", "see https://evil.example", "x".repeat(1001)))
            assertEquals(b.take(20), SendPolicy.REJECTED, SendPolicy.evaluate(input(body = b)).state)
    }

    @Test fun rateLimits() {
        assertEquals(SendPolicy.REJECTED, SendPolicy.evaluate(input(global = listOf(now - 10_000))).state)
        val three = listOf(now - 60_000, now - 120_000, now - 180_000)
        assertEquals(SendPolicy.REJECTED, SendPolicy.evaluate(input(per = three, global = three)).state)
        val ten = (1..10).map { now - it * 2 * 3_600_000L }
        assertEquals(SendPolicy.REJECTED, SendPolicy.evaluate(input(per = ten, global = ten)).state)
        val twenty = (1..20).map { now - it * 60 * 60_000L }
        assertEquals(SendPolicy.REJECTED, SendPolicy.evaluate(input(global = twenty)).state)
        assertEquals(SendPolicy.PENDING_CONFIRM, SendPolicy.evaluate(input(global = listOf(now - 40_000))).state)
    }
}
