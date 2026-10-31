package com.rjkyker.shizukuai.broker.assist

import android.os.Bundle
import android.text.InputType
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import com.rjkyker.shizukuai.broker.accessibility.AgentAccessibilityService
import com.rjkyker.shizukuai.broker.autonomy.memory.MemoryStore
import com.rjkyker.shizukuai.broker.ui.mod.ModFx
import java.util.concurrent.Executors

/** Device-side settings for type-anywhere assist (all apps by default, exclusions, remote opt-in, try-it field). */
class AssistSettingsActivity : AppCompatActivity() {
    private val io = Executors.newSingleThreadExecutor()
    private lateinit var status: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "Type-anywhere assist"
        val pad = (16 * resources.displayMetrics.density).toInt()
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(pad, pad, pad, pad); setBackgroundColor(ModFx.BG) }
        fun txt(t: String, c: Int = ModFx.TEXT, sp: Float = 14f) = TextView(this).apply { text = t; setTextColor(c); textSize = sp; setPadding(0, pad / 3, 0, pad / 3) }
        fun sw(label: String, key: String, get: () -> Boolean) = SwitchCompat(this).apply {
            text = label; setTextColor(ModFx.TEXT); isChecked = get()
            setOnCheckedChangeListener { _, v -> AssistSettings.setBool(this@AssistSettingsActivity, key, v); AssistController.refreshBubbleAsync(); refresh() }
        }
        col.addView(txt("FINISH MY SENTENCES", ModFx.CYAN, 18f))
        col.addView(txt("Suggests the next words in any app's focused text box. Tap the chip to insert at the cursor. " +
            "It never presses Enter/Send, and never reads or types into password, PIN, OTP or card fields.", ModFx.DIM, 13f))
        col.addView(sw("Assist enabled (all apps)", "enabled") { AssistSettings.enabled(this) })
        col.addView(sw("Auto-suggest while typing", "auto_suggest") { AssistSettings.autoSuggest(this) })
        col.addView(sw("Persistent ✦ Assist bubble (all apps, draggable)", "floating_button") { AssistSettings.floatingButton(this) })
        col.addView(sw("Remote AI via box (sends field text off-phone; REMOTE badge)", "remote_enabled") { AssistSettings.remoteEnabled(this) })
        col.addView(Button(this).apply { text = "Excluded apps…"; setOnClickListener { editExclusions() } })
        col.addView(Button(this).apply { text = "Remote URL…"; setOnClickListener { editUrls() } })
        col.addView(txt("TRY IT (harmless test box — nothing is sent anywhere)", ModFx.GREEN, 13f))
        col.addView(EditText(this).apply {
            hint = "Type a few words, e.g. \"I'm running a little\""; setTextColor(ModFx.TEXT); setHintTextColor(ModFx.DIM)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            minLines = 3
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        status = txt("", ModFx.DIM, 12f)
        col.addView(status)
        setContentView(ScrollView(this).apply { addView(col) })
        refresh()
    }

    private fun refresh() = io.execute {
        val s = buildString {
            append("accessibility: ").append(if (AgentAccessibilityService.instance != null) "ON" else "OFF (enable in Settings → Accessibility)").append('\n')
            append("route: ").append(CompletionRouter.statusJson(this@AssistSettingsActivity)).append('\n')
            append("remote token set: ").append(AssistSettings.hasToken(this@AssistSettingsActivity)).append('\n')
            append("learning from accepted words: ").append(if (MemoryStore.enabled(this@AssistSettingsActivity)) "on (memory enabled)" else "off (memory disabled)").append('\n')
            append("offline predictor: word prediction, not AI (FrequencyWords CC-BY-SA-4.0 + public-domain texts)")
        }
        runOnUiThread { status.text = s }
    }

    private fun editExclusions() {
        val e = EditText(this).apply { setText(AssistSettings.excluded(this@AssistSettingsActivity).sorted().joinToString("\n")); minLines = 5 }
        AlertDialog.Builder(this).setTitle("Excluded packages (one per line)").setView(e)
            .setPositiveButton("Save") { _, _ ->
                AssistSettings.setExcluded(this, e.text.split('\n', ',', ' ').map { it.trim() }.filter { it.isNotEmpty() }.toSet()); refresh()
            }.setNegativeButton("Cancel", null).show()
    }

    private fun editUrls() {
        val e = EditText(this).apply { setText(AssistSettings.remoteUrls(this@AssistSettingsActivity).joinToString("\n")) }
        AlertDialog.Builder(this).setTitle("Remote /complete URLs (tried in order)").setView(e)
            .setPositiveButton("Save") { _, _ -> AssistSettings.setUrls(this, e.text.toString().replace('\n', ',')); refresh(); Toast.makeText(this, "Saved", Toast.LENGTH_SHORT).show() }
            .setNegativeButton("Cancel", null).show()
    }
}
