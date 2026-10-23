package io.github.hafeee44lab.solargrid.ui.components

import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import java.text.SimpleDateFormat
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.hafeee44lab.solargrid.domain.Phrases
import io.github.hafeee44lab.solargrid.ui.theme.Solar
import io.github.hafeee44lab.solargrid.ui.theme.SolarType
import kotlin.math.roundToInt

val CardShape = RoundedCornerShape(16.dp)

/** Keeps "1h 32m" or "90 s" on one line inside a sentence. */
fun String.nb(): String = replace(' ', '\u00A0')

/** The one container: a flat card on the grey ground. No border in light, no glow anywhere. */
@Composable
fun SCard(
    modifier: Modifier = Modifier,
    container: Color = Solar.colors.surface,
    padding: PaddingValues = PaddingValues(16.dp),
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(CardShape)
            .background(container)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(padding),
        content = content
    )
}

/** A card's heading row: sentence-case title, optional trailing text and chevron. */
@Composable
fun CardHeader(title: String, modifier: Modifier = Modifier, trailing: String? = null, chevron: Boolean = false) {
    val c = Solar.colors
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(title, style = SolarType.cardTitle, color = c.ink, modifier = Modifier.weight(1f))
        if (trailing != null) Text(trailing, style = SolarType.caption, color = c.ink3)
        if (chevron) Icon(Icons.Chevron, null, tint = c.ink3, modifier = Modifier.size(18.dp))
    }
}

/**
 * Numbers that roll gently when they change. Keyed from the right so "99" → "100" moves the
 * digits that actually changed; a screen reader hears the whole value once.
 */
@Composable
fun RollingText(text: String, style: TextStyle, color: Color, modifier: Modifier = Modifier) {
    // Only numbers roll. Words are drawn whole: Urdu letters join up, and splitting them into
    // separate characters would break the script.
    if (Solar.reduceMotion || text.any { it.isLetter() }) {
        Text(text, style = style, color = color, modifier = modifier, maxLines = 1)
        return
    }
    // Digits keep their left-to-right order in every language, or "61" would read "16" in Urdu.
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
    Row(modifier.clearAndSetSemantics { contentDescription = text }) {
        text.forEachIndexed { index, char ->
            key(text.length - index) {
                AnimatedContent(
                    targetState = char,
                    transitionSpec = {
                        if (initialState.isDigit() && targetState.isDigit()) {
                            val up = targetState > initialState
                            (slideInVertically(tween(260, easing = FastOutSlowInEasing)) { h -> if (up) h / 2 else -h / 2 } + fadeIn(tween(200))) togetherWith
                                (slideOutVertically(tween(260, easing = FastOutSlowInEasing)) { h -> if (up) -h / 2 else h / 2 } + fadeOut(tween(140)))
                        } else {
                            EnterTransition.None togetherWith ExitTransition.None
                        }
                    },
                    label = "digit"
                ) { ch -> Text(ch.toString(), style = style, color = color, maxLines = 1) }
            }
        }
    }
    }
}

/** "10.6 kWh" with the label underneath: the figure style used everywhere. */
@Composable
fun Figure(
    value: String,
    modifier: Modifier = Modifier,
    unit: String? = null,
    label: String? = null,
    style: TextStyle = SolarType.figure,
    color: Color = Solar.colors.ink,
    note: String? = null
) {
    val c = Solar.colors
    Column(modifier) {
        Row(verticalAlignment = Alignment.Bottom) {
            RollingText(value, style, color)
            if (unit != null) {
                Text(unit, style = SolarType.unit, color = c.ink3, modifier = Modifier.padding(start = 3.dp, bottom = if (style.fontSize.value > 30) 9.dp else 4.dp))
            }
        }
        if (label != null) Text(label, style = SolarType.caption, color = c.ink3, modifier = Modifier.padding(top = 2.dp))
        if (note != null) Text(note, style = SolarType.caption, color = c.ink2, modifier = Modifier.padding(top = 2.dp), maxLines = 2)
    }
}

@Composable
fun StatusDot(color: Color, size: Dp = 8.dp) {
    Box(Modifier.size(size).clip(CircleShape).background(color))
}

/** A small tinted label: status, demo, "battery should cover it". */
@Composable
fun Tag(text: String, color: Color, modifier: Modifier = Modifier, icon: ImageVector? = null) {
    Row(
        modifier
            .clip(RoundedCornerShape(8.dp))
            .background(color.copy(alpha = if (Solar.colors.isDark) 0.18f else 0.10f))
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (icon != null) {
            Icon(icon, null, tint = color, modifier = Modifier.size(14.dp))
            Spacer(Modifier.width(4.dp))
        }
        Text(text, style = SolarType.caption, color = color, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** State of charge as ten cells, like the battery glyph everyone already reads. */
@Composable
fun BatteryMeter(soc: Double?, color: Color, modifier: Modifier = Modifier, cells: Int = 10) {
    val c = Solar.colors
    val filled = ((soc ?: 0.0) / 100.0 * cells).roundToInt().coerceIn(0, cells)
    Row(modifier.fillMaxWidth().height(10.dp), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
        repeat(cells) { i ->
            val on = i < filled
            val tint by animateColorAsState(if (on) color else c.surfaceVariant, tween(500), label = "cell")
            Box(
                Modifier
                    .weight(1f)
                    .height(10.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(tint)
            )
        }
    }
}

@Composable
fun Divider(modifier: Modifier = Modifier) {
    HorizontalDivider(modifier, thickness = 1.dp, color = Solar.colors.outline)
}

@Composable
fun PrimaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, icon: ImageVector? = null, danger: Boolean = false) {
    val c = Solar.colors
    val haptics = LocalHapticFeedback.current
    Button(
        onClick = { haptics.performHapticFeedback(HapticFeedbackType.Confirm); onClick() },
        enabled = enabled,
        modifier = modifier.heightIn(min = 48.dp),
        colors = if (danger) ButtonDefaults.buttonColors(containerColor = c.down, contentColor = Color.White) else ButtonDefaults.buttonColors(),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp)
    ) { ButtonContent(text, icon) }
}

@Composable
fun SecondaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, icon: ImageVector? = null, color: Color = Solar.colors.brand) {
    OutlinedButton(
        onClick = onClick,
        modifier = modifier.heightIn(min = 48.dp),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = color),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp)
    ) { ButtonContent(text, icon) }
}

@Composable
private fun RowScope.ButtonContent(text: String, icon: ImageVector?) {
    if (icon != null) {
        Icon(icon, null, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
    }
    Text(text, style = SolarType.bodyStrong)
}

@Composable
fun SolarSwitch(checked: Boolean, onChange: (Boolean) -> Unit, enabled: Boolean = true) {
    val haptics = LocalHapticFeedback.current
    Switch(
        checked = checked,
        onCheckedChange = {
            haptics.performHapticFeedback(if (it) HapticFeedbackType.ToggleOn else HapticFeedbackType.ToggleOff)
            onChange(it)
        },
        enabled = enabled
    )
}

/** A list row: optional line icon, title, explanation, and a trailing control. */
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
    val c = Solar.colors
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (icon != null) {
            Icon(icon, null, tint = iconTint, modifier = Modifier.size(22.dp))
            Spacer(Modifier.width(16.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(title, style = SolarType.body.copy(fontSize = SolarType.cardTitle.fontSize), color = c.ink)
            if (subtitle != null) Text(subtitle, style = SolarType.caption, color = c.ink3, modifier = Modifier.padding(top = 2.dp))
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
    SettingRow(title, subtitle = subtitle, icon = icon, iconTint = iconTint, onClick = if (enabled) ({ onChange(!checked) }) else null) {
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
    accent: Color = Solar.colors.brand,
    enabled: Boolean = true
) {
    val c = Solar.colors
    val haptics = LocalHapticFeedback.current
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title, style = SolarType.body.copy(fontSize = SolarType.cardTitle.fontSize), color = if (enabled) c.ink else c.ink3)
                if (subtitle != null) Text(subtitle, style = SolarType.caption, color = c.ink3)
            }
            Text(format(value), style = SolarType.figureS, color = if (enabled) c.ink else c.ink3)
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
                inactiveTrackColor = c.surfaceVariant,
                activeTickColor = Color.Transparent,
                inactiveTickColor = Color.Transparent
            )
        )
    }
}

/** Material 3 segmented buttons. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun <T> Segmented(options: List<Pair<T, String>>, selected: T, onSelect: (T) -> Unit, modifier: Modifier = Modifier) {
    val haptics = LocalHapticFeedback.current
    SingleChoiceSegmentedButtonRow(modifier.fillMaxWidth()) {
        options.forEachIndexed { index, (value, label) ->
            SegmentedButton(
                selected = value == selected,
                onClick = {
                    if (value != selected) haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
                    onSelect(value)
                },
                shape = SegmentedButtonDefaults.itemShape(index, options.size),
                icon = {}
            ) { Text(label, style = SolarType.bodyStrong) }
        }
    }
}

/** A titled group of list rows in one card — the Settings pattern. */
@Composable
fun ListGroup(title: String?, modifier: Modifier = Modifier, footer: String? = null, content: @Composable ColumnScope.() -> Unit) {
    val c = Solar.colors
    Column(modifier.fillMaxWidth()) {
        if (title != null) {
            Text(title, style = SolarType.bodyStrong, color = c.brand, modifier = Modifier.padding(start = 16.dp, bottom = 8.dp, top = 8.dp))
        }
        Column(Modifier.fillMaxWidth().clip(CardShape).background(c.surface).padding(vertical = 4.dp), content = content)
        if (footer != null) {
            Text(footer, style = SolarType.caption, color = c.ink3, modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp))
        }
    }
}

/** The domain layer's phrases (status, durations) in the app's current language. */
@Composable
fun phrases(): Phrases = Phrases.of(LocalConfiguration.current.locales[0])

/** A date format in the app's current language, rebuilt when the language changes. */
@Composable
fun rememberDateFormat(pattern: String): SimpleDateFormat {
    val locale = LocalConfiguration.current.locales[0]
    return remember(pattern, locale) { SimpleDateFormat(pattern, locale) }
}
