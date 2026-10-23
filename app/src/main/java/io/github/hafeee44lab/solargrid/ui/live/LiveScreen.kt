package io.github.hafeee44lab.solargrid.ui.live

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.hafeee44lab.solargrid.domain.BatteryMode
import io.github.hafeee44lab.solargrid.domain.HomeUi
import io.github.hafeee44lab.solargrid.domain.OutageLog
import io.github.hafeee44lab.solargrid.domain.OutagePredictor
import io.github.hafeee44lab.solargrid.domain.RuntimeEstimator
import io.github.hafeee44lab.solargrid.domain.StateRole
import io.github.hafeee44lab.solargrid.ui.MainViewModel
import io.github.hafeee44lab.solargrid.ui.SystemActions
import io.github.hafeee44lab.solargrid.ui.UiState
import io.github.hafeee44lab.solargrid.ui.components.BatteryMeter
import io.github.hafeee44lab.solargrid.ui.components.CardHeader
import io.github.hafeee44lab.solargrid.ui.components.DayChart
import io.github.hafeee44lab.solargrid.ui.components.Divider
import io.github.hafeee44lab.solargrid.ui.components.Figure
import io.github.hafeee44lab.solargrid.ui.components.Icons
import io.github.hafeee44lab.solargrid.ui.components.RollingText
import io.github.hafeee44lab.solargrid.ui.components.SCard
import io.github.hafeee44lab.solargrid.ui.components.ScheduleRibbon
import io.github.hafeee44lab.solargrid.ui.components.SecondaryButton
import io.github.hafeee44lab.solargrid.ui.components.StatusDot
import io.github.hafeee44lab.solargrid.ui.components.Tag
import io.github.hafeee44lab.solargrid.ui.components.minuteLabel
import io.github.hafeee44lab.solargrid.ui.components.nb
import io.github.hafeee44lab.solargrid.ui.screenPadding
import io.github.hafeee44lab.solargrid.ui.theme.Solar
import io.github.hafeee44lab.solargrid.ui.theme.SolarType
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LiveScreen(
    vm: MainViewModel,
    state: UiState,
    system: SystemActions,
    onOpenHistory: () -> Unit,
    onOpenSettings: () -> Unit
) {
    val checking by vm.checking.collectAsStateWithLifecycle()
    val permissions by vm.permissions.collectAsStateWithLifecycle()
    val c = Solar.colors
    val ui = state.home

    PullToRefreshBox(isRefreshing = checking, onRefresh = vm::refresh, modifier = Modifier.fillMaxSize()) {
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .windowInsetsPadding(WindowInsets.statusBars)
                .padding(screenPadding()),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            TopBar(state, onRefresh = vm::refresh)

            AnimatedVisibility(!state.settings.monitoring, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
                Notice(Icons.Warning, c.warn, "Monitoring is paused", "Nothing will alert you if the mains go.", "Turn on") { vm.setMonitoring(true) }
            }
            AnimatedVisibility(state.settings.monitoring && permissions.blocking > 0, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
                Notice(
                    Icons.Shield,
                    c.down,
                    if (permissions.blocking == 1) "1 setting can stop alarms reaching you" else "${permissions.blocking} settings can stop alarms reaching you",
                    "Fix it in Settings, under Reliability.",
                    "Fix",
                    onOpenSettings
                )
            }

            StatusCard(ui, state)

            SCard {
                CardHeader("Power flow")
                Spacer(Modifier.height(4.dp))
                PowerFlow(
                    powers = ui.powers,
                    soc = ui.soc,
                    gridDown = if (ui.trusted) state.snapshot.gridDown else null,
                    lowBatteryPercent = state.settings.alerts.lowBatteryPercent
                )
            }

            TodayTiles(ui, state)

            ForecastCard(state)

            SCard(onClick = onOpenHistory) {
                CardHeader("Today", trailing = "History", chevron = true)
                Spacer(Modifier.height(12.dp))
                val today = OutageLog.startOfDay(state.now)
                DayChart(samples = state.todaySamples, outages = state.outages, from = today, to = today + OutageLog.DAY_MS, now = state.now)
            }

            GridCard(ui)

            Text(
                "${ui.freshPrimary} · ${ui.freshSecondary.replaceFirstChar { it.lowercase() }}",
                style = SolarType.caption,
                color = if (ui.freshAlert) c.warn else c.ink3,
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                textAlign = androidx.compose.ui.text.style.TextAlign.Center
            )

            if (state.settings.demoMode) DemoCard(vm)
        }
    }
}

@Composable
private fun TopBar(state: UiState, onRefresh: () -> Unit) {
    val c = Solar.colors
    Row(Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(
                when {
                    state.settings.demoMode -> "Demo home"
                    !state.account?.label.isNullOrBlank() -> state.account!!.label
                    else -> "My home"
                },
                style = SolarType.title,
                color = c.ink,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.semantics { heading() }
            )
            Text(
                if (state.settings.monitoring) state.home.freshPrimary.replaceFirstChar { it.uppercase() } else "Not watching",
                style = SolarType.caption,
                color = if (state.home.freshAlert) c.warn else c.ink3,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        if (state.settings.demoMode) Tag("Demo", c.brand)
        IconButton(onClick = onRefresh) {
            Icon(Icons.Refresh, contentDescription = "Check now", tint = c.ink2)
        }
    }
}

@Composable
private fun Notice(icon: ImageVector, tone: Color, title: String, body: String, action: String, onAction: () -> Unit) {
    val c = Solar.colors
    SCard(padding = androidx.compose.foundation.layout.PaddingValues(start = 16.dp, top = 12.dp, bottom = 12.dp, end = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, tint = tone, modifier = Modifier.size(22.dp))
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = SolarType.bodyStrong, color = c.ink)
                Text(body, style = SolarType.caption, color = c.ink3)
            }
            TextButton(onClick = onAction) { Text(action, style = SolarType.bodyStrong, color = tone) }
        }
    }
}

/**
 * The one card that matters. Quiet white when the mains are on; red-tinted during an outage,
 * with the outage clock running and the battery's time left.
 */
@Composable
private fun StatusCard(ui: HomeUi, state: UiState) {
    val c = Solar.colors
    val down = ui.heroRole == StateRole.DOWN
    val container by animateColorAsState(if (down) c.downContainer else c.surface, tween(600), label = "status")
    val clock = remember { SimpleDateFormat("HH:mm", Locale.getDefault()) }
    val alerts = state.settings.alerts
    val batteryTint = c.battery(ui.soc, alerts.lowBatteryPercent)
    val dot = when (ui.heroRole) {
        StateRole.UP -> c.ok
        StateRole.DOWN -> c.down
        StateRole.NEUTRAL -> c.ink3
    }

    SCard(container = container, padding = androidx.compose.foundation.layout.PaddingValues(20.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }) {
            StatusDot(dot, 10.dp)
            Spacer(Modifier.width(10.dp))
            AnimatedContent(ui.heroState, transitionSpec = { fadeIn(tween(300)) togetherWith fadeOut(tween(200)) }, label = "hero") {
                Text(
                    it,
                    style = SolarType.title,
                    color = if (down) c.onDownContainer else c.ink
                )
            }
            Spacer(Modifier.weight(1f))
            // Mains on "since" the last cut ended; mains off since the cut began.
            val since = if (down) ui.outageSince else state.outages.firstOrNull { it.endedAt != null }?.endedAt.takeIf { ui.heroRole == StateRole.UP }
            Text(
                if (since != null) sinceLabel(since, state.now, clock) else "",
                style = SolarType.caption,
                color = if (down) c.onDownContainer else c.ink3
            )
        }

        if (ui.heroRole == StateRole.NEUTRAL) {
            Text(ui.heroSub, style = SolarType.body, color = c.ink2, modifier = Modifier.padding(top = 6.dp))
        }

        Spacer(Modifier.height(16.dp))

        if (down && ui.outageSince != null) {
            OutageClock(ui.outageSince, c.onDownContainer)
            Text("without mains", style = SolarType.caption, color = c.onDownContainer)
            Spacer(Modifier.height(16.dp))
        }

        Row(verticalAlignment = Alignment.Bottom) {
            Figure(
                value = ui.socText,
                unit = "%",
                label = "Battery",
                style = if (down) SolarType.figureL else SolarType.figureXL,
                color = if (ui.trusted) c.ink else c.ink3
            )
            Spacer(Modifier.weight(1f))
            val runtime = ui.runtime
            Column(horizontalAlignment = Alignment.End) {
                when {
                    ui.fullIn != null -> {
                        Text(ui.fullIn.nb(), style = SolarType.figure, color = c.ink)
                        Text("to full${ui.fullAt?.let { ", about ${clock.format(Date(it))}" } ?: ""}", style = SolarType.caption, color = c.ink3)
                    }
                    runtime != null -> {
                        Text(runtime.nb(), style = SolarType.figure, color = if (down) c.onDownContainer else c.ink)
                        Text(
                            when {
                                down && ui.batteryMode == BatteryMode.DISCHARGING -> ui.emptyAt?.let { "left, until about ${clock.format(Date(it))}" } ?: "left"
                                down -> "left once solar drops"
                                else -> "backup if the mains go"
                            },
                            style = SolarType.caption,
                            color = if (down) c.onDownContainer else c.ink3
                        )
                    }
                    else -> Text(ui.runtimeNote, style = SolarType.caption, color = c.ink3)
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        BatteryMeter(ui.soc, if (ui.trusted) batteryTint else c.ink3)
        Spacer(Modifier.height(10.dp))
        Text(
            batteryLine(ui),
            style = SolarType.caption,
            color = if (down) c.onDownContainer else c.ink3
        )
    }
}

private fun batteryLine(ui: HomeUi): String = when (ui.batteryMode) {
    BatteryMode.CHARGING -> "Charging at ${ui.batteryLabel.removeSuffix(" in")}"
    BatteryMode.DISCHARGING -> "Supplying the house at ${ui.batteryLabel.removeSuffix(" out")}"
    BatteryMode.IDLE -> if (ui.heroRole == StateRole.DOWN) "Solar is carrying the house for now" else "Battery is resting"
    BatteryMode.UNKNOWN -> ui.heroSub
}

/** The outage, counting up live. */
@Composable
private fun OutageClock(since: Long, color: Color) {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(since) {
        while (true) {
            now = System.currentTimeMillis()
            delay(1000L - now % 1000L)
        }
    }
    val s = ((now - since).coerceAtLeast(0L)) / 1000L
    RollingText("%d:%02d:%02d".format(s / 3600, (s % 3600) / 60, s % 60), SolarType.figureXL, color)
}

@Composable
private fun TodayTiles(ui: HomeUi, state: UiState) {
    val c = Solar.colors
    val today = ui.today
    val todayStart = OutageLog.startOfDay(state.now)
    val cuts = state.outages.filter { (it.endedAt ?: state.now) >= todayStart }
    val cutMs = cuts.sumOf { minOf(it.endedAt ?: state.now, state.now) - maxOf(it.startedAt, todayStart) }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Tile(today.producedKwh.kwh(), "kWh", "Solar made", Icons.Sun, c.solar, Modifier.weight(1f))
            Tile(today.consumedKwh.kwh(), "kWh", "Home used", Icons.Home, c.home, Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Tile(today.boughtKwh.kwh(), "kWh", "From grid", Icons.Pylon, c.grid, Modifier.weight(1f))
            val (cutValue, cutUnit) = compactDuration(if (cuts.isEmpty()) 0L else cutMs)
            Tile(
                cutValue,
                cutUnit,
                when (cuts.size) {
                    0 -> "No cuts today"
                    1 -> "1 cut today"
                    else -> "${cuts.size} cuts today"
                },
                Icons.Live,
                if (cuts.isEmpty()) c.ok else c.down,
                Modifier.weight(1f)
            )
        }
        today.selfSufficiency?.let { share ->
            val fill by animateFloatAsState(share.toFloat(), tween(800, easing = FastOutSlowInEasing), label = "self")
            SCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Self-powered today", style = SolarType.body, color = c.ink, modifier = Modifier.weight(1f))
                    Text("%.0f%%".format(share * 100), style = SolarType.figureS, color = c.ink)
                }
                Spacer(Modifier.height(8.dp))
                Box(Modifier.fillMaxWidth().height(6.dp).clip(CircleShape).background(c.surfaceVariant)) {
                    Box(Modifier.fillMaxWidth(fill).height(6.dp).clip(CircleShape).background(c.ok))
                }
                Text("Share of today's use that didn't come from the grid", style = SolarType.caption, color = c.ink3, modifier = Modifier.padding(top = 6.dp))
            }
        }
    }
}

@Composable
private fun Tile(value: String, unit: String?, label: String, icon: ImageVector, tint: Color, modifier: Modifier = Modifier) {
    SCard(modifier) {
        Row {
            Figure(value, unit = unit, label = label, modifier = Modifier.weight(1f))
            Icon(icon, null, tint = tint, modifier = Modifier.size(22.dp))
        }
    }
}

private fun Double?.kwh(): String = this?.let { "%.1f".format(it) } ?: "—"

/** "since 21:30" today, "since yesterday 21:30", "since Thu 21:30" this week, else "since 12 Oct". */
private fun sinceLabel(at: Long, now: Long, clock: SimpleDateFormat): String {
    val today = OutageLog.startOfDay(now)
    val time = clock.format(Date(at))
    return when {
        at >= today -> "since $time"
        at >= OutageLog.startOfDay(today - 1) -> "since yesterday $time"
        at >= today - 6 * OutageLog.DAY_MS -> "since ${SimpleDateFormat("EEE", Locale.getDefault()).format(Date(at))} $time"
        else -> "since ${SimpleDateFormat("d MMM", Locale.getDefault()).format(Date(at))}"
    }
}

/** Short value + unit for a half-width tile, where "under a minute" would not fit. */
private fun compactDuration(ms: Long): Pair<String, String?> {
    val minutes = ms / 60_000L
    return when {
        ms <= 0L -> "0" to "min"
        minutes < 1 -> "<1" to "min"
        minutes < 60 -> "$minutes" to "min"
        else -> "${minutes / 60}h\u00A0${minutes % 60}m" to null
    }
}

@Composable
private fun ForecastCard(state: UiState) {
    val c = Solar.colors
    val prediction = state.prediction
    val cal = Calendar.getInstance().apply { timeInMillis = state.now }
    val nowMinute = cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE)

    SCard {
        CardHeader("Next likely cut")
        Spacer(Modifier.height(8.dp))
        if (prediction == null) {
            val days = state.observedSince?.let { ((state.now - it) / OutageLog.DAY_MS).toInt() } ?: 0
            Text(
                if (days < OutagePredictor.MIN_DAYS_OBSERVED) {
                    "Learning your schedule — forecasts start after ${OutagePredictor.MIN_DAYS_OBSERVED} days of history ($days so far)."
                } else {
                    "No regular pattern in the last two weeks."
                },
                style = SolarType.body,
                color = c.ink2
            )
            if (state.schedule.isNotEmpty()) ScheduleRibbon(state.schedule, nowMinute, null, Modifier.padding(top = 12.dp))
            return@SCard
        }

        val clock = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(prediction.startsAt))
        val inMin = ((prediction.startsAt - state.now) / 60_000L).toInt().coerceAtLeast(1)
        val typical = prediction.slot.typicalDurationMs
        val runtime = state.snapshot.runtimeMinutes

        Row(verticalAlignment = Alignment.Bottom) {
            Text("~$clock", style = SolarType.figureL, color = c.ink)
            Spacer(Modifier.width(10.dp))
            Text("in ${RuntimeEstimator.describe(inMin).nb()}", style = SolarType.body, color = c.ink2, modifier = Modifier.padding(bottom = 5.dp))
        }
        Text(
            "Went off near this time on ${prediction.slot.daysSeen} of the last ${prediction.slot.daysObserved} days" +
                (typical?.let { ", usually for ${OutageLog.describeDuration(it).nb()}" } ?: "") + ".",
            style = SolarType.caption,
            color = c.ink3,
            modifier = Modifier.padding(top = 4.dp)
        )
        if (runtime != null && typical != null && state.home.trusted) {
            val covers = runtime * 60_000L >= typical
            Tag(
                if (covers) "Battery should cover it" else "Battery may run short at this load",
                if (covers) c.ok else c.warn,
                icon = if (covers) Icons.Check else Icons.Warning,
                modifier = Modifier.padding(top = 10.dp)
            )
        }
        ScheduleRibbon(state.schedule, nowMinute, prediction.slot.minuteOfDay, Modifier.padding(top = 14.dp))
        if (state.schedule.size > 1) {
            Text("Usual times: " + state.schedule.joinToString(" · ") { minuteLabel(it.minuteOfDay) }, style = SolarType.caption, color = c.ink3, modifier = Modifier.padding(top = 6.dp))
        }
    }
}

@Composable
private fun GridCard(ui: HomeUi) {
    val c = Solar.colors
    SCard(padding = androidx.compose.foundation.layout.PaddingValues(vertical = 8.dp)) {
        CardHeader("Grid and inverter", modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
        Fact("Grid voltage", if (ui.voltUnit.isEmpty()) "—" else "${ui.voltText} V")
        Fact("Frequency", ui.frequencyText ?: "—")
        if (ui.phases.isNotEmpty()) {
            Fact(
                "Phases",
                ui.phases.joinToString("  ") { "${it.label} ${it.volts}" },
                note = ui.phaseNote,
                alert = ui.phaseNote != null
            )
        }
        Fact("Inverter", ui.inverterTemp ?: "—", note = if (ui.inverterTempHot) "Running hot" else null, alert = ui.inverterTempHot)
        Fact("Battery temperature", ui.batteryTemp ?: "—")
        Fact("Decided by", ui.basis, last = true)
    }
}

@Composable
private fun Fact(label: String, value: String, note: String? = null, alert: Boolean = false, last: Boolean = false) {
    val c = Solar.colors
    Column(Modifier.padding(horizontal = 16.dp)) {
        Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(label, style = SolarType.body, color = c.ink2)
                if (note != null) Text(note, style = SolarType.caption, color = if (alert) c.down else c.ink3)
            }
            Text(value, style = SolarType.body.copy(fontFeatureSettings = "tnum"), color = if (alert) c.down else c.ink)
        }
        if (!last) Divider()
    }
}

@Composable
private fun DemoCard(vm: MainViewModel) {
    val c = Solar.colors
    SCard {
        CardHeader("Demo controls")
        Text(
            "Make the simulated grid fail to try the alarm, the outage notification and the outage clock.",
            style = SolarType.caption,
            color = c.ink3,
            modifier = Modifier.padding(top = 4.dp, bottom = 12.dp)
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SecondaryButton("Simulate outage", onClick = { vm.simulateOutage() }, color = c.down, modifier = Modifier.weight(1f))
            SecondaryButton("Restore", onClick = { vm.endSimulatedOutage() }, modifier = Modifier.weight(1f))
        }
    }
}
