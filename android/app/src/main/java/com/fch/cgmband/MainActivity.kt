package com.fch.cgmband

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan
import android.util.TypedValue
import android.view.View
import android.view.ViewTreeObserver
import android.widget.LinearLayout
import android.widget.TextView
import com.fch.cgmband.core.ChartData
import com.fch.cgmband.core.ChartWindow
import com.fch.cgmband.core.CursorReadout
import com.fch.cgmband.core.DemoData
import com.fch.cgmband.core.Fingerstick
import com.fch.cgmband.core.GlucoseLevel
import com.fch.cgmband.core.GlucosePoint
import com.fch.cgmband.core.GlucoseReading
import com.fch.cgmband.core.GlucoseStats
import com.fch.cgmband.core.ReadingState
import com.fch.cgmband.core.ReadingTimeLabel
import com.fch.cgmband.core.TargetRange
import com.fch.cgmband.core.arrow
import java.text.SimpleDateFormat
import java.time.ZoneId
import java.util.Date
import java.util.Locale

/** Dashboard: latest reading, 4/8/12/24-hour chart with yesterday overlay, and range statistics. */
class MainActivity : Activity() {

    private lateinit var bannerView: TextView
    private lateinit var timeLabelView: TextView
    private lateinit var valueView: TextView
    private lateinit var arrowView: TextView
    private lateinit var unitView: TextView
    private lateinit var bandChipView: TextView
    private lateinit var sensorChipView: TextView
    private lateinit var tabsView: LinearLayout
    private lateinit var chartView: GlucoseChartView
    private lateinit var legendTimeView: TextView
    private lateinit var legendTodayView: TextView
    private lateinit var legendYesterdayView: TextView
    private lateinit var legendView: View
    private lateinit var legendYesterdayGroup: View
    private lateinit var legendFingerstickView: View
    private lateinit var legendFingerstickLabel: TextView
    private lateinit var legendFingerstickValue: TextView

    private var hours = DisplaySettings.CHART_HOURS.first()
    private var range = TargetRange()
    private var chartWindowEnd = 0L
    private var hasFingersticks = false
    private var chart: ChartData? = null

    /** Bumped by every chart load, so a slower earlier load (or one overtaken by demo data) is dropped. */
    private var chartLoad = 0

    private val timeFormat = SimpleDateFormat("HH:mm", Locale.US)
    private val ticker = Handler(Looper.getMainLooper())
    private val tick = object : Runnable {
        override fun run() {
            renderReading()
            if (ChartWindow.forHours(hours, System.currentTimeMillis(), zone()).end != chartWindowEnd) loadChart()
            ticker.postDelayed(this, TICK_MS)
        }
    }

    private val onNewReading: () -> Unit = {
        runOnUiThread {
            renderReading()
            loadChart()
        }
    }
    private val onBandSynced: () -> Unit = { runOnUiThread(::renderChips) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        DemoMode.handle(this, intent)
        setContentView(R.layout.activity_main)

        bannerView = findViewById(R.id.setup_banner)
        timeLabelView = findViewById(R.id.time_label)
        valueView = findViewById(R.id.value)
        arrowView = findViewById(R.id.arrow)
        unitView = findViewById(R.id.unit)
        bandChipView = findViewById(R.id.chip_band)
        sensorChipView = findViewById(R.id.chip_sensor)
        tabsView = findViewById(R.id.tabs)
        chartView = findViewById(R.id.chart)
        legendTimeView = findViewById(R.id.legend_time)
        legendTodayView = findViewById(R.id.legend_today_value)
        legendYesterdayView = findViewById(R.id.legend_yesterday_value)
        legendView = findViewById(R.id.legend)
        legendYesterdayGroup = findViewById(R.id.legend_yesterday)
        legendFingerstickView = findViewById(R.id.legend_fingerstick)
        legendFingerstickLabel = findViewById(R.id.legend_fingerstick_label)
        legendFingerstickValue = findViewById(R.id.legend_fingerstick_value)
        chartView.onCursorChanged = ::renderLegend

        findViewById<View>(R.id.btn_settings).setOnClickListener { openSettings() }
        bannerView.setOnClickListener { openSettings() }
        buildTabs()

        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQUEST_NOTIFICATIONS)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        DemoMode.handle(this, intent)
    }

    override fun onResume() {
        super.onResume()
        hours = DisplaySettings.chartHours(this)
        range = DisplaySettings.targetRange(this)
        ReadingRepository.observe(onNewReading)
        SyncStatus.observe(onBandSynced)
        KeepAliveService.start(this)
        DesktopLink.start(this)

        bannerView.visibility = if (SetupChecks.isListenerEnabled(this)) View.GONE else View.VISIBLE
        renderTabs()
        renderReading()
        loadChart()
        ticker.postDelayed(tick, TICK_MS)
    }

    override fun onPause() {
        ticker.removeCallbacks(tick)
        ReadingRepository.stopObserving(onNewReading)
        SyncStatus.stopObserving(onBandSynced)
        super.onPause()
    }

    // ---- latest reading ------------------------------------------------------------------

    /** Demo data replaces real data on screen only; see [DemoMode]. */
    private fun demoPoints(): List<GlucosePoint>? =
        if (DemoMode.active) DemoData.points(System.currentTimeMillis(), zone()) else null

    private fun displayedState(): ReadingState =
        demoPoints()?.let { DemoData.state(it, zone()) } ?: ReadingRepository.state

    private fun renderReading() {
        val state = displayedState()
        val latest = state.latest
        val now = System.currentTimeMillis()
        if (latest == null) {
            timeLabelView.text = getString(R.string.waiting_for_data)
            valueView.text = getString(R.string.no_reading)
            valueView.setTextColor(getColor(R.color.text_secondary))
            arrowView.text = ""
            unitView.text = ""
        } else {
            timeLabelView.text = ReadingTimeLabel.format(latest.readingAt, now, zone())
            valueView.text = latest.value
            valueView.setTextColor(getColor(valueColor(latest, now)))
            arrowView.text = state.trend.arrow
            unitView.text = latest.unit
        }
        renderChips()
    }

    /** Stale, untimed or disconnected readings are greyed out so they are never mistaken for live data. */
    private fun valueColor(reading: GlucoseReading, now: Long): Int {
        val readingAt = reading.readingAt
        if (readingAt == null || now - readingAt > STALE_MS || !reading.connected) return R.color.level_stale
        return when (range.classify(reading.mmol)) {
            GlucoseLevel.LOW -> R.color.level_low
            GlucoseLevel.HIGH -> R.color.level_high
            GlucoseLevel.IN_RANGE -> R.color.level_in_range
            GlucoseLevel.UNKNOWN -> R.color.text_primary
        }
    }

    private fun renderChips() {
        val syncedAt = if (DemoMode.active) displayedState().latest?.readingAt else SyncStatus.lastBandSyncAt
        bandChipView.text = if (syncedAt == null) {
            getString(R.string.chip_band_waiting)
        } else {
            getString(R.string.chip_band_synced, timeFormat.format(Date(syncedAt)))
        }
        val sensorLost = displayedState().latest?.connected == false
        sensorChipView.text = getString(if (sensorLost) R.string.chip_sensor_lost else R.string.chip_sensor_ok)
        sensorChipView.setTextColor(getColor(if (sensorLost) R.color.level_low else R.color.text_secondary))
    }

    // ---- chart span tabs -----------------------------------------------------------------

    private fun buildTabs() {
        DisplaySettings.CHART_HOURS.forEach { h ->
            val tab = TextView(this, null, 0, R.style.SegmentItem).apply {
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f)
                text = getString(R.string.chart_hours, h)
                tag = h
                setOnClickListener { selectHours(h) }
            }
            tabsView.addView(tab)
        }
    }

    private fun selectHours(selected: Int) {
        if (selected == hours) return
        hours = selected
        DisplaySettings.saveChartHours(this, selected)
        renderTabs()
        loadChart()
    }

    private fun renderTabs() {
        (0 until tabsView.childCount).map { tabsView.getChildAt(it) as TextView }.forEach { tab ->
            val selected = tab.tag == hours
            tab.setBackgroundResource(if (selected) R.drawable.bg_segment_selected else 0)
            tab.setTypeface(null, if (selected) Typeface.BOLD else Typeface.NORMAL)
            tab.setTextColor(getColor(if (selected) R.color.text_primary else R.color.text_secondary))
        }
    }

    // ---- chart + statistics ---------------------------------------------------------------

    private fun loadChart() {
        val window = ChartWindow.forHours(hours, System.currentTimeMillis(), zone())
        chartWindowEnd = window.end
        val load = ++chartLoad
        val demo = demoPoints()
        if (demo != null) {
            showChart(ChartData.build(demo, window, DemoData.fingersticks(System.currentTimeMillis(), zone())))
            return
        }
        ReadingDatabase.get(this).load(window.start - DAY_MS, window.end) { points ->
            if (isDestroyed || load != chartLoad) return@load
            MeterDatabase.get(this).between(window.start, window.end) { fingersticks ->
                if (isDestroyed || load != chartLoad) return@between
                showChart(ChartData.build(points, window, fingersticks))
            }
        }
    }

    private fun showChart(data: ChartData) {
        chart = data
        hasFingersticks = data.fingersticks.isNotEmpty()
        chartView.setData(data, range)
        renderStats(GlucoseStats.compute(data.today, range))
        if (legendTimeView.visibility != View.VISIBLE) renderLegend(null)
    }

    /**
     * While the chart cursor is shown, the legend reads "12:27 — 今日 6.3 — 昨日 5.9", plus
     * "• 空腹 4.4" when the cursor sits on a fingerstick (tap it to change the tag).
     */
    private fun renderLegend(readout: CursorReadout?) {
        val visibility = if (readout == null) View.GONE else View.VISIBLE
        listOf(legendTimeView, legendTodayView, legendYesterdayView).forEach { it.visibility = visibility }
        renderFingerstickLegend(readout)
        if (readout != null) {
            legendTimeView.text = timeFormat.format(Date(readout.at))
            legendTodayView.text = readout.today?.let { format(it.mmol) } ?: getString(R.string.stat_empty)
            legendYesterdayView.text = readout.yesterday?.let { format(it.mmol) } ?: getString(R.string.stat_empty)
        }
        fitLegend() // after every text is set, so the measurement sees the final widths
    }

    private fun renderFingerstickLegend(readout: CursorReadout?) {
        val stick = readout?.fingerstick
        legendFingerstickView.visibility = if (stick != null || (readout == null && hasFingersticks)) View.VISIBLE else View.GONE
        legendFingerstickValue.visibility = if (stick != null) View.VISIBLE else View.GONE
        legendFingerstickLabel.text = stick?.let { FingerstickTags.label(this, it) } ?: getString(R.string.legend_fingerstick)
        legendFingerstickValue.text = stick?.record?.label
        legendFingerstickLabel.visibility = View.VISIBLE
        // Demo results are not in the database, so there is nothing to tag.
        if (stick != null && stick.device != DemoData.DEVICE) {
            legendFingerstickView.setOnClickListener { retag(stick) }
        } else {
            legendFingerstickView.setOnClickListener(null)
            legendFingerstickView.isClickable = false
        }
    }

    /** Shows the new tag at once (the cursor may have hidden while the dialog was open), then reloads. */
    private fun retag(stick: Fingerstick) {
        FingerstickTags.pick(this, stick) { tag ->
            chart?.let { showChart(it.withTag(stick.device, stick.record.sequence, tag)) }
            chartView.showCursor(stick.at)
            loadChart()
        }
    }

    /**
     * Narrow screens and large fonts cannot fit the whole row: drop yesterday, then the cursor
     * time, then the fingerstick's tag, until it fits; the values themselves always stay.
     */
    private fun fitLegend() {
        legendYesterdayGroup.visibility = View.VISIBLE
        if (legendView.width <= 0) return // not laid out yet
        val droppable = listOf(legendYesterdayGroup, legendTimeView, legendFingerstickLabel)
            .filter { it.visibility == View.VISIBLE }
        val unbounded = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        for (view in droppable) {
            legendView.measure(unbounded, unbounded)
            if (legendView.measuredWidth <= legendView.width) break
            view.visibility = View.GONE
        }
        legendView.requestLayout()
    }

    private fun renderStats(stats: GlucoseStats?) {
        val tir = findViewById<TextView>(R.id.stat_tir_value)
        val swing = findViewById<TextView>(R.id.stat_swing_value)
        val highLabel = findViewById<TextView>(R.id.stat_high_label)
        val high = findViewById<TextView>(R.id.stat_high_value)
        val lowLabel = findViewById<TextView>(R.id.stat_low_label)
        val low = findViewById<TextView>(R.id.stat_low_value)
        val values = listOf(tir, swing, high, low)
        values.forEach {
            it.setAutoSizeTextTypeUniformWithConfiguration(
                STAT_MIN_SP, STAT_MAX_SP, 1, TypedValue.COMPLEX_UNIT_SP,
            )
        }
        if (stats == null) {
            values.forEach { it.text = getString(R.string.stat_empty) }
            highLabel.text = getString(R.string.stat_highest_plain)
            lowLabel.text = getString(R.string.stat_lowest_plain)
            equalizeTextSize(values)
            return
        }
        tir.text = numberWithUnit(String.format(Locale.US, "%.1f", stats.timeInRangePercent), getString(R.string.unit_percent))
        swing.text = numberWithUnit(format(stats.maxSwing), MMOL)
        highLabel.text = getString(R.string.stat_highest, timeFormat.format(Date(stats.highest.at)))
        high.text = numberWithUnit(format(stats.highest.mmol), MMOL)
        lowLabel.text = getString(R.string.stat_lowest, timeFormat.format(Date(stats.lowest.at)))
        low.text = numberWithUnit(format(stats.lowest.mmol), MMOL)
        equalizeTextSize(values)
    }

    /**
     * Auto-size fits each value separately; once that layout pass is done, pin all four to the
     * smallest size so the row reads evenly (the cancelled draw is redone with the final sizes).
     */
    private fun equalizeTextSize(views: List<TextView>) {
        val anchor = views.first()
        anchor.viewTreeObserver.addOnPreDrawListener(object : ViewTreeObserver.OnPreDrawListener {
            override fun onPreDraw(): Boolean {
                anchor.viewTreeObserver.removeOnPreDrawListener(this)
                val smallest = views.minOf { it.textSize }
                views.forEach {
                    it.setAutoSizeTextTypeWithDefaults(TextView.AUTO_SIZE_TEXT_TYPE_NONE)
                    it.setTextSize(TypedValue.COMPLEX_UNIT_PX, smallest)
                }
                return false
            }
        })
    }

    /** Big bold number followed by a small regular unit, as in the iCan statistics row. */
    private fun numberWithUnit(number: String, unit: String): CharSequence =
        SpannableStringBuilder(number).apply {
            setSpan(StyleSpan(Typeface.BOLD), 0, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            val start = length
            append(" ").append(unit)
            setSpan(RelativeSizeSpan(UNIT_SCALE), start, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }

    private fun format(value: Double) = String.format(Locale.US, "%.1f", value)

    private fun openSettings() = startActivity(Intent(this, SettingsActivity::class.java))

    private fun zone(): ZoneId = ZoneId.systemDefault()

    companion object {
        private const val REQUEST_NOTIFICATIONS = 1
        private const val TICK_MS = 30_000L
        private const val STALE_MS = 10 * 60_000L
        private const val DAY_MS = 24 * 60 * 60_000L
        private const val UNIT_SCALE = 0.55f
        private const val STAT_MIN_SP = 12
        private const val STAT_MAX_SP = 24
        private const val MMOL = "mmol/L"
    }
}
