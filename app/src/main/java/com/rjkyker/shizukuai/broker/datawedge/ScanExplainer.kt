package com.rjkyker.shizukuai.broker.datawedge

import org.json.JSONArray
import org.json.JSONObject

/**
 * Plain-English "What this means" for an enriched scan (output of [ScanParsers.enrich]).
 * Pure / Android-free. Explains ONLY what is actually encoded — never invents product names,
 * prices, owners or anything a lookup service would be needed for.
 *
 * Output: JSONArray of {"field": short label, "value": raw value (may be ""), "meaning": English}.
 */
object ScanExplainer {

    // ---------------- GS1 Application Identifiers ----------------
    val GS1_AI_ENGLISH: Map<String, String> = linkedMapOf(
        "00" to "Shipping container code (SSCC) — a unique ID for one pallet, case or parcel in a shipment.",
        "01" to "This is the product's GTIN (global trade item number), like a UPC for worldwide trade. It identifies the product type, not one individual item.",
        "02" to "GTIN of the products contained inside this case or pallet.",
        "10" to "Batch or lot number — which production run this item came from (used for recalls).",
        "11" to "Production date — when the item was made.",
        "12" to "Due date — when payment or delivery is due.",
        "13" to "Packaging date — when the item was packed.",
        "15" to "Best-before date — quality is best before this date (still may be safe after).",
        "16" to "Sell-by date — the store should sell it by this date.",
        "17" to "Expiration date — do not use after this date.",
        "20" to "Product variant — a 2-digit internal variant code set by the brand owner.",
        "21" to "Serial number — identifies this one individual item.",
        "22" to "Consumer product variant (often used for healthcare / pharma).",
        "30" to "Variable count — how many items are in this package.",
        "37" to "Count of trade items contained in a logistic unit.",
        "240" to "Additional product ID assigned by the manufacturer.",
        "241" to "Customer part number.",
        "250" to "Secondary serial number.",
        "251" to "Reference to the source entity (e.g. the animal or origin batch).",
        "400" to "Customer's purchase order number.",
        "410" to "Ship-to / deliver-to location (GLN — global location number).",
        "414" to "Identification of a physical location (GLN).",
        "420" to "Ship-to postal code (within a single postal authority).",
        "421" to "Ship-to postal code with 3-digit ISO country code in front.",
        "392" to "Price to pay for a variable-measure item (local currency).",
        "393" to "Price to pay with a 3-digit ISO currency code in front.",
    )

    private val GS1_MEASURE = mapOf(
        "310" to "Net weight in kilograms", "311" to "Length in metres", "312" to "Width in metres",
        "313" to "Depth/height in metres", "314" to "Area in square metres", "315" to "Net volume in litres",
        "316" to "Net volume in cubic metres", "320" to "Net weight in pounds", "321" to "Length in inches",
        "322" to "Length in feet", "330" to "Gross weight in kilograms", "340" to "Gross weight in pounds",
    )

    private val GS1_DATE_AIS = setOf("11", "12", "13", "15", "16", "17")

    // ---------------- AAMVA (US/Canada driver licence & ID PDF417) ----------------
    val AAMVA_ENGLISH: Map<String, String> = linkedMapOf(
        "DAQ" to "Licence / ID number", "DCS" to "Last name", "DAC" to "First name", "DAD" to "Middle name(s)",
        "DBB" to "Date of birth", "DBA" to "Licence expiration date", "DBD" to "Licence issue date",
        "DBC" to "Sex", "DAY" to "Eye colour", "DAU" to "Height", "DAW" to "Weight (pounds)",
        "DAG" to "Street address", "DAH" to "Address line 2", "DAI" to "City", "DAJ" to "State / province",
        "DAK" to "Postal (ZIP) code", "DCF" to "Document discriminator (unique ID for this card print)",
        "DCG" to "Country", "DCK" to "Inventory control number (card stock tracking)",
        "DCA" to "Vehicle class you are licensed for", "DCB" to "Restrictions (e.g. corrective lenses)",
        "DCD" to "Endorsements (e.g. motorcycle, hazmat)", "DAZ" to "Hair colour", "DDA" to "REAL ID / compliance type",
        "DDB" to "Card revision date", "DDK" to "Organ donor indicator", "DDL" to "Veteran indicator",
        "DDE" to "Last name truncated?", "DDF" to "First name truncated?", "DDG" to "Middle name truncated?",
    )

    private val EYE = mapOf("BLK" to "black", "BLU" to "blue", "BRO" to "brown", "GRY" to "gray", "GRN" to "green",
        "HAZ" to "hazel", "MAR" to "maroon", "PNK" to "pink", "DIC" to "dichromatic", "UNK" to "unknown")

    // ---------------- Symbologies ----------------
    private val LABEL_ENGLISH = linkedMapOf(
        "EAN13" to "EAN-13 retail product barcode (13 digits, used worldwide on shop items)",
        "EAN8" to "EAN-8 short retail barcode for small packages",
        "UPCA" to "UPC-A retail product barcode (12 digits, US/Canada shop items)",
        "UPCE" to "UPC-E compressed UPC for small packages",
        "CODE128" to "Code 128 — general-purpose barcode (shipping labels, serial numbers, inventory)",
        "GS1_128" to "GS1-128 — Code 128 carrying GS1 data fields (logistics/shipping)",
        "CODE39" to "Code 39 — older alphanumeric barcode (ID badges, industrial, military)",
        "CODE93" to "Code 93 — compact alphanumeric barcode",
        "I2OF5" to "Interleaved 2 of 5 — numeric barcode common on cartons",
        "D2OF5" to "Discrete 2 of 5 — numeric industrial barcode",
        "CODABAR" to "Codabar — older barcode used by libraries, blood banks, FedEx airbills",
        "PDF417" to "PDF417 — stacked 2D barcode (driver licences, boarding passes, shipping)",
        "MICROPDF" to "MicroPDF417 — small stacked 2D barcode",
        "QRCODE" to "QR Code — 2D code that can hold links, text, Wi-Fi, contacts or payments",
        "MICROQR" to "Micro QR — smaller QR code",
        "DATAMATRIX" to "Data Matrix — small square 2D code (parts marking, pharma, GS1 healthcare)",
        "GS1_DATAMATRIX" to "GS1 Data Matrix — Data Matrix carrying GS1 fields (pharma/medical)",
        "AZTEC" to "Aztec — 2D code used on tickets and boarding passes",
        "MAXICODE" to "MaxiCode — UPS shipping 2D code",
        "GS1_DATABAR" to "GS1 DataBar — compact retail barcode (produce, coupons)",
        "USPS4CB" to "USPS Intelligent Mail barcode (postal routing)",
        "MSI" to "MSI Plessey — numeric barcode for shelf labels",
    )

    /** GS1 company-prefix ranges → issuing GS1 member organisation (NOT country of manufacture). */
    private val GS1_PREFIX = listOf(
        0..19 to "GS1 US (United States / Canada)", 30..39 to "GS1 US", 60..139 to "GS1 US / Canada",
        200..299 to "restricted / in-store use (e.g. weighed items, store codes)",
        300..379 to "GS1 France", 380..380 to "GS1 Bulgaria", 400..440 to "GS1 Germany",
        450..459 to "GS1 Japan", 490..499 to "GS1 Japan", 460..469 to "GS1 Russia",
        471..471 to "GS1 Taiwan", 489..489 to "GS1 Hong Kong", 500..509 to "GS1 UK",
        520..521 to "GS1 Greece", 540..549 to "GS1 Belgium & Luxembourg", 560..560 to "GS1 Portugal",
        570..579 to "GS1 Denmark", 590..590 to "GS1 Poland", 600..601 to "GS1 South Africa",
        640..649 to "GS1 Finland", 690..699 to "GS1 China", 700..709 to "GS1 Norway",
        729..729 to "GS1 Israel", 730..739 to "GS1 Sweden", 750..750 to "GS1 Mexico",
        760..769 to "GS1 Switzerland", 778..779 to "GS1 Argentina", 789..790 to "GS1 Brazil",
        800..839 to "GS1 Italy", 840..849 to "GS1 Spain", 850..850 to "GS1 Cuba", 869..869 to "GS1 Turkey",
        870..879 to "GS1 Netherlands", 880..880 to "GS1 South Korea", 885..885 to "GS1 Thailand",
        888..888 to "GS1 Singapore", 890..890 to "GS1 India", 893..893 to "GS1 Vietnam",
        899..899 to "GS1 Indonesia", 900..919 to "GS1 Austria", 930..939 to "GS1 Australia",
        940..949 to "GS1 New Zealand", 955..955 to "GS1 Malaysia", 977..977 to "ISSN (magazine / periodical)",
        978..979 to "ISBN (this is a book)", 980..980 to "refund receipt", 981..984 to "coupon",
        990..999 to "coupon",
    )

    private val MONTHS = listOf("January", "February", "March", "April", "May", "June", "July",
        "August", "September", "October", "November", "December")

    private val CURRENCY = mapOf("840" to "US dollar", "124" to "Canadian dollar", "978" to "euro",
        "826" to "British pound", "484" to "Mexican peso", "356" to "Indian rupee", "156" to "Chinese yuan",
        "392" to "Japanese yen", "986" to "Brazilian real", "036" to "Australian dollar", "702" to "Singapore dollar")

    private val EMV_TAGS = mapOf("00" to "Payload format version", "01" to "Point of initiation",
        "52" to "Merchant category code (type of business)", "53" to "Transaction currency",
        "54" to "Transaction amount", "55" to "Tip / convenience indicator", "58" to "Merchant country code",
        "59" to "Merchant name", "60" to "Merchant city", "61" to "Postal code",
        "62" to "Additional data (bill / reference / terminal)", "63" to "CRC checksum (error check)",
        "64" to "Merchant info in alternate language")

    // ---------------- helpers (public for tests) ----------------

    /** YYMMDD → "June 30, 2027". DD=00 means "end of month" per GS1. Null if not a valid date. */
    fun gs1Date(v: String): String? {
        if (v.length != 6 || !v.all { it.isDigit() }) return null
        val yy = v.substring(0, 2).toInt(); val mm = v.substring(2, 4).toInt(); val dd = v.substring(4, 6).toInt()
        if (mm !in 1..12 || dd !in 0..31) return null
        val year = 2000 + yy // GS1 sliding window; fine for 2000–2099
        return if (dd == 0) "end of ${MONTHS[mm - 1]} $year" else "${MONTHS[mm - 1]} $dd, $year"
    }

    /** AAMVA MMDDCCYY (US) or CCYYMMDD (Canada) → readable date. */
    fun aamvaDate(v: String): String? {
        val d = v.trim()
        if (d.length != 8 || !d.all { it.isDigit() }) return null
        val (y, m, day) = if (d.startsWith("19") || d.startsWith("20")) {
            Triple(d.substring(0, 4).toInt(), d.substring(4, 6).toInt(), d.substring(6, 8).toInt())
        } else Triple(d.substring(4, 8).toInt(), d.substring(0, 2).toInt(), d.substring(2, 4).toInt())
        if (m !in 1..12 || day !in 1..31) return null
        return "${MONTHS[m - 1]} $day, $y"
    }

    fun gs1PrefixMeaning(digits13: String): String? {
        if (digits13.length < 3) return null
        val p = digits13.take(3).toIntOrNull() ?: return null
        return GS1_PREFIX.firstOrNull { p in it.first }?.second
    }

    fun symbologyEnglish(labelType: String): String? {
        val k = labelType.uppercase().removePrefix("LABEL-TYPE-").replace("-", "_")
        return LABEL_ENGLISH[k] ?: LABEL_ENGLISH.entries.firstOrNull { k.contains(it.key) }?.value
    }

    private fun row(field: String, value: String, meaning: String) =
        JSONObject().put("field", field).put("value", value).put("meaning", meaning)

    // ---------------- main ----------------

    fun explain(scan: JSONObject): JSONArray {
        val out = JSONArray()
        val label = scan.optString("label_type", "unknown")
        val text = scan.optString("data_string").ifEmpty { scan.optString("visible_text") }
        symbologyEnglish(label)?.let { out.put(row("Barcode type", label, it)) }
            ?: if (label != "unknown") out.put(row("Barcode type", label, "Barcode symbology reported by the scanner.")) else Unit

        val parsers = scan.optJSONArray("parsers") ?: JSONArray()
        var structured = false
        for (i in 0 until parsers.length()) {
            val p = parsers.optJSONObject(i) ?: continue
            when (p.optString("format")) {
                "GS1" -> { structured = true; explainGs1(p, out) }
                "AAMVA" -> { structured = true; explainAamva(p, out) }
                "WIFI" -> { structured = true; explainWifi(p, out) }
                "vCard", "MECARD" -> { structured = true; explainContact(p, out) }
                "URL" -> { structured = true; explainUrl(p.optString("url"), out) }
                "geo" -> { structured = true; explainGeo(p.optString("value"), out) }
                "mailto" -> { structured = true; out.put(row("Email link", p.optString("value"),
                    "Opens an email to ${p.optString("value").removePrefix("mailto:").substringBefore('?')}. Not sent automatically.")) }
                "tel/sms" -> { structured = true; val v = p.optString("value"); out.put(row(
                    if (v.startsWith("tel", true)) "Phone link" else "Text-message link", v,
                    "${if (v.startsWith("tel", true)) "Calls" else "Starts a text to"} ${v.substringAfter(':').substringBefore(':')}. Never dialled or sent automatically.")) }
                "EMVCo" -> { structured = true; explainEmv(p, out) }
                "ISO/IEC 15434" -> { structured = true; out.put(row("Envelope", p.optString("envelope_format"),
                    "ISO/IEC 15434 data envelope (${p.optString("meaning")}): a standard wrapper that packs several labelled fields into one 2D code, common on industrial, DoD and medical labels.")) }
                "HIBC" -> { structured = true; out.put(row("HIBC", p.optString("body"),
                    "Health Industry Bar Code: identifies a medical/surgical product (labeler + product code, sometimes lot/expiry).")) }
                "JSON" -> { structured = true; val o = p.optJSONObject("object"); out.put(row("JSON data",
                    "", if (o != null) "Structured JSON with ${o.length()} field(s): ${o.keys().asSequence().take(8).joinToString()}." else "A JSON list of ${p.optJSONArray("array")?.length() ?: 0} item(s).")) }
                "key=value" -> { structured = true; val f = p.optJSONObject("fields"); out.put(row("Key/value data",
                    "", "Settings-style data with ${f?.length() ?: 0} field(s): ${f?.keys()?.asSequence()?.take(8)?.joinToString() ?: ""}.")) }
            }
        }

        val digits = text.trim()
        if (!structured && digits.isNotEmpty() && digits.all { it.isDigit() } && digits.length in listOf(8, 12, 13, 14)) {
            structured = true
            explainRetail(digits, label, out)
        }
        if (!structured) {
            out.put(row("Content", text.take(120), when {
                text.isEmpty() -> "The scanner returned no text."
                digits.all { it.isDigit() } -> "A plain ${digits.length}-digit number. No standard structure detected, so its meaning is defined by whoever printed it (e.g. an internal ID, serial or tracking number)."
                else -> "Plain text (${text.length} characters). No standard structure detected — it reads exactly as shown."
            }))
        }
        val visible = scan.optString("visible_text")
        if (visible.contains("<GS>")) out.put(row("Hidden <GS> characters", "", "Group-separator control characters split variable-length fields. They are normal in GS1 and ID-card barcodes and invisible when printed."))
        if (visible.contains("<RS>") || visible.contains("<EOT>")) out.put(row("Hidden <RS>/<EOT>", "", "Record-separator / end-of-transmission markers used by ISO 15434 envelopes."))
        if (visible.contains("<CR>") || visible.contains("<LF>")) out.put(row("Line breaks", "", "The data contains carriage-return / line-feed characters (multi-line content)."))
        scan.optString("aim_id").takeIf { it.isNotEmpty() && it != "null" }?.let {
            out.put(row("AIM symbology ID", it, "Standard prefix the scanner adds to say which barcode type was read (${aimMeaning(it)})."))
        }
        return out
    }

    private fun aimMeaning(aim: String): String = when {
        aim.startsWith("]C1") -> "GS1-128"
        aim.startsWith("]C") -> "Code 128"
        aim.startsWith("]E") -> "EAN/UPC"
        aim.startsWith("]d2") -> "GS1 Data Matrix"
        aim.startsWith("]d") -> "Data Matrix"
        aim.startsWith("]Q3") -> "GS1 QR"
        aim.startsWith("]Q") -> "QR Code"
        aim.startsWith("]L") -> "PDF417"
        aim.startsWith("]A") -> "Code 39"
        aim.startsWith("]z") -> "Aztec"
        aim.startsWith("]e") -> "GS1 DataBar"
        else -> "see AIM ISO/IEC 15424 table"
    }

    private fun explainRetail(d: String, label: String, out: JSONArray) {
        val ok = ScanParsers.gtinCheckDigitOk(d)
        val kind = when (d.length) { 8 -> "EAN-8"; 12 -> "UPC-A"; 13 -> "EAN-13"; else -> "GTIN-14" }
        out.put(row("Product code ($kind)", d, "A retail product number (GTIN). It identifies the product type and the brand owner's GS1 company prefix, not one individual item. Looking up the product name needs an online database; nothing else is hidden in this barcode."))
        val gtin13 = when (d.length) { 12 -> "0$d"; 14 -> d.drop(1); else -> d }
        if (d.length != 8) gs1PrefixMeaning(gtin13)?.let {
            out.put(row("GS1 prefix", gtin13.take(3), "Company number was issued by $it. This is where the brand registered, not necessarily where it was made."))
        }
        if (d.length == 14) out.put(row("Packaging level", d.take(1), "Indicator digit: 0 = single item, 1–8 = a case / pack level, 9 = variable-measure item."))
        out.put(row("Check digit", d.takeLast(1), if (ok) "Valid — the last digit correctly checks the others, so the scan is not misread." else "Does NOT match — possible misread or a non-GTIN number."))
    }

    private fun explainGs1(p: JSONObject, out: JSONArray) {
        out.put(row("GS1 data", "", "GS1 element string: a list of standard fields, each starting with a number in brackets called an Application Identifier (AI)."))
        val ais = p.optJSONArray("application_identifiers") ?: return
        for (i in 0 until ais.length()) {
            val a = ais.optJSONObject(i) ?: continue
            val ai = a.optString("ai"); val v = a.optString("value")
            val base = GS1_AI_ENGLISH[ai] ?: GS1_MEASURE[ai.take(3)]?.let { m ->
                val dec = ai.getOrNull(3)?.digitToIntOrNull() ?: 0
                val num = v.toLongOrNull()
                val shown = if (num != null) java.math.BigDecimal(num).movePointLeft(dec).toPlainString() else v
                "$m: $shown (last AI digit $dec = number of decimal places)."
            } ?: "GS1 field ($ai) not in the built-in table; value shown as encoded."
            var meaning = base
            if (ai in GS1_DATE_AIS) gs1Date(v)?.let { meaning = "$base → $it." }
            if (ai == "01" || ai == "02") meaning += if (a.optBoolean("check_digit_ok")) " Check digit valid." else " Check digit does NOT match."
            out.put(row("($ai)", v, meaning))
        }
    }

    private fun explainAamva(p: JSONObject, out: JSONArray) {
        out.put(row("ID card", "", "AAMVA driver-licence / ID-card data (US/Canada). Decoded on this phone only, never uploaded."))
        val f = p.optJSONObject("fields") ?: return
        for (code in f.keys()) {
            val o = f.optJSONObject(code) ?: continue
            val v = o.optString("value")
            var m = AAMVA_ENGLISH[code] ?: "AAMVA field $code"
            when (code) {
                "DBB", "DBA", "DBD", "DDB" -> aamvaDate(v)?.let { m += " → $it" }
                "DBC" -> m += when (v.trim()) { "1" -> " → male"; "2" -> " → female"; "9" -> " → not specified"; else -> "" }
                "DAY" -> EYE[v.trim().uppercase()]?.let { m += " → $it" }
                "DDK", "DDL" -> if (v.trim() == "1") m += " → yes"
            }
            out.put(row(code, v, "$m."))
        }
    }

    private fun explainWifi(p: JSONObject, out: JSONArray) {
        val f = p.optJSONObject("fields") ?: JSONObject()
        val ssid = f.optString("ssid"); val sec = f.optString("security").uppercase()
        val secEn = when (sec) { "WPA", "WPA2" -> "WPA/WPA2 password-protected"; "WPA3", "SAE" -> "WPA3 password-protected"
            "WEP" -> "old WEP (weak security)"; "", "NOPASS" -> "open (no password)"; else -> sec }
        out.put(row("Wi-Fi", "WIFI:T:$sec", "Wi-Fi network credentials for \"$ssid\" — $secEn." +
            (if (f.optString("password").isNotEmpty()) " A password (${f.optString("password").length} characters) is included in the code." else "") +
            (if (f.optString("hidden").equals("true", true)) " The network is hidden (doesn't broadcast its name)." else "") +
            " Display only — the phone will not join it automatically."))
    }

    private fun explainContact(p: JSONObject, out: JSONArray) {
        val f = p.optJSONObject("fields") ?: JSONObject()
        val names = mapOf("FN" to "Full name", "N" to "Name", "TEL" to "Phone", "EMAIL" to "Email", "ORG" to "Company",
            "TITLE" to "Job title", "ADR" to "Address", "URL" to "Website", "NOTE" to "Note")
        out.put(row("Contact card", p.optString("format"), "A digital business card you could save to contacts (not saved automatically)."))
        for (k in f.keys()) names[k.uppercase()]?.let { out.put(row(it, f.optString(k), "$it from the contact card.")) }
    }

    private fun explainUrl(url: String, out: JSONArray) {
        val host = url.substringAfter("://").substringBefore('/').substringBefore('?')
        out.put(row("Web link", url, "A website address on $host. ${if (url.startsWith("https", true)) "Uses HTTPS (encrypted)." else "Uses plain HTTP (not encrypted)."} Not opened automatically — check the domain before visiting."))
        if (url.contains('?')) out.put(row("Link parameters", url.substringAfter('?').take(120), "Extra data passed to the website (tracking codes, IDs or campaign tags)."))
    }

    private fun explainGeo(v: String, out: JSONArray) {
        val c = v.removePrefix("geo:").substringBefore('?').split(',')
        out.put(row("Location", v, if (c.size >= 2) "Map coordinates: latitude ${c[0]}, longitude ${c[1]}." else "A map location link."))
    }

    private fun explainEmv(p: JSONObject, out: JSONArray) {
        out.put(row("Payment QR", "", "EMVCo merchant-presented payment QR code. Display only — nothing is paid automatically."))
        val tlv = p.optJSONArray("tlv") ?: return
        for (i in 0 until tlv.length()) {
            val t = tlv.optJSONObject(i) ?: continue
            val id = t.optString("id"); val v = t.optString("value")
            val idn = id.toIntOrNull() ?: -1
            var m = EMV_TAGS[id] ?: if (idn in 2..51) "Merchant account information (payment network ID)" else "EMV field $id"
            if (id == "01") m += if (v == "12") " → dynamic (one-time amount)" else if (v == "11") " → static (reusable)" else ""
            if (id == "53") CURRENCY[v]?.let { m += " → $it" }
            out.put(row("EMV $id", v, "$m."))
        }
    }
}
