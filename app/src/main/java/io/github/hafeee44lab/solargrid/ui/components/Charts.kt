package io.github.hafeee44lab.solargrid.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.hafeee44lab.solargrid.data.DayEnergy
import io.github.hafeee44lab.solargrid.data.Sample
import io.github.hafeee44lab.solargrid.domain.HeatmapDay
import io.github.hafeee44lab.solargrid.domain.OutageLog
import io.github.hafeee44lab.solargrid.domain.OutageRecord
import io.github.hafeee44lab.solargrid.domain.OutageSlot
import io.github.hafeee44lab.solargrid.ui.theme.Solar
import io.github.hafeee44lab.solargrid.ui.theme.SolarType
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.roundToInt

// ---------------------------------------------------------------------------------------------
// Day chart
// ---------------------------------------------------------------------------------------------

/**
 * Solar, home use and battery level across a day, outages hatched. The legend above reads
 * the latest values, or the values under your finger while you press and drag — the way the
 * SOLARMAN report reads them.
 */
@Composable
fun DayChart(
    samples: List<Sample>,
    outages: List<OutageRecord>,
    from: Long,
    to: Long,
    now: Long,
    modifier: Modifier = Modifier,
    height: Dp = 180.dp
) {
    val c = Solar.colors
    val haptics = LocalHapticFeedback.current
    val density = LocalDensity.current
    val still = Solar.reduceMotion

    val reveal = remember(from) { Animatable(if (still) 1f else 0f) }
    LaunchedEffect(from) { reveal.animateTo(1f, tween(900, easing = FastOutSlowInEasing)) }

    var scrub by remember { mutableStateOf<Float?>(null) }
    var lastIndex by remember { mutableIntStateOf(-1) }

    val peak = max(1000.0, samples.maxOfOrNull { max(it.solarW ?: 0.0, it.loadW ?: 0.0) } ?: 0.0)
    val ceilingKw = ceil(peak / 1000.0)
    val ceiling = ceilingKw * 1000.0
    val span = (to - from).coerceAtLeast(1L).toDouble()
    val time = remember { SimpleDateFormat("HH:mm", Locale.getDefault()) }
    val batteryColor = c.batteryHigh

    BoxWithConstraints(modifier.fillMaxWidth()) {
        val widthPx = with(density) { maxWidth.toPx() }

        fun nearest(x: Float): Int {
            if (samples.isEmpty()) return -1
            val t = from + (x / widthPx).coerceIn(0f, 1f) * span
            var best = 0
            var bestGap = Double.MAX_VALUE
            samples.forEachIndexed { i, s ->
                val gap = abs(s.at - t)
                if (gap < bestGap) { bestGap = gap; best = i }
            }
            return best
        }

        val shown = scrub?.let { samples.getOrNull(nearest(it)) } ?: samples.lastOrNull()
        val summary = "Day chart from ${time.format(Date(from))}. " +
            "${outages.count { (it.endedAt ?: now) > from && it.startedAt < to }} outages hatched."

        Column(Modifier.semantics { contentDescription = summary }) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                LegendValue("Solar", shown?.solarW?.let { "%.2f kW".format(it / 1000) } ?: "—", c.solar)
                LegendValue("Home", shown?.loadW?.let { "%.2f kW".format(it / 1000) } ?: "—", c.home)
                LegendValue("Battery", shown?.soc?.let { "%.0f%%".format(it) } ?: "—", batteryColor)
            }
            Spacer(Modifier.height(12.dp))
            Box(Modifier.fillMaxWidth().height(height)) {
                Canvas(
                    Modifier
                        .fillMaxSize()
                        // Long-press then drag so scrubbing never fights the page scroll; a tap pins a reading.
                        .pointerInput(samples, from, to) {
                            detectDragGesturesAfterLongPress(
                                onDragStart = {
                                    scrub = it.x
                                    lastIndex = nearest(it.x)
                                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                },
                                onDragEnd = { scrub = null; lastIndex = -1 },
                                onDragCancel = { scrub = null; lastIndex = -1 },
                                onDrag = { change, _ ->
                                    change.consume()
                                    scrub = change.position.x
                                    val i = nearest(change.position.x)
                                    if (i != lastIndex) {
                                        lastIndex = i
                                        haptics.performHapticFeedback(HapticFeedbackType.SegmentFrequentTick)
                                    }
                                }
                            )
                        }
                        .pointerInput(samples, from, to) {
                            detectTapGestures { pos -> scrub = if (scrub == null) pos.x else null }
                        }
                ) {
                    val w = size.width
                    val top = 18.dp.toPx()
                    val bottom = size.height
                    val plotH = bottom - top

                    fun x(t: Long) = ((t - from) / span * w).toFloat()
                    fun yW(watts: Double) = (bottom - (watts / ceiling) * plotH).toFloat()
                    fun ySoc(soc: Double) = (bottom - (soc / 100.0) * plotH).toFloat()

                    for (i in 0..4) {
                        val y = top + plotH * i / 4f
                        drawLine(c.outline, Offset(0f, y), Offset(w, y), 1.dp.toPx(), pathEffect = if (i < 4) PathEffect.dashPathEffect(floatArrayOf(3.dp.toPx(), 4.dp.toPx())) else null)
                    }

                    outages.forEach { o ->
                        val start = max(o.startedAt, from)
                        val end = minOf(o.endedAt ?: now, to)
                        if (end <= start) return@forEach
                        hatch(x(start), x(end).coerceAtLeast(x(start) + 2.dp.toPx()), top, bottom, c.down)
                    }

                    clipRect(right = w * reveal.value) {
                        val gap = 25 * 60_000L
                        fun line(value: (Sample) -> Double?, y: (Double) -> Float): Path {
                            val path = Path()
                            var pen = false
                            var prev: Sample? = null
                            samples.forEach { s ->
                                val v = value(s)
                                if (v == null || (prev != null && s.at - prev!!.at > gap)) pen = false
                                if (v != null) {
                                    if (!pen) path.moveTo(x(s.at), y(v)) else path.lineTo(x(s.at), y(v))
                                    pen = true
                                }
                                prev = s
                            }
                            return path
                        }

                        val solarLine = line({ it.solarW }, ::yW)
                        val first = samples.firstOrNull { it.solarW != null }
                        val last = samples.lastOrNull { it.solarW != null }
                        if (first != null && last != null) {
                            val area = Path().apply {
                                addPath(solarLine)
                                lineTo(x(last.at), bottom)
                                lineTo(x(first.at), bottom)
                                close()
                            }
                            drawPath(area, Brush.verticalGradient(listOf(c.solar.copy(alpha = 0.28f), c.solar.copy(alpha = 0.04f)), startY = top, endY = bottom))
                        }
                        drawPath(solarLine, c.solar, style = Stroke(1.6.dp.toPx(), join = StrokeJoin.Round))
                        drawPath(line({ it.loadW }, ::yW), c.home, style = Stroke(1.4.dp.toPx(), join = StrokeJoin.Round))
                        drawPath(line({ it.soc }, ::ySoc), batteryColor, style = Stroke(1.6.dp.toPx(), join = StrokeJoin.Round, pathEffect = PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 3.dp.toPx()))))
                    }

                    val sx = scrub
                    if (sx != null && samples.isNotEmpty()) {
                        val s = samples[nearest(sx).coerceAtLeast(0)]
                        val px = x(s.at)
                        drawLine(c.ink3, Offset(px, top), Offset(px, bottom), 1.dp.toPx())
                        s.solarW?.let { drawCircle(c.surface, 5.dp.toPx(), Offset(px, yW(it))); drawCircle(c.solar, 3.5.dp.toPx(), Offset(px, yW(it))) }
                        s.loadW?.let { drawCircle(c.surface, 5.dp.toPx(), Offset(px, yW(it))); drawCircle(c.home, 3.5.dp.toPx(), Offset(px, yW(it))) }
                        s.soc?.let { drawCircle(c.surface, 5.dp.toPx(), Offset(px, ySoc(it))); drawCircle(batteryColor, 3.5.dp.toPx(), Offset(px, ySoc(it))) }
                    } else if (now in from..to) {
                        val nx = x(now)
                        drawLine(c.outlineStrong, Offset(nx, top), Offset(nx, bottom), 1.dp.toPx())
                    }
                }

                Text("${ceilingKw.roundToInt()} kW", style = SolarType.caption, color = c.ink3, modifier = Modifier.align(Alignment.TopStart))
                // The dashed battery line reads against its own 0–100% scale on the right.
                Text("100%", style = SolarType.caption, color = batteryColor, modifier = Modifier.align(Alignment.TopEnd))
                Text("0%", style = SolarType.caption, color = batteryColor, modifier = Modifier.align(Alignment.BottomEnd).padding(bottom = 2.dp))
                val label = scrub?.let { samples.getOrNull(nearest(it)) }?.let { time.format(Date(it.at)) + if (it.gridDown == true) " · grid off" else "" }
                if (label != null) {
                    Text(label, style = SolarType.caption, color = c.ink, modifier = Modifier.align(Alignment.TopCenter))
                }
            }
            Row(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                for (i in 0..4) {
                    Text(time.format(Date(from + (to - from) * i / 4)), style = SolarType.caption, color = c.ink3)
                }
            }
        }
    }
}

/** Outage bands are hatched, not just tinted, so they read without colour. */
private fun DrawScope.hatch(left: Float, right: Float, top: Float, bottom: Float, color: Color) {
    drawRect(color.copy(alpha = 0.06f), Offset(left, top), Size(right - left, bottom - top))
    clipRect(left, top, right, bottom) {
        val step = 6.dp.toPx()
        var x = left - (bottom - top)
        while (x < right) {
            drawLine(color.copy(alpha = 0.28f), Offset(x, bottom), Offset(x + (bottom - top), top), 1.dp.toPx())
            x += step
        }
    }
}

@Composable
private fun LegendValue(label: String, value: String, color: Color) {
    val c = Solar.colors
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(color))
        Spacer(Modifier.width(6.dp))
        Text("$label ", style = SolarType.caption, color = c.ink3)
        Text(value, style = SolarType.caption.copy(fontFeatureSettings = "tnum"), color = c.ink)
    }
}

@Composable
fun Legend(label: String, color: Color) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(color))
        Spacer(Modifier.width(6.dp))
        Text(label, style = SolarType.caption, color = Solar.colors.ink3)
    }
}

// ---------------------------------------------------------------------------------------------
// Outage map
// ---------------------------------------------------------------------------------------------

/**
 * Hour by day, how long the mains were off. A load-shedding schedule shows up as columns.
 * Tap a cell for the exact minutes.
 */
@Composable
fun OutageHeatmap(days: List<HeatmapDay>, modifier: Modifier = Modifier, onPick: (HeatmapDay, Int) -> Unit = { _, _ -> }) {
    val c = Solar.colors
    val haptics = LocalHapticFeedback.current
    val dayFormat = remember { SimpleDateFormat("EEE d", Locale.getDefault()) }
    val labelW = 48.dp
    var picked by remember { mutableStateOf<Pair<Int, Int>?>(null) }

    Column(modifier) {
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val rowH = ((maxWidth - labelW) / 24).coerceIn(10.dp, 16.dp)
            Row {
                Column(Modifier.width(labelW)) {
                    days.forEach { day ->
                        Box(Modifier.height(rowH).semantics { contentDescription = "${dayFormat.format(Date(day.dayStart))}: ${spokenCuts(day)}" }, contentAlignment = Alignment.CenterStart) {
                            Text(dayFormat.format(Date(day.dayStart)), style = SolarType.caption.copy(fontSize = SolarType.caption.fontSize * 0.9f), color = c.ink3, maxLines = 1)
                        }
                    }
                }
                Canvas(
                    Modifier
                        .fillMaxWidth()
                        .height(rowH * days.size)
                        // Cells are too small to hit one by one: tap or drag across the grid to read it.
                        .pointerInput(days) {
                            fun pick(pos: Offset) {
                                val col = (pos.x / (size.width / 24f)).toInt().coerceIn(0, 23)
                                val row = (pos.y / (size.height / days.size.toFloat())).toInt().coerceIn(0, days.lastIndex)
                                if (picked != row to col) {
                                    picked = row to col
                                    haptics.performHapticFeedback(HapticFeedbackType.SegmentFrequentTick)
                                    onPick(days[row], col)
                                }
                            }
                            detectHorizontalDragGestures(onDragStart = ::pick) { change, _ -> change.consume(); pick(change.position) }
                        }
                        .pointerInput(days) {
                            detectTapGestures { pos ->
                                val col = (pos.x / (size.width / 24f)).toInt().coerceIn(0, 23)
                                val row = (pos.y / (size.height / days.size.toFloat())).toInt().coerceIn(0, days.lastIndex)
                                picked = row to col
                                onPick(days[row], col)
                            }
                        }
                ) {
                    val cw = size.width / 24f
                    val ch = size.height / days.size
                    val gap = 1.5.dp.toPx()
                    days.forEachIndexed { row, day ->
                        for (col in 0 until 24) {
                            val minutes = day.minutesDown[col]
                            val color = if (minutes == 0) c.surfaceVariant else lerp(c.down.copy(alpha = 0.25f), c.down, (minutes / 60f).coerceIn(0f, 1f))
                            val origin = Offset(col * cw + gap / 2, row * ch + gap / 2)
                            val cell = Size(cw - gap, ch - gap)
                            drawRoundRect(color, origin, cell, CornerRadius(2.dp.toPx()))
                            if (picked == (row to col)) drawRoundRect(c.ink, origin, cell, CornerRadius(2.dp.toPx()), style = Stroke(1.5.dp.toPx()))
                        }
                    }
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(start = labelW, top = 6.dp)) {
            listOf("00", "06", "12", "18").forEach {
                Text(it, style = SolarType.caption, color = c.ink3, modifier = Modifier.weight(1f))
            }
        }
    }
}

/** "off 07:00–09:00, 20:00–22:00" from a day's hour cells, for TalkBack. */
private fun spokenCuts(day: HeatmapDay): String {
    val spans = mutableListOf<IntRange>()
    var start = -1
    for (h in 0..24) {
        val off = h < 24 && day.minutesDown[h] > 0
        if (off && start < 0) start = h
        if (!off && start >= 0) { spans += start until h; start = -1 }
    }
    if (spans.isEmpty()) return "mains on all day"
    return "off " + spans.joinToString(", ") { "%02d:00 to %02d:00".format(it.first, it.last + 1) }
}

// ---------------------------------------------------------------------------------------------
// Daily energy bars
// ---------------------------------------------------------------------------------------------

/** Solar made against energy used, one pair of bars per day. Tap a day to read it. */
@Composable
fun EnergyBars(days: List<DayEnergy>, modifier: Modifier = Modifier, height: Dp = 140.dp, onPick: (DayEnergy?) -> Unit = {}) {
    val c = Solar.colors
    val still = Solar.reduceMotion
    val grow = remember(days.size) { Animatable(if (still) 1f else 0f) }
    LaunchedEffect(days.size) { grow.animateTo(1f, tween(700, easing = FastOutSlowInEasing)) }
    var picked by remember { mutableIntStateOf(-1) }
    val top = max(1.0, days.maxOfOrNull { max(it.energy.producedKwh ?: 0.0, it.energy.consumedKwh ?: 0.0) } ?: 1.0)

    Canvas(
        modifier
            .fillMaxWidth()
            .height(height)
            .pointerInput(days) {
                detectTapGestures { pos ->
                    if (days.isEmpty()) return@detectTapGestures
                    val i = (pos.x / (size.width / days.size.toFloat())).toInt().coerceIn(0, days.lastIndex)
                    picked = if (picked == i) -1 else i
                    onPick(days.getOrNull(picked))
                }
            }
    ) {
        if (days.isEmpty()) return@Canvas
        val slot = size.width / days.size
        val barW = (slot * 0.32f).coerceAtMost(10.dp.toPx())
        val bottom = size.height
        val radius = CornerRadius(2.dp.toPx())
        drawLine(c.outline, Offset(0f, bottom), Offset(size.width, bottom), 1.dp.toPx())

        days.forEachIndexed { i, day ->
            val cx = slot * i + slot / 2
            val alpha = if (picked == -1 || picked == i) 1f else 0.35f
            val ph = ((day.energy.producedKwh ?: 0.0) / top * size.height * grow.value).toFloat()
            val chh = ((day.energy.consumedKwh ?: 0.0) / top * size.height * grow.value).toFloat()
            drawRoundRect(c.solar.copy(alpha = alpha), Offset(cx - barW - 1.dp.toPx(), bottom - ph), Size(barW, ph), radius)
            drawRoundRect(c.home.copy(alpha = 0.55f * alpha), Offset(cx + 1.dp.toPx(), bottom - chh), Size(barW, chh), radius)
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Learned schedule
// ---------------------------------------------------------------------------------------------

/** The day as one bar with the learned outage slots on it and a tick for now. */
@Composable
fun ScheduleRibbon(slots: List<OutageSlot>, nowMinute: Int, nextMinute: Int?, modifier: Modifier = Modifier) {
    val c = Solar.colors
    Column(modifier) {
        Canvas(Modifier.fillMaxWidth().height(22.dp)) {
            val barTop = 6.dp.toPx()
            val barH = 10.dp.toPx()
            val r = CornerRadius(barH / 2)
            drawRoundRect(c.surfaceVariant, Offset(0f, barTop), Size(size.width, barH), r)
            slots.forEach { slot ->
                val start = slot.minuteOfDay / 1440f * size.width
                val length = ((slot.typicalDurationMs ?: 3_600_000L) / 60_000f / 1440f * size.width).coerceAtLeast(6.dp.toPx())
                val next = slot.minuteOfDay == nextMinute
                // A forecast, not an outage: a lighter tint than real cuts get anywhere else.
                val color = c.down.copy(alpha = if (next) 0.55f else 0.2f + 0.2f * slot.share.toFloat())
                drawRoundRect(color, Offset(start, barTop), Size(length.coerceAtMost(size.width - start), barH), r)
                if (start + length > size.width) drawRoundRect(color, Offset(0f, barTop), Size(start + length - size.width, barH), r)
            }
            val nx = nowMinute / 1440f * size.width
            drawLine(c.ink, Offset(nx, 0f), Offset(nx, size.height), 2.dp.toPx(), StrokeCap.Round)
        }
        Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            listOf("00:00", "06:00", "12:00", "18:00", "24:00").forEach { Text(it, style = SolarType.caption, color = c.ink3) }
        }
    }
}

fun minuteLabel(minute: Int): String = "%02d:%02d".format(minute / 60, minute % 60)

fun durationLabel(ms: Long?): String = ms?.let { OutageLog.describeDuration(it) } ?: "—"
