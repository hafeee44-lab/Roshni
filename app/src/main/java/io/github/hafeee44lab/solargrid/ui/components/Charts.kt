package io.github.hafeee44lab.solargrid.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import io.github.hafeee44lab.solargrid.domain.BatteryMode
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
// Battery ring
// ---------------------------------------------------------------------------------------------

/**
 * State of charge as a 270° arc. Charging sends a highlight sweeping along it; a low pack
 * breathes in coral; the reserve the inverter holds back is marked as a notch.
 */
@Composable
fun BatteryRing(
    soc: Double?,
    mode: BatteryMode,
    reservePercent: Int,
    lowPercent: Int,
    modifier: Modifier = Modifier,
    size: Dp = 148.dp,
    content: @Composable () -> Unit = {}
) {
    val colors = Solar.colors
    val still = Solar.reduceMotion
    val fraction by animateFloatAsState(((soc ?: 0.0) / 100.0).toFloat().coerceIn(0f, 1f), tween(1100, easing = FastOutSlowInEasing), label = "ring")
    val low = soc != null && soc <= lowPercent

    val transition = rememberInfiniteTransition(label = "ringFx")
    val sweep by transition.animateFloat(0f, 1f, infiniteRepeatable(tween(2200, easing = LinearEasing)), label = "sweep")
    val breath by transition.animateFloat(0.55f, 1f, infiniteRepeatable(tween(1100), RepeatMode.Reverse), label = "breath")

    val accent = when {
        low -> colors.down
        mode == BatteryMode.CHARGING -> colors.ok
        else -> colors.battery
    }

    Box(modifier.size(size), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val stroke = 12.dp.toPx()
            val inset = stroke / 2 + 4.dp.toPx()
            val arcSize = Size(this.size.width - inset * 2, this.size.height - inset * 2)
            val topLeft = Offset(inset, inset)
            val start = 135f
            val total = 270f

            drawArc(colors.track, start, total, false, topLeft, arcSize, style = Stroke(stroke, cap = StrokeCap.Round))

            if (fraction > 0f) {
                val alpha = if (low && !still) breath else 1f
                drawArc(
                    brush = Brush.sweepGradient(
                        0f to accent.copy(alpha = 0.55f * alpha),
                        0.75f to accent.copy(alpha = alpha),
                        1f to accent.copy(alpha = 0.55f * alpha),
                        center = center
                    ),
                    startAngle = start,
                    sweepAngle = total * fraction,
                    useCenter = false,
                    topLeft = topLeft,
                    size = arcSize,
                    style = Stroke(stroke, cap = StrokeCap.Round)
                )

                if (mode == BatteryMode.CHARGING && !still) {
                    val head = start + total * fraction * sweep
                    drawArc(
                        color = Color.White.copy(alpha = 0.55f * (1f - sweep)),
                        startAngle = head - 10f,
                        sweepAngle = 10f,
                        useCenter = false,
                        topLeft = topLeft,
                        size = arcSize,
                        style = Stroke(stroke * 0.55f, cap = StrokeCap.Round)
                    )
                }
            }

            // The reserve notch: below this the inverter will not discharge.
            val notch = Math.toRadians((start + total * reservePercent / 100f).toDouble())
            val rOuter = arcSize.width / 2 + stroke * 0.9f
            val rInner = arcSize.width / 2 - stroke * 0.9f
            drawLine(
                colors.ink3,
                Offset(center.x + (rInner * kotlin.math.cos(notch)).toFloat(), center.y + (rInner * kotlin.math.sin(notch)).toFloat()),
                Offset(center.x + (rOuter * kotlin.math.cos(notch)).toFloat(), center.y + (rOuter * kotlin.math.sin(notch)).toFloat()),
                strokeWidth = 2.dp.toPx(),
                cap = StrokeCap.Round
            )
        }
        content()
    }
}

// ---------------------------------------------------------------------------------------------
// Day chart
// ---------------------------------------------------------------------------------------------

/**
 * Solar, home load and battery level across a window, with outages shaded. Drag across it to
 * read exact values; it ticks under your finger as you pass each reading.
 */
@Composable
fun DayChart(
    samples: List<Sample>,
    outages: List<OutageRecord>,
    from: Long,
    to: Long,
    now: Long,
    modifier: Modifier = Modifier,
    height: Dp = 196.dp
) {
    val colors = Solar.colors
    val haptics = LocalHapticFeedback.current
    val density = LocalDensity.current
    val still = Solar.reduceMotion

    val reveal = remember(from) { Animatable(if (still) 1f else 0f) }
    LaunchedEffect(from) { reveal.animateTo(1f, tween(1300, easing = FastOutSlowInEasing)) }

    var scrub by remember { mutableStateOf<Float?>(null) }
    var lastIndex by remember { mutableIntStateOf(-1) }

    val peak = max(1000.0, samples.maxOfOrNull { max(it.solarW ?: 0.0, it.loadW ?: 0.0) } ?: 0.0)
    val ceilingKw = ceil(peak / 1000.0)
    val ceiling = ceilingKw * 1000.0
    val span = (to - from).coerceAtLeast(1L).toDouble()
    val timeFormat = remember { SimpleDateFormat("HH:mm", Locale.getDefault()) }

    val summary = "Chart of solar, home use and battery from ${timeFormat.format(Date(from))} to ${timeFormat.format(Date(minOf(to, now)))}, " +
        "${outages.count { (it.endedAt ?: now) > from && it.startedAt < to }} outages shaded."

    Column(modifier.semantics { contentDescription = summary }) {
        Text("Tap or press and drag to read values", style = SolarType.caption, color = colors.ink3.copy(alpha = 0.8f), modifier = Modifier.padding(bottom = 8.dp))
        BoxWithConstraints(Modifier.fillMaxWidth().height(height)) {
            val widthPx = with(density) { maxWidth.toPx() }

            fun nearest(x: Float): Int {
                if (samples.isEmpty()) return -1
                val t = from + (x / widthPx).coerceIn(0f, 1f) * span
                var best = 0
                var bestGap = Double.MAX_VALUE
                samples.forEachIndexed { i, s ->
                    val gap = abs(s.at - t)
                    if (gap < bestGap) {
                        bestGap = gap
                        best = i
                    }
                }
                return best
            }

            Canvas(
                Modifier
                    .fillMaxSize()
                    // Scrubbing starts with a long-press so it never fights the page scroll;
                    // a plain tap pins the readout at that time instead.
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
                val h = size.height
                val top = 8.dp.toPx()
                val bottom = h - 4.dp.toPx()
                val plotH = bottom - top

                fun x(t: Long) = ((t - from) / span * w).toFloat()
                fun yW(watts: Double) = (bottom - (watts / ceiling) * plotH).toFloat()
                fun ySoc(soc: Double) = (bottom - (soc / 100.0) * plotH).toFloat()

                // Guides
                for (i in 1..3) {
                    val y = top + plotH * i / 4f
                    drawLine(colors.hairline, Offset(0f, y), Offset(w, y), 1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(3.dp.toPx(), 5.dp.toPx())))
                }
                drawLine(colors.hairlineStrong, Offset(0f, bottom), Offset(w, bottom), 1.dp.toPx())

                // Outages
                outages.forEach { o ->
                    val start = max(o.startedAt, from)
                    val end = minOf(o.endedAt ?: now, to)
                    if (end <= start) return@forEach
                    val left = x(start)
                    val right = x(end).coerceAtLeast(left + 2.dp.toPx())
                    drawRect(colors.down.copy(alpha = if (colors.isDark) 0.13f else 0.10f), Offset(left, top), Size(right - left, plotH))
                    drawRoundRect(colors.down.copy(alpha = 0.85f), Offset(left, top - 3.dp.toPx()), Size(right - left, 3.dp.toPx()), CornerRadius(2.dp.toPx()))
                }

                clipRect(right = w * reveal.value) {
                    val gapLimit = 25 * 60_000L

                    fun line(value: (Sample) -> Double?, y: (Double) -> Float): Path {
                        val path = Path()
                        var pen = false
                        var prev: Sample? = null
                        samples.forEach { s ->
                            val v = value(s)
                            if (v == null || (prev != null && s.at - prev!!.at > gapLimit)) pen = false
                            if (v != null) {
                                if (!pen) path.moveTo(x(s.at), y(v)) else path.lineTo(x(s.at), y(v))
                                pen = true
                            }
                            prev = s
                        }
                        return path
                    }

                    // Solar area
                    val solarPoints = samples.filter { it.solarW != null }
                    if (solarPoints.size > 1) {
                        val area = Path()
                        var started = false
                        var lastX = 0f
                        var prev: Sample? = null
                        solarPoints.forEach { s ->
                            val px = x(s.at)
                            val py = yW(s.solarW!!)
                            if (!started || (prev != null && s.at - prev!!.at > gapLimit)) {
                                if (started) {
                                    area.lineTo(lastX, bottom)
                                    area.close()
                                }
                                area.moveTo(px, bottom)
                                area.lineTo(px, py)
                                started = true
                            } else {
                                area.lineTo(px, py)
                            }
                            lastX = px
                            prev = s
                        }
                        area.lineTo(lastX, bottom)
                        area.close()
                        drawPath(area, Brush.verticalGradient(listOf(colors.solar.copy(alpha = 0.38f), colors.solar.copy(alpha = 0.02f)), startY = top, endY = bottom))
                    }
                    drawPath(line({ it.solarW }, ::yW), colors.solar, style = Stroke(2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
                    drawPath(line({ it.loadW }, ::yW), colors.ink2.copy(alpha = 0.9f), style = Stroke(1.5.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
                    drawPath(line({ it.soc }, ::ySoc), colors.battery, style = Stroke(2.2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
                }

                // Now marker
                if (now in from..to) {
                    val nx = x(now)
                    drawLine(colors.ink3, Offset(nx, top), Offset(nx, bottom), 1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(2.dp.toPx(), 4.dp.toPx())))
                }

                // Scrubber
                val sx = scrub
                if (sx != null && samples.isNotEmpty()) {
                    val s = samples[nearest(sx).coerceAtLeast(0)]
                    val px = x(s.at)
                    drawLine(colors.ink.copy(alpha = 0.5f), Offset(px, top), Offset(px, bottom), 1.dp.toPx())
                    s.solarW?.let { drawCircle(colors.solar, 4.5.dp.toPx(), Offset(px, yW(it))) }
                    s.loadW?.let { drawCircle(colors.ink2, 4.dp.toPx(), Offset(px, yW(it))) }
                    s.soc?.let { drawCircle(colors.battery, 4.5.dp.toPx(), Offset(px, ySoc(it))) }
                }
            }

            // Axis captions
            Text("${ceilingKw.roundToInt()} kW", style = SolarType.caption, color = colors.ink3, modifier = Modifier.align(Alignment.TopStart))
            Text("100%", style = SolarType.caption, color = colors.battery.copy(alpha = 0.8f), modifier = Modifier.align(Alignment.TopEnd))

            // Tooltip
            val sx = scrub
            if (sx != null && samples.isNotEmpty()) {
                val s = samples[nearest(sx).coerceAtLeast(0)]
                val tipW = 150.dp
                val left = with(density) { (sx.toDp() - tipW / 2).coerceIn(0.dp, maxWidth - tipW) }
                Column(
                    Modifier
                        .offset(x = left, y = 18.dp)
                        .width(tipW)
                        .clip(RoundedCornerShape(14.dp))
                        .background(colors.surfaceHigh)
                        .border(1.dp, colors.hairlineStrong, RoundedCornerShape(14.dp))
                        .padding(horizontal = 12.dp, vertical = 9.dp)
                ) {
                    Text(timeFormat.format(Date(s.at)) + if (s.gridDown == true) " · grid down" else "", style = SolarType.label, color = if (s.gridDown == true) colors.down else colors.ink3)
                    TipLine("Solar", s.solarW?.let { "%.2f kW".format(it / 1000) } ?: "—", colors.solar)
                    TipLine("Home", s.loadW?.let { "%.2f kW".format(it / 1000) } ?: "—", colors.ink2)
                    TipLine("Battery", s.soc?.let { "%.0f%%".format(it) } ?: "—", colors.battery)
                }
            }
        }

        // Hour axis
        Row(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            val ticks = 4
            for (i in 0..ticks) {
                val t = from + ((to - from) * i / ticks)
                Text(timeFormat.format(Date(t)), style = SolarType.caption, color = colors.ink3)
            }
        }

        Row(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Legend("Solar", colors.solar)
            Legend("Home", colors.ink2)
            Legend("Battery %", colors.battery)
            Legend("Outage", colors.down)
        }
    }
}

@Composable
private fun TipLine(label: String, value: String, color: Color) {
    Row(Modifier.fillMaxWidth().padding(top = 3.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(7.dp).clip(CircleShape).background(color))
        Spacer(Modifier.width(6.dp))
        Text(label, style = SolarType.caption, color = Solar.colors.ink2, modifier = Modifier.weight(1f))
        Text(value, style = SolarType.caption.copy(fontFeatureSettings = "tnum"), color = Solar.colors.ink)
    }
}

@Composable
fun Legend(label: String, color: Color) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(width = 12.dp, height = 4.dp).clip(CircleShape).background(color))
        Spacer(Modifier.width(6.dp))
        Text(label, style = SolarType.caption, color = Solar.colors.ink3)
    }
}

// ---------------------------------------------------------------------------------------------
// Outage heatmap
// ---------------------------------------------------------------------------------------------

/**
 * Hour by day, how long the mains were off. Load-shedding schedules jump out as columns.
 * Tap a cell for the exact minutes.
 */
@Composable
fun OutageHeatmap(
    days: List<HeatmapDay>,
    modifier: Modifier = Modifier,
    onPick: (HeatmapDay, Int) -> Unit = { _, _ -> }
) {
    val colors = Solar.colors
    val still = Solar.reduceMotion
    val appear = remember(days.size) { Animatable(if (still) 1f else 0f) }
    LaunchedEffect(days.size) { appear.animateTo(1f, tween(1100, easing = FastOutSlowInEasing)) }
    val dayFormat = remember { SimpleDateFormat("EEE d", Locale.getDefault()) }
    val labelW = 46.dp
    var picked by remember { mutableStateOf<Pair<Int, Int>?>(null) }

    Column(modifier) {
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val cell = (maxWidth - labelW) / 24
            val rowH = cell.coerceAtLeast(10.dp).coerceAtMost(18.dp)
            val gridH = rowH * days.size

            Row {
                Column(Modifier.width(labelW)) {
                    days.forEach { day ->
                        Box(Modifier.height(rowH), contentAlignment = Alignment.CenterStart) {
                            Text(dayFormat.format(Date(day.dayStart)), style = SolarType.caption.copy(fontSize = SolarType.caption.fontSize * 0.85f), color = colors.ink3, maxLines = 1)
                        }
                    }
                }
                Canvas(
                    Modifier
                        .fillMaxWidth()
                        .height(gridH)
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
                    val gap = 2.dp.toPx()
                    days.forEachIndexed { row, day ->
                        for (col in 0 until 24) {
                            val minutes = day.minutesDown[col]
                            val stagger = ((col + row) / 40f)
                            val a = ((appear.value - stagger) / (1f - stagger).coerceAtLeast(0.01f)).coerceIn(0f, 1f)
                            val color = if (minutes == 0) {
                                colors.track
                            } else {
                                lerp(colors.down.copy(alpha = 0.28f), colors.down, (minutes / 60f).coerceIn(0f, 1f))
                            }
                            val isPicked = picked == (row to col)
                            drawRoundRect(
                                color.copy(alpha = color.alpha * a),
                                topLeft = Offset(col * cw + gap / 2, row * ch + gap / 2),
                                size = Size(cw - gap, ch - gap),
                                cornerRadius = CornerRadius(3.dp.toPx())
                            )
                            if (isPicked) {
                                drawRoundRect(
                                    colors.ink,
                                    topLeft = Offset(col * cw + gap / 2, row * ch + gap / 2),
                                    size = Size(cw - gap, ch - gap),
                                    cornerRadius = CornerRadius(3.dp.toPx()),
                                    style = Stroke(1.5.dp.toPx())
                                )
                            }
                        }
                    }
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(start = labelW, top = 6.dp)) {
            listOf("00", "06", "12", "18", "").forEachIndexed { i, label ->
                Text(label, style = SolarType.caption, color = colors.ink3, modifier = Modifier.weight(if (i == 4) 0.001f else 1f))
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Energy bars
// ---------------------------------------------------------------------------------------------

/** Solar made vs. energy used, per day. Tap a day to read it. */
@Composable
fun EnergyBars(
    days: List<DayEnergy>,
    modifier: Modifier = Modifier,
    height: Dp = 150.dp,
    onPick: (DayEnergy?) -> Unit = {}
) {
    val colors = Solar.colors
    val still = Solar.reduceMotion
    val grow = remember(days.size) { Animatable(if (still) 1f else 0f) }
    LaunchedEffect(days.size) { grow.animateTo(1f, tween(900, easing = FastOutSlowInEasing)) }
    var picked by remember { mutableIntStateOf(-1) }

    val top = max(1.0, days.maxOfOrNull { max(it.energy.producedKwh ?: 0.0, it.energy.consumedKwh ?: 0.0) } ?: 1.0)
    val average = days.mapNotNull { it.energy.producedKwh }.takeIf { it.isNotEmpty() }?.average()

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
        val barW = (slot * 0.34f).coerceAtMost(12.dp.toPx())
        val bottom = size.height
        val radius = CornerRadius(barW / 2)

        days.forEachIndexed { i, day ->
            val cx = slot * i + slot / 2
            val produced = (day.energy.producedKwh ?: 0.0) / top
            val consumed = (day.energy.consumedKwh ?: 0.0) / top
            val alpha = if (picked == -1 || picked == i) 1f else 0.35f

            val ph = (produced * size.height * grow.value).toFloat()
            val chh = (consumed * size.height * grow.value).toFloat()

            drawRoundRect(
                colors.ink3.copy(alpha = 0.35f * alpha),
                topLeft = Offset(cx + 1.dp.toPx(), bottom - chh),
                size = Size(barW, chh),
                cornerRadius = radius
            )
            drawRoundRect(
                Brush.verticalGradient(listOf(colors.solar.copy(alpha = alpha), colors.solarDeep.copy(alpha = 0.75f * alpha)), startY = bottom - ph, endY = bottom),
                topLeft = Offset(cx - barW - 1.dp.toPx(), bottom - ph),
                size = Size(barW, ph),
                cornerRadius = radius
            )
        }

        if (average != null) {
            val y = (bottom - average / top * size.height).toFloat()
            drawLine(colors.solar.copy(alpha = 0.6f), Offset(0f, y), Offset(size.width, y), 1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 4.dp.toPx())))
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Learned schedule
// ---------------------------------------------------------------------------------------------

/** The day as a ribbon, with the learned outage slots on it and a marker for now. */
@Composable
fun ScheduleRibbon(slots: List<OutageSlot>, nowMinute: Int, nextMinute: Int?, modifier: Modifier = Modifier) {
    val colors = Solar.colors
    val still = Solar.reduceMotion
    val transition = rememberInfiniteTransition(label = "ribbon")
    val glow by transition.animateFloat(0.4f, 1f, infiniteRepeatable(tween(1400), RepeatMode.Reverse), label = "glow")

    Column(modifier) {
        Canvas(Modifier.fillMaxWidth().height(34.dp)) {
            val h = size.height
            val barTop = h * 0.3f
            val barH = h * 0.4f
            drawRoundRect(colors.track, Offset(0f, barTop), Size(size.width, barH), CornerRadius(barH / 2))

            slots.forEach { slot ->
                val start = slot.minuteOfDay / 1440f * size.width
                val length = ((slot.typicalDurationMs ?: 3_600_000L) / 60_000f / 1440f * size.width).coerceAtLeast(6.dp.toPx())
                val isNext = slot.minuteOfDay == nextMinute
                val alpha = (0.35f + 0.65f * slot.share.toFloat()) * if (isNext && !still) glow else 1f
                drawRoundRect(
                    colors.down.copy(alpha = alpha),
                    Offset(start, barTop),
                    Size(length.coerceAtMost(size.width - start), barH),
                    CornerRadius(barH / 2)
                )
                if (start + length > size.width) {
                    drawRoundRect(colors.down.copy(alpha = alpha), Offset(0f, barTop), Size(start + length - size.width, barH), CornerRadius(barH / 2))
                }
            }

            val nx = nowMinute / 1440f * size.width
            drawLine(colors.ink, Offset(nx, 0f), Offset(nx, h), 2.dp.toPx(), StrokeCap.Round)
            drawCircle(colors.ink, 3.dp.toPx(), Offset(nx, 0f))
        }
        Row(Modifier.fillMaxWidth().padding(top = 2.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            listOf("00:00", "06:00", "12:00", "18:00", "24:00").forEach {
                Text(it, style = SolarType.caption, color = colors.ink3)
            }
        }
    }
}

fun minuteLabel(minute: Int): String = "%02d:%02d".format(minute / 60, minute % 60)

fun durationLabel(ms: Long?): String = ms?.let { OutageLog.describeDuration(it) } ?: "—"
