package com.rjkyker.shizukuai.broker.datawedge

import org.json.JSONArray
import org.json.JSONObject
import java.nio.charset.Charset

/**
 * Offline barcode/QR payload parsers for the SCAN OUTPUT panel + scan_history MCP tool.
 * Label anything not determinable as "unknown". Never invents fields that aren't encoded.
 * AAMVA / driver-license fields are parsed locally only — never uploaded.
 */
object ScanParsers {

    private val CTRL = mapOf(
        0 to "<NUL>", 1 to "<SOH>", 2 to "<STX>", 3 to "<ETX>", 4 to "<EOT>",
        5 to "<ENQ>", 6 to "<ACK>", 7 to "<BEL>", 8 to "<BS>", 9 to "<HT>",
        10 to "<LF>", 11 to "<VT>", 12 to "<FF>", 13 to "<CR>", 14 to "<SO>",
        15 to "<SI>", 16 to "<DLE>", 17 to "<DC1>", 18 to "<DC2>", 19 to "<DC3>",
        20 to "<DC4>", 21 to "<NAK>", 22 to "<SYN>", 23 to "<ETB>", 24 to "<CAN>",
        25 to "<EM>", 26 to "<SUB>", 27 to "<ESC>", 28 to "<FS>", 29 to "<GS>",
        30 to "<RS>", 31 to "<US>", 127 to "<DEL>"
    )

    /** Render non-printables as <GS> / \xNN; printable ASCII/Latin kept. */
    fun renderVisible(raw: String): String {
        val sb = StringBuilder()
        for (ch in raw) {
            val c = ch.code
            when {
                CTRL.containsKey(c) -> sb.append(CTRL[c])
                c in 32..126 -> sb.append(ch)
                c in 160..255 -> sb.append(ch) // Latin-1 printable
                else -> sb.append("\\x%02X".format(c and 0xFF))
            }
        }
        return sb.toString()
    }

    fun renderVisibleBytes(bytes: ByteArray): String {
        val sb = StringBuilder()
        for (b in bytes) {
            val c = b.toInt() and 0xFF
            when {
                CTRL.containsKey(c) -> sb.append(CTRL[c])
                c in 32..126 -> sb.append(c.toChar())
                else -> sb.append("\\x%02X".format(c))
            }
        }
        return sb.toString()
    }

    fun hexDump(bytes: ByteArray, width: Int = 16): String {
        if (bytes.isEmpty()) return ""
        val sb = StringBuilder()
        var i = 0
        while (i < bytes.size) {
            val end = minOf(i + width, bytes.size)
            sb.append("%04X: ".format(i))
            for (j in i until end) sb.append("%02X ".format(bytes[j].toInt() and 0xFF))
            // pad
            repeat(width - (end - i)) { sb.append("   ") }
            sb.append(" |")
            for (j in i until end) {
                val c = bytes[j].toInt() and 0xFF
                sb.append(if (c in 32..126) c.toChar() else '.')
            }
            sb.append("|\n")
            i = end
        }
        return sb.toString().trimEnd()
    }

    fun detectCharset(bytes: ByteArray): String {
        if (bytes.isEmpty()) return "empty"
        // UTF-8 validity
        try {
            val dec = Charset.forName("UTF-8").newDecoder()
            dec.onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
            dec.onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
            dec.decode(java.nio.ByteBuffer.wrap(bytes))
            // if any high bytes, call it utf-8; if all ASCII, ascii
            return if (bytes.any { it.toInt() and 0x80 != 0 }) "UTF-8" else "ASCII"
        } catch (_: Exception) {
        }
        // ISO-8859-1 always works for single bytes — only claim it if high bytes present and not utf-8
        if (bytes.any { it.toInt() and 0x80 != 0 }) return "ISO-8859-1"
        return "binary"
    }

    fun gtinCheckDigitOk(gtin: String): Boolean {
        val d = gtin.filter { it.isDigit() }
        if (d.length !in listOf(8, 12, 13, 14)) return false
        var sum = 0
        val rev = d.dropLast(1).reversed()
        for ((idx, ch) in rev.withIndex()) {
            val n = ch - '0'
            sum += if (idx % 2 == 0) n * 3 else n
        }
        val check = (10 - (sum % 10)) % 10
        return check == (d.last() - '0')
    }

    /** GS1 element-string parser. Handles FNC1 / GS (0x1D) separators. */
    fun parseGs1(data: String): JSONObject? {
        // Normalize FNC1 representations
        var s = data
            .replace("\u001D", "\u001D")
            .replace("{FNC1}", "\u001D")
            .replace("<GS>", "\u001D")
        // Must look like GS1: leading AI digits, or starts with ]C1 / ]e0 / ]d2 AIM
        val aim = Regex("^\\][A-Za-z0-9]{2}").find(s)
        var aimId: String? = null
        if (aim != null) {
            aimId = aim.value
            s = s.substring(aim.value.length)
        }
        // Heuristic: starts with known fixed-length AI
        if (s.isEmpty() || !s[0].isDigit()) return null
        val ais = JSONArray()
        var i = 0
        val knownFixed = mapOf(
            "00" to 18, "01" to 14, "02" to 14, "10" to -1, "11" to 6, "12" to 6, "13" to 6,
            "15" to 6, "16" to 6, "17" to 6, "20" to 2, "21" to -1, "22" to -1,
            "240" to -1, "241" to -1, "250" to -1, "251" to -1,
            "30" to -1, "37" to -1,
            "310" to 6, "311" to 6, "312" to 6, "313" to 6, "314" to 6, "315" to 6, "316" to 6,
            "320" to 6, "321" to 6, "322" to 6, "330" to 6, "340" to 6,
            "392" to -1, "393" to -1, "400" to -1, "410" to 13, "414" to 13, "420" to -1, "421" to -1
        )
        fun matchAi(at: Int): Pair<String, Int>? {
            for (len in listOf(4, 3, 2)) {
                if (at + len > s.length) continue
                val ai = s.substring(at, at + len)
                // 310n..316n / 320n etc — AI is 3 digits + decimal indicator
                if (len == 4 && ai.take(3) in listOf("310","311","312","313","314","315","316","320","321","322","330","340","392","393")) {
                    return ai to (if (ai.take(3) in listOf("392","393")) -1 else 6)
                }
                if (ai in knownFixed) return ai to knownFixed[ai]!!
                if (len == 3 && ai.take(3) in knownFixed) return ai to knownFixed[ai]!!
            }
            // try 2-digit unknown
            if (at + 2 <= s.length && s[at].isDigit() && s[at + 1].isDigit()) {
                return s.substring(at, at + 2) to -1
            }
            return null
        }
        var guard = 0
        while (i < s.length && guard++ < 64) {
            if (s[i] == '\u001D') { i++; continue }
            val m = matchAi(i) ?: break
            val (ai, fixed) = m
            i += ai.length
            val value: String
            if (fixed > 0) {
                if (i + fixed > s.length) break
                value = s.substring(i, i + fixed)
                i += fixed
            } else {
                val nextGs = s.indexOf('\u001D', i)
                if (nextGs < 0) {
                    value = s.substring(i)
                    i = s.length
                } else {
                    value = s.substring(i, nextGs)
                    i = nextGs + 1
                }
            }
            val entry = JSONObject().put("ai", ai).put("value", value)
            when (ai) {
                "01", "02" -> {
                    entry.put("meaning", if (ai == "01") "GTIN" else "CONTENT")
                    entry.put("check_digit_ok", gtinCheckDigitOk(value))
                }
                "10" -> entry.put("meaning", "BATCH/LOT")
                "17", "15", "11", "12", "13", "16" -> {
                    entry.put("meaning", when (ai) {
                        "17" -> "EXPIRY"; "15" -> "BEST_BEFORE"; "11" -> "PROD_DATE"
                        "12" -> "DUE_DATE"; "13" -> "PACK_DATE"; else -> "SELL_BY"
                    })
                    if (value.length == 6 && value.all { it.isDigit() }) {
                        entry.put("date_yy_mm_dd", value)
                    }
                }
                "21" -> entry.put("meaning", "SERIAL")
                "00" -> entry.put("meaning", "SSCC")
                "30" -> entry.put("meaning", "VAR_COUNT")
                "37" -> entry.put("meaning", "COUNT")
                else -> if (!entry.has("meaning")) entry.put("meaning", "unknown")
            }
            // weights 310n..
            if (ai.length == 4 && ai.take(3) in listOf("310","311","312","313","314","315","316","320","321","322","330","340")) {
                entry.put("meaning", "MEASURE")
                entry.put("decimal_indicator", ai[3].toString())
            }
            ais.put(entry)
        }
        if (ais.length() == 0) return null
        return JSONObject()
            .put("format", "GS1")
            .put("aim_id", aimId ?: JSONObject.NULL)
            .put("application_identifiers", ais)
    }

    /** ISO/IEC 15434 envelope (]>, rs, format 05/06/DD, gs, ... eot). */
    fun parseIso15434(data: String): JSONObject? {
        if (!data.contains("[)>") && !data.startsWith("]>", ignoreCase = false) &&
            !data.contains("\u001E") && !data.contains("<RS>")
        ) {
            // also accept literal markers
            if (!data.contains("[)>")) return null
        }
        var s = data.replace("<RS>", "\u001E").replace("<GS>", "\u001D").replace("<EOT>", "\u0004")
        val start = s.indexOf("[)>")
        if (start < 0) return null
        s = s.substring(start)
        // [)><RS>format<GS>...
        val parts = s.split("\u001E")
        if (parts.size < 2) return null
        // parts[0] = "[)>" ; parts[1] starts with format code
        val body = parts.drop(1).joinToString("\u001E")
        val format = body.takeWhile { it.isLetterOrDigit() }.take(2).ifEmpty {
            Regex("""^(05|06|12|DD|PA)""").find(body)?.value ?: "unknown"
        }
        val fields = JSONArray()
        for (seg in body.split("\u001D", "\u001E")) {
            val t = seg.trim('\u0004', ' ')
            if (t.isEmpty() || t == "[)>") continue
            fields.put(t)
        }
        val meaning = when (format) {
            "05" -> "GS1 (format 05)"
            "06" -> "ASC MH10 / DoD (format 06)"
            "12" -> "Text (format 12)"
            "DD" -> "UDI / FDA"
            else -> "unknown"
        }
        return JSONObject()
            .put("format", "ISO/IEC 15434")
            .put("envelope_format", format)
            .put("meaning", meaning)
            .put("fields", fields)
    }

    fun parseHibc(data: String): JSONObject? {
        if (!data.startsWith("+") || data.length < 5) return null
        // HIBC: + / primary / secondary
        return JSONObject()
            .put("format", "HIBC")
            .put("raw", data)
            .put("note", "HIBC/UDI candidate (leading '+'); full field split depends on check char")
            .put("body", data.drop(1))
    }

    /** AAMVA PDF417 — local only. */
    fun parseAamva(data: String): JSONObject? {
        if (!data.contains("ANSI ") && !data.startsWith("@\n") && !data.contains("\nDAQ") &&
            !data.contains("DCS") && !("AAMVA" in data)
        ) {
            // typical header @\n\u001e\rANSI
            if (!(data.startsWith("@") && data.contains("ANSI"))) return null
        }
        if (!data.contains("ANSI") && !Regex("\\bD[A-Z]{2}").containsMatchIn(data)) return null
        val fields = JSONObject()
        val re = Regex("""\b(D[A-Z]{2})([^\n\r]*)""")
        for (m in re.findAll(data)) {
            val code = m.groupValues[1]
            val value = m.groupValues[2].trim()
            val label = AAMVA_CODES[code] ?: "unknown"
            fields.put(code, JSONObject().put("label", label).put("value", value))
        }
        if (fields.length() == 0) return null
        return JSONObject()
            .put("format", "AAMVA")
            .put("note", "Parsed locally only — never uploaded")
            .put("fields", fields)
    }

    private val AAMVA_CODES = mapOf(
        "DAQ" to "customer_id", "DCS" to "last_name", "DAC" to "first_name", "DAD" to "middle_name",
        "DBB" to "dob", "DBA" to "expiry", "DBD" to "issue_date", "DBC" to "sex",
        "DAY" to "eye_color", "DAU" to "height", "DAG" to "address", "DAI" to "city",
        "DAJ" to "state", "DAK" to "postal_code", "DCF" to "doc_discriminator",
        "DCG" to "country", "DCK" to "inventory_control", "DCA" to "vehicle_class",
        "DCB" to "restrictions", "DCD" to "endorsements"
    )

    fun parseWifiQr(data: String): JSONObject? {
        if (!data.startsWith("WIFI:", ignoreCase = true)) return null
        val fields = JSONObject()
        // T:WPA;S:ssid;P:pass;H:true;;
        val re = Regex("""([TSPH]):([^;]*)""")
        for (m in re.findAll(data)) {
            val k = when (m.groupValues[1]) {
                "T" -> "security"; "S" -> "ssid"; "P" -> "password"; "H" -> "hidden"; else -> m.groupValues[1]
            }
            fields.put(k, m.groupValues[2])
        }
        return JSONObject().put("format", "WIFI").put("fields", fields)
            .put("note", "Display only — never auto-connect")
    }

    fun parseVcard(data: String): JSONObject? {
        val upper = data.uppercase()
        if (!upper.contains("BEGIN:VCARD") && !upper.startsWith("MECARD:")) return null
        if (upper.startsWith("MECARD:")) {
            val fields = JSONObject()
            for (part in data.removePrefix("MECARD:").trimEnd(';').split(';')) {
                val idx = part.indexOf(':')
                if (idx > 0) fields.put(part.take(idx), part.substring(idx + 1))
            }
            return JSONObject().put("format", "MECARD").put("fields", fields)
        }
        val fields = JSONObject()
        for (line in data.lines()) {
            val idx = line.indexOf(':')
            if (idx > 0) {
                val key = line.take(idx).substringBefore(';')
                fields.put(key, line.substring(idx + 1))
            }
        }
        return JSONObject().put("format", "vCard").put("fields", fields)
    }

    fun parseUrl(data: String): JSONObject? {
        val t = data.trim()
        if (Regex("""^https?://""", RegexOption.IGNORE_CASE).containsMatchIn(t) ||
            t.startsWith("URL:", ignoreCase = true)
        ) {
            return JSONObject().put("format", "URL").put("url", t.removePrefix("URL:").removePrefix("url:"))
                .put("note", "Display only — never auto-open")
        }
        return null
    }

    fun parseGeoMailtoTel(data: String): JSONObject? {
        val t = data.trim()
        return when {
            t.startsWith("geo:", ignoreCase = true) ->
                JSONObject().put("format", "geo").put("value", t).put("note", "Display only")
            t.startsWith("mailto:", ignoreCase = true) ->
                JSONObject().put("format", "mailto").put("value", t).put("note", "Display only — never send")
            t.startsWith("tel:", ignoreCase = true) || t.startsWith("sms:", ignoreCase = true) ||
                t.startsWith("smsto:", ignoreCase = true) ->
                JSONObject().put("format", "tel/sms").put("value", t).put("note", "Display only — never call/send")
            else -> null
        }
    }

    /** EMVCo merchant QR (payload format indicator "01" / ID "000201"). */
    fun parseEmvco(data: String): JSONObject? {
        if (!data.startsWith("000201")) return null
        val tlvs = JSONArray()
        var i = 0
        var guard = 0
        while (i + 4 <= data.length && guard++ < 128) {
            val id = data.substring(i, i + 2)
            val len = data.substring(i + 2, i + 4).toIntOrNull() ?: break
            if (i + 4 + len > data.length) break
            val value = data.substring(i + 4, i + 4 + len)
            tlvs.put(JSONObject().put("id", id).put("len", len).put("value", value))
            i += 4 + len
        }
        if (tlvs.length() == 0) return null
        return JSONObject().put("format", "EMVCo").put("tlv", tlvs)
    }

    fun parseJsonOrKv(data: String): JSONObject? {
        val t = data.trim()
        if (t.startsWith("{") && t.endsWith("}")) {
            return try {
                JSONObject().put("format", "JSON").put("object", JSONObject(t))
            } catch (_: Exception) { null }
        }
        if (t.startsWith("[") && t.endsWith("]")) {
            return try {
                JSONObject().put("format", "JSON").put("array", JSONArray(t))
            } catch (_: Exception) { null }
        }
        // key=value pairs
        if ('=' in t && !t.contains(' ') && t.split('&', ';').size >= 1) {
            val seps = if ('&' in t) '&' else if (';' in t && '=' in t) ';' else null
            if (seps != null || (t.count { it == '=' } >= 1 && t.length < 500)) {
                val fields = JSONObject()
                val parts = when {
                    '&' in t -> t.split('&')
                    ';' in t -> t.split(';')
                    else -> listOf(t)
                }
                var ok = 0
                for (p in parts) {
                    val idx = p.indexOf('=')
                    if (idx > 0) { fields.put(p.take(idx), p.substring(idx + 1)); ok++ }
                }
                if (ok >= 1) return JSONObject().put("format", "key=value").put("fields", fields)
            }
        }
        return null
    }

    fun noteStructuredAppendOrEci(labelType: String, data: String): String? {
        val notes = mutableListOf<String>()
        if (labelType.contains("STRUCTURED", ignoreCase = true) || data.contains("\\0000")) {
            notes += "structured-append possible"
        }
        if (data.contains("\\0000") || labelType.contains("ECI", ignoreCase = true)) {
            notes += "ECI present/possible"
        }
        // Code128 checksum is internal — we cannot verify without full decode path
        return notes.takeIf { it.isNotEmpty() }?.joinToString("; ")
    }

    /**
     * Build full enriched scan record from DataWedge extras.
     * [decodeSegments] = list of raw byte arrays from decode_data extras.
     */
    fun enrich(
        dataString: String,
        labelType: String,
        source: String,
        decodeSegments: List<ByteArray>,
        aimId: String?,
        decodedMode: String?,
        ts: Long = System.currentTimeMillis(),
    ): JSONObject {
        val bytes: ByteArray = when {
            decodeSegments.isNotEmpty() -> decodeSegments.first()
            else -> dataString.toByteArray(Charsets.ISO_8859_1)
        }
        val charset = detectCharset(bytes)
        val decodedText = try {
            when (charset) {
                "UTF-8", "ASCII" -> String(bytes, Charsets.UTF_8)
                else -> dataString.ifEmpty { String(bytes, Charsets.ISO_8859_1) }
            }
        } catch (_: Exception) { dataString }

        val segments = JSONArray()
        val segs = if (decodeSegments.isEmpty()) listOf(bytes) else decodeSegments
        for ((idx, seg) in segs.withIndex()) {
            segments.put(
                JSONObject()
                    .put("index", idx)
                    .put("byte_length", seg.size)
                    .put("hex_dump", hexDump(seg))
                    .put("visible", renderVisibleBytes(seg))
                    .put("base64", java.util.Base64.getEncoder().encodeToString(seg))
                    .put("charset", detectCharset(seg))
            )
        }

        val parsers = JSONArray()
        val candidates = listOf(
            parseIso15434(decodedText), parseGs1(decodedText), parseHibc(decodedText),
            parseAamva(decodedText), parseWifiQr(decodedText), parseVcard(decodedText),
            parseUrl(decodedText), parseGeoMailtoTel(decodedText), parseEmvco(decodedText),
            parseJsonOrKv(decodedText)
        )
        for (p in candidates) if (p != null) parsers.put(p)
        if (parsers.length() == 0) {
            parsers.put(JSONObject().put("format", "unknown").put("note", "no known structure detected"))
        }

        val out = JSONObject()
            .put("type", "scan")
            .put("ts", ts)
            .put("label_type", labelType.ifEmpty { "unknown" })
            .put("aim_id", aimId ?: JSONObject.NULL)
            .put("source", source.ifEmpty { "unknown" })
            .put("decoded_mode", decodedMode ?: JSONObject.NULL)
            .put("length", decodedText.length)
            .put("byte_length", bytes.size)
            .put("data_string", dataString)
            .put("visible_text", renderVisible(decodedText))
            .put("charset", charset)
            .put("base64", java.util.Base64.getEncoder().encodeToString(bytes))
            .put("decode_data", segments)
            .put("parsers", parsers)
        noteStructuredAppendOrEci(labelType, decodedText)?.let { out.put("structure_note", it) }
        return out
    }
}
