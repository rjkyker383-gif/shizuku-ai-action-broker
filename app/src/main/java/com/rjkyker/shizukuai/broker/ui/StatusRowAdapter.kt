package com.rjkyker.shizukuai.broker.ui

import android.content.res.ColorStateList
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.rjkyker.shizukuai.broker.R

/** One dashboard row: LED + label + value, optional COPY action. */
data class StatusRow(
    val label: String,
    val value: String,
    val state: Led,
    /** Non-null → show a COPY button that copies this exact text. Never a secret. */
    val copyText: String? = null
)

enum class Led(val colorRes: Int) {
    OK(R.color.led_ok), WARN(R.color.led_warn), BAD(R.color.led_bad), INFO(R.color.led_info), OFF(R.color.led_off)
}

class StatusRowAdapter(
    private val onCopy: (label: String, text: String) -> Unit
) : RecyclerView.Adapter<StatusRowAdapter.VH>() {

    private var rows: List<StatusRow> = emptyList()
    var accent: Int = 0
        set(value) { field = value; notifyDataSetChanged() }

    fun submit(newRows: List<StatusRow>) {
        rows = newRows
        notifyDataSetChanged()
    }

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val led: View = v.findViewById(R.id.rowLed)
        val label: TextView = v.findViewById(R.id.rowLabel)
        val value: TextView = v.findViewById(R.id.rowValue)
        val copy: MaterialButton = v.findViewById(R.id.rowCopy)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(LayoutInflater.from(parent.context).inflate(R.layout.item_status_row, parent, false))

    override fun getItemCount(): Int = rows.size

    override fun onBindViewHolder(h: VH, position: Int) {
        val r = rows[position]
        val ctx = h.itemView.context
        h.led.backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(ctx, r.state.colorRes))
        h.label.text = r.label
        h.value.text = r.value
        h.itemView.contentDescription = "${r.label}: ${r.value}"
        if (r.copyText != null) {
            h.copy.visibility = View.VISIBLE
            if (accent != 0) h.copy.setTextColor(accent)
            h.copy.setOnClickListener { onCopy(r.label, r.copyText) }
        } else {
            h.copy.visibility = View.GONE
            h.copy.setOnClickListener(null)
        }
    }
}
