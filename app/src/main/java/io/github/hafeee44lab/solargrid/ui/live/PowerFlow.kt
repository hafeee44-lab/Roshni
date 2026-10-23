package io.github.hafeee44lab.solargrid.ui.live

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.hafeee44lab.solargrid.domain.FlowPowers
import io.github.hafeee44lab.solargrid.domain.kw
import io.github.hafeee44lab.solargrid.ui.components.Icons
import io.github.hafeee44lab.solargrid.ui.theme.Solar
import io.github.hafeee44lab.solargrid.ui.theme.SolarColors
import io.github.hafeee44lab.solargrid.ui.theme.SolarType
import kotlin.math.abs

private const val FLOW_MIN_W = 50.0

private val NODE = 52.dp
private val HUB = 40.dp
private val CORNER = 18.dp

/**
 * Where power is going, drawn the way SOLARMAN draws it: an inverter hub, solar above,
 * battery, home and grid below, joined by thin wires with rounded elbows. A live wire takes
 * its source's colour and carries slow marching dashes in the direction of flow.
 */
@Composable
fun PowerFlow(
    powers: FlowPowers,
    soc: Double?,
    gridDown: Boolean?,
    lowBatteryPercent: Int,
    modifier: Modifier = Modifier
) {
    val c = Solar.colors
    val still = Solar.reduceMotion
    val phase by rememberInfiniteTransition(label = "flow").animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1600, easing = LinearEasing), RepeatMode.Restart),
        label = "phase"
    )

    val solar = powers.solarW ?: 0.0
    val load = powers.loadW ?: 0.0
    val grid = powers.gridW ?: 0.0
    val battery = powers.batteryW ?: 0.0
    val batteryTint = c.battery(soc, lowBatteryPercent)

    val solarLive = solar > FLOW_MIN_W
    val gridLive = abs(grid) > FLOW_MIN_W && gridDown != true
    val batteryLive = abs(battery) > FLOW_MIN_W
    val homeLive = load > FLOW_MIN_W

    // The house wire takes the colour of whoever supplies most of it.
    val supplier = listOf(
        c.solar to solar,
        c.grid to grid.coerceAtLeast(0.0),
        batteryTint to battery.coerceAtLeast(0.0)
    ).maxByOrNull { it.second }?.takeIf { it.second > FLOW_MIN_W }?.first ?: c.home

    val description = buildString {
        append("Power flow. ")
        if (powers.solarW != null) append("Solar ${kw(solar)}. ")
        when {
            gridDown == true -> append("Grid off. ")
            powers.gridW != null -> append(if (grid >= 0) "Using ${kw(grid)} from the grid. " else "Sending ${kw(-grid)} to the grid. ")
        }
        if (powers.batteryW != null) append(if (battery > FLOW_MIN_W) "Battery supplying ${kw(battery)}. " else if (battery < -FLOW_MIN_W) "Battery charging at ${kw(-battery)}. " else "Battery idle. ")
        if (powers.loadW != null) append("Home using ${kw(load)}.")
    }

    // Captions grow with the system font size; give them room instead of clipping.
    val fontScale = LocalDensity.current.fontScale.coerceAtLeast(1f)
    BoxWithConstraints(
        modifier
            .fillMaxWidth()
            .height(272.dp + 44.dp * (fontScale - 1f))
            .semantics { contentDescription = description }
    ) {
        val w = maxWidth
        val solarAt = Spot(w * 0.5f, 34.dp)
        val hubAt = Spot(w * 0.5f, 116.dp)
        val batteryAt = Spot(w * 0.16f, 196.dp)
        val homeAt = Spot(w * 0.5f, 196.dp)
        val gridAt = Spot(w * 0.84f, 196.dp)

        Canvas(Modifier.fillMaxSize()) {
            val node = NODE.toPx() / 2
            val hub = HUB.toPx() / 2
            val s = solarAt.px(this)
            val h = hubAt.px(this)
            val b = batteryAt.px(this)
            val home = homeAt.px(this)
            val g = gridAt.px(this)
            val r = CORNER.toPx()
            val t = if (still) 0f else phase

            // Solar → hub
            wire(straight(Offset(s.x, s.y + node), Offset(h.x, h.y - hub)), c, c.solar, solarLive, forward = true, phase = t, watts = solar)
            // Hub → home
            wire(straight(Offset(h.x, h.y + hub), Offset(home.x, home.y - node)), c, supplier, homeLive, forward = true, phase = t, watts = load)
            // Hub ↔ battery (built hub → battery; flow reverses when the battery supplies the house)
            wire(elbow(Offset(h.x - hub, h.y), Offset(b.x, b.y - node), r), c, batteryTint, batteryLive, forward = battery < 0, phase = t, watts = abs(battery))
            // Hub ↔ grid (built hub → grid; import flows toward the hub)
            val gridPath = elbow(Offset(h.x + hub, h.y), Offset(g.x, g.y - node), r)
            if (gridDown == true) {
                drawPath(gridPath, c.down, style = Stroke(1.5.dp.toPx(), cap = StrokeCap.Round, pathEffect = PathEffect.dashPathEffect(floatArrayOf(3.dp.toPx(), 5.dp.toPx()))))
            } else {
                wire(gridPath, c, c.grid, gridLive, forward = grid < 0, phase = t, watts = abs(grid))
            }
        }

        Node(solarAt, if (solarLive || powers.solarW == null) Icons.Sun else Icons.Moon, c.solar, active = solarLive)
        Hub(hubAt)
        Node(batteryAt, Icons.Battery, batteryTint, active = soc != null)
        Node(homeAt, Icons.Home, c.home, active = homeLive)
        Node(gridAt, Icons.Pylon, if (gridDown == true) c.down else c.grid, active = gridLive, alarm = gridDown == true)

        Caption(Spot(solarAt.x + 70.dp, solarAt.y - 20.dp), "Solar", if (powers.solarW == null) "—" else kw(solar), align = TextAlign.Start)
        Caption(
            Spot(batteryAt.x, batteryAt.y + 32.dp),
            when {
                battery > FLOW_MIN_W -> "Battery · out"
                battery < -FLOW_MIN_W -> "Battery · in"
                else -> "Battery"
            },
            buildString {
                append(soc?.let { "%.0f%%".format(it) } ?: "—")
                if (batteryLive) append(" · ${kw(abs(battery))}")
            }
        )
        Caption(Spot(homeAt.x, homeAt.y + 32.dp), "Home", if (powers.loadW == null) "—" else kw(load))
        Caption(
            Spot(gridAt.x, gridAt.y + 32.dp),
            if (grid < -FLOW_MIN_W && gridDown != true) "Grid · sending" else "Grid",
            when {
                gridDown == true -> "Off"
                powers.gridW == null -> "—"
                else -> kw(abs(grid))
            },
            valueColor = if (gridDown == true) c.down else null
        )
    }
}

private data class Spot(val x: Dp, val y: Dp) {
    fun px(scope: DrawScope) = with(scope) { Offset(x.toPx(), y.toPx()) }
}

@Composable
private fun Node(at: Spot, icon: ImageVector, tint: Color, active: Boolean, alarm: Boolean = false) {
    val c = Solar.colors
    Box(
        Modifier
            .offset(at.x - NODE / 2, at.y - NODE / 2)
            .size(NODE)
            .clip(CircleShape)
            // A pale wash of the node's colour (a little stronger on dark so it doesn't vanish).
            // Only the dead grid gets a ring, so it stands apart from everything else.
            .background(if (active || alarm) tint.copy(alpha = if (c.isDark) 0.16f else 0.10f) else c.surfaceVariant)
            .then(if (alarm) Modifier.border(1.5.dp, tint, CircleShape) else Modifier),
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, null, tint = if (active || alarm) tint else c.ink3, modifier = Modifier.size(24.dp))
    }
}

@Composable
private fun Hub(at: Spot) {
    val c = Solar.colors
    Box(
        Modifier
            .offset(at.x - HUB / 2, at.y - HUB / 2)
            .size(HUB)
            .clip(RoundedCornerShape(10.dp))
            .background(c.surface)
            .border(1.dp, c.outlineStrong, RoundedCornerShape(10.dp)),
        contentAlignment = Alignment.Center
    ) {
        Icon(Icons.Inverter, null, tint = c.ink2, modifier = Modifier.size(20.dp))
    }
}

@Composable
private fun Caption(at: Spot, label: String, value: String, align: TextAlign = TextAlign.Center, valueColor: Color? = null) {
    val c = Solar.colors
    val width = 124.dp
    Column(
        Modifier
            .offset(if (align == TextAlign.Center) at.x - width / 2 else at.x - 30.dp, at.y)
            .width(width),
        horizontalAlignment = if (align == TextAlign.Center) Alignment.CenterHorizontally else Alignment.Start
    ) {
        Text(label, style = SolarType.caption, color = c.ink3, textAlign = align, maxLines = 2)
        Text(value, style = SolarType.figureS, color = valueColor ?: c.ink, textAlign = align, maxLines = 2)
    }
}

private fun straight(from: Offset, to: Offset) = Path().apply {
    moveTo(from.x, from.y)
    lineTo(to.x, to.y)
}

/** Horizontal out of the hub, a rounded elbow, then straight down into the node. */
private fun elbow(from: Offset, to: Offset, radius: Float) = Path().apply {
    val dir = if (to.x < from.x) -1f else 1f
    moveTo(from.x, from.y)
    lineTo(to.x - dir * radius, from.y)
    quadraticTo(to.x, from.y, to.x, from.y + radius)
    lineTo(to.x, to.y)
}

private fun DrawScope.wire(
    path: Path,
    c: SolarColors,
    color: Color,
    live: Boolean,
    forward: Boolean,
    phase: Float,
    watts: Double
) {
    if (!live) {
        drawPath(path, c.outlineStrong, style = Stroke(1.dp.toPx(), cap = StrokeCap.Round))
        return
    }
    drawPath(path, color, style = Stroke(2.5.dp.toPx(), cap = StrokeCap.Round))

    // Dashes march toward the receiving end, a little quicker when a lot of power is on the wire.
    val dash = 5.dp.toPx()
    val gap = 9.dp.toPx()
    val period = dash + gap
    val speed = if (watts > 2500) 2f else 1f
    val offset = period * ((phase * speed) % 1f)
    drawPath(
        path,
        lerp(color, c.surface, 0.62f),
        style = Stroke(
            2.5.dp.toPx(),
            cap = StrokeCap.Round,
            pathEffect = PathEffect.dashPathEffect(floatArrayOf(dash, gap), if (forward) -offset else offset)
        )
    )
}
