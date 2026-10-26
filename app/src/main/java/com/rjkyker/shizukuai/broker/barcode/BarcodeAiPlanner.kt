package com.rjkyker.shizukuai.broker.barcode

import org.json.JSONObject
import java.util.Locale

data class BarcodeTransformationProposal(
    val operation: String,
    val argument: String = "",
    val replacement: String = "",
    val rationale: String,
    val confidence: Double,
    val provider: String
)

/**
 * Plug-in boundary for a real model provider. Implementations should use a
 * server-side credential or another secure proxy, not a key embedded in the APK.
 */
interface BarcodeAiProvider {
    val providerName: String
    fun proposeTransformation(input: String, instruction: String): BarcodeTransformationProposal?
}

/**
 * Safe instruction planner. The built-in fallback is deterministic and offline.
 * A configured AI provider may propose a plan, but the plan is validated against
 * the operation allowlist and previewed before any caller can apply it.
 */
object BarcodeAiPlanner {
    private val operations = setOf(
        "prefix", "suffix", "strip_prefix", "strip_suffix", "replace_first",
        "replace_all", "uppercase", "lowercase", "trim", "collapse_whitespace"
    )

    @Volatile
    private var provider: BarcodeAiProvider? = null

    fun installProvider(value: BarcodeAiProvider?) {
        provider = value
    }

    fun providerName(): String = provider?.providerName ?: "OFFLINE_RULE_FALLBACK"

    fun plan(input: String, instruction: String): JSONObject {
        if (input.length > 4096) {
            return JSONObject().put("ok", false).put("error", "input exceeds 4096 characters")
        }
        if (instruction.isBlank() || instruction.length > 1000) {
            return JSONObject().put("ok", false).put("error", "instruction must contain 1..1000 characters")
        }

        val activeProvider = provider
        val proposal = if (activeProvider == null) {
            localPlan(instruction)
        } else {
            try {
                activeProvider.proposeTransformation(input, instruction)
            } catch (e: Exception) {
                return JSONObject().put("ok", false)
                    .put("error", "configured provider failed: " + (e.message ?: "unknown error"))
                    .put("provider", activeProvider.providerName)
            }
        }

        if (proposal == null) {
            return JSONObject().put("ok", false)
                .put("planned", false)
                .put("instruction", instruction)
                .put("provider", activeProvider?.providerName ?: "OFFLINE_RULE_FALLBACK")
                .put("message", "No safe rule matched. Connect a BarcodeAiProvider or choose a supported operation directly.")
                .put("applied", false)
        }
        if (proposal.operation !in operations) {
            return JSONObject().put("ok", false)
                .put("error", "provider proposed an unsupported operation")
                .put("provider", proposal.provider)
        }

        val preview = BarcodeGhostEngine.transform(
            input, proposal.operation, proposal.argument, proposal.replacement
        )
        val p = JSONObject()
            .put("operation", proposal.operation)
            .put("argument", proposal.argument)
            .put("replacement", proposal.replacement)
            .put("rationale", proposal.rationale)
            .put("confidence", proposal.confidence.coerceIn(0.0, 1.0))
            .put("provider", proposal.provider)
        return JSONObject()
            .put("ok", preview.success)
            .put("planned", true)
            .put("instruction", instruction)
            .put("proposal", p)
            .put("preview", preview.toJson())
            .put("preview_output", preview.output ?: JSONObject.NULL)
            .put("applied", false)
            .put("message", "Preview only. The caller must explicitly accept the proposal.")
    }

    private fun localPlan(instruction: String): BarcodeTransformationProposal? {
        val text = instruction.trim()
        val upper = text.uppercase(Locale.ROOT)
        val localProvider = "OFFLINE_RULE_FALLBACK"

        if (upper in setOf("UPPERCASE", "CONVERT TO UPPERCASE", "MAKE UPPERCASE") ||
            Regex("^(CONVERT|MAKE|TURN|CHANGE).*(UPPERCASE|UPPER CASE)$").matches(upper)) {
            return BarcodeTransformationProposal("uppercase", rationale = "Convert letters to uppercase.",
                confidence = 0.99, provider = localProvider)
        }
        if (upper in setOf("LOWERCASE", "CONVERT TO LOWERCASE", "MAKE LOWERCASE") ||
            Regex("^(CONVERT|MAKE|TURN|CHANGE).*(LOWERCASE|LOWER CASE)$").matches(upper)) {
            return BarcodeTransformationProposal("lowercase", rationale = "Convert letters to lowercase.",
                confidence = 0.99, provider = localProvider)
        }
        if (Regex("^(TRIM|REMOVE LEADING AND TRAILING SPACES|TRIM WHITESPACE)$", RegexOption.IGNORE_CASE).matches(text)) {
            return BarcodeTransformationProposal("trim", rationale = "Remove leading and trailing whitespace.",
                confidence = 0.98, provider = localProvider)
        }
        if (Regex("^(COLLAPSE|NORMALIZE|NORMALISE) WHITESPACE$", RegexOption.IGNORE_CASE).matches(text)) {
            return BarcodeTransformationProposal("collapse_whitespace", rationale = "Collapse whitespace runs to one space.",
                confidence = 0.98, provider = localProvider)
        }

        val prefix = Regex("^(?:add|prepend)\\s+(.+?)\\s+to\\s+(?:the\\s+)?(?:beginning|start|front)(?:\\s+of\\s+(?:the\\s+)?(?:value|barcode|text))?\\.?$", RegexOption.IGNORE_CASE)
            .matchEntire(text)
        if (prefix != null) return BarcodeTransformationProposal(
            "prefix", cleanToken(prefix.groupValues[1]), rationale = "Insert the requested prefix at the beginning.",
            confidence = 0.95, provider = localProvider
        )

        val suffix = Regex("^(?:append|add)\\s+(.+?)\\s+to\\s+(?:the\\s+)?(?:end|back)(?:\\s+of\\s+(?:the\\s+)?(?:value|barcode|text))?\\.?$", RegexOption.IGNORE_CASE)
            .matchEntire(text)
        if (suffix != null) return BarcodeTransformationProposal(
            "suffix", cleanToken(suffix.groupValues[1]), rationale = "Append the requested text.",
            confidence = 0.95, provider = localProvider
        )

        val stripPrefix = Regex("^(?:remove|strip)\\s+(?:the\\s+)?prefix\\s+(.+?)\\.?$", RegexOption.IGNORE_CASE)
            .matchEntire(text)
        if (stripPrefix != null) return BarcodeTransformationProposal(
            "strip_prefix", cleanToken(stripPrefix.groupValues[1]),
            rationale = "Remove the specified prefix only when it matches.", confidence = 0.94, provider = localProvider
        )

        val stripSuffix = Regex("^(?:remove|strip)\\s+(?:the\\s+)?suffix\\s+(.+?)\\.?$", RegexOption.IGNORE_CASE)
            .matchEntire(text)
        if (stripSuffix != null) return BarcodeTransformationProposal(
            "strip_suffix", cleanToken(stripSuffix.groupValues[1]),
            rationale = "Remove the specified suffix only when it matches.", confidence = 0.94, provider = localProvider
        )

        val replacement = Regex("^replace\\s+(.+?)\\s+with\\s+(.+?)\\.?$", RegexOption.IGNORE_CASE)
            .matchEntire(text)
        if (replacement != null) return BarcodeTransformationProposal(
            "replace_all", cleanToken(replacement.groupValues[1]), cleanToken(replacement.groupValues[2]),
            rationale = "Replace every literal occurrence; this does not use a regular expression.",
            confidence = 0.90, provider = localProvider
        )
        return null
    }

    private fun cleanToken(value: String): String {
        val trimmed = value.trim()
        return if (trimmed.length >= 2 &&
            ((trimmed.first() == '"' && trimmed.last() == '"') ||
             (trimmed.first() == '\'' && trimmed.last() == '\''))) {
            trimmed.substring(1, trimmed.length - 1)
        } else trimmed
    }
}
