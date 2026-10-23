package io.github.hafeee44lab.solargrid.ui.settings

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.hafeee44lab.solargrid.BuildConfig
import io.github.hafeee44lab.solargrid.data.AppSettings
import io.github.hafeee44lab.solargrid.data.ThemeMode
import io.github.hafeee44lab.solargrid.domain.AlertSettings
import io.github.hafeee44lab.solargrid.ui.MainViewModel
import io.github.hafeee44lab.solargrid.ui.SystemActions
import io.github.hafeee44lab.solargrid.ui.UiState
import io.github.hafeee44lab.solargrid.ui.components.Icons
import io.github.hafeee44lab.solargrid.ui.components.ListGroup
import io.github.hafeee44lab.solargrid.ui.components.SecondaryButton
import io.github.hafeee44lab.solargrid.ui.components.Segmented
import io.github.hafeee44lab.solargrid.ui.components.SettingRow
import io.github.hafeee44lab.solargrid.ui.components.SliderRow
import io.github.hafeee44lab.solargrid.ui.components.ToggleRow
import io.github.hafeee44lab.solargrid.ui.components.minuteLabel
import io.github.hafeee44lab.solargrid.ui.onboarding.AccountForm
import io.github.hafeee44lab.solargrid.ui.screenPadding
import io.github.hafeee44lab.solargrid.ui.theme.Solar
import io.github.hafeee44lab.solargrid.ui.theme.SolarType
import kotlinx.coroutines.launch

@Composable
fun SettingsScreen(vm: MainViewModel, state: UiState, system: SystemActions, onOpenDiagnostics: () -> Unit) {
    val c = Solar.colors
    val settings = state.settings
    val alerts = settings.alerts
    fun alerts(transform: AlertSettings.() -> AlertSettings) = vm.updateSettings { it.copy(alerts = it.alerts.transform()) }
    var editingAccount by remember { mutableStateOf(false) }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .windowInsetsPadding(WindowInsets.statusBars)
            .imePadding()
            .padding(screenPadding()),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text("Settings", style = SolarType.title, color = c.ink, modifier = Modifier.padding(top = 14.dp).semantics { heading() })

        ListGroup(null) {
            ToggleRow(
                "Watch for outages",
                settings.monitoring,
                vm::setMonitoring,
                subtitle = if (settings.monitoring) "Checks after every inverter upload, every 90\u00A0s during a cut" else "Paused. No alerts will fire.",
                icon = Icons.Live,
                iconTint = if (settings.monitoring) c.ok else c.ink3
            )
        }

        Reliability(vm, system)

        ListGroup("Outage alarm") {
            ToggleRow("Ring and show over the lock screen", alerts.alarmOnOutage, { v -> alerts { copy(alarmOnOutage = v) } }, subtitle = "Off: a normal notification instead", icon = Icons.Bell)
            ToggleRow("Ring in silent mode", alerts.ringInSilentMode, { v -> alerts { copy(ringInSilentMode = v) } }, subtitle = "Uses the alarm volume, like an alarm clock", icon = Icons.Bell, enabled = alerts.alarmOnOutage)
            val ringIndex = AlertSettings.RING_CHOICES.indexOf(alerts.ringSeconds).takeIf { it >= 0 } ?: 2
            SliderRow(
                "Ring for", ringIndex, 0..AlertSettings.RING_CHOICES.lastIndex, 1,
                { AlertSettings.describeRing(AlertSettings.RING_CHOICES[it]) },
                { v -> alerts { copy(ringSeconds = AlertSettings.RING_CHOICES[v]) } },
                subtitle = "Then it goes quiet. It always stops when the mains return.",
                enabled = alerts.alarmOnOutage
            )
            ToggleRow("Tell me when the mains are back", alerts.notifyRestored, { v -> alerts { copy(notifyRestored = v) } }, subtitle = "With how long they were off", icon = Icons.Live)
            SettingRow("Test the alarm", subtitle = "Rings once so you know what it sounds like", icon = Icons.Bell, iconTint = c.down, onClick = vm::testAlarm)
        }

        ListGroup("Battery") {
            SliderRow("Low battery warning", alerts.lowBatteryPercent, 10..60, 5, { "$it%" }, { v -> alerts { copy(lowBatteryPercent = v, criticalBatteryPercent = minOf(criticalBatteryPercent, v - 5)) } }, subtitle = "During a cut")
            SliderRow("Critical battery", alerts.criticalBatteryPercent, 5..40, 5, { "$it%" }, { v -> alerts { copy(criticalBatteryPercent = minOf(v, lowBatteryPercent - 5)) } }, accent = c.down)
            ToggleRow("Critical battery rings", alerts.criticalAlarm, { v -> alerts { copy(criticalAlarm = v) } }, subtitle = "Even in quiet hours", icon = Icons.Battery)
            SliderRow("Inverter reserve", alerts.batteryReservePercent, 0..50, 5, { "$it%" }, { v -> alerts { copy(batteryReservePercent = v) } }, subtitle = "Where your inverter stops discharging. Runtime counts down to this.")
            SliderRow("Heavy load on battery", alerts.overloadWatts, 0..6000, 500, { if (it == 0) "Off" else "%.1f kW".format(it / 1000.0) }, { v -> alerts { copy(overloadWatts = v) } }, subtitle = "Warn when the house draws this much during a cut")
            ToggleRow("Battery full", alerts.notifyBatteryFull, { v -> alerts { copy(notifyBatteryFull = v) } }, subtitle = "A nudge to run heavy appliances on spare solar", icon = Icons.Sun)
        }

        ListGroup("Forecasts and health") {
            ToggleRow("Warn before a likely cut", alerts.headsUp, { v -> alerts { copy(headsUp = v) } }, subtitle = "Only once your history shows a real pattern", icon = Icons.Clock)
            AnimatedVisibility(alerts.headsUp, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
                SliderRow("How early", alerts.headsUpLeadMinutes, 5..60, 5, { "$it min" }, { v -> alerts { copy(headsUpLeadMinutes = v) } })
            }
            ToggleRow("System health", alerts.notifyHealth, { v -> alerts { copy(notifyHealth = v) } }, subtitle = "Inverter gone quiet, faults, running hot", icon = Icons.Thermometer)
            AnimatedVisibility(alerts.notifyHealth, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
                SliderRow("Hot inverter", alerts.inverterHotC, 50..85, 5, { "$it°C" }, { v -> alerts { copy(inverterHotC = v) } })
            }
            ToggleRow("Daily summary", alerts.dailySummary, { v -> alerts { copy(dailySummary = v) } }, subtitle = "Cuts, solar and self-sufficiency, once a day", icon = Icons.Calendar)
            AnimatedVisibility(alerts.dailySummary, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
                SliderRow("Send at", alerts.summaryMinuteOfDay, 17 * 60..23 * 60 + 30, 30, ::minuteLabel, { v -> alerts { copy(summaryMinuteOfDay = v) } })
            }
        }

        ListGroup("Quiet hours", footer = "During quiet hours a cut still shows a notification, silently. A critical battery still rings.") {
            ToggleRow("Don't ring at night", alerts.quietHours, { v -> alerts { copy(quietHours = v) } }, icon = Icons.Moon)
            AnimatedVisibility(alerts.quietHours, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
                Column {
                    TimeRow("From", alerts.quietStartMinute) { v -> alerts { copy(quietStartMinute = v) } }
                    TimeRow("Until", alerts.quietEndMinute) { v -> alerts { copy(quietEndMinute = v) } }
                }
            }
        }

        ListGroup("Data source") {
            SettingRow(
                if (settings.demoMode) "Demo home" else state.account?.email ?: "Not connected",
                subtitle = if (settings.demoMode) "Simulated inverter and history" else state.account?.let { "Inverter ${it.deviceSn}" } ?: "Connect your Solarman account",
                icon = Icons.Account,
                onClick = { editingAccount = !editingAccount }
            ) { Icon(Icons.ChevronDown, null, tint = c.ink3, modifier = Modifier.size(20.dp)) }
            AnimatedVisibility(editingAccount, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                    AccountForm(vm = vm, initial = state.account, onDone = { editingAccount = false })
                    if (state.account != null) {
                        TextButton(onClick = { vm.signOut(); editingAccount = false }) { Text("Sign out", color = c.down, style = SolarType.bodyStrong) }
                    }
                }
            }
            if (!settings.demoMode && state.account != null) {
                SettingRow(
                    "Solarman calls: ${state.apiCallsToday} today" + (state.apiCallsYesterday?.let { " · $it yesterday" } ?: ""),
                    subtitle = if (state.quotaExhausted) {
                        "Solarman says your App ID's allowance is used up. Checks retry hourly; ask Solarman support to raise it."
                    } else {
                        "About one per inverter upload, a few more during a cut. Solarman limits calls per App ID."
                    },
                    icon = Icons.Wave,
                    iconTint = if (state.quotaExhausted) c.down else c.ink3
                )
            }
            ToggleRow("Demo mode", settings.demoMode, vm::setDemo, subtitle = "A simulated home to explore. Your real history is kept separately.", icon = Icons.Flask)
        }

        RelayGroup(vm, settings)

        ListGroup("Appearance") {
            Segmented(
                options = listOf(ThemeMode.SYSTEM to "System", ThemeMode.LIGHT to "Light", ThemeMode.DARK to "Dark"),
                selected = settings.theme,
                onSelect = { mode -> vm.updateSettings { it.copy(theme = mode) } },
                modifier = Modifier.padding(16.dp)
            )
        }

        DataGroup(vm, state, system, onOpenDiagnostics)

        ListGroup("About", footer = "Not affiliated with Solarman or any inverter maker. Readings come from the Solarman OpenAPI.") {
            SettingRow("Roshni ${BuildConfig.VERSION_NAME}", subtitle = "Free and open source, MIT licence", icon = Icons.Info)
            SettingRow("Source code", subtitle = "Issues, ideas and pull requests welcome", icon = Icons.Code, onClick = { system.openUrl(BuildConfig.SOURCE_URL) }) {
                Icon(Icons.Chevron, null, tint = c.ink3, modifier = Modifier.size(20.dp))
            }
        }
    }
}

@Composable
private fun Reliability(vm: MainViewModel, system: SystemActions) {
    val c = Solar.colors
    val p by vm.permissions.collectAsStateWithLifecycle()
    ListGroup(
        "Reliability",
        footer = "Xiaomi, Oppo, Vivo, Huawei and Samsung phones may also need Autostart or Never sleeping turned on. dontkillmyapp.com has steps for each brand."
    ) {
        CheckRow("Notifications", "Needed for every alert", p.notifications, system::requestNotifications)
        CheckRow("Full-screen alarm", "Lets the alarm show over a locked screen", p.fullScreen, system::openFullScreenSettings)
        CheckRow("Run in background", "Stops Android pausing checks while the phone sleeps", p.unrestricted, system::requestUnrestricted)
        CheckRow("Precise timing", "Optional. Checks land right after each upload.", p.exactAlarms, system::openExactAlarmSettings, optional = true)
        SettingRow("Brand-specific steps", icon = Icons.Info, onClick = { system.openUrl("https://dontkillmyapp.com") }) {
            Icon(Icons.Chevron, null, tint = c.ink3, modifier = Modifier.size(20.dp))
        }
    }
}

@Composable
private fun CheckRow(title: String, subtitle: String, ok: Boolean, onFix: () -> Unit, optional: Boolean = false) {
    val c = Solar.colors
    val tone = when {
        ok -> c.ok
        optional -> c.warn
        else -> c.down
    }
    SettingRow(title, subtitle = subtitle, icon = if (ok) Icons.Check else Icons.Warning, iconTint = tone, onClick = if (ok) null else onFix) {
        if (!ok) TextButton(onClick = onFix) { Text("Fix", style = SolarType.bodyStrong, color = c.brand) }
    }
}

@Composable
private fun TimeRow(label: String, minute: Int, onChange: (Int) -> Unit) {
    val c = Solar.colors
    Row(Modifier.fillMaxWidth().padding(start = 54.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = SolarType.body, color = c.ink2, modifier = Modifier.weight(1f))
        IconButton(onClick = { onChange((minute - 30 + 1440) % 1440) }) { Icon(Icons.Back, contentDescription = "$label earlier", tint = c.ink2) }
        Text(minuteLabel(minute), style = SolarType.figureS, color = c.ink, modifier = Modifier.width(56.dp), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        IconButton(onClick = { onChange((minute + 30) % 1440) }) { Icon(Icons.Chevron, contentDescription = "$label later", tint = c.ink2) }
    }
}

@Composable
private fun RelayGroup(vm: MainViewModel, settings: AppSettings) {
    val c = Solar.colors
    val relay = settings.relay
    val scope = rememberCoroutineScope()
    var url by remember(relay.url) { mutableStateOf(relay.url) }
    var secret by remember(relay.secret) { mutableStateOf(relay.secret) }
    var result by remember { mutableStateOf<String?>(null) }

    ListGroup("WhatsApp relay") {
        ToggleRow(
            "Forward alerts to WhatsApp",
            relay.enabled,
            { v -> vm.updateSettings { it.copy(relay = it.relay.copy(enabled = v)) } },
            subtitle = "Through your own bridge (tools/whatsapp-bridge), so the family hears too",
            icon = Icons.Message
        )
        AnimatedVisibility(relay.enabled, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                SolarField("Bridge URL", url, { url = it }, placeholder = "http://100.64.0.2:8787", keyboard = KeyboardType.Uri)
                SolarField("Shared secret", secret, { secret = it }, secret = true, placeholder = "At least 16 characters")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SecondaryButton("Save", onClick = {
                        vm.updateSettings { it.copy(relay = it.relay.copy(url = url, secret = secret)) }
                        result = "Saved"
                    }, modifier = Modifier.weight(1f))
                    SecondaryButton("Send test", onClick = {
                        vm.updateSettings { it.copy(relay = it.relay.copy(url = url, secret = secret)) }
                        scope.launch {
                            result = "Sending…"
                            result = if (vm.testRelay()) "Delivered" else "Couldn't reach the bridge"
                        }
                    }, modifier = Modifier.weight(1f))
                }
                result?.let { Text(it, style = SolarType.caption, color = c.ink2) }
            }
        }
    }
}

@Composable
fun SolarField(
    label: String,
    value: String,
    onChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String? = null,
    secret: Boolean = false,
    keyboard: KeyboardType = KeyboardType.Text
) {
    val c = Solar.colors
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        placeholder = placeholder?.let { { Text(it, color = c.ink3) } },
        singleLine = true,
        visualTransformation = if (secret) PasswordVisualTransformation() else VisualTransformation.None,
        keyboardOptions = KeyboardOptions(keyboardType = if (secret) KeyboardType.Password else keyboard),
        shape = RoundedCornerShape(12.dp),
        colors = OutlinedTextFieldDefaults.colors(unfocusedBorderColor = c.outlineStrong),
        modifier = modifier.fillMaxWidth()
    )
}

@Composable
private fun DataGroup(vm: MainViewModel, state: UiState, system: SystemActions, onOpenDiagnostics: () -> Unit) {
    val c = Solar.colors
    val importing by vm.importProgress.collectAsState()
    val scope = rememberCoroutineScope()
    var confirmClear by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }

    ListGroup("Data", footer = message) {
        SettingRow("Export cuts", subtitle = "CSV, for a spreadsheet or a complaint to the utility", icon = Icons.Export, onClick = {
            scope.launch {
                val intent = vm.exportIntent()
                if (intent == null) message = "Nothing to export yet" else system.share(intent)
            }
        })
        if (!state.settings.demoMode && state.account != null) {
            SettingRow(
                "Import from Solarman",
                subtitle = importing?.let { (done, total) -> "Fetching day ${done + 1} of $total…" }
                    ?: state.importNote
                    ?: "The last two weeks of readings and cuts, so History isn't empty",
                icon = Icons.Calendar,
                onClick = if (importing == null) ({ vm.importHistory { message = it } }) else null
            )
        }
        SettingRow("Inverter data", subtitle = "Every value from the last reading", icon = Icons.Code, onClick = onOpenDiagnostics) {
            Icon(Icons.Chevron, null, tint = c.ink3, modifier = Modifier.size(20.dp))
        }
        SettingRow("Clear history", subtitle = "Cuts, charts and activity for the current source", icon = Icons.Trash, iconTint = c.down, onClick = { confirmClear = true })
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("Clear history?") },
            text = { Text("Cuts, charts and the learned schedule will be deleted. This can't be undone.") },
            confirmButton = { TextButton(onClick = { vm.clearHistory(); confirmClear = false }) { Text("Clear", color = c.down) } },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Cancel") } }
        )
    }
}
