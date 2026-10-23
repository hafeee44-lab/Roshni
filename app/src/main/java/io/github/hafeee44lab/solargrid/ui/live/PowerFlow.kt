package io.github.hafeee44lab.solargrid.ui.live

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.hafeee44lab.solargrid.domain.FlowPowers
import io.github.hafeee44lab.solargrid.domain.kw
import io.github.hafeee44lab.solargrid.ui.components.Icons
import io.github.hafeee44lab.solargrid.ui.components.RollingText
import io.github.hafeee44lab.solargrid.ui.components.SectionLabel
import io.github.hafeee44lab.solargrid.ui.theme.Solar
import io.github.hafeee44lab.solargrid.ui.theme.SolarType
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.sin
import kotlin.math.sqrt

private const val FLOW_MIN_W = 50.0

/**
 * Where the power is going, right now. Comets travel each live connection; how fast and how
 * many scale with the watts on that wire, so 4 kW of sun looks like 4 kW of sun.
 */
@Composable
fun PowerFlow(
    powers: FlowPowers,
    soc: Double?,
    gridDown: Boolean?,
    modifier: Modifier = Modifier
) {
    val colors = Solar.colors
    val animate = !Solar.reduceMotion

    var time by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(animate) {
        if (!animate) return@LaunchedEffect
        val start = withFrameNanos { it }
        while (true) {
            withFrameNanos { time = (it - start) / 1_000_000_000f }
        }
    }

    val solar = powers.solarW ?: 0.0
    val load = powers.loadW ?: 0.0
    val grid = powers.gridW ?: 0.0
    val battery = powers.batteryW ?: 0.0

    val solarLive = solar > FLOW_MIN_W
    val gridLive = abs(grid) > FLOW_MIN_W && gridDown != true
    val batteryLive = abs(battery) > FLOW_MIN_W
    val homeLive = load > FLOW_MIN_W

    val socFill by animateFloatAsState(((soc ?: 0.0) / 100.0).toFloat().coerceIn(0f, 1f), tween(900), label = "soc")

    // The line into the house takes the colour of whoever is supplying most of it.
    val supplier = listOf(
        colors.solar to solar,
        colors.grid to grid.coerceAtLeast(0.0),
        colors.battery to battery.coerceAtLeast(0.0)
    ).maxByOrNull { it.second }?.takeIf { it.second > FLOW_MIN_W }?.first ?: colors.home

    val description = buildString {
        append("Power flow. ")
        if (powers.solarW != null) append("Solar ${kw(solar)}. ")
        if (gridDown == true) append("Grid offline. ") else if (powers.gridW != null) append(if (grid >= 0) "Grid importing ${kw(grid)}. " else "Exporting ${kw(-grid)}. ")
        if (powers.batteryW != null) append(if (battery > 0) "Battery discharging ${kw(battery)}. " else if (battery < 0) "Battery charging ${kw(-battery)}. " else "Battery idle. ")
        if (powers.loadW != null) append("Home using ${kw(load)}.")
    }

    BoxWithConstraints(
        modifier
            .fillMaxWidth()
            .height(312.dp)
            .semantics { contentDescription = description }
    ) {
        val w = maxWidth
        val nodeR = 30.dp
        val solarAt = Pos(w * 0.5f, 44.dp)
        val gridAt = Pos(w * 0.14f, 156.dp)
        val batteryAt = Pos(w * 0.86f, 156.dp)
        val hubAt = Pos(w * 0.5f, 156.dp)
        val homeAt = Pos(w * 0.5f, 268.dp)

        Canvas(Modifier.fillMaxSize()) {
            val r = nodeR.toPx()
            val hubR = 13.dp.toPx()
            val s = solarAt.px(this)
            val g = gridAt.px(this)
            val b = batteryAt.px(this)
            val h = hubAt.px(this)
            val home = homeAt.px(this)

            // Wires
            wire(s, h, r, hubR, colors.solar, solarLive, forward = true, watts = solar, time = time)
            if (gridDown == true) {
                deadWire(g, h, r, hubR, colors.down)
            } else {
                wire(g, h, r, hubR, colors.grid, gridLive, forward = grid >= 0, watts = abs(grid), time = time)
            }
            wire(b, h, r, hubR, colors.battery, batteryLive, forward = battery > 0, watts = abs(battery), time = time)
            wire(h, home, hubR, r, supplier, homeLive, forward = true, watts = load, time = time)

            // Hub: the inverter. A slow orbit says "this is live data".
            drawCircle(colors.surfaceHigh, hubR, h)
            drawCircle(colors.hairlineStrong, hubR, h, style = Stroke(1.dp.toPx()))
            rotate(degrees = time * 90f, pivot = h) {
                drawArc(
                    brush = Brush.sweepGradient(listOf(Color.Transparent, colors.ink2.copy(alpha = 0.8f)), center = h),
                    startAngle = 0f,
                    sweepAngle = 300f,
                    useCenter = false,
                    topLeft = Offset(h.x - hubR - 4.dp.toPx(), h.y - hubR - 4.dp.toPx()),
                    size = androidx.compose.ui.geometry.Size((hubR + 4.dp.toPx()) * 2, (hubR + 4.dp.toPx()) * 2),
                    style = Stroke(1.4.dp.toPx(), cap = StrokeCap.Round)
                )
            }
            drawCircle(colors.ink2, 3.dp.toPx(), h)

            // Nodes
            node(s, r, colors.solar, solarLive, time)
            node(g, r, if (gridDown == true) colors.down else colors.grid, gridLive || gridDown == true, time, alarm = gridDown == true)
            node(b, r, colors.battery, batteryLive, time)
            node(home, r, supplier, homeLive, time)

            // Battery level as a ring around its node.
            drawArc(
                color = colors.battery,
                startAngle = -90f,
                sweepAngle = 360f * socFill,
                useCenter = false,
                topLeft = Offset(b.x - r - 5.dp.toPx(), b.y - r - 5.dp.toPx()),
                size = androidx.compose.ui.geometry.Size((r + 5.dp.toPx()) * 2, (r + 5.dp.toPx()) * 2),
                style = Stroke(3.dp.toPx(), cap = StrokeCap.Round)
            )
        }

        // Glyphs
        // The spin is read inside the graphics layer, so the sun turns without recomposing the diagram.
        val spinRate = 8f + (solar / 400.0).toFloat() * 6f
        Glyph(solarAt, if (solar > FLOW_MIN_W || (powers.solarW == null)) Icons.Sun else Icons.Moon, colors.solar, { if (solarLive) (time * spinRate) % 360f else 0f }, dim = !solarLive)
        Glyph(gridAt, Icons.Pylon, if (gridDown == true) colors.down else colors.grid, { 0f }, dim = !gridLive && gridDown != true)
        Glyph(batteryAt, Icons.Battery, colors.battery, { 0f }, dim = !batteryLive)
        Glyph(homeAt, Icons.Home, colors.home, { 0f }, dim = false)

        // Labels
        Caption(
            at = Pos(solarAt.x + 92.dp, solarAt.y - 2.dp),
            label = "Solar",
            value = if (powers.solarW == null) "—" else kw(solar),
            tone = colors.solar
        )
        Caption(
            at = Pos(gridAt.x, gridAt.y + 54.dp),
            label = when {
                gridDown == true -> "Grid"
                grid < -FLOW_MIN_W -> "Exporting"
                else -> "Grid"
            },
            value = when {
                gridDown == true -> "Offline"
                powers.gridW == null -> "—"
                else -> kw(abs(grid))
            },
            tone = if (gridDown == true) colors.down else colors.grid
        )
        Caption(
            at = Pos(batteryAt.x, batteryAt.y + 54.dp),
            label = when {
                battery > FLOW_MIN_W -> "Discharging"
                battery < -FLOW_MIN_W -> "Charging"
                else -> "Battery"
            },
            value = if (powers.batteryW == null) "—" else if (batteryLive) kw(abs(battery)) else "${soc?.let { "%.0f".format(it) } ?: "—"}%",
            tone = colors.battery
        )
        Caption(
            at = Pos(homeAt.x + 92.dp, homeAt.y - 2.dp),
            label = "Home",
            value = if (powers.loadW == null) "—" else kw(load),
            tone = colors.home
        )
    }
}

private data class Pos(val x: Dp, val y: Dp) {
    fun px(scope: DrawScope) = with(scope) { Offset(x.toPx(), y.toPx()) }
}

@Composable
private fun Glyph(at: Pos, icon: androidx.compose.ui.graphics.vector.ImageVector, tint: Color, rotation: () -> Float, dim: Boolean) {
    val alpha by animateFloatAsState(if (dim) 0.45f else 1f, tween(600), label = "glyph")
    Icon(
        icon,
        contentDescription = null,
        tint = tint,
        modifier = Modifier
            .offset(at.x - 13.dp, at.y - 13.dp)
            .size(26.dp)
            .graphicsLayer {
                rotationZ = rotation()
                this.alpha = alpha
            }
    )
}

@Composable
private fun Caption(at: Pos, label: String, value: String, tone: Color) {
    val colors = Solar.colors
    Column(
        modifier = Modifier
            .offset(at.x - 52.dp, at.y - 18.dp)
            .width(104.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        SectionLabel(label, color = tone.copy(alpha = 0.9f))
        Box(contentAlignment = Alignment.Center) {
            RollingText(value, SolarType.numberSmall, colors.ink)
        }
    }
}

/** A wire between two nodes, with comets riding it when power flows. */
private fun DrawScope.wire(
    from: Offset,
    to: Offset,
    fromR: Float,
    toR: Float,
    color: Color,
    live: Boolean,
    forward: Boolean,
    watts: Double,
    time: Float
) {
    val (a, b) = trim(from, to, fromR + 6.dp.toPx(), toR + 6.dp.toPx())

    drawLine(color.copy(alpha = if (live) 0.22f else 0.08f), a, b, strokeWidth = 2.dp.toPx(), cap = StrokeCap.Round)
    if (!live) return

    // Log scale: 100 W is a gentle drift, 6 kW is a rush, nothing ever stops reading.
    val strength = (ln(1.0 + watts / 100.0) / ln(1.0 + 80.0)).toFloat().coerceIn(0.08f, 1f)
    val speed = 0.22f + 0.55f * strength
    val count = 1 + (strength * 3f).toInt()
    val (start, end) = if (forward) a to b else b to a
    val dx = end.x - start.x
    val dy = end.y - start.y
    val length = sqrt(dx * dx + dy * dy).coerceAtLeast(1f)
    val tail = (30.dp.toPx() / length).coerceAtMost(0.45f)

    for (i in 0 until count) {
        val t = ((time * speed) + i.toFloat() / count) % 1f
        val head = Offset(start.x + dx * t, start.y + dy * t)
        val tailT = (t - tail).coerceAtLeast(0f)
        val tailAt = Offset(start.x + dx * tailT, start.y + dy * tailT)
        val fade = sin(t * PI).toFloat().coerceIn(0f, 1f)

        drawLine(
            brush = Brush.linearGradient(listOf(color.copy(alpha = 0f), color.copy(alpha = 0.9f * fade)), start = tailAt, end = head),
            start = tailAt,
            end = head,
            strokeWidth = 3.dp.toPx(),
            cap = StrokeCap.Round
        )
        drawCircle(color.copy(alpha = 0.25f * fade), radius = 8.dp.toPx(), center = head)
        drawCircle(lerp(color, Color.White, 0.6f).copy(alpha = fade), radius = 2.6.dp.toPx(), center = head)
    }
}

private fun DrawScope.deadWire(from: Offset, to: Offset, fromR: Float, toR: Float, color: Color) {
    val (a, b) = trim(from, to, fromR + 6.dp.toPx(), toR + 6.dp.toPx())
    drawLine(
        color.copy(alpha = 0.5f), a, b,
        strokeWidth = 2.dp.toPx(),
        cap = StrokeCap.Round,
        pathEffect = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 7.dp.toPx()))
    )
    val mid = Offset((a.x + b.x) / 2, (a.y + b.y) / 2)
    val x = 5.dp.toPx()
    drawLine(color, Offset(mid.x - x, mid.y - x), Offset(mid.x + x, mid.y + x), 2.2.dp.toPx(), StrokeCap.Round)
    drawLine(color, Offset(mid.x - x, mid.y + x), Offset(mid.x + x, mid.y - x), 2.2.dp.toPx(), StrokeCap.Round)
}

private fun DrawScope.node(center: Offset, r: Float, color: Color, live: Boolean, time: Float, alarm: Boolean = false) {
    if (live) {
        val pulse = if (alarm) (0.5f + 0.5f * sin(time * 4f)) else 1f
        drawCircle(
            Brush.radialGradient(listOf(color.copy(alpha = 0.32f * pulse), Color.Transparent), center = center, radius = r * 2.1f),
            radius = r * 2.1f,
            center = center
        )
        if (alarm) {
            val ring = (time * 0.7f) % 1f
            drawCircle(color.copy(alpha = 0.5f * (1f - ring)), radius = r + r * 0.9f * ring, center = center, style = Stroke(1.5.dp.toPx()))
        }
    }
    drawCircle(
        brush = Brush.verticalGradient(
            listOf(color.copy(alpha = if (live) 0.20f else 0.07f), color.copy(alpha = if (live) 0.06f else 0.03f)),
            startY = center.y - r,
            endY = center.y + r
        ),
        radius = r,
        center = center
    )
    drawCircle(color.copy(alpha = if (live) 0.85f else 0.25f), r, center, style = Stroke(1.5.dp.toPx()))
}

private fun trim(from: Offset, to: Offset, fromR: Float, toR: Float): Pair<Offset, Offset> {
    val dx = to.x - from.x
    val dy = to.y - from.y
    val len = sqrt(dx * dx + dy * dy).coerceAtLeast(0.001f)
    val ux = dx / len
    val uy = dy / len
    return Offset(from.x + ux * fromR, from.y + uy * fromR) to Offset(to.x - ux * toR, to.y - uy * toR)
}
