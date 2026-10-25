package com.rjkyker.shizukuai.broker.barcode

import android.app.AlertDialog
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.rjkyker.shizukuai.broker.datawedge.DataWedgeResultReceiver
import org.json.JSONObject

/** On-device first-pass UI for inspection, transformation previews, local prices, and label creation. */
class BarcodeLabActivity : AppCompatActivity() {
    private lateinit var valueInput: EditText
    private lateinit var operationInput: EditText
    private lateinit var argumentInput: EditText
    private lateinit var replacementInput: EditText
    private lateinit var symbologyInput: EditText
    private lateinit var productNameInput: EditText
    private lateinit var priceInput: EditText
    private lateinit var currencyInput: EditText
    private lateinit var formatInput: EditText
    private lateinit var output: TextView
    private var latestTransform: BarcodeTransformResult? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val last = DataWedgeResultReceiver.lastScanJson.get()
        val initial = last?.optString("data_string", "").orEmpty()

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(18), dp(18), dp(28))
            setBackgroundColor(Color.rgb(11, 14, 26))
        }
        content.addView(TextView(this).apply {
            text = "⟁ BARCODE GHOST LAB"
            textSize = 26f
            setTextColor(Color.rgb(100, 235, 255))
            gravity = Gravity.CENTER
            typeface = android.graphics.Typeface.DEFAULT_BOLD
        }, matchWrap())
        content.addView(TextView(this).apply {
            text = "Local-first barcode tools · changes here do not rewrite a physical label or a retailer's system."
            textSize = 13f
            setTextColor(Color.LTGRAY)
            setPadding(0, dp(8), 0, dp(14))
        }, matchWrap())

        valueInput = field(content, "Scanned or replacement data", initial)
        symbologyInput = field(content, "Symbology (optional, e.g. UPC-A)", last?.optString("label_type", "").orEmpty())
        operationInput = field(content, "Transform operation", "prefix")
        argumentInput = field(content, "Argument / search text", "RJ-")
        replacementInput = field(content, "Replacement text", "")
        productNameInput = field(content, "Local product name", "")
        priceInput = field(content, "Local catalog price (e.g. 12.99)", "")
        currencyInput = field(content, "Currency code", "USD")
        formatInput = field(content, "Output barcode format", "QR_CODE")

        addButton(content, "LOAD LATEST SCAN") {
            val scan = DataWedgeResultReceiver.lastScanJson.get()
            if (scan == null) {
                showMessage("No scan has been received in this app process yet.")
            } else {
                valueInput.setText(scan.optString("data_string", ""))
                symbologyInput.setText(scan.optString("label_type", ""))
                showMessage("Latest scan loaded. Original value preserved in scan history.")
            }
        }
        addButton(content, "INSPECT CURRENT DATA") {
            val v = currentValue()
            if (v.isEmpty()) return@addButton showMessage("Scan a barcode or enter data first.")
            val lastScan = DataWedgeResultReceiver.lastScanJson.get()
            val inspection = BarcodeGhostEngine.inspect(
                v,
                symbologyInput.text.toString().trim(),
                lastScan?.optString("source", "").orEmpty()
            )
            showMessage(inspection.toJson().toString(2))
        }
        addButton(content, "PREVIEW TRANSFORMATION") {
            val v = currentValue()
            if (v.isEmpty()) return@addButton showMessage("Enter data to transform.")
            val result = BarcodeGhostEngine.transform(
                v,
                operationInput.text.toString(),
                argumentInput.text.toString(),
                replacementInput.text.toString()
            )
            latestTransform = result
            showMessage(result.toJson().toString(2))
        }
        addButton(content, "USE PREVIEW AS NEW DRAFT") {
            val result = latestTransform
            if (result == null || !result.success || result.output == null) {
                showMessage("Run a successful transformation preview first.")
            } else {
                valueInput.setText(result.output)
                showMessage("Draft updated in this app. No label, source scan, or external catalog was changed.")
            }
        }

        addButton(content, "SAVE LOCAL CATALOG PRICE") {
            if (currentValue().isBlank() || productNameInput.text.toString().isBlank() || priceInput.text.toString().isBlank()) {
                showMessage("Barcode, product name, and price are required.")
                return@addButton
            }
            AlertDialog.Builder(this)
                .setTitle("Save local catalog change?")
                .setMessage("This updates only this app's local catalog. It does not change a retailer's price or its point-of-sale database.")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Save locally") { _, _ ->
                    val request = JSONObject()
                        .put("confirm", true)
                        .put("barcode", currentValue())
                        .put("productName", productNameInput.text.toString())
                        .put("catalogPrice", priceInput.text.toString())
                        .put("currency", currencyInput.text.toString())
                    val result = BarcodeWorkspaceStore.upsert(applicationContext, request)
                    showMessage(result.toString(2))
                }
                .show()
        }

        addButton(content, "GENERATE REPLACEMENT BARCODE PNG") {
            val v = currentValue()
            if (v.isBlank()) return@addButton showMessage("Enter data to encode.")
            val request = JSONObject()
                .put("value", v)
                .put("format", formatInput.text.toString().trim())
                .put("width", 512)
                .put("height", 512)
            val result = BarcodeLabelGenerator.generate(applicationContext, request)
            showMessage(result.toString(2) + if (result.optBoolean("ok")) "\n\nScan the generated image back before relying on it." else "")
        }

        addButton(content, "INSERT GHOST CIPHER GAME CODE") {
            valueInput.setText("BGX:1|TYPE=GAME|ID=GHOST_CIPHER|VERSION=1")
            formatInput.setText("QR_CODE")
            showMessage("Custom command loaded. Generate a QR code, then scan it with this app to launch Ghost Cipher.")
        }
        addButton(content, "INSERT GHOST MODE EASTER EGG") {
            valueInput.setText("BGX:1|TYPE=EASTER_EGG|ID=GHOST_MODE|VERSION=1")
            formatInput.setText("QR_CODE")
            showMessage("Custom command loaded. Generate a QR code, then scan it with this app to trigger the Easter egg.")
        }
        addButton(content, "PREVIEW / LAUNCH BGX COMMAND") {
            when (val parsed = BarcodeGhostEngine.parseCommand(currentValue())) {
                is CommandParseResult.Valid -> {
                    showMessage("Registered command:\n" + parsed.command.toJson().toString(2))
                    startActivity(android.content.Intent(this, BarcodeExperienceActivity::class.java).apply {
                        putExtra(BarcodeExperienceActivity.EXTRA_TYPE, parsed.command.type)
                        putExtra(BarcodeExperienceActivity.EXTRA_ID, parsed.command.id)
                        putExtra(BarcodeExperienceActivity.EXTRA_VERSION, parsed.command.version)
                    })
                }
                is CommandParseResult.Invalid -> showMessage("Command rejected: " + parsed.reason)
                CommandParseResult.NotCommand -> showMessage("Current data is not a BGX command.")
            }
        }

        content.addView(TextView(this).apply {
            text = "RESULT / DIAGNOSTICS"
            textSize = 15f
            setTextColor(Color.rgb(100, 235, 255))
            setPadding(0, dp(20), 0, dp(8))
        }, matchWrap())
        output = TextView(this).apply {
            text = "Ready. Scan a barcode, then inspect or transform its data."
            textSize = 12f
            typeface = android.graphics.Typeface.MONOSPACE
            setTextColor(Color.WHITE)
            setTextIsSelectable(true)
            setPadding(dp(12), dp(12), dp(12), dp(12))
            setBackgroundColor(Color.rgb(23, 28, 44))
        }
        content.addView(output, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ))

        val scroll = ScrollView(this)
        scroll.addView(content)
        setContentView(scroll)
    }

    private fun currentValue(): String = valueInput.text.toString()

    private fun field(parent: LinearLayout, hintText: String, initial: String): EditText {
        val edit = EditText(this).apply {
            hint = hintText
            setText(initial)
            setHintTextColor(Color.GRAY)
            setTextColor(Color.WHITE)
            textSize = 14f
            singleLine = false
            maxLines = 3
            setPadding(dp(10), dp(4), dp(10), dp(4))
        }
        parent.addView(edit, matchWrap())
        return edit
    }

    private fun addButton(parent: LinearLayout, title: String, action: () -> Unit) {
        parent.addView(Button(this).apply {
            text = title
            setOnClickListener { action() }
        }, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(5) })
    }

    private fun showMessage(message: String) {
        output.text = message
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun matchWrap() = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
    )
}
