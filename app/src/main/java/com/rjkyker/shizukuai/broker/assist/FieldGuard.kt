package com.rjkyker.shizukuai.broker.assist

/**
 * Pure check: is this field one we must NEVER read text from or insert into (passwords, OTP/2FA codes, PINs,
 * card numbers, CVC)? Conservative by design: any hit -> blocked.
 */
object FieldGuard {
    // android.text.InputType constants (duplicated so this stays a pure JVM-testable object)
    private const val TYPE_MASK_CLASS = 0x0000000f
    private const val TYPE_MASK_VARIATION = 0x00000ff0
    private const val TYPE_CLASS_TEXT = 0x00000001
    private const val TYPE_CLASS_NUMBER = 0x00000002
    private const val TEXT_VARIATION_PASSWORD = 0x00000080
    private const val TEXT_VARIATION_VISIBLE_PASSWORD = 0x00000090
    private const val TEXT_VARIATION_WEB_PASSWORD = 0x000000e0
    private const val NUMBER_VARIATION_PASSWORD = 0x00000010

    private val SENSITIVE = Regex(
        "\\bpass\\b|pass(word|code|phrase|wd)|pwd|\\bpin\\b|_pin|pin_|otp|one[- ]?time|2fa|mfa|two[- ]?factor|verification[ _-]?code|" +
            "security[ _-]?code|auth[ _-]?code|sms[ _-]?code|cvv|cvc|csc|card[ _-]?(number|num|no)|cc[ _-]?(number|num)|credit[ _-]?card|" +
            "expir|ssn|social[ _-]?security|routing[ _-]?number|account[ _-]?number|smsotp|creditcard",
        RegexOption.IGNORE_CASE,
    )

    data class Field(
        val isPassword: Boolean = false,
        val inputType: Int = 0,
        val viewId: String? = null,
        val hint: String? = null,
        val contentDescription: String? = null,
        val autofillHints: List<String> = emptyList(),
    )

    /** @return null when allowed, else a short reason (never includes field text). */
    fun blockReason(f: Field): String? {
        if (f.isPassword) return "password field"
        val cls = f.inputType and TYPE_MASK_CLASS
        val v = f.inputType and TYPE_MASK_VARIATION
        if (cls == TYPE_CLASS_TEXT && (v == TEXT_VARIATION_PASSWORD || v == TEXT_VARIATION_VISIBLE_PASSWORD || v == TEXT_VARIATION_WEB_PASSWORD))
            return "password input type"
        if (cls == TYPE_CLASS_NUMBER && v == NUMBER_VARIATION_PASSWORD) return "numeric password input type"
        if (f.autofillHints.any { SENSITIVE.containsMatchIn(it) }) return "sensitive autofill hint"
        listOf(f.viewId, f.hint, f.contentDescription).forEach { s -> if (s != null && SENSITIVE.containsMatchIn(s)) return "sensitive field label" }
        return null
    }

    fun allowed(f: Field) = blockReason(f) == null
}
