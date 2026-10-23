package io.github.hafeee44lab.solargrid.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.hafeee44lab.solargrid.ui.theme.Solar
import io.github.hafeee44lab.solargrid.ui.theme.SolarType
import kotlin.math.max
import kotlin.math.roundToInt

val PanelShape = RoundedCornerShape(26.dp)

/** The one card style. A hairline edge and, optionally, a faint glow in the accent colour. */
@Composable
fun Panel(
    modifier: Modifier = Modifier,
    glow: Color? = null,
    padding: PaddingValues = PaddingValues(20.dp),
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    val colors = Solar.colors
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.985f else 1f, spring(stiffness = Spring.StiffnessMediumLow), label = "press")

    Column(
        modifier = modifier
            .scale(scale)
            .clip(PanelShape)
            .background(colors.surface)
            .drawBehind {
                if (glow != null) {
                    drawRect(
                        Brush.radialGradient(
                            colors = listOf(glow.copy(alpha = if (colors.isDark) 0.16f else 0.10f), Color.Transparent),
                            center = Offset(size.width * 0.12f, 0f),
                            radius = max(size.width, size.height) * 0.9f
                        )
                    )
                }
                drawRect(
                    Brush.verticalGradient(
                        listOf(Color.White.copy(alpha = if (colors.isDark) 0.035f else 0f), Color.Transparent),
                        endY = size.height * 0.4f
                    )
                )
            }
            .border(1.dp, colors.hairline, PanelShape)
            .then(
                if (onClick != null) {
                    Modifier.clickable(interactionSource = interaction, indication = null, role = Role.Button, onClick = onClick)
                } else {
                    Modifier
                }
            )
            .padding(padding),
        content = content
    )
}

/**
 * The living background. Two soft glows drift very slowly and take on the colour of the grid
 * state, so the mood of the screen tells you the answer before you read anything.
 */
@Composable
fun Atmosphere(tint: Color, intensity: Float, pulse: Boolean, modifier: Modifier = Modifier) {
    val colors = Solar.colors
    val animatedTint by animateColorAsState(tint, tween(1200), label = "tint")
    val animatedIntensity by animateFloatAsState(intensity, tween(1200), label = "intensity")
    val still = Solar.reduceMotion

    val transition = rememberInfiniteTransition(label = "atmosphere")
    val drift by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(24_000, easing = LinearEasing), RepeatMode.Reverse),
        label = "drift"
    )
    val breath by transition.animateFloat(
        initialValue = 0.7f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1600, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "breath"
    )

    Canvas(modifier.fillMaxSize()) {
        drawRect(colors.bg)
        val d = if (still) 0.5f else drift
        val strength = animatedIntensity * (if (pulse && !still) breath else 1f)
        val span = max(size.width, size.height)

        drawRect(
            Brush.radialGradient(
                colors = listOf(animatedTint.copy(alpha = 0.30f * strength), Color.Transparent),
                center = Offset(size.width * (0.15f + 0.25f * d), size.height * (0.02f + 0.05f * d)),
                radius = span * 0.62f
            )
        )
        drawRect(
            Brush.radialGradient(
                colors = listOf(colors.battery.copy(alpha = 0.10f * animatedIntensity), Color.Transparent),
                center = Offset(size.width * (0.95f - 0.2f * d), size.height * (0.30f + 0.1f * d)),
                radius = span * 0.5f
            )
        )
        drawRect(
            Brush.verticalGradient(
                listOf(Color.Transparent, colors.bgDeep.copy(alpha = 0.6f)),
                startY = size.height * 0.5f
            )
        )
    }
}

@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier, color: Color = Solar.colors.ink3) {
    Text(text.uppercase(), style = SolarType.label, color = color, modifier = modifier)
}

/**
 * Numbers that roll like an odometer when they change. Keyed from the right so "99" → "100"
 * rolls the digits that actually moved.
 */
@Composable
fun RollingText(
    text: String,
    style: TextStyle,
    color: Color,
    modifier: Modifier = Modifier
) {
    if (Solar.reduceMotion) {
        Text(text, style = style, color = color, modifier = modifier, maxLines = 1)
        return
    }

    // One value to a screen reader, however many characters roll on screen.
    Row(modifier.clearAndSetSemantics { contentDescription = text }) {
        text.forEachIndexed { index, char ->
            key(text.length - index) {
                AnimatedContent(
                    targetState = char,
                    transitionSpec = {
                        if (initialState.isDigit() && targetState.isDigit()) {
                            val up = targetState > initialState
                            (slideInVertically(tween(320, easing = FastOutSlowInEasing)) { h -> if (up) h else -h } + fadeIn(tween(220))) togetherWith
                                (slideOutVertically(tween(320, easing = FastOutSlowInEasing)) { h -> if (up) -h else h } + fadeOut(tween(160)))
                        } else {
                            EnterTransition.None togetherWith ExitTransition.None
                        }
                    },
                    label = "digit"
                ) { c ->
                    Text(c.toString(), style = style, color = color, maxLines = 1)
                }
            }
        }
    }
}

@Composable
fun Pill(
    text: String,
    color: Color,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    filled: Boolean = false
) {
    Row(
        modifier = modifier
            .clip(CircleShape)
            .background(color.copy(alpha = if (filled) 0.18f else 0.10f))
            .padding(horizontal = 10.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (icon != null) {
            Icon(icon, null, tint = color, modifier = Modifier.size(13.dp))
            Spacer(Modifier.width(5.dp))
        }
        Text(text, style = SolarType.caption, color = color, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** A status light that breathes while it means "live". */
@Composable
fun StatusDot(color: Color, live: Boolean, size: Dp = 8.dp) {
    val still = Solar.reduceMotion
    val transition = rememberInfiniteTransition(label = "dot")
    val ring by transition.animateFloat(
        0f, 1f,
        infiniteRepeatable(tween(1800, easing = FastOutSlowInEasing)),
        label = "ring"
    )

    Canvas(Modifier.size(size * 2.6f)) {
        val r = size.toPx() / 2
        if (live && !still) {
            drawCircle(color.copy(alpha = 0.35f * (1f - ring)), radius = r + r * 1.6f * ring)
        }
        drawCircle(color, radius = r)
    }
}

@Composable
fun SolarSwitch(checked: Boolean, onChange: (Boolean) -> Unit, enabled: Boolean = true, accent: Color = Solar.colors.ok) {
    val colors = Solar.colors
    val haptics = LocalHapticFeedback.current
    Switch(
        checked = checked,
        onCheckedChange = {
            haptics.performHapticFeedback(HapticFeedbackType.ToggleOn)
            onChange(it)
        },
        enabled = enabled,
        colors = SwitchDefaults.colors(
            checkedThumbColor = colors.onAccent,
            checkedTrackColor = accent,
            checkedBorderColor = accent,
            uncheckedThumbColor = colors.ink3,
            uncheckedTrackColor = colors.track,
            uncheckedBorderColor = colors.hairlineStrong
        )
    )
}

/** A settings row: icon, title, explanation, and whatever control goes on the right. */
@Composable
fun SettingRow(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    icon: ImageVector? = null,
    iconTint: Color = Solar.colors.ink2,
    onClick: (() -> Unit)? = null,
    trailing: @Composable (() -> Unit)? = null
) {
    val colors = Solar.colors
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .clip(RoundedCornerShape(16.dp))
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (icon != null) {
            Box(
                Modifier
                    .size(36.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(iconTint.copy(alpha = 0.12f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(icon, null, tint = iconTint, modifier = Modifier.size(19.dp))
            }
            Spacer(Modifier.width(14.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(title, style = SolarType.bodyStrong, color = colors.ink)
            if (subtitle != null) {
                Text(subtitle, style = SolarType.caption, color = colors.ink3, modifier = Modifier.padding(top = 2.dp))
            }
        }
        if (trailing != null) {
            Spacer(Modifier.width(12.dp))
            trailing()
        }
    }
}

@Composable
fun ToggleRow(
    title: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
    subtitle: String? = null,
    icon: ImageVector? = null,
    iconTint: Color = Solar.colors.ink2,
    enabled: Boolean = true
) {
    SettingRow(
        title = title,
        subtitle = subtitle,
        icon = icon,
        iconTint = iconTint,
        onClick = if (enabled) ({ onChange(!checked) }) else null
    ) {
        SolarSwitch(checked, onChange, enabled)
    }
}

@Composable
fun SliderRow(
    title: String,
    value: Int,
    range: IntRange,
    step: Int,
    format: (Int) -> String,
    onChange: (Int) -> Unit,
    subtitle: String? = null,
    accent: Color = Solar.colors.battery,
    enabled: Boolean = true
) {
    val colors = Solar.colors
    val haptics = LocalHapticFeedback.current
    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title, style = SolarType.bodyStrong, color = if (enabled) colors.ink else colors.ink3)
                if (subtitle != null) Text(subtitle, style = SolarType.caption, color = colors.ink3)
            }
            Text(format(value), style = SolarType.numberSmall, color = if (enabled) accent else colors.ink3)
        }
        val steps = ((range.last - range.first) / step - 1).coerceAtLeast(0)
        Slider(
            value = value.toFloat(),
            onValueChange = {
                val snapped = (((it - range.first) / step).roundToInt() * step + range.first).coerceIn(range.first, range.last)
                if (snapped != value) {
                    haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
                    onChange(snapped)
                }
            },
            valueRange = range.first.toFloat()..range.last.toFloat(),
            steps = steps,
            enabled = enabled,
            colors = SliderDefaults.colors(
                thumbColor = accent,
                activeTrackColor = accent,
                inactiveTrackColor = colors.track,
                activeTickColor = Color.Transparent,
                inactiveTickColor = Color.Transparent
            )
        )
    }
}

/** A segmented control with a sliding, springy selection pill. */
@Composable
fun <T> Segmented(
    options: List<Pair<T, String>>,
    selected: T,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = Solar.colors
    val haptics = LocalHapticFeedback.current
    val index = options.indexOfFirst { it.first == selected }.coerceAtLeast(0)

    BoxWithConstraints(
        modifier
            .fillMaxWidth()
            .height(42.dp)
            .clip(CircleShape)
            .background(colors.track)
            .padding(4.dp)
    ) {
        val segment = maxWidth / options.size
        val offset by animateDpAsState(segment * index, spring(dampingRatio = 0.78f, stiffness = 500f), label = "segment")

        Box(
            Modifier
                .offset(x = offset)
                .width(segment)
                .fillMaxHeight()
                .clip(CircleShape)
                .background(colors.surface)
                .border(1.dp, colors.hairline, CircleShape)
        )
        Row(Modifier.fillMaxSize()) {
            options.forEachIndexed { i, (value, label) ->
                val active = i == index
                val tint by animateColorAsState(if (active) colors.ink else colors.ink3, label = "segmentTint")
                Box(
                    Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .clip(CircleShape)
                        .clickable {
                            if (!active) haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
                            onSelect(value)
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Text(label, style = SolarType.bodyStrong.copy(fontSize = SolarType.caption.fontSize), color = tint)
                }
            }
        }
    }
}

@Composable
fun PrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    color: Color = Solar.colors.ink,
    textColor: Color = Solar.colors.bg,
    enabled: Boolean = true,
    icon: ImageVector? = null
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.97f else 1f, spring(stiffness = Spring.StiffnessMedium), label = "btn")
    val haptics = LocalHapticFeedback.current

    Row(
        modifier = modifier
            .scale(scale)
            .heightIn(min = 54.dp)
            .clip(CircleShape)
            .background(if (enabled) color else color.copy(alpha = 0.3f))
            .clickable(interactionSource = interaction, indication = null, enabled = enabled, role = Role.Button) {
                haptics.performHapticFeedback(HapticFeedbackType.Confirm)
                onClick()
            }
            .padding(horizontal = 24.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (icon != null) {
            Icon(icon, null, tint = textColor, modifier = Modifier.size(19.dp))
            Spacer(Modifier.width(8.dp))
        }
        Text(text, style = SolarType.bodyStrong.copy(fontSize = SolarType.body.fontSize), color = textColor)
    }
}

@Composable
fun GhostButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    color: Color = Solar.colors.ink,
    icon: ImageVector? = null
) {
    val colors = Solar.colors
    Row(
        modifier = modifier
            .heightIn(min = 48.dp)
            .clip(CircleShape)
            .border(1.dp, colors.hairlineStrong, CircleShape)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 18.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (icon != null) {
            Icon(icon, null, tint = color, modifier = Modifier.size(17.dp))
            Spacer(Modifier.width(8.dp))
        }
        Text(text, style = SolarType.bodyStrong, color = color)
    }
}

@Composable
fun Hairline(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().height(1.dp).background(Solar.colors.hairline))
}

/** Label over a value — the small stat block used all over the live screen. */
@Composable
fun Stat(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    unit: String? = null,
    color: Color = Solar.colors.ink,
    note: String? = null
) {
    val colors = Solar.colors
    Column(modifier) {
        SectionLabel(label)
        Row(verticalAlignment = Alignment.Bottom, modifier = Modifier.padding(top = 4.dp)) {
            RollingText(value, SolarType.number.copy(fontSize = SolarType.title.fontSize), color)
            if (unit != null) {
                Text(unit, style = SolarType.caption, color = colors.ink3, modifier = Modifier.padding(start = 3.dp, bottom = 3.dp))
            }
        }
        if (note != null) {
            Text(note, style = SolarType.caption, color = colors.ink3, maxLines = 2)
        }
    }
}
