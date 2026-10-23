package io.github.hafeee44lab.solargrid.ui.insights

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.hafeee44lab.solargrid.data.DayEnergy
import io.github.hafeee44lab.solargrid.data.Sample
import io.github.hafeee44lab.solargrid.domain.EventKind
import io.github.hafeee44lab.solargrid.domain.Insights
import io.github.hafeee44lab.solargrid.domain.OutageLog
import io.github.hafeee44lab.solargrid.domain.OutagePredictor
import io.github.hafeee44lab.solargrid.domain.OutageRecord
import io.github.hafeee44lab.solargrid.ui.MainViewModel
import io.github.hafeee44lab.solargrid.ui.SystemActions
import io.github.hafeee44lab.solargrid.ui.UiState
import io.github.hafeee44lab.solargrid.ui.components.DayChart
import io.github.hafeee44lab.solargrid.ui.components.EnergyBars
import io.github.hafeee44lab.solargrid.ui.components.GhostButton
import io.github.hafeee44lab.solargrid.ui.components.Hairline
import io.github.hafeee44lab.solargrid.ui.components.Icons
import io.github.hafeee44lab.solargrid.ui.components.Legend
import io.github.hafeee44lab.solargrid.ui.components.OutageHeatmap
import io.github.hafeee44lab.solargrid.ui.components.Panel
import io.github.hafeee44lab.solargrid.ui.components.RollingText
import io.github.hafeee44lab.solargrid.ui.components.SectionLabel
import io.github.hafeee44lab.solargrid.ui.components.Segmented
import io.github.hafeee44lab.solargrid.ui.components.Stat
import io.github.hafeee44lab.solargrid.ui.components.durationLabel
import io.github.hafeee44lab.solargrid.ui.components.minuteLabel
import io.github.hafeee44lab.solargrid.ui.screenPadding
import io.github.hafeee44lab.solargrid.ui.theme.Solar
import io.github.hafeee44lab.solargrid.ui.theme.SolarType
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class Range(val label: String, val days: Int) { DAY("Today", 1), WEEK("7 days", 7), MONTH("30 days", 30) }

@Composable
fun InsightsScreen(vm: MainViewModel, state: UiState, system: SystemActions) {
    var range by rememberSaveable { mutableStateOf(Range.WEEK) }
    val colors = Solar.colors

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .windowInsetsPadding(WindowInsets.statusBars)
            .padding(screenPadding()),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Text("Insights", style = SolarType.hero, color = colors.ink, modifier = Modifier.padding(top = 16.dp))
        Text(
            "What the grid has really been doing, from your own history.",
            style = SolarType.body,
            color = colors.ink2
        )

        Segmented(
            options = Range.entries.map { it to it.label },
            selected = range,
            onSelect = { range = it },
            modifier = Modifier.padding(top = 4.dp)
        )

        AnimatedContent(range, transitionSpec = { fadeIn(tween(250)) togetherWith fadeOut(tween(150)) }, label = "range") { r ->
            ReliabilityPanel(state, r)
        }

        SchedulePanel(state)

        Panel {
            SectionLabel("Outage map · last 14 days")
            Text(
                "Each square is an hour. The darker it is, the longer the mains were off.",
                style = SolarType.caption,
                color = colors.ink3,
                modifier = Modifier.padding(top = 4.dp, bottom = 14.dp)
            )
            var picked by remember { mutableStateOf<String?>(null) }
            val heatmap = remember(state.outages, state.now / 60_000L) { Insights.heatmap(state.outages, 14, state.now) }
            val dayFormat = remember { SimpleDateFormat("EEE d MMM", Locale.getDefault()) }
            OutageHeatmap(
                heatmap,
                Modifier.semantics {
                    contentDescription = "Outage map: " + heatmap.joinToString("; ") { d -> "${dayFormat.format(Date(d.dayStart))} ${d.minutesDown.sum()} minutes without grid" }
                }
            ) { day, hour ->
                val minutes = day.minutesDown[hour]
                picked = "${dayFormat.format(Date(day.dayStart))}, ${"%02d:00".format(hour)} — " +
                    if (minutes == 0) "grid on" else "$minutes min without grid"
            }
            Text(
                picked ?: "Tap a square for details.",
                style = SolarType.caption,
                color = if (picked != null) colors.ink else colors.ink3,
                modifier = Modifier.padding(top = 10.dp)
            )
        }

        DayBrowser(vm, state)

        EnergyPanel(state, range)

        OutageList(vm, state, range, system)

        ActivityPanel(state)
    }
}

@Composable
private fun ReliabilityPanel(state: UiState, range: Range) {
    val colors = Solar.colors
    val now = state.now
    val from = if (range == Range.DAY) OutageLog.startOfDay(now) else now - range.days * OutageLog.DAY_MS
    val stats = remember(state.outages, range, now / 60_000L, state.observedSince) {
        Insights.range(state.outages, from, now, state.observedSince)
    }

    Panel(glow = if ((stats.uptime ?: 1.0) < 0.9) colors.down else colors.ok) {
        SectionLabel("Grid reliability · ${range.label.lowercase()}")
        Row(Modifier.padding(top = 10.dp), verticalAlignment = Alignment.Bottom) {
            RollingText(
                stats.uptime?.let { "%.1f".format(it * 100) } ?: "—",
                SolarType.display.copy(fontSize = SolarType.display.fontSize * 1.2f),
                colors.ink
            )
            if (stats.uptime != null) {
                Text("%", style = SolarType.title, color = colors.ink3, modifier = Modifier.padding(bottom = 8.dp, start = 2.dp))
            }
            Spacer(Modifier.width(10.dp))
            Text(
                if (stats.uptime == null) "not enough history yet" else "of the time the mains were on",
                style = SolarType.caption,
                color = colors.ink3,
                modifier = Modifier.padding(bottom = 10.dp)
            )
        }

        val uptime by animateFloatAsState((stats.uptime ?: 0.0).toFloat(), tween(900, easing = FastOutSlowInEasing), label = "uptime")
        Box(Modifier.fillMaxWidth().padding(top = 8.dp).height(8.dp).clip(CircleShape).background(colors.down.copy(alpha = 0.5f))) {
            Box(Modifier.fillMaxWidth(uptime).height(8.dp).clip(CircleShape).background(colors.ok))
        }

        Spacer(Modifier.height(18.dp))
        Row {
            Stat("Outages", stats.count.toString(), Modifier.weight(1f))
            Stat("On battery", durationLabel(stats.downMs.takeIf { it > 0 }), Modifier.weight(1f))
        }
        Spacer(Modifier.height(14.dp))
        Row {
            Stat("Longest", durationLabel(stats.longestMs.takeIf { it > 0 }), Modifier.weight(1f))
            Stat("Typical", durationLabel(stats.averageMs.takeIf { it > 0 }), Modifier.weight(1f))
        }
    }
}

@Composable
private fun SchedulePanel(state: UiState) {
    val colors = Solar.colors
    Panel {
        SectionLabel("Learned schedule")
        if (state.schedule.isEmpty()) {
            val days = state.observedSince?.let { ((state.now - it) / OutageLog.DAY_MS).toInt() } ?: 0
            Text(
                if (days < OutagePredictor.MIN_DAYS_OBSERVED) {
                    "The app needs ${OutagePredictor.MIN_DAYS_OBSERVED} days of history before it can spot a pattern ($days so far)."
                } else {
                    "No time of day stands out in the last two weeks — outages have been irregular."
                },
                style = SolarType.body,
                color = colors.ink2,
                modifier = Modifier.padding(top = 8.dp)
            )
            return@Panel
        }

        Text(
            "Times the mains went off on at least half of the days watched.",
            style = SolarType.caption,
            color = colors.ink3,
            modifier = Modifier.padding(top = 4.dp, bottom = 8.dp)
        )
        state.schedule.forEach { slot ->
            val next = state.prediction?.slot?.minuteOfDay == slot.minuteOfDay
            val share by animateFloatAsState(slot.share.toFloat(), tween(800), label = "share")
            Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(minuteLabel(slot.minuteOfDay), style = SolarType.number.copy(fontSize = SolarType.title.fontSize), color = if (next) colors.down else colors.ink, modifier = Modifier.width(78.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        "${slot.daysSeen} of ${slot.daysObserved} days" + (slot.typicalDurationMs?.let { " · ~${OutageLog.describeDuration(it)}" } ?: ""),
                        style = SolarType.caption,
                        color = colors.ink2
                    )
                    Spacer(Modifier.height(6.dp))
                    Box(Modifier.fillMaxWidth().height(5.dp).clip(CircleShape).background(colors.track)) {
                        Box(Modifier.fillMaxWidth(share).height(5.dp).clip(CircleShape).background(colors.down.copy(alpha = 0.4f + 0.6f * slot.share.toFloat())))
                    }
                }
                if (next) {
                    Spacer(Modifier.width(10.dp))
                    Text("NEXT", style = SolarType.label, color = colors.down)
                }
            }
        }
    }
}

@Composable
private fun DayBrowser(vm: MainViewModel, state: UiState) {
    val colors = Solar.colors
    var back by rememberSaveable { mutableIntStateOf(0) }
    val today = OutageLog.startOfDay(state.now)
    val dayStart = OutageLog.startOfDay(today - back * OutageLog.DAY_MS + 3_600_000L)
    val dayEnd = OutageLog.startOfDay(dayStart + OutageLog.DAY_MS + 3_600_000L)

    val samples by produceState(emptyList<Sample>(), dayStart, state.todaySamples.size) {
        value = vm.loadSamples(dayStart, dayEnd)
    }
    val title = when (back) {
        0 -> "Today"
        1 -> "Yesterday"
        else -> SimpleDateFormat("EEEE d MMM", Locale.getDefault()).format(Date(dayStart))
    }

    Panel {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, style = SolarType.title, color = colors.ink, modifier = Modifier.weight(1f))
            RoundIcon(Icons.Back, "Previous day", enabled = back < 29) { back++ }
            Spacer(Modifier.width(8.dp))
            RoundIcon(Icons.Chevron, "Next day", enabled = back > 0) { back-- }
        }
        Spacer(Modifier.height(16.dp))
        DayChart(samples = samples, outages = state.outages, from = dayStart, to = dayEnd, now = state.now)
        if (samples.isEmpty()) {
            Text("No readings recorded for this day.", style = SolarType.caption, color = colors.ink3, modifier = Modifier.padding(top = 8.dp))
        }
    }
}

@Composable
private fun RoundIcon(icon: ImageVector, description: String, enabled: Boolean, onClick: () -> Unit) {
    val colors = Solar.colors
    Box(
        Modifier
            .size(48.dp)
            .clip(CircleShape)
            .background(colors.track)
            .clickable(enabled = enabled, onClick = onClick)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, null, tint = if (enabled) colors.ink else colors.ink3.copy(alpha = 0.4f), modifier = Modifier.size(18.dp))
    }
}

@Composable
private fun EnergyPanel(state: UiState, range: Range) {
    val colors = Solar.colors
    val count = if (range == Range.MONTH) 30 else 14
    val today = OutageLog.startOfDay(state.now)
    val days: List<DayEnergy> = remember(state.days, state.snapshot.today, count) {
        val stored = state.days.filter { it.dayStart >= today - (count - 1) * OutageLog.DAY_MS && it.dayStart < today }
        val live = if (state.snapshot.today.hasAny) listOf(DayEnergy(today, state.snapshot.today)) else emptyList()
        stored + live
    }
    if (days.isEmpty()) return

    var picked by remember { mutableStateOf<DayEnergy?>(null) }
    val produced = days.mapNotNull { it.energy.producedKwh }
    val consumed = days.mapNotNull { it.energy.consumedKwh }
    val bought = days.mapNotNull { it.energy.boughtKwh }

    Panel(glow = colors.solar) {
        SectionLabel("Solar · last ${days.size} days")
        Spacer(Modifier.height(10.dp))
        Row {
            Stat("Made", "%.0f".format(produced.sum()), Modifier.weight(1f), unit = "kWh", color = colors.solar)
            Stat("Per day", if (produced.isEmpty()) "—" else "%.1f".format(produced.average()), Modifier.weight(1f), unit = "kWh")
            Stat(
                "Self-powered",
                if (consumed.sum() > 0 && bought.isNotEmpty()) "%.0f".format((1 - bought.sum() / consumed.sum()).coerceIn(0.0, 1.0) * 100) else "—",
                Modifier.weight(1f),
                unit = "%",
                color = colors.ok
            )
        }
        Spacer(Modifier.height(18.dp))
        EnergyBars(days) { picked = it }
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Legend("Solar made", colors.solar)
            Legend("Home used", colors.ink3)
            Legend("Average", colors.solar.copy(alpha = 0.6f))
        }
        picked?.let { day ->
            Text(
                SimpleDateFormat("EEE d MMM", Locale.getDefault()).format(Date(day.dayStart)) +
                    " — made ${day.energy.producedKwh?.let { "%.1f".format(it) } ?: "—"} kWh, used ${day.energy.consumedKwh?.let { "%.1f".format(it) } ?: "—"} kWh",
                style = SolarType.caption,
                color = colors.ink,
                modifier = Modifier.padding(top = 10.dp)
            )
        }
    }
}

@Composable
private fun OutageList(vm: MainViewModel, state: UiState, range: Range, system: SystemActions) {
    val colors = Solar.colors
    val scope = rememberCoroutineScope()
    val from = if (range == Range.DAY) OutageLog.startOfDay(state.now) else state.now - range.days * OutageLog.DAY_MS
    val records = state.outages.filter { (it.endedAt ?: state.now) >= from }
    val dayFormat = remember { SimpleDateFormat("EEEE d MMM", Locale.getDefault()) }
    val time = remember { SimpleDateFormat("HH:mm", Locale.getDefault()) }

    Panel {
        Row(verticalAlignment = Alignment.CenterVertically) {
            SectionLabel("Outages", modifier = Modifier.weight(1f))
            Text("${records.size}", style = SolarType.numberSmall, color = colors.ink3)
        }

        if (records.isEmpty()) {
            Text("No outages in this period.", style = SolarType.body, color = colors.ink2, modifier = Modifier.padding(top = 10.dp))
        } else {
            var lastDay = ""
            records.take(60).forEach { record ->
                val day = dayFormat.format(Date(record.startedAt))
                if (day != lastDay) {
                    lastDay = day
                    Text(day, style = SolarType.label, color = colors.ink3, modifier = Modifier.padding(top = 16.dp, bottom = 4.dp))
                }
                OutageRow(record, state.now, time)
            }
        }

        Spacer(Modifier.height(16.dp))
        GhostButton(
            "Export all as CSV",
            onClick = { scope.launch { vm.exportIntent()?.let(system::share) } },
            icon = Icons.Export,
            modifier = Modifier.fillMaxWidth()
        )
    }
}

@Composable
private fun OutageRow(record: OutageRecord, now: Long, time: SimpleDateFormat) {
    val colors = Solar.colors
    val ongoing = record.ongoing
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(if (ongoing) colors.down.copy(alpha = 0.10f) else Color.Transparent)
            .padding(vertical = 8.dp, horizontal = if (ongoing) 10.dp else 0.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(if (ongoing) colors.down else colors.down.copy(alpha = 0.45f)))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                "${time.format(Date(record.startedAt))} – ${record.endedAt?.let { time.format(Date(it)) } ?: "now"}",
                style = SolarType.bodyStrong,
                color = colors.ink
            )
            record.batteryUsed?.let {
                Text("used ${"%.0f".format(it)}% of the battery", style = SolarType.caption, color = colors.ink3)
            }
        }
        Text(
            OutageLog.describeDuration(record.durationMs(now)),
            style = SolarType.numberSmall,
            color = if (ongoing) colors.down else colors.ink2
        )
    }
}

@Composable
private fun ActivityPanel(state: UiState) {
    val colors = Solar.colors
    if (state.events.isEmpty()) return
    val format = remember { SimpleDateFormat("d MMM, HH:mm", Locale.getDefault()) }

    Panel {
        SectionLabel("Activity")
        Spacer(Modifier.height(6.dp))
        state.events.take(25).forEachIndexed { index, event ->
            if (index > 0) Hairline()
            val (icon, tone) = when (event.kind) {
                EventKind.OUTAGE -> Icons.Pylon to colors.down
                EventKind.RESTORED -> Icons.Live to colors.ok
                EventKind.BATTERY -> Icons.Battery to colors.battery
                EventKind.HEALTH -> Icons.Thermometer to colors.warn
                EventKind.INFO -> Icons.Info to colors.ink3
            }
            Row(Modifier.padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(30.dp).clip(CircleShape).background(tone.copy(alpha = 0.12f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(icon, null, tint = tone, modifier = Modifier.size(15.dp))
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(event.text, style = SolarType.body, color = colors.ink)
                    Text(format.format(Date(event.at)), style = SolarType.caption, color = colors.ink3)
                }
            }
        }
    }
}
