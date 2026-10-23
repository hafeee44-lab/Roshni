package io.github.hafeee44lab.solargrid.ui.live

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
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
import io.github.hafeee44lab.solargrid.ui.components.BatteryRing
import io.github.hafeee44lab.solargrid.ui.components.DayChart
import io.github.hafeee44lab.solargrid.ui.components.GhostButton
import io.github.hafeee44lab.solargrid.ui.components.Hairline
import io.github.hafeee44lab.solargrid.ui.components.Icons
import io.github.hafeee44lab.solargrid.ui.components.Panel
import io.github.hafeee44lab.solargrid.ui.components.Pill
import io.github.hafeee44lab.solargrid.ui.components.RollingText
import io.github.hafeee44lab.solargrid.ui.components.ScheduleRibbon
import io.github.hafeee44lab.solargrid.ui.components.SectionLabel
import io.github.hafeee44lab.solargrid.ui.components.Stat
import io.github.hafeee44lab.solargrid.ui.components.StatusDot
import io.github.hafeee44lab.solargrid.ui.components.minuteLabel
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
    onOpenInsights: () -> Unit,
    onOpenSettings: () -> Unit
) {
    val checking by vm.checking.collectAsStateWithLifecycle()
    val permissions by vm.permissions.collectAsStateWithLifecycle()
    val ui = state.home
    val colors = Solar.colors

    PullToRefreshBox(
        isRefreshing = checking,
        onRefresh = vm::refresh,
        modifier = Modifier.fillMaxSize()
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .windowInsetsPadding(WindowInsets.statusBars)
                .padding(screenPadding()),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Header(state, checking, onRefresh = vm::refresh)

            AnimatedVisibility(!state.settings.monitoring, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
                Banner(
                    title = "Monitoring is paused",
                    body = "Nothing will alert you if the grid fails.",
                    action = "Turn on",
                    tone = colors.warn,
                    onAction = { vm.setMonitoring(true) }
                )
            }

            AnimatedVisibility(state.settings.monitoring && permissions.blocking > 0, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
                Banner(
                    title = if (permissions.blocking == 1) "1 setting can stop alarms reaching you" else "${permissions.blocking} settings can stop alarms reaching you",
                    body = "Fix them in Settings → Reliability.",
                    action = "Fix",
                    tone = colors.down,
                    onAction = onOpenSettings
                )
            }

            Hero(ui)

            Panel(padding = PaddingValues(horizontal = 8.dp, vertical = 12.dp)) {
                PowerFlow(
                    powers = ui.powers,
                    soc = ui.soc,
                    gridDown = if (ui.trusted) state.snapshot.gridDown else null
                )
            }

            BatteryPanel(ui, state)

            ForecastPanel(state)

            TodayPanel(ui)

            Panel(onClick = onOpenInsights) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    SectionLabel("Today", modifier = Modifier.weight(1f))
                    Text("Insights", style = SolarType.caption, color = colors.ink3)
                    Icon(Icons.Chevron, null, tint = colors.ink3, modifier = Modifier.size(16.dp))
                }
                Spacer(Modifier.height(14.dp))
                val today = OutageLog.startOfDay(state.now)
                DayChart(
                    samples = state.todaySamples,
                    outages = state.outages,
                    from = today,
                    to = today + OutageLog.DAY_MS,
                    now = state.now
                )
            }

            GridDetails(ui)

            Freshness(ui)

            if (state.settings.demoMode) {
                DemoControls(vm)
            }
        }
    }
}

@Composable
private fun Header(state: UiState, checking: Boolean, onRefresh: () -> Unit) {
    val colors = Solar.colors
    val ui = state.home
    val dot = when (ui.heroRole) {
        StateRole.UP -> colors.ok
        StateRole.DOWN -> colors.down
        StateRole.NEUTRAL -> colors.ink3
    }

    val spin = rememberInfiniteTransition(label = "spin")
    val angle by spin.animateFloat(0f, 360f, infiniteRepeatable(tween(900, easing = LinearEasing)), label = "angle")

    Row(
        Modifier.fillMaxWidth().padding(top = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        StatusDot(dot, live = state.settings.monitoring && ui.trusted)
        Spacer(Modifier.width(4.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = when {
                    state.settings.demoMode -> "Demo system"
                    !state.account?.label.isNullOrBlank() -> state.account!!.label
                    else -> "My system"
                },
                style = SolarType.bodyStrong,
                color = colors.ink,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = if (state.settings.monitoring) "Watching · adaptive polling" else "Not monitoring",
                style = SolarType.caption,
                color = colors.ink3
            )
        }
        if (state.settings.demoMode) {
            Pill("Demo", colors.battery, icon = Icons.Flask)
            Spacer(Modifier.width(8.dp))
        }
        Box(
            Modifier
                .size(48.dp)
                .clip(CircleShape)
                .background(colors.surface)
                .clickable(onClick = onRefresh)
                .semantics { contentDescription = "Check now" },
            contentAlignment = Alignment.Center
        ) {
            Icon(
                Icons.Refresh,
                null,
                tint = colors.ink2,
                modifier = Modifier
                    .size(20.dp)
                    .graphicsLayer { rotationZ = if (checking) angle else 0f }
            )
        }
    }
}

@Composable
private fun Banner(title: String, body: String, action: String, tone: Color, onAction: () -> Unit) {
    val colors = Solar.colors
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(tone.copy(alpha = 0.12f))
            .clickable(onClick = onAction)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(Icons.Warning, null, tint = tone, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = SolarType.bodyStrong, color = colors.ink)
            Text(body, style = SolarType.caption, color = colors.ink2)
        }
        Text(action, style = SolarType.bodyStrong, color = tone)
    }
}

@Composable
private fun Hero(ui: HomeUi) {
    val colors = Solar.colors
    val tone by animateColorAsState(
        when (ui.heroRole) {
            StateRole.UP -> colors.ok
            StateRole.DOWN -> colors.down
            StateRole.NEUTRAL -> colors.ink2
        },
        tween(700),
        label = "heroTone"
    )

    Column(Modifier.fillMaxWidth().padding(top = 6.dp, bottom = 2.dp)) {
        AnimatedContent(
            targetState = ui.heroState,
            transitionSpec = {
                (slideInVertically(tween(450, easing = FastOutSlowInEasing)) { it / 2 } + fadeIn(tween(350))) togetherWith
                    (slideOutVertically(tween(300)) { -it / 2 } + fadeOut(tween(200)))
            },
            label = "hero"
        ) { text ->
            Text(
                text = text,
                style = SolarType.display,
                color = tone,
                modifier = Modifier.semantics { heading() }
            )
        }
        Text(ui.heroSub, style = SolarType.body, color = colors.ink2, modifier = Modifier.padding(top = 4.dp))

        ui.outageSince?.let { since ->
            OutageClock(since)
        }

        Row(Modifier.padding(top = 14.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (ui.trusted && ui.basis != "—") Pill("via ${ui.basis}", colors.ink2, icon = Icons.Info)
            Pill(
                ui.freshPrimary,
                if (ui.freshAlert) colors.warn else colors.ink2,
                icon = Icons.Clock
            )
        }
    }
}

/** The outage, counting up live — the number people actually want during load-shedding. */
@Composable
private fun OutageClock(since: Long) {
    val colors = Solar.colors
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(since) {
        while (true) {
            now = System.currentTimeMillis()
            delay(1000L - now % 1000L)
        }
    }
    val elapsed = (now - since).coerceAtLeast(0L) / 1000L
    val text = "%d:%02d:%02d".format(elapsed / 3600, (elapsed % 3600) / 60, elapsed % 60)
    val started = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(since))

    Row(
        Modifier
            .padding(top = 16.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(colors.down.copy(alpha = 0.12f))
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column {
            SectionLabel("On battery for", color = colors.down)
            RollingText(text, SolarType.number, colors.ink, modifier = Modifier.padding(top = 2.dp))
        }
        Spacer(Modifier.width(18.dp))
        Column {
            SectionLabel("Since", color = colors.down)
            Text(started, style = SolarType.number, color = colors.ink, modifier = Modifier.padding(top = 2.dp))
        }
    }
}

@Composable
private fun BatteryPanel(ui: HomeUi, state: UiState) {
    val colors = Solar.colors
    val alerts = state.settings.alerts
    val clock = SimpleDateFormat("HH:mm", Locale.getDefault())

    Panel(glow = if (ui.batteryMode == BatteryMode.CHARGING) colors.ok else colors.battery) {
        SectionLabel("Battery")
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            BatteryRing(
                soc = ui.soc,
                mode = ui.batteryMode,
                reservePercent = alerts.batteryReservePercent,
                lowPercent = alerts.lowBatteryPercent
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Row(verticalAlignment = Alignment.Bottom) {
                        RollingText(ui.socText, SolarType.display.copy(fontSize = SolarType.hero.fontSize), colors.ink)
                        Text("%", style = SolarType.bodyStrong, color = colors.ink3, modifier = Modifier.padding(bottom = 6.dp, start = 1.dp))
                    }
                    Text(
                        when (ui.batteryMode) {
                            BatteryMode.CHARGING -> "Charging"
                            BatteryMode.DISCHARGING -> "Discharging"
                            BatteryMode.IDLE -> "Standby"
                            BatteryMode.UNKNOWN -> "—"
                        },
                        style = SolarType.caption,
                        color = when (ui.batteryMode) {
                            BatteryMode.CHARGING -> colors.ok
                            BatteryMode.DISCHARGING -> colors.battery
                            else -> colors.ink3
                        }
                    )
                }
            }
            Spacer(Modifier.width(18.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                when {
                    ui.fullIn != null -> Stat("Full in", ui.fullIn, note = ui.fullAt?.let { "around ${clock.format(Date(it))}" })
                    ui.runtime != null && ui.batteryMode == BatteryMode.DISCHARGING ->
                        Stat("Lasts", ui.runtime, note = ui.emptyAt?.let { "empty around ${clock.format(Date(it))}" } ?: ui.runtimeNote)
                    ui.runtime != null && ui.heroRole == StateRole.DOWN ->
                        Stat("Lasts", ui.runtime, note = "solar is carrying the house for now")
                    ui.runtime != null -> Stat("Backup", ui.runtime, note = "if the grid failed now")
                    else -> Stat("Runtime", "—", note = ui.runtimeNote)
                }
                Stat(
                    label = when (ui.batteryMode) {
                        BatteryMode.CHARGING -> "Going in"
                        BatteryMode.DISCHARGING -> "Coming out"
                        else -> "Flow"
                    },
                    value = ui.batteryLabel.removeSuffix(" in").removeSuffix(" out"),
                    color = if (ui.batteryMode == BatteryMode.CHARGING) colors.ok else colors.ink
                )
            }
        }
        Spacer(Modifier.height(14.dp))
        Hairline()
        Spacer(Modifier.height(12.dp))
        Row {
            MiniFact("Reserve", "${alerts.batteryReservePercent}%", Modifier.weight(1f))
            MiniFact("Capacity", state.snapshot.capacityWh?.let { "%.1f kWh".format(it / 1000) } ?: "—", Modifier.weight(1f))
            MiniFact("Temp", ui.batteryTemp ?: "—", Modifier.weight(1f))
        }
        if (ui.runtime != null && !state.snapshot.runtimeCalibrated) {
            Text(
                "Runtime uses the rated capacity until the app has watched a real discharge.",
                style = SolarType.caption,
                color = colors.ink3,
                modifier = Modifier.padding(top = 10.dp)
            )
        }
    }
}

@Composable
private fun MiniFact(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier) {
        Text(label, style = SolarType.caption, color = Solar.colors.ink3)
        Text(value, style = SolarType.numberSmall, color = Solar.colors.ink)
    }
}

@Composable
private fun ForecastPanel(state: UiState) {
    val colors = Solar.colors
    val prediction = state.prediction
    val calendar = Calendar.getInstance().apply { timeInMillis = state.now }
    val nowMinute = calendar.get(Calendar.HOUR_OF_DAY) * 60 + calendar.get(Calendar.MINUTE)

    Panel(glow = if (prediction != null) colors.down else null) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            SectionLabel("Outage forecast", modifier = Modifier.weight(1f))
            Icon(Icons.Sparkle, null, tint = colors.ink3, modifier = Modifier.size(16.dp))
        }

        if (prediction == null) {
            val days = state.observedSince?.let { ((state.now - it) / OutageLog.DAY_MS).toInt() } ?: 0
            val learning = days < OutagePredictor.MIN_DAYS_OBSERVED
            Text(
                if (learning) "Learning your schedule" else "No regular pattern",
                style = SolarType.title,
                color = colors.ink,
                modifier = Modifier.padding(top = 10.dp)
            )
            Text(
                if (learning) {
                    "Forecasts start after ${OutagePredictor.MIN_DAYS_OBSERVED} days of history — $days so far."
                } else {
                    "Outages haven't clustered around a time of day in the last two weeks."
                },
                style = SolarType.body,
                color = colors.ink2,
                modifier = Modifier.padding(top = 4.dp)
            )
            if (state.schedule.isNotEmpty()) {
                ScheduleRibbon(state.schedule, nowMinute, null, Modifier.padding(top = 16.dp))
            }
            return@Panel
        }

        val clock = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(prediction.startsAt))
        val inMs = prediction.startsAt - state.now
        val runtime = state.snapshot.runtimeMinutes
        val typical = prediction.slot.typicalDurationMs

        Row(Modifier.padding(top = 10.dp), verticalAlignment = Alignment.Bottom) {
            Text("~$clock", style = SolarType.hero, color = colors.ink)
            Spacer(Modifier.width(10.dp))
            Text(
                "in ${RuntimeEstimator.describe((inMs / 60_000L).toInt().coerceAtLeast(1))}",
                style = SolarType.bodyStrong,
                color = colors.down,
                modifier = Modifier.padding(bottom = 5.dp)
            )
        }
        Text(
            "Went off near this time on ${prediction.slot.daysSeen} of the last ${prediction.slot.daysObserved} days" +
                (typical?.let { ", usually for ${OutageLog.describeDuration(it)}" } ?: "") + ".",
            style = SolarType.body,
            color = colors.ink2,
            modifier = Modifier.padding(top = 4.dp)
        )

        if (runtime != null && typical != null && state.home.trusted) {
            val covers = runtime * 60_000L >= typical
            Pill(
                if (covers) "Battery should cover it" else "Battery may fall short at this load",
                if (covers) colors.ok else colors.warn,
                icon = if (covers) Icons.Check else Icons.Warning,
                modifier = Modifier.padding(top = 12.dp)
            )
        }

        ScheduleRibbon(state.schedule, nowMinute, prediction.slot.minuteOfDay, Modifier.padding(top = 16.dp))

        if (state.schedule.size > 1) {
            Text(
                "Usual times: " + state.schedule.joinToString(" · ") { minuteLabel(it.minuteOfDay) },
                style = SolarType.caption,
                color = colors.ink3,
                modifier = Modifier.padding(top = 8.dp)
            )
        }
    }
}

@Composable
private fun TodayPanel(ui: HomeUi) {
    val colors = Solar.colors
    val today = ui.today
    if (!today.hasAny) return

    Panel(glow = colors.solar) {
        SectionLabel("Energy today")
        Spacer(Modifier.height(14.dp))
        Row {
            Stat("Solar made", today.producedKwh.kwh(), Modifier.weight(1f), unit = "kWh", color = colors.solar)
            Stat("Home used", today.consumedKwh.kwh(), Modifier.weight(1f), unit = "kWh")
        }
        Spacer(Modifier.height(14.dp))
        Row {
            Stat("From grid", today.boughtKwh.kwh(), Modifier.weight(1f), unit = "kWh", color = colors.grid)
            Stat("To grid", today.soldKwh.kwh(), Modifier.weight(1f), unit = "kWh", color = colors.ink2)
        }

        today.selfSufficiency?.let { share ->
            val animated by animateFloatAsState(share.toFloat(), tween(1000, easing = FastOutSlowInEasing), label = "self")
            Spacer(Modifier.height(18.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Self-powered", style = SolarType.bodyStrong, color = colors.ink, modifier = Modifier.weight(1f))
                Text("%.0f%%".format(share * 100), style = SolarType.numberSmall, color = colors.ok)
            }
            Spacer(Modifier.height(8.dp))
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(8.dp)
                    .clip(CircleShape)
                    .background(colors.track)
            ) {
                Box(
                    Modifier
                        .fillMaxWidth(animated)
                        .height(8.dp)
                        .clip(CircleShape)
                        .background(androidx.compose.ui.graphics.Brush.horizontalGradient(listOf(colors.solar, colors.ok)))
                )
            }
            Text(
                "Share of today's use that didn't come from the grid.",
                style = SolarType.caption,
                color = colors.ink3,
                modifier = Modifier.padding(top = 6.dp)
            )
        }
    }
}

private fun Double?.kwh(): String = this?.let { "%.1f".format(it) } ?: "—"

@Composable
private fun GridDetails(ui: HomeUi) {
    val colors = Solar.colors
    Panel {
        SectionLabel("Grid & inverter")
        Spacer(Modifier.height(14.dp))
        Row {
            Stat("Voltage", ui.voltText, Modifier.weight(1f), unit = ui.voltUnit.ifEmpty { null }, note = ui.voltNote)
            Stat("Frequency", ui.frequencyText?.removeSuffix(" Hz") ?: "—", Modifier.weight(1f), unit = if (ui.frequencyText != null) "Hz" else null)
        }

        if (ui.phases.isNotEmpty()) {
            Spacer(Modifier.height(16.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                SectionLabel("Phases", modifier = Modifier.weight(1f))
                ui.phaseNote?.let { Text(it, style = SolarType.caption, color = colors.down) }
            }
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                ui.phases.forEach { phase ->
                    val fill by animateFloatAsState(if (phase.live) ((phase.volts.toFloatOrNull() ?: 0f) / 260f).coerceIn(0f, 1f) else 0.02f, tween(800), label = "phase")
                    Column(Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.Bottom) {
                            Text(phase.label, style = SolarType.label, color = colors.ink3, modifier = Modifier.weight(1f))
                            Text("${phase.volts} V", style = SolarType.numberSmall, color = if (phase.live) colors.ink else colors.down)
                        }
                        Spacer(Modifier.height(6.dp))
                        Box(Modifier.fillMaxWidth().height(5.dp).clip(CircleShape).background(colors.track)) {
                            Box(Modifier.fillMaxWidth(fill).height(5.dp).clip(CircleShape).background(if (phase.live) colors.grid else colors.down))
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(16.dp))
        Row {
            Stat(
                "Inverter",
                ui.inverterTemp ?: "—",
                Modifier.weight(1f),
                color = if (ui.inverterTempHot) colors.down else colors.ink,
                note = if (ui.inverterTempHot) "Running hot" else null
            )
            Stat("Home load", ui.houseLoad, Modifier.weight(1f))
        }
    }
}

@Composable
private fun Freshness(ui: HomeUi) {
    val colors = Solar.colors
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(ui.freshPrimary, style = SolarType.caption, color = if (ui.freshAlert) colors.warn else colors.ink3)
        Text(ui.freshSecondary, style = SolarType.caption, color = colors.ink3)
    }
}

@Composable
private fun DemoControls(vm: MainViewModel) {
    val colors = Solar.colors
    Panel(glow = colors.battery) {
        SectionLabel("Demo controls", color = colors.battery)
        Text(
            "Make the simulated grid fail to see the alarm, the live notification and the outage clock.",
            style = SolarType.body,
            color = colors.ink2,
            modifier = Modifier.padding(top = 6.dp, bottom = 12.dp)
        )
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            GhostButton("Simulate outage", onClick = { vm.simulateOutage() }, color = colors.down, icon = Icons.Pylon)
            GhostButton("Restore", onClick = { vm.endSimulatedOutage() }, color = colors.ok, icon = Icons.Check)
        }
    }
}
