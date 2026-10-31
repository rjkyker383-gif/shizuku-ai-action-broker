package com.rjkyker.shizukuai.broker.autonomy.registry

import com.rjkyker.shizukuai.broker.action.ActionAllowList
import com.rjkyker.shizukuai.broker.action.RiskLevel

/** Who is asking. Only USER_DEVICE is a human on the phone. */
enum class Actor { USER_DEVICE, MCP_AI, PLANNER, WATCHDOG, SCHEDULER;
    val autonomous: Boolean get() = this == PLANNER || this == WATCHDOG || this == SCHEDULER
}

enum class Tier { READ, LOW_WRITE, MEDIUM, HIGH, LOCKOUT, FORBIDDEN }

data class ToolSpec(val name: String, val tier: Tier, val autonomousOk: Boolean, val summary: String)

data class Verdict(
    val allowed: Boolean,
    val tier: Tier,
    val reason: String,
    /** Allowed only after an on-device human approval (e.g. sends). */
    val needsUser: Boolean = false,
    /** Allowed if the caller re-sends with confirm=true (MCP HIGH). */
    val needsConfirm: Boolean = false,
) {
    val decision: String get() = when {
        allowed && needsUser -> "ALLOW_PENDING_USER"
        allowed -> "ALLOW"
        needsConfirm -> "NEEDS_CONFIRM"
        else -> "DENY"
    }
}

/**
 * U7 tool registry + permission gate (design doc §U7). Single place that decides whether an actor may run a
 * tool. Autonomous actors (planner/scheduler/watchdog) are limited to an explicit registry of read-only and
 * draft-only tools; they can never run shell, policy writes, UI control, or anything HIGH. Nobody but the
 * human on the device can approve a message send or accept an AI memory.
 */
object PermissionEngine {

    /** Autonomy-layer tools (task kinds + 1.9.0 MCP tools). */
    val REGISTRY: Map<String, ToolSpec> = listOf(
        ToolSpec("noop", Tier.READ, true, "test task"),
        ToolSpec("status_probe", Tier.READ, true, "battery/thermal/broker health snapshot"),
        ToolSpec("get_system_status", Tier.READ, true, "broker status"),
        ToolSpec("broker_pause_status", Tier.READ, true, "pause state"),
        ToolSpec("llm_status", Tier.READ, true, "on-device model status"),
        ToolSpec("llm_draft", Tier.READ, true, "generate text only; never sends"),
        ToolSpec("plan", Tier.LOW_WRITE, true, "template planner running registry tools"),
        ToolSpec("memory_propose", Tier.LOW_WRITE, true, "AI memory proposal; inert until accepted on device"),
        ToolSpec("send_request", Tier.LOW_WRITE, true, "queue a message preview; needs per-message device approval"),
        ToolSpec("task_submit", Tier.LOW_WRITE, false, "enqueue autonomy task"),
        ToolSpec("task_status", Tier.READ, false, "read tasks"),
        ToolSpec("task_cancel", Tier.LOW_WRITE, false, "cancel task"),
        ToolSpec("autonomy_audit", Tier.READ, false, "read audit"),
        ToolSpec("autonomy_control", Tier.MEDIUM, false, "flags; enabling autonomy needs confirm"),
        ToolSpec("memory_control", Tier.LOW_WRITE, false, "list/propose/delete memory"),
        ToolSpec("llm_model", Tier.MEDIUM, false, "model preflight/download/unload"),
        // Device-only. Listed so every actor gets an explicit, audited answer.
        ToolSpec("input_focused_read", Tier.MEDIUM, false, "focused field meta; text only with confirm"),
        ToolSpec("input_suggest", Tier.MEDIUM, false, "completion suggestion for the focused field; inserts nothing"),
        ToolSpec("input_focused_insert", Tier.HIGH, false, "insert at cursor; refuses password fields; never submits"),
        ToolSpec("input_assist_config", Tier.MEDIUM, false, "type-anywhere settings; remote needs confirm"),
        ToolSpec("send_approve", Tier.HIGH, false, "approve one pending send (device UI only)"),
        ToolSpec("memory_accept", Tier.MEDIUM, false, "accept an AI memory proposal (device UI only)"),
        ToolSpec("send_allowlist_edit", Tier.HIGH, false, "edit send allowlists (device UI only)"),
        ToolSpec("send_kill_switch", Tier.HIGH, false, "turn sending on/off (device UI only)"),
    ).associateBy { it.name }

    val DEVICE_ONLY = setOf("send_approve", "memory_accept", "send_allowlist_edit", "send_kill_switch")

    private val FORBIDDEN_PATTERNS = listOf("wipe", "factory_reset", "reset_password", "remove_admin",
        "clear_device_owner", "clear_profile_owner", "zebra_license", "mx_license")

    /** Tools an autonomous actor must never touch even if someone adds them to the registry. */
    private val NEVER_AUTONOMOUS = setOf("exec_shell", "priv_service", "owner_policy", "manage_dhizuku_policy",
        "zebra_wifi_config", "a11y_tap", "a11y_text", "a11y_swipe", "a11y_global", "a11y_click_text",
        "input_focused_read", "input_focused_insert", "input_suggest", "input_assist_config",
        "perform_gesture", "hotspot_start", "upstream_mcp_relay", "bridge_relay", "client_delegation")

    fun tierOf(tool: String): Tier {
        val t = tool.lowercase()
        if (FORBIDDEN_PATTERNS.any { t.contains(it) }) return Tier.FORBIDDEN
        REGISTRY[t]?.let { return it.tier }
        return when (ActionAllowList.riskLevelFor(t)) {
            RiskLevel.LOW -> Tier.READ
            RiskLevel.MEDIUM -> Tier.MEDIUM
            RiskLevel.HIGH -> Tier.HIGH
            null -> Tier.FORBIDDEN // unknown tools are denied by default
        }
    }

    fun check(actor: Actor, tool: String, confirmed: Boolean = false): Verdict {
        val t = tool.lowercase()
        val tier = tierOf(t)
        if (tier == Tier.FORBIDDEN) return Verdict(false, tier, "forbidden or unknown tool '$tool'")
        if (t in DEVICE_ONLY) {
            return if (actor == Actor.USER_DEVICE) Verdict(true, tier, "on-device owner action")
            else Verdict(false, tier, "'$tool' is device-only; ${actor.name} can never perform it")
        }
        if (actor.autonomous) {
            val spec = REGISTRY[t]
            if (t in NEVER_AUTONOMOUS || spec == null || !spec.autonomousOk)
                return Verdict(false, tier, "${actor.name} may only run registry tools marked autonomous; '$tool' is not")
            if (tier >= Tier.MEDIUM) return Verdict(false, tier, "autonomous actors are limited to READ/LOW_WRITE")
            if (t == "send_request") return Verdict(true, tier, "queued as preview only", needsUser = true)
            return Verdict(true, tier, "registry tool")
        }
        if (actor == Actor.MCP_AI) {
            if (tier == Tier.LOCKOUT) return Verdict(false, tier, "lockout-tier ops are dashboard-only")
            if (t == "send_request") return Verdict(true, tier, "queued as preview only", needsUser = true)
            if (tier == Tier.HIGH && !confirmed) return Verdict(false, tier, "HIGH: resend with confirm=true", needsConfirm = true)
            return Verdict(true, tier, "mcp tier $tier")
        }
        // USER_DEVICE
        if (t == "send_request") return Verdict(true, tier, "preview; approve on the confirm screen", needsUser = true)
        return Verdict(true, tier, "owner on device")
    }
}
