package io.github.hafeee44lab.solargrid.ui.settings

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.hafeee44lab.solargrid.BuildConfig
import io.github.hafeee44lab.solargrid.data.AppSettings
import io.github.hafeee44lab.solargrid.data.ThemeMode
import io.github.hafeee44lab.solargrid.domain.AlertSettings
import io.github.hafeee44lab.solargrid.ui.MainViewModel
import io.github.hafeee44lab.solargrid.ui.SystemActions
import io.github.hafeee44lab.solargrid.ui.UiState
import io.github.hafeee44lab.solargrid.ui.components.GhostButton
import io.github.hafeee44lab.solargrid.ui.components.Icons
import io.github.hafeee44lab.solargrid.ui.components.Panel
import io.github.hafeee44lab.solargrid.ui.components.SectionLabel
import io.github.hafeee44lab.solargrid.ui.components.Segmented
import io.github.hafeee44lab.solargrid.ui.components.SettingRow
import io.github.hafeee44lab.solargrid.ui.components.SliderRow
import io.github.hafeee44lab.solargrid.ui.components.SolarSwitch
import io.github.hafeee44lab.solargrid.ui.components.ToggleRow
import io.github.hafeee44lab.solargrid.ui.components.minuteLabel
import io.github.hafeee44lab.solargrid.ui.onboarding.AccountForm
import io.github.hafeee44lab.solargrid.ui.screenPadding
import io.github.hafeee44lab.solargrid.ui.theme.Solar
import io.github.hafeee44lab.solargrid.ui.theme.SolarType
import kotlinx.coroutines.launch

@Composable
fun SettingsScreen(vm: MainViewModel, state: UiState, system: SystemActions, onOpenDiagnostics: () -> Unit) {
    val colors = Solar.colors
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
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Text("Settings", style = SolarType.hero, color = colors.ink, modifier = Modifier.padding(top = 16.dp))

        // ---------------- monitoring ----------------
        Panel(glow = if (settings.monitoring) colors.ok else colors.warn) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Watch for outages", style = SolarType.title, color = colors.ink)
                    Text(
                        if (settings.monitoring) "Checking around every upload, every 90 s during an outage." else "Paused — no alerts will fire.",
                        style = SolarType.caption,
                        color = colors.ink3,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
                SolarSwitch(settings.monitoring, vm::setMonitoring)
            }
        }

        // ---------------- reliability ----------------
        Reliability(vm, system)

        // ---------------- alarm ----------------
        Group("Outage alarm") {
            ToggleRow(
                "Ring and take over the screen",
                alerts.alarmOnOutage,
                { v -> alerts { copy(alarmOnOutage = v) } },
                subtitle = "Off: a normal notification instead",
                icon = Icons.Bell,
                iconTint = colors.down
            )
            ToggleRow(
                "Ring in silent mode",
                alerts.ringInSilentMode,
                { v -> alerts { copy(ringInSilentMode = v) } },
                subtitle = "Uses the alarm volume, like an alarm clock",
                icon = Icons.Bell,
                enabled = alerts.alarmOnOutage
            )
            ToggleRow(
                "Grid restored",
                alerts.notifyRestored,
                { v -> alerts { copy(notifyRestored = v) } },
                subtitle = "With how long it was out",
                icon = Icons.Live,
                iconTint = colors.ok
            )
            Spacer(Modifier.height(6.dp))
            GhostButton("Test the alarm", onClick = vm::testAlarm, icon = Icons.Bell, color = colors.down, modifier = Modifier.fillMaxWidth())
        }

        // ---------------- battery ----------------
        Group("Battery") {
            SliderRow(
                "Low battery warning",
                alerts.lowBatteryPercent,
                10..60,
                5,
                { "$it%" },
                { v -> alerts { copy(lowBatteryPercent = v, criticalBatteryPercent = minOf(criticalBatteryPercent, v - 5)) } },
                subtitle = "During an outage"
            )
            SliderRow(
                "Critical battery",
                alerts.criticalBatteryPercent,
                5..40,
                5,
                { "$it%" },
                { v -> alerts { copy(criticalBatteryPercent = minOf(v, lowBatteryPercent - 5)) } },
                accent = colors.down
            )
            ToggleRow(
                "Critical battery rings",
                alerts.criticalAlarm,
                { v -> alerts { copy(criticalAlarm = v) } },
                subtitle = "Even in quiet hours",
                icon = Icons.Battery,
                iconTint = colors.down
            )
            SliderRow(
                "Inverter reserve",
                alerts.batteryReservePercent,
                0..50,
                5,
                { "$it%" },
                { v -> alerts { copy(batteryReservePercent = v) } },
                subtitle = "The level your inverter stops discharging at — runtime counts down to this"
            )
            SliderRow(
                "Heavy load on battery",
                alerts.overloadWatts,
                0..6000,
                500,
                { if (it == 0) "Off" else "%.1f kW".format(it / 1000.0) },
                { v -> alerts { copy(overloadWatts = v) } },
                subtitle = "Warn when the house draws this much during an outage",
                accent = colors.warn
            )
            ToggleRow(
                "Battery full",
                alerts.notifyBatteryFull,
                { v -> alerts { copy(notifyBatteryFull = v) } },
                subtitle = "A nudge to run heavy appliances on spare solar",
                icon = Icons.Sun,
                iconTint = colors.solar
            )
        }

        // ---------------- forecasts & health ----------------
        Group("Forecasts and health") {
            ToggleRow(
                "Heads-up before likely outages",
                alerts.headsUp,
                { v -> alerts { copy(headsUp = v) } },
                subtitle = "Learned from your history — only once there's a real pattern",
                icon = Icons.Sparkle,
                iconTint = colors.battery
            )
            AnimatedVisibility(alerts.headsUp, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
                SliderRow(
                    "How early",
                    alerts.headsUpLeadMinutes,
                    5..60,
                    5,
                    { "$it min" },
                    { v -> alerts { copy(headsUpLeadMinutes = v) } }
                )
            }
            ToggleRow(
                "System health",
                alerts.notifyHealth,
                { v -> alerts { copy(notifyHealth = v) } },
                subtitle = "Inverter gone quiet, faults, running hot",
                icon = Icons.Thermometer,
                iconTint = colors.warn
            )
            AnimatedVisibility(alerts.notifyHealth, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
                SliderRow(
                    "Hot inverter",
                    alerts.inverterHotC,
                    50..85,
                    5,
                    { "$it°C" },
                    { v -> alerts { copy(inverterHotC = v) } },
                    accent = colors.warn
                )
            }
            ToggleRow(
                "Daily summary",
                alerts.dailySummary,
                { v -> alerts { copy(dailySummary = v) } },
                subtitle = "Outages, solar and self-sufficiency, once a day",
                icon = Icons.Calendar
            )
            AnimatedVisibility(alerts.dailySummary, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
                SliderRow(
                    "Send at",
                    alerts.summaryMinuteOfDay,
                    17 * 60..23 * 60 + 30,
                    30,
                    ::minuteLabel,
                    { v -> alerts { copy(summaryMinuteOfDay = v) } }
                )
            }
        }

        // ---------------- quiet hours ----------------
        Group("Quiet hours") {
            ToggleRow(
                "Don't ring at night",
                alerts.quietHours,
                { v -> alerts { copy(quietHours = v) } },
                subtitle = "Outages still notify, silently. Critical battery still rings.",
                icon = Icons.Moon,
                iconTint = colors.battery
            )
            AnimatedVisibility(alerts.quietHours, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
                Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    TimeStepper("From", alerts.quietStartMinute, Modifier.weight(1f)) { v -> alerts { copy(quietStartMinute = v) } }
                    TimeStepper("Until", alerts.quietEndMinute, Modifier.weight(1f)) { v -> alerts { copy(quietEndMinute = v) } }
                }
            }
        }

        // ---------------- source ----------------
        Group("Data source") {
            SettingRow(
                title = if (settings.demoMode) "Demo system" else state.account?.email ?: "Not connected",
                subtitle = if (settings.demoMode) "Simulated inverter and history" else state.account?.let { "Inverter ${it.deviceSn}" } ?: "Connect your Solarman account",
                icon = Icons.Account,
                iconTint = colors.grid,
                onClick = { editingAccount = !editingAccount }
            ) {
                Icon(Icons.ChevronDown, null, tint = colors.ink3, modifier = Modifier.size(18.dp))
            }
            AnimatedVisibility(editingAccount, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
                Column(Modifier.padding(top = 8.dp)) {
                    AccountForm(vm = vm, initial = state.account, onDone = { editingAccount = false })
                    if (state.account != null) {
                        Spacer(Modifier.height(8.dp))
                        TextButton(onClick = { vm.signOut(); editingAccount = false }) {
                            Text("Sign out", color = colors.down, style = SolarType.bodyStrong)
                        }
                    }
                }
            }
            ToggleRow(
                "Demo mode",
                settings.demoMode,
                vm::setDemo,
                subtitle = "Explore with a simulated system. Your real history is kept separately.",
                icon = Icons.Flask,
                iconTint = colors.battery
            )
        }

        // ---------------- relay ----------------
        RelayGroup(vm, settings)

        // ---------------- appearance ----------------
        Group("Appearance") {
            Segmented(
                options = listOf(ThemeMode.SYSTEM to "Auto", ThemeMode.LIGHT to "Light", ThemeMode.DARK to "Dark"),
                selected = settings.theme,
                onSelect = { mode -> vm.updateSettings { it.copy(theme = mode) } },
                modifier = Modifier.padding(vertical = 6.dp)
            )
        }

        // ---------------- data ----------------
        DataGroup(vm, system, onOpenDiagnostics)

        // ---------------- about ----------------
        Group("About") {
            SettingRow("Solar Grid ${BuildConfig.VERSION_NAME}", subtitle = "Free and open source · MIT licence", icon = Icons.Info)
            SettingRow(
                "Source code",
                subtitle = "Issues, ideas and pull requests welcome",
                icon = Icons.Code,
                onClick = { system.openUrl(BuildConfig.SOURCE_URL) }
            ) { Icon(Icons.Chevron, null, tint = colors.ink3, modifier = Modifier.size(18.dp)) }
            Text(
                "Not affiliated with Solarman or any inverter maker. Readings come from the Solarman OpenAPI.",
                style = SolarType.caption,
                color = colors.ink3,
                modifier = Modifier.padding(top = 8.dp)
            )
        }
    }
}

@Composable
private fun Group(title: String, content: @Composable ColumnScope.() -> Unit) {
    Panel {
        SectionLabel(title)
        Spacer(Modifier.height(6.dp))
        content()
    }
}

@Composable
private fun Reliability(vm: MainViewModel, system: SystemActions) {
    val colors = Solar.colors
    val p by vm.permissions.collectAsStateWithLifecycle()

    Panel(glow = if (p.blocking > 0) colors.down else null) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            SectionLabel("Reliability", modifier = Modifier.weight(1f))
            Text(
                if (p.blocking == 0) "All set" else "${p.blocking} to fix",
                style = SolarType.caption,
                color = if (p.blocking == 0) colors.ok else colors.down
            )
        }
        Text(
            "Android is built to stop apps working in the background. These make sure an outage still reaches you.",
            style = SolarType.caption,
            color = colors.ink3,
            modifier = Modifier.padding(top = 4.dp, bottom = 6.dp)
        )
        CheckRow("Notifications", "Required for every alert", p.notifications, system::requestNotifications)
        CheckRow("Full-screen alarm", "Lets the alarm take over a locked screen", p.fullScreen, system::openFullScreenSettings)
        CheckRow("Run in background", "Stops Android pausing checks while the phone sleeps", p.unrestricted, system::requestUnrestricted)
        CheckRow("Precise timing", "Optional — checks land exactly when the inverter uploads", p.exactAlarms, system::openExactAlarmSettings, optional = true)
        Text(
            "Xiaomi, Oppo, Vivo, Huawei and Samsung phones may also need \"Autostart\" or \"Never sleeping\" turned on.",
            style = SolarType.caption,
            color = colors.ink3,
            modifier = Modifier
                .padding(top = 8.dp)
                .clickable { system.openUrl("https://dontkillmyapp.com") }
        )
    }
}

@Composable
private fun CheckRow(title: String, subtitle: String, ok: Boolean, onFix: () -> Unit, optional: Boolean = false) {
    val colors = Solar.colors
    val tone = when {
        ok -> colors.ok
        optional -> colors.warn
        else -> colors.down
    }
    SettingRow(
        title = title,
        subtitle = subtitle,
        icon = if (ok) Icons.Check else Icons.Warning,
        iconTint = tone,
        onClick = if (ok) null else onFix
    ) {
        if (!ok) {
            Box(
                Modifier
                    .clip(CircleShape)
                    .background(tone.copy(alpha = 0.14f))
                    .clickable(onClick = onFix)
                    .padding(horizontal = 14.dp, vertical = 8.dp)
            ) {
                Text("Fix", style = SolarType.bodyStrong, color = tone)
            }
        }
    }
}

@Composable
private fun TimeStepper(label: String, minute: Int, modifier: Modifier = Modifier, onChange: (Int) -> Unit) {
    val colors = Solar.colors
    Column(
        modifier
            .clip(androidx.compose.foundation.shape.RoundedCornerShape(18.dp))
            .background(colors.track)
            .padding(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(label.uppercase(), style = SolarType.label, color = colors.ink3)
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 6.dp)) {
            StepButton("−", "Earlier") { onChange((minute - 30 + 1440) % 1440) }
            Text(minuteLabel(minute), style = SolarType.number.copy(fontSize = SolarType.title.fontSize), color = colors.ink, modifier = Modifier.padding(horizontal = 10.dp))
            StepButton("+", "Later") { onChange((minute + 30) % 1440) }
        }
    }
}

@Composable
private fun StepButton(symbol: String, description: String, onClick: () -> Unit) {
    val colors = Solar.colors
    Box(
        Modifier
            .size(48.dp)
            .clip(CircleShape)
            .background(colors.surface)
            .clickable(onClick = onClick)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center
    ) {
        Text(symbol, style = SolarType.title, color = colors.ink)
    }
}

@Composable
private fun RelayGroup(vm: MainViewModel, settings: AppSettings) {
    val colors = Solar.colors
    val relay = settings.relay
    val scope = rememberCoroutineScope()
    var url by remember(relay.url) { mutableStateOf(relay.url) }
    var secret by remember(relay.secret) { mutableStateOf(relay.secret) }
    var result by remember { mutableStateOf<String?>(null) }

    Group("WhatsApp relay") {
        ToggleRow(
            "Forward alerts to WhatsApp",
            relay.enabled,
            { v -> vm.updateSettings { it.copy(relay = it.relay.copy(enabled = v)) } },
            subtitle = "Through your own bridge (tools/whatsapp-bridge) — so the family hears too",
            icon = Icons.Message,
            iconTint = colors.ok
        )
        AnimatedVisibility(relay.enabled, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(top = 6.dp)) {
                SolarField("Bridge URL", url, { url = it }, placeholder = "http://100.64.0.2:8787", keyboard = KeyboardType.Uri)
                SolarField("Shared secret", secret, { secret = it }, secret = true, placeholder = "at least 16 characters")
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    GhostButton("Save", onClick = {
                        vm.updateSettings { it.copy(relay = it.relay.copy(url = url, secret = secret)) }
                        result = "Saved"
                    }, icon = Icons.Check)
                    GhostButton("Send test", onClick = {
                        vm.updateSettings { it.copy(relay = it.relay.copy(url = url, secret = secret)) }
                        scope.launch {
                            result = "Sending…"
                            result = if (vm.testRelay()) "Delivered ✓" else "Couldn't reach the bridge"
                        }
                    }, icon = Icons.Message)
                }
                result?.let { Text(it, style = SolarType.caption, color = colors.ink2) }
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
    val colors = Solar.colors
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        placeholder = placeholder?.let { { Text(it, color = colors.ink3) } },
        singleLine = true,
        visualTransformation = if (secret) PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
        keyboardOptions = KeyboardOptions(keyboardType = if (secret) KeyboardType.Password else keyboard),
        shape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp),
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = colors.ink2,
            unfocusedBorderColor = colors.hairlineStrong,
            focusedLabelColor = colors.ink2,
            unfocusedLabelColor = colors.ink3,
            cursorColor = colors.ok,
            focusedTextColor = colors.ink,
            unfocusedTextColor = colors.ink
        ),
        modifier = modifier.fillMaxWidth()
    )
}

@Composable
private fun DataGroup(vm: MainViewModel, system: SystemActions, onOpenDiagnostics: () -> Unit) {
    val colors = Solar.colors
    val scope = rememberCoroutineScope()
    var confirmClear by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }

    Group("Data") {
        SettingRow("Export outages", subtitle = "CSV, for a spreadsheet or a complaint to the utility", icon = Icons.Export, onClick = {
            scope.launch {
                val intent = vm.exportIntent()
                if (intent == null) message = "Nothing to export yet" else system.share(intent)
            }
        })
        SettingRow("Inverter data", subtitle = "Every value from the last reading, raw", icon = Icons.Code, onClick = onOpenDiagnostics) {
            Icon(Icons.Chevron, null, tint = colors.ink3, modifier = Modifier.size(18.dp))
        }
        SettingRow("Clear history", subtitle = "Outages, charts and activity for the current source", icon = Icons.Trash, iconTint = colors.down, onClick = { confirmClear = true })
        message?.let { Text(it, style = SolarType.caption, color = colors.ink3) }
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("Clear history?") },
            text = { Text("Outage records, charts and the learned schedule will be deleted. This can't be undone.") },
            confirmButton = {
                TextButton(onClick = {
                    vm.clearHistory()
                    confirmClear = false
                }) { Text("Clear", color = colors.down) }
            },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Cancel", color = colors.ink2) } },
            containerColor = colors.surfaceHigh,
            titleContentColor = colors.ink,
            textContentColor = colors.ink2
        )
    }
}
