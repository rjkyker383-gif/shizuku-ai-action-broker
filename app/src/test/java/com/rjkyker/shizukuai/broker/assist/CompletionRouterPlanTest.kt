package com.rjkyker.shizukuai.broker.assist

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CompletionRouterPlanTest {
    @Test fun no_phone_model_no_remote_goes_to_predictor() {
        val (order, why) = CompletionRouter.plan(localPresent = false, llmEnabled = true, thermalStop = false,
            remoteEnabled = false, tokenSet = false, predictorLoaded = true)
        assertEquals(listOf("predictor"), order)
        assertTrue(why.any { it.contains("no on-phone model") }); assertTrue(why.any { it.contains("opt-in") })
    }
    @Test fun order_local_box_predictor() {
        val (order, _) = CompletionRouter.plan(true, true, false, true, true, true)
        assertEquals(listOf("local_llm", "box", "predictor"), order)
    }
    @Test fun remote_needs_token() {
        val (order, why) = CompletionRouter.plan(false, true, false, true, false, true)
        assertEquals(listOf("predictor"), order); assertTrue(why.any { it.contains("token") })
    }
    @Test fun thermal_stop_skips_local_only() {
        val (order, _) = CompletionRouter.plan(true, true, true, true, true, true)
        assertEquals(listOf("box", "predictor"), order)
    }
    @Test fun forced_source() {
        assertEquals(listOf("box"), CompletionRouter.plan(true, true, false, true, true, true, force = "box").first)
        assertEquals(emptyList<String>(), CompletionRouter.plan(false, true, false, false, false, false, force = "predictor").first)
    }
}
