package com.rjkyker.shizukuai.broker.autonomy

import com.rjkyker.shizukuai.broker.autonomy.audit.AuditLog
import com.rjkyker.shizukuai.broker.autonomy.context.ContextManager
import com.rjkyker.shizukuai.broker.autonomy.eval.Verifier
import com.rjkyker.shizukuai.broker.autonomy.memory.MemoryStore
import com.rjkyker.shizukuai.broker.autonomy.planner.Planner
import com.rjkyker.shizukuai.broker.autonomy.router.RoutePolicy
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ContextPlannerVerifierTest {
    @Test fun contextFitsWindowAndKeepsUserRequest() {
        val mem = (1..200).map { "note $it " + "x".repeat(300) }
        val b = ContextManager.build("sys", "Reply to Sam: ok", mem, listOf("old", "new"), maxNewTokens = 256)
        assertTrue(b.estTokens <= ContextManager.N_CTX - 256)
        assertTrue(b.prompt.endsWith("Reply to Sam: ok"))
        assertTrue(b.droppedMemories > 0)
    }

    @Test fun hugeUserInputIsTruncatedNotOverflowed() {
        val b = ContextManager.build("sys", "y".repeat(20_000), maxNewTokens = 256)
        assertTrue(b.truncatedUser)
        assertTrue(b.estTokens <= ContextManager.N_CTX - 256 + 20)
    }

    @Test fun plannerTemplates() {
        assertEquals("status_report", Planner.plan("status report")!!.template)
        val p = Planner.plan("draft and send to org.thoughtcrime.securesms:Mom: I'm running late")!!
        assertEquals(listOf("llm_draft", "send_request"), p.steps.map { it.kind })
        assertEquals("Mom", p.steps[1].args.getString("recipient"))
        assertEquals("draft", Planner.plan("draft: thank-you note to the team")!!.template)
        assertNull(Planner.plan("delete all my photos"))
        assertNull(Planner.plan(""))
        assertEquals("hello world", Planner.substitute(JSONObject().put("body", "{prev}"), "hello world").getString("body"))
    }

    @Test fun verifierCatchesBadDrafts() {
        assertTrue(Verifier.verifyDraft("Sure — see you at 6!", "reply to Sam").ok)
        assertFalse(Verifier.verifyDraft("", "x").ok)
        assertFalse(Verifier.verifyDraft("Your code is 482913", "reply").ok)
        assertTrue(Verifier.verifyDraft("Booking ref 482913 confirmed", "my booking ref is 482913").ok)
        assertFalse(Verifier.verifyDraft("Click https://phish.example now", "reply").ok)
        assertFalse(Verifier.verifyDraft("As an AI language model I can't", "reply").ok)
        assertFalse(Verifier.verifyDraft("ok ok ok ok ok ok ok ok ok ok ok ok ok ok ok ok ok ok ok ok", "reply").ok)
    }

    @Test fun routerPrefersLocalAndNeverUsesDisabledHosted() {
        val b = listOf(RoutePolicy.Backend("qwen", true, false, "not downloaded"), RoutePolicy.Backend("smol", true, true),
            RoutePolicy.Backend("hosted", false, true))
        val r = RoutePolicy.route(b, llmEnabled = true, hostedEnabled = false, online = true)
        assertEquals(listOf("smol"), r.order)
        assertEquals("not downloaded", r.skipped["qwen"])
        assertEquals(listOf("smol", "hosted"), RoutePolicy.route(b, true, true, true).order)
        assertEquals(listOf("smol"), RoutePolicy.route(b, true, true, online = false).order)
        assertTrue(RoutePolicy.route(b, llmEnabled = false, hostedEnabled = false, online = true).order.isEmpty())
        assertTrue(RoutePolicy.shouldFallback("load_failed")); assertFalse(RoutePolicy.shouldFallback("busy"))
    }

    @Test fun memoryRefusesSecrets() {
        assertNull(MemoryStore.validate("Prefers short replies"))
        assertNotNull(MemoryStore.validate("wifi password is hunter2"))
        assertNotNull(MemoryStore.validate("card 4111 1111 1111 1111"))
        assertNotNull(MemoryStore.validate("otp 123456"))
        assertNotNull(MemoryStore.validate("x".repeat(501)))
    }

    @Test fun auditHashIsCanonicalAndIgnoresConfirm() {
        val a = AuditLog.argsHash(JSONObject().put("b", 2).put("a", 1).put("confirm", true))
        val b = AuditLog.argsHash(JSONObject().put("a", 1).put("b", 2))
        assertEquals(a, b); assertEquals(64, a!!.length)
    }
}
