package com.rjkyker.shizukuai.broker.barcode

import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

data class BarcodeInspection(
    val originalValue: String,
    val symbology: String,
    val source: String,
    val characterCount: Int,
    val codePointCount: Int,
    val utf8ByteCount: Int,
    val rawBytesProvided: Boolean,
    val rawByteHex: String,
    val rawBytesTruncated: Boolean,
    val controlCharacters: List<String>,
    val hasGs1GroupSeparator: Boolean,
    val looksLikeGtin: Boolean,
    val gtinCheckDigitValid: Boolean?
) {
    fun toJson(): JSONObject = JSONObject()
        .put("original_value", originalValue)
        .put("symbology", symbology)
        .put("source", source)
        .put("character_count", characterCount)
        .put("code_point_count", codePointCount)
        .put("utf8_byte_count", utf8ByteCount)
        .put("raw_bytes_provided_by_scanner", rawBytesProvided)
        .put("raw_byte_hex_preview", rawByteHex)
        .put("raw_bytes_truncated", rawBytesTruncated)
        .put("control_characters", JSONArray(controlCharacters))
        .put("has_gs1_group_separator", hasGs1GroupSeparator)
        .put("looks_like_gtin", looksLikeGtin)
        .put("gtin_check_digit_valid", gtinCheckDigitValid ?: JSONObject.NULL)
}

data class BarcodeTransformResult(
    val success: Boolean,
    val input: String,
    val operation: String,
    val output: String?,
    val error: String? = null
) {
    fun toJson(): JSONObject = JSONObject()
        .put("ok", success)
        .put("input", input)
        .put("operation", operation)
        .put("output", output ?: JSONObject.NULL)
        .put("error", error ?: JSONObject.NULL)
        .put("changed", success && output != input)
        .put("persistent_change", false)
}

data class CustomBarcodeCommand(val type: String, val id: String, val version: Int) {
    fun toJson(): JSONObject = JSONObject()
        .put("type", type)
        .put("id", id)
        .put("version", version)
        .put("source", "BGX")
}

sealed class CommandParseResult {
    object NotCommand : CommandParseResult()
    data class Invalid(val reason: String) : CommandParseResult()
    data class Valid(val command: CustomBarcodeCommand) : CommandParseResult()
}

/** Local-first barcode utilities. BGX values are data, never scripts or shell commands. */
object BarcodeGhostEngine {
    private const val MAX_TRANSFORM_CHARS = 4096
    private const val MAX_COMMAND_CHARS = 512
    private const val MAX_HEX_PREVIEW_BYTES = 1024
    private const val MAX_CONTROL_PREVIEW = 64
    private const val GS1_SEPARATOR = '\u001D'
    private const val HEX = "0123456789ABCDEF"

    private val supportedGames = setOf(
        "NEON_MAZE", "BARCODE_MEMORY", "GHOST_CIPHER", "SCAN_SEQUENCE",
        "TREASURE_HUNT", "DAILY_PUZZLE", "COLLECTIBLE_DISCOVERY", "REACTION_CHALLENGE"
    )
    private val supportedEasterEggs = setOf(
        "GHOST_MODE", "SPIN_LOGO", "SECRET_MESSAGE", "TINY_FIREWORKS"
    )

    fun inspect(
        value: String,
        symbology: String = "",
        source: String = "",
        rawBytes: ByteArray? = null
    ): BarcodeInspection {
        val bytes = rawBytes ?: value.toByteArray(Charsets.UTF_8)
        val previewSize = minOf(bytes.size, MAX_HEX_PREVIEW_BYTES)
        val hex = buildString(previewSize * 2) {
            for (i in 0 until previewSize) {
                val b = bytes[i].toInt() and 0xFF
                append(HEX[b ushr 4])
                append(HEX[b and 0x0F])
            }
        }
        val controls = value.mapIndexedNotNull { index, char ->
            if (char.isISOControl() && char != GS1_SEPARATOR) {
                "index=$index " + String.format(Locale.ROOT, "U+%04X", char.code)
            } else null
        }.take(MAX_CONTROL_PREVIEW)
        return BarcodeInspection(
            originalValue = value,
            symbology = symbology,
            source = source,
            characterCount = value.length,
            codePointCount = value.codePointCount(0, value.length),
            utf8ByteCount = bytes.size,
            rawBytesProvided = rawBytes != null,
            rawByteHex = hex,
            rawBytesTruncated = bytes.size > MAX_HEX_PREVIEW_BYTES,
            controlCharacters = controls,
            hasGs1GroupSeparator = value.contains(GS1_SEPARATOR),
            looksLikeGtin = value.length in setOf(8, 12, 13, 14) && value.all(Char::isDigit),
            gtinCheckDigitValid = checkGtinDigit(value)
        )
    }

    /** Literal, bounded operations only; no user-supplied regex or code is evaluated. */
    fun transform(
        input: String,
        operationName: String,
        argument: String = "",
        replacement: String = ""
    ): BarcodeTransformResult {
        val operation = operationName.trim().lowercase(Locale.ROOT)
        if (input.length > MAX_TRANSFORM_CHARS) return failed(input, operation, "input exceeds 4096 characters")
        if (argument.length > MAX_TRANSFORM_CHARS || replacement.length > MAX_TRANSFORM_CHARS) {
            return failed(input, operation, "transformation argument is too long")
        }
        val output = try {
            when (operation) {
                "prefix" -> argument + input
                "suffix" -> input + argument
                "strip_prefix" -> {
                    if (argument.isEmpty() || !input.startsWith(argument)) {
                        return failed(input, operation, "input does not start with the requested prefix")
                    }
                    input.removePrefix(argument)
                }
                "strip_suffix" -> {
                    if (argument.isEmpty() || !input.endsWith(argument)) {
                        return failed(input, operation, "input does not end with the requested suffix")
                    }
                    input.removeSuffix(argument)
                }
                "replace_first" -> {
                    if (argument.isEmpty()) return failed(input, operation, "search text must not be empty")
                    input.replaceFirst(argument, replacement)
                }
                "replace_all" -> {
                    if (argument.isEmpty()) return failed(input, operation, "search text must not be empty")
                    input.replace(argument, replacement)
                }
                "uppercase" -> input.uppercase(Locale.ROOT)
                "lowercase" -> input.lowercase(Locale.ROOT)
                "trim" -> input.trim()
                "collapse_whitespace" -> input.trim().replace(Regex("\\s+"), " ")
                else -> return failed(input, operation, "unsupported operation")
            }
        } catch (e: Exception) {
            return failed(input, operation, e.message ?: "transformation failed")
        }
        if (output.length > MAX_TRANSFORM_CHARS) return failed(input, operation, "output exceeds 4096 characters")
        return BarcodeTransformResult(true, input, operation, output)
    }

    fun parseCommand(raw: String): CommandParseResult {
        if (!raw.startsWith("BGX:")) return CommandParseResult.NotCommand
        if (raw.length > MAX_COMMAND_CHARS) return CommandParseResult.Invalid("command exceeds 512 characters")
        if (raw.any { it.isISOControl() }) return CommandParseResult.Invalid("control characters are not allowed")
        val pieces = raw.split('|')
        if (pieces.firstOrNull() != "BGX:1") return CommandParseResult.Invalid("unsupported BGX protocol version")
        val fields = linkedMapOf<String, String>()
        for (piece in pieces.drop(1)) {
            val separator = piece.indexOf('=')
            if (separator <= 0 || separator == piece.lastIndex) return CommandParseResult.Invalid("malformed field")
            val key = piece.substring(0, separator)
            val value = piece.substring(separator + 1)
            if (key !in setOf("TYPE", "ID", "VERSION")) return CommandParseResult.Invalid("unknown field: $key")
            if (fields.put(key, value) != null) return CommandParseResult.Invalid("duplicate field: $key")
        }
        if (fields.keys != setOf("TYPE", "ID", "VERSION")) {
            return CommandParseResult.Invalid("TYPE, ID, and VERSION are required")
        }
        val type = fields["TYPE"] ?: return CommandParseResult.Invalid("TYPE required")
        val id = fields["ID"] ?: return CommandParseResult.Invalid("ID required")
        val version = fields["VERSION"]?.toIntOrNull()
            ?: return CommandParseResult.Invalid("VERSION must be an integer")
        if (version != 1) return CommandParseResult.Invalid("unsupported command version")
        if (!id.matches(Regex("[A-Z0-9_]{1,48}"))) {
            return CommandParseResult.Invalid("ID must contain only A-Z, 0-9, and underscore")
        }
        val allowed = when (type) {
            "GAME" -> supportedGames
            "EASTER_EGG" -> supportedEasterEggs
            else -> return CommandParseResult.Invalid("unsupported TYPE")
        }
        if (id !in allowed) return CommandParseResult.Invalid("ID is not registered")
        return CommandParseResult.Valid(CustomBarcodeCommand(type, id, version))
    }

    private fun failed(input: String, operation: String, error: String) =
        BarcodeTransformResult(false, input, operation, null, error)

    private fun checkGtinDigit(value: String): Boolean? {
        if (value.length !in setOf(8, 12, 13, 14) || !value.all(Char::isDigit)) return null
        val body = value.dropLast(1)
        val sum = body.reversed().mapIndexed { index, char ->
            char.digitToInt() * if (index % 2 == 0) 3 else 1
        }.sum()
        return value.last().digitToInt() == (10 - sum % 10) % 10
    }
}
