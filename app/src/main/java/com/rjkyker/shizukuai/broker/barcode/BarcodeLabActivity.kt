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
import android.content.Intent
import org.json.JSONObject

/** On-device first-pass UI for inspection, transformation previews, local prices, and label creation. */
class BarcodeLabActivity : AppCompatActivity() {
    private lateinit var valueInput: EditText
    private lateinit var instructionInput: EditText
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
    private val barcodeCommandListener: (CustomBarcodeCommand) -> Unit = { command ->
        startActivity(Intent(this, BarcodeExperienceActivity::class.java).apply {
            putExtra(BarcodeExperienceActivity.EXTRA_TYPE, command.type)
            putExtra(BarcodeExperienceActivity.EXTRA_ID, command.id)
            putExtra(BarcodeExperienceActivity.EXTRA_VERSION, command.version)
        })
    }

    override fun onStart() {
        super.onStart()
        BarcodeCommandRuntime.attach(barcodeCommandListener)
        BarcodeCommandRuntime.consumePending(applicationContext)?.let(barcodeCommandListener)
    }

    override fun onStop() {
        BarcodeCommandRuntime.detach(barcodeCommandListener)
        super.onStop()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        BarcodeAiProviderSettings.install(applicationContext)
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
        instructionInput = field(content, "Natural-language transformation instruction", "Add RJ- to the beginning")
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
        addButton(content, "CONFIGURE AI PROVIDER") {
            showAiProviderDialog()
        }
        addButton(content, "TEST AI PROVIDER") {
            val provider = BarcodeAiProviderSettings.install(applicationContext)
            if (provider == "OFFLINE_RULE_FALLBACK") {
                showMessage("No hosted AI provider is configured. Choose CONFIGURE AI PROVIDER first.")
            } else {
                showMessage("Testing " + provider + " …")
                Thread({
                    val result = BarcodeAiPlanner.plan("BGX-TEST-123", "Add RJ- to the beginning")
                    runOnUiThread { if (!isFinishing) showMessage(result.toString(2)) }
                }, "BarcodeAiTest").start()
            }
        }
        addButton(content, "INTERPRET NATURAL-LANGUAGE INSTRUCTION") {
            val value = currentValue()
            val instruction = instructionInput.text.toString()
            showMessage("Planning transformation. Hosted providers may send this barcode value and instruction to your configured endpoint…")
            Thread({
                val plan = BarcodeAiPlanner.plan(value, instruction)
                val preview = plan.optJSONObject("preview")
                if (plan.optBoolean("planned") && preview != null && preview.optBoolean("ok")) {
                    latestTransform = BarcodeTransformResult(
                        true, value, preview.optString("operation", ""),
                        preview.optString("output", "")
                    )
                }
                runOnUiThread { if (!isFinishing) showMessage(plan.toString(2)) }
            }, "BarcodeAiPlanner").start()
        }
        addButton(content, "INSPECT CURRENT DATA") {
            val v = currentValue()
            if (v.isEmpty()) return@addButton showMessage("Scan a barcode or enter data first.")
            val lastScan = DataWedgeResultReceiver.lastScanJson.get()
            val savedInspection = lastScan?.optJSONObject("inspection")
            if (lastScan?.optString("data_string", null) == v && savedInspection != null) {
                showMessage(savedInspection.toString(2))
            } else {
                val inspection = BarcodeGhostEngine.inspect(
                    v,
                    symbologyInput.text.toString().trim(),
                    lastScan?.optString("source", "").orEmpty()
                )
                showMessage(inspection.toJson().toString(2))
            }
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

        addButton(content, "LOOK UP LOCAL CATALOG RECORD") {
            val barcode = currentValue()
            if (barcode.isBlank()) return@addButton showMessage("Scan or enter a barcode first.")
            showMessage(BarcodeWorkspaceStore.lookup(applicationContext, barcode).toString(2))
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

    private fun showAiProviderDialog() {
        val saved = BarcodeAiProviderSettings.load(applicationContext)
        val form = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), dp(4))
            setBackgroundColor(Color.rgb(11, 14, 26))
        }
        fun input(hintText: String, initial: String, password: Boolean = false): EditText {
            val edit = EditText(this).apply {
                hint = hintText
                setText(initial)
                setTextColor(Color.WHITE)
                setHintTextColor(Color.GRAY)
                if (password) inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
                setPadding(dp(8), dp(4), dp(8), dp(4))
            }
            form.addView(edit, matchWrap())
            return edit
        }
        form.addView(TextView(this).apply {
            text = "Use an HTTPS OpenAI-compatible chat-completions endpoint. When AI planning is used, the current barcode value and instruction are sent to that endpoint. The API key is stored in encrypted app-private preferences."
            setTextColor(Color.LTGRAY)
            textSize = 13f
            setPadding(0, 0, 0, dp(10))
        }, matchWrap())
        val endpoint = input("HTTPS endpoint URL", saved?.endpoint ?: "https://api.openai.com/v1/chat/completions")
        val model = input("Model name", saved?.model ?: "gpt-4.1-mini")
        val key = input("API key (blank keeps existing key)", "", password = true)
        val dialog = AlertDialog.Builder(this)
            .setTitle("Configure Barcode AI")
            .setView(form)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Save securely", null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                try {
                    BarcodeAiProviderSettings.save(
                        applicationContext,
                        endpoint.text.toString(),
                        model.text.toString(),
                        key.text.toString()
                    )
                    val provider = BarcodeAiProviderSettings.install(applicationContext)
                    dialog.dismiss()
                    showMessage("AI provider saved securely: " + provider + "\nUse TEST AI PROVIDER to verify the connection.")
                } catch (e: Exception) {
                    Toast.makeText(this, e.message ?: "Could not save AI settings", Toast.LENGTH_LONG).show()
                }
            }
        }
        dialog.show()
    }

    private fun currentValue(): String = valueInput.text.toString()

    private fun field(parent: LinearLayout, hintText: String, initial: String): EditText {
        val edit = EditText(this).apply {
            hint = hintText
            setText(initial)
            setHintTextColor(Color.GRAY)
            setTextColor(Color.WHITE)
            textSize = 14f
            setSingleLine(false)
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
