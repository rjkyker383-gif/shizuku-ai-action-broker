package com.rjkyker.shizukuai.broker.barcode

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.math.BigDecimal
import java.math.RoundingMode
import java.util.Locale

/** Local-only catalog. Updates do not alter retailer systems or physical labels. */
object BarcodeWorkspaceStore {
    private const val DIR = "barcode-workspace"
    private const val CATALOG = "catalog.json"
    private const val AUDIT = "catalog-audit.jsonl"

    @Synchronized
    fun lookup(context: Context, barcode: String): JSONObject {
        if (barcode.isBlank() || barcode.length > 512) {
            return JSONObject().put("ok", false).put("error", "barcode must contain 1..512 characters")
        }
        val record = readCatalog(context).optJSONObject(barcode)
        return JSONObject().put("ok", true)
            .put("found", record != null)
            .put("catalog_scope", "LOCAL_APP_ONLY")
            .put("record", record ?: JSONObject.NULL)
    }

    @Synchronized
    fun upsert(context: Context, request: JSONObject): JSONObject {
        if (!request.optBoolean("confirm", false)) {
            return JSONObject().put("ok", false).put("error", "set confirm=true to save a local catalog change")
        }
        val barcode = request.optString("barcode", request.optString("value", ""))
        val name = request.optString("productName", request.optString("product_name", "")).trim()
        val rawPrice = request.optString("catalogPrice", request.optString("catalog_price", ""))
        val currency = request.optString("currency", "USD").uppercase(Locale.ROOT)
        if (barcode.isBlank() || barcode.length > 512) {
            return JSONObject().put("ok", false).put("error", "barcode must contain 1..512 characters")
        }
        if (name.isBlank() || name.length > 256) {
            return JSONObject().put("ok", false).put("error", "productName must contain 1..256 characters")
        }
        if (!currency.matches(Regex("[A-Z]{3}"))) {
            return JSONObject().put("ok", false).put("error", "currency must be a three-letter code such as USD")
        }
        val price = try {
            BigDecimal(rawPrice).setScale(2, RoundingMode.HALF_UP)
        } catch (_: Exception) {
            return JSONObject().put("ok", false).put("error", "catalogPrice must be a decimal amount")
        }
        if (price < BigDecimal.ZERO || price > BigDecimal("99999999.99")) {
            return JSONObject().put("ok", false).put("error", "catalogPrice is outside the supported range")
        }

        val directory = directory(context)
        val catalog = readCatalog(context)
        val previous = catalog.optJSONObject(barcode)
        val now = System.currentTimeMillis()
        val record = JSONObject()
            .put("barcode", barcode)
            .put("product_name", name)
            .put("catalog_price", price.toPlainString())
            .put("currency", currency)
            .put("updated_at_epoch_ms", now)
            .put("catalog_scope", "LOCAL_APP_ONLY")
        catalog.put(barcode, record)
        try {
            File(directory, CATALOG).writeText(catalog.toString(2))
            val audit = JSONObject()
                .put("event", if (previous == null) "CATALOG_CREATE" else "CATALOG_UPDATE")
                .put("at_epoch_ms", now)
                .put("barcode", barcode)
                .put("previous", previous ?: JSONObject.NULL)
                .put("current", record)
                .put("catalog_scope", "LOCAL_APP_ONLY")
            File(directory, AUDIT).appendText(audit.toString() + "\n")
        } catch (e: Exception) {
            return JSONObject().put("ok", false).put("error", e.message ?: "catalog write failed")
        }
        return JSONObject().put("ok", true)
            .put("catalog_scope", "LOCAL_APP_ONLY")
            .put("record", record)
            .put("previous", previous ?: JSONObject.NULL)
    }

    private fun readCatalog(context: Context): JSONObject {
        val file = File(directory(context), CATALOG)
        if (!file.exists()) return JSONObject()
        return try { JSONObject(file.readText()) } catch (_: Exception) { JSONObject() }
    }

    private fun directory(context: Context): File = File(context.filesDir, DIR).apply { mkdirs() }
}
