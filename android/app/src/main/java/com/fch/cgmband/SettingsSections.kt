package com.fch.cgmband

import android.content.Context
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.TextView

/**
 * Grouped settings in the system-settings style: a small grey title, a rounded card of
 * uniform rows separated by inset dividers, and an optional footnote.
 */
class SettingsSections(private val container: LinearLayout) {

    private val context: Context = container.context
    private val inflater = LayoutInflater.from(context)

    /** Adds a titled card; keep the returned [Section] to rebuild rows that change at runtime. */
    fun section(title: String, footer: String? = null, rows: Section.() -> Unit): Section {
        val view = inflater.inflate(R.layout.item_setting_section, container, false)
        view.findViewById<TextView>(R.id.section_title).text = title
        view.findViewById<TextView>(R.id.section_footer).apply {
            text = footer.orEmpty()
            visibility = if (footer == null) View.GONE else View.VISIBLE
        }
        val section = Section(view.findViewById(R.id.section_rows))
        section.rows()
        container.addView(view)
        return section
    }

    inner class Section(private val card: LinearLayout) {

        /** Replaces every row of this card. */
        fun rebuild(rows: Section.() -> Unit) {
            card.removeAllViews()
            rows()
        }

        /** A row with a right-hand value; a chevron is shown only when the row does something. */
        fun row(title: String, subtitle: String? = null, onClick: (() -> Unit)? = null): Row {
            val row = addRow(title, subtitle)
            row.chevron.visibility = if (onClick == null) View.GONE else View.VISIBLE
            if (onClick != null) row.view.setOnClickListener { onClick() } else row.view.isClickable = false
            return row
        }

        fun switchRow(title: String, subtitle: String? = null, checked: Boolean, onChange: (Boolean) -> Unit): Row {
            val row = addRow(title, subtitle)
            row.chevron.visibility = View.GONE
            row.toggle.visibility = View.VISIBLE
            row.toggle.isChecked = checked
            row.toggle.setOnCheckedChangeListener { _, isChecked -> onChange(isChecked) }
            row.view.setOnClickListener { row.toggle.toggle() }
            return row
        }

        private fun addRow(title: String, subtitle: String?): Row {
            if (card.childCount > 0) card.addView(divider())
            val view = inflater.inflate(R.layout.item_setting_row, card, false)
            card.addView(view)
            return Row(view).apply {
                setTitle(title)
                setSubtitle(subtitle)
            }
        }

        private fun divider() = View(context).apply {
            setBackgroundColor(context.getColor(R.color.chart_grid))
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1).apply {
                marginStart = TypedValue.applyDimension(
                    TypedValue.COMPLEX_UNIT_DIP, 16f, context.resources.displayMetrics,
                ).toInt()
            }
        }
    }

    class Row(val view: View) {
        private val title: TextView = view.findViewById(R.id.row_title)
        private val subtitle: TextView = view.findViewById(R.id.row_subtitle)
        private val value: TextView = view.findViewById(R.id.row_value)
        val chevron: ImageView = view.findViewById(R.id.row_chevron)
        val toggle: Switch = view.findViewById(R.id.row_switch)

        fun setTitle(text: String) {
            title.text = text
        }

        fun setSubtitle(text: String?) {
            subtitle.text = text.orEmpty()
            subtitle.visibility = if (text.isNullOrEmpty()) View.GONE else View.VISIBLE
        }

        fun setValue(text: String, colorRes: Int = R.color.text_secondary) {
            value.text = text
            value.setTextColor(view.context.getColor(colorRes))
        }
    }
}
