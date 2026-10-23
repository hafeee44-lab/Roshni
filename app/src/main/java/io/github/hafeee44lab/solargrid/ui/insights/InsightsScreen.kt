package io.github.hafeee44lab.solargrid.ui.insights

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
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
import io.github.hafeee44lab.solargrid.ui.components.CardHeader
import io.github.hafeee44lab.solargrid.ui.components.DayChart
import io.github.hafeee44lab.solargrid.ui.components.Divider
import io.github.hafeee44lab.solargrid.ui.components.EnergyBars
import io.github.hafeee44lab.solargrid.ui.components.Figure
import io.github.hafeee44lab.solargrid.ui.components.Icons
import io.github.hafeee44lab.solargrid.ui.components.Legend
import io.github.hafeee44lab.solargrid.ui.components.OutageHeatmap
import io.github.hafeee44lab.solargrid.ui.components.SCard
import io.github.hafeee44lab.solargrid.ui.components.SecondaryButton
import io.github.hafeee44lab.solargrid.ui.components.Segmented
import io.github.hafeee44lab.solargrid.ui.components.durationLabel
import io.github.hafeee44lab.solargrid.ui.components.minuteLabel
import io.github.hafeee44lab.solargrid.ui.components.nb
import io.github.hafeee44lab.solargrid.ui.screenPadding
import io.github.hafeee44lab.solargrid.ui.theme.Solar
import io.github.hafeee44lab.solargrid.ui.theme.SolarType
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class Range(val label: String, val days: Int) { DAY("Today", 1), WEEK("Week", 7), MONTH("Month", 30) }

@Composable
fun InsightsScreen(vm: MainViewModel, state: UiState, system: SystemActions) {
    var range by rememberSaveable { mutableStateOf(Range.WEEK) }
    val c = Solar.colors

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .windowInsetsPadding(WindowInsets.statusBars)
            .padding(screenPadding()),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("History", style = SolarType.title, color = c.ink, modifier = Modifier.padding(top = 14.dp, bottom = 4.dp).semantics { heading() })

        Segmented(Range.entries.map { it to it.label }, range, { range = it })

        ReliabilityCard(state, range)
        ScheduleCard(state)

        SCard {
            CardHeader("Outage map", trailing = "Last 14 days")
            Text("Each square is an hour. Darker means the mains were off longer.", style = SolarType.caption, color = c.ink3, modifier = Modifier.padding(top = 2.dp, bottom = 12.dp))
            var picked by remember { mutableStateOf<String?>(null) }
            val heatmap = remember(state.outages, state.now / 60_000L) { Insights.heatmap(state.outages, 14, state.now) }
            val dayFormat = remember { SimpleDateFormat("EEE d MMM", Locale.getDefault()) }
            OutageHeatmap(
                heatmap,
                Modifier.semantics {
                    contentDescription = "Outage map: " + heatmap.joinToString("; ") { d -> "${dayFormat.format(Date(d.dayStart))} ${d.minutesDown.sum()} minutes off" }
                }
            ) { day, hour ->
                val minutes = day.minutesDown[hour]
                picked = "${dayFormat.format(Date(day.dayStart))}, ${"%02d:00".format(hour)}: " + if (minutes == 0) "mains on" else "$minutes min off"
            }
            Text(picked ?: "Tap or drag across the map", style = SolarType.caption, color = if (picked != null) c.ink else c.ink3, modifier = Modifier.padding(top = 10.dp))
        }

        DayCard(vm, state)
        EnergyCard(state, range)
        OutagesCard(vm, state, range, system)
        ActivityCard(state)
    }
}

@Composable
private fun ReliabilityCard(state: UiState, range: Range) {
    val c = Solar.colors
    val now = state.now
    val from = if (range == Range.DAY) OutageLog.startOfDay(now) else now - range.days * OutageLog.DAY_MS
    val stats = remember(state.outages, range, now / 60_000L, state.observedSince) { Insights.range(state.outages, from, now, state.observedSince) }
    val uptime by animateFloatAsState((stats.uptime ?: 0.0).toFloat(), tween(700, easing = FastOutSlowInEasing), label = "uptime")

    SCard {
        CardHeader("Mains reliability")
        Spacer(Modifier.height(10.dp))
        Figure(
            value = stats.uptime?.let { "%.1f".format(it * 100) } ?: "—",
            unit = if (stats.uptime != null) "%" else null,
            label = if (stats.uptime == null) "Not enough history yet" else "of the time the mains were on",
            style = SolarType.figureL
        )
        Spacer(Modifier.height(12.dp))
        Box(Modifier.fillMaxWidth().height(6.dp).clip(CircleShape).background(c.down.copy(alpha = 0.35f))) {
            Box(Modifier.fillMaxWidth(uptime).height(6.dp).clip(CircleShape).background(c.ok))
        }
        Spacer(Modifier.height(16.dp))
        Row {
            Figure(stats.count.toString(), label = "Cuts", modifier = Modifier.weight(1f), style = SolarType.figure)
            Figure(durationLabel(stats.downMs.takeIf { it > 0 }), label = "On battery", modifier = Modifier.weight(1f), style = SolarType.figure)
        }
        Spacer(Modifier.height(12.dp))
        Row {
            Figure(durationLabel(stats.longestMs.takeIf { it > 0 }), label = "Longest", modifier = Modifier.weight(1f), style = SolarType.figure)
            Figure(durationLabel(stats.averageMs.takeIf { it > 0 }), label = "Typical", modifier = Modifier.weight(1f), style = SolarType.figure)
        }
    }
}

@Composable
private fun ScheduleCard(state: UiState) {
    val c = Solar.colors
    SCard {
        CardHeader("Load-shedding pattern")
        if (state.schedule.isEmpty()) {
            val days = state.observedSince?.let { ((state.now - it) / OutageLog.DAY_MS).toInt() } ?: 0
            Text(
                if (days < OutagePredictor.MIN_DAYS_OBSERVED) {
                    "A pattern needs ${OutagePredictor.MIN_DAYS_OBSERVED} days of history ($days so far)."
                } else {
                    "No time of day stands out in the last two weeks."
                },
                style = SolarType.body,
                color = c.ink2,
                modifier = Modifier.padding(top = 6.dp)
            )
            return@SCard
        }
        Text("Times the mains went off on at least half of the days watched", style = SolarType.caption, color = c.ink3, modifier = Modifier.padding(top = 2.dp, bottom = 4.dp))
        state.schedule.forEach { slot ->
            val next = state.prediction?.slot?.minuteOfDay == slot.minuteOfDay
            val share by animateFloatAsState(slot.share.toFloat(), tween(700), label = "share")
            Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(minuteLabel(slot.minuteOfDay), style = SolarType.figure, color = c.ink, modifier = Modifier.width(76.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        "${slot.daysSeen} of ${slot.daysObserved} days" + (slot.typicalDurationMs?.let { " · about ${OutageLog.describeDuration(it).nb()}" } ?: ""),
                        style = SolarType.caption,
                        color = c.ink2
                    )
                    Spacer(Modifier.height(6.dp))
                    Box(Modifier.fillMaxWidth().height(4.dp).clip(CircleShape).background(c.surfaceVariant)) {
                        Box(Modifier.fillMaxWidth(share).height(4.dp).clip(CircleShape).background(c.down.copy(alpha = 0.7f)))
                    }
                }
                if (next) {
                    Spacer(Modifier.width(12.dp))
                    Text("Next", style = SolarType.bodyStrong, color = c.ink)
                }
            }
        }
    }
}

@Composable
private fun DayCard(vm: MainViewModel, state: UiState) {
    val c = Solar.colors
    var back by rememberSaveable { mutableIntStateOf(0) }
    val today = OutageLog.startOfDay(state.now)
    val dayStart = OutageLog.startOfDay(today - back * OutageLog.DAY_MS + 3_600_000L)
    val dayEnd = OutageLog.startOfDay(dayStart + OutageLog.DAY_MS + 3_600_000L)
    val samples by produceState(emptyList<Sample>(), dayStart, state.todaySamples.size) { value = vm.loadSamples(dayStart, dayEnd) }
    val title = when (back) {
        0 -> "Today"
        1 -> "Yesterday"
        else -> SimpleDateFormat("EEEE d MMM", Locale.getDefault()).format(Date(dayStart))
    }

    SCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { back++ }, enabled = back < 29) { Icon(Icons.Back, contentDescription = "Previous day", tint = c.ink2) }
            Text(title, style = SolarType.cardTitle, color = c.ink, modifier = Modifier.weight(1f), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            IconButton(onClick = { back-- }, enabled = back > 0) { Icon(Icons.Chevron, contentDescription = "Next day", tint = if (back > 0) c.ink2 else c.outlineStrong) }
        }
        Spacer(Modifier.height(8.dp))
        DayChart(samples = samples, outages = state.outages, from = dayStart, to = dayEnd, now = state.now)
        if (samples.isEmpty()) Text("No readings recorded for this day.", style = SolarType.caption, color = c.ink3, modifier = Modifier.padding(top = 8.dp))
    }
}

@Composable
private fun EnergyCard(state: UiState, range: Range) {
    val c = Solar.colors
    val count = if (range == Range.MONTH) 30 else 14
    val today = OutageLog.startOfDay(state.now)
    val days: List<DayEnergy> = remember(state.days, state.snapshot.today, count) {
        val stored = state.days.filter { it.dayStart >= today - (count - 1) * OutageLog.DAY_MS && it.dayStart < today }
        stored + if (state.snapshot.today.hasAny) listOf(DayEnergy(today, state.snapshot.today)) else emptyList()
    }
    if (days.isEmpty()) return

    var picked by remember { mutableStateOf<DayEnergy?>(null) }
    val produced = days.mapNotNull { it.energy.producedKwh }
    val consumed = days.mapNotNull { it.energy.consumedKwh }
    val bought = days.mapNotNull { it.energy.boughtKwh }

    SCard {
        CardHeader("Solar and use", trailing = "Last ${days.size} days")
        Spacer(Modifier.height(12.dp))
        Row {
            Figure("%.0f".format(produced.sum()), unit = "kWh", label = "Solar made", modifier = Modifier.weight(1f))
            Figure(if (produced.isEmpty()) "—" else "%.1f".format(produced.average()), unit = "kWh", label = "Per day", modifier = Modifier.weight(1f))
            Figure(
                if (consumed.sum() > 0 && bought.isNotEmpty()) "%.0f".format((1 - bought.sum() / consumed.sum()).coerceIn(0.0, 1.0) * 100) else "—",
                unit = "%",
                label = "Self-powered",
                modifier = Modifier.weight(1f)
            )
        }
        Spacer(Modifier.height(16.dp))
        EnergyBars(days) { picked = it }
        Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Legend("Solar made", c.solar)
            Legend("Home used", c.home.copy(alpha = 0.55f))
        }
        picked?.let { day ->
            Text(
                SimpleDateFormat("EEE d MMM", Locale.getDefault()).format(Date(day.dayStart)) +
                    ": made ${day.energy.producedKwh?.let { "%.1f".format(it) } ?: "—"} kWh, used ${day.energy.consumedKwh?.let { "%.1f".format(it) } ?: "—"} kWh",
                style = SolarType.caption,
                color = c.ink,
                modifier = Modifier.padding(top = 8.dp)
            )
        }
    }
}

@Composable
private fun OutagesCard(vm: MainViewModel, state: UiState, range: Range, system: SystemActions) {
    val c = Solar.colors
    val scope = rememberCoroutineScope()
    val from = if (range == Range.DAY) OutageLog.startOfDay(state.now) else state.now - range.days * OutageLog.DAY_MS
    val records = state.outages.filter { (it.endedAt ?: state.now) >= from }
    val dayFormat = remember { SimpleDateFormat("EEEE d MMM", Locale.getDefault()) }
    val time = remember { SimpleDateFormat("HH:mm", Locale.getDefault()) }

    SCard(padding = PaddingValues(top = 16.dp, bottom = 12.dp)) {
        CardHeader("Cuts", trailing = "${records.size}", modifier = Modifier.padding(horizontal = 16.dp))
        if (records.isEmpty()) {
            Text("No cuts in this period.", style = SolarType.body, color = c.ink2, modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp))
        } else {
            var lastDay = ""
            records.take(60).forEach { record ->
                val day = dayFormat.format(Date(record.startedAt))
                if (day != lastDay) {
                    lastDay = day
                    Text(day, style = SolarType.caption, color = c.ink3, modifier = Modifier.padding(start = 16.dp, top = 14.dp, bottom = 2.dp))
                }
                OutageRow(record, state.now, time)
            }
        }
        Spacer(Modifier.height(8.dp))
        SecondaryButton(
            "Export all as CSV",
            onClick = { scope.launch { vm.exportIntent()?.let(system::share) } },
            icon = Icons.Export,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)
        )
    }
}

@Composable
private fun OutageRow(record: OutageRecord, now: Long, time: SimpleDateFormat) {
    val c = Solar.colors
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(
                "${time.format(Date(record.startedAt))} – ${record.endedAt?.let { time.format(Date(it)) } ?: "now"}",
                style = SolarType.body.copy(fontFeatureSettings = "tnum"),
                color = if (record.ongoing) c.down else c.ink
            )
            record.batteryUsed?.let { Text("Used ${"%.0f".format(it)}% of the battery", style = SolarType.caption, color = c.ink3) }
        }
        Text(OutageLog.describeDuration(record.durationMs(now)), style = SolarType.figureS, color = if (record.ongoing) c.down else c.ink2)
    }
}

@Composable
private fun ActivityCard(state: UiState) {
    val c = Solar.colors
    if (state.events.isEmpty()) return
    val format = remember { SimpleDateFormat("d MMM, HH:mm", Locale.getDefault()) }

    SCard(padding = PaddingValues(vertical = 12.dp)) {
        CardHeader("Activity", modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
        state.events.take(25).forEachIndexed { index, event ->
            if (index > 0) Divider(Modifier.padding(start = 52.dp))
            val (icon, tone) = when (event.kind) {
                EventKind.OUTAGE -> Icons.Pylon to c.down
                EventKind.RESTORED -> Icons.Live to c.ok
                EventKind.BATTERY -> Icons.Battery to c.batteryMid
                EventKind.HEALTH -> Icons.Thermometer to c.warn
                EventKind.INFO -> Icons.Info to c.ink3
            }
            Row(Modifier.padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(icon, null, tint = tone, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(16.dp))
                Column(Modifier.weight(1f)) {
                    Text(event.text, style = SolarType.body, color = c.ink)
                    Text(format.format(Date(event.at)), style = SolarType.caption, color = c.ink3)
                }
            }
        }
    }
}
