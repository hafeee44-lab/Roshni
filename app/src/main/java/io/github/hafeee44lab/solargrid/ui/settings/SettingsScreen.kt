package io.github.hafeee44lab.solargrid.ui.settings

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
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
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.hafeee44lab.solargrid.BuildConfig
import io.github.hafeee44lab.solargrid.R
import io.github.hafeee44lab.solargrid.data.AppSettings
import io.github.hafeee44lab.solargrid.data.DataSource
import io.github.hafeee44lab.solargrid.data.cloud.CloudBrand
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
import io.github.hafeee44lab.solargrid.ui.components.phrases
import io.github.hafeee44lab.solargrid.ui.onboarding.AccountForm
import io.github.hafeee44lab.solargrid.ui.onboarding.BrandForm
import io.github.hafeee44lab.solargrid.ui.onboarding.CloudBrandPicker
import io.github.hafeee44lab.solargrid.ui.onboarding.LoggerForm
import io.github.hafeee44lab.solargrid.ui.onboarding.SourcePicker
import io.github.hafeee44lab.solargrid.ui.screenPadding
import io.github.hafeee44lab.solargrid.ui.theme.Solar
import io.github.hafeee44lab.solargrid.ui.theme.SolarType
import kotlinx.coroutines.launch
import java.util.Locale

@Composable
fun SettingsScreen(vm: MainViewModel, state: UiState, system: SystemActions, onOpenDiagnostics: () -> Unit) {
    val c = Solar.colors
    val settings = state.settings
    val alerts = settings.alerts
    fun alerts(transform: AlertSettings.() -> AlertSettings) = vm.updateSettings { it.copy(alerts = it.alerts.transform()) }
    var editingAccount by remember { mutableStateOf(false) }
    val p = phrases()
    val minUnit = stringResource(R.string.unit_min)
    val offLabel = stringResource(R.string.off)

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .windowInsetsPadding(WindowInsets.statusBars)
            .imePadding()
            .padding(screenPadding()),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(stringResource(R.string.settings), style = SolarType.title, color = c.ink, modifier = Modifier.padding(top = 14.dp).semantics { heading() })

        ListGroup(null) {
            ToggleRow(
                stringResource(R.string.watch_for_outages),
                settings.monitoring,
                vm::setMonitoring,
                subtitle = stringResource(if (settings.monitoring) R.string.watching_on else R.string.watching_paused),
                icon = Icons.Live,
                iconTint = if (settings.monitoring) c.ok else c.ink3
            )
        }

        Reliability(vm, system)

        ListGroup(stringResource(R.string.outage_alarm)) {
            ToggleRow(stringResource(R.string.ring_over_lock), alerts.alarmOnOutage, { v -> alerts { copy(alarmOnOutage = v) } }, subtitle = stringResource(R.string.ring_over_lock_sub), icon = Icons.Bell)
            ToggleRow(stringResource(R.string.ring_in_silent), alerts.ringInSilentMode, { v -> alerts { copy(ringInSilentMode = v) } }, subtitle = stringResource(R.string.ring_in_silent_sub), icon = Icons.Bell, enabled = alerts.alarmOnOutage)
            val ringIndex = AlertSettings.RING_CHOICES.indexOf(alerts.ringSeconds).takeIf { it >= 0 } ?: 2
            SliderRow(
                stringResource(R.string.ring_for), ringIndex, 0..AlertSettings.RING_CHOICES.lastIndex, 1,
                { AlertSettings.describeRing(AlertSettings.RING_CHOICES[it], p) },
                { v -> alerts { copy(ringSeconds = AlertSettings.RING_CHOICES[v]) } },
                subtitle = stringResource(R.string.ring_for_sub),
                enabled = alerts.alarmOnOutage
            )
            ToggleRow(stringResource(R.string.notify_restored), alerts.notifyRestored, { v -> alerts { copy(notifyRestored = v) } }, subtitle = stringResource(R.string.notify_restored_sub), icon = Icons.Live)
            SettingRow(stringResource(R.string.test_alarm), subtitle = stringResource(R.string.test_alarm_sub), icon = Icons.Bell, iconTint = c.down, onClick = vm::testAlarm)
        }

        ListGroup(stringResource(R.string.battery)) {
            SliderRow(stringResource(R.string.low_battery_warning), alerts.lowBatteryPercent, 10..60, 5, { "$it%" }, { v -> alerts { copy(lowBatteryPercent = v, criticalBatteryPercent = minOf(criticalBatteryPercent, v - 5)) } }, subtitle = stringResource(R.string.during_a_cut))
            SliderRow(stringResource(R.string.critical_battery), alerts.criticalBatteryPercent, 5..40, 5, { "$it%" }, { v -> alerts { copy(criticalBatteryPercent = minOf(v, lowBatteryPercent - 5)) } }, accent = c.down)
            ToggleRow(stringResource(R.string.critical_rings), alerts.criticalAlarm, { v -> alerts { copy(criticalAlarm = v) } }, subtitle = stringResource(R.string.critical_rings_sub), icon = Icons.Battery)
            SliderRow(stringResource(R.string.inverter_reserve), alerts.batteryReservePercent, 0..50, 5, { "$it%" }, { v -> alerts { copy(batteryReservePercent = v) } }, subtitle = stringResource(R.string.inverter_reserve_sub))
            SliderRow(stringResource(R.string.heavy_load), alerts.overloadWatts, 0..6000, 500, { if (it == 0) offLabel else "%.1f kW".format(Locale.US, it / 1000.0) }, { v -> alerts { copy(overloadWatts = v) } }, subtitle = stringResource(R.string.heavy_load_sub))
            ToggleRow(stringResource(R.string.battery_full), alerts.notifyBatteryFull, { v -> alerts { copy(notifyBatteryFull = v) } }, subtitle = stringResource(R.string.battery_full_sub), icon = Icons.Sun)
        }

        ListGroup(stringResource(R.string.forecasts_health)) {
            ToggleRow(stringResource(R.string.warn_before_cut), alerts.headsUp, { v -> alerts { copy(headsUp = v) } }, subtitle = stringResource(R.string.warn_before_cut_sub), icon = Icons.Clock)
            AnimatedVisibility(alerts.headsUp, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
                SliderRow(stringResource(R.string.how_early), alerts.headsUpLeadMinutes, 5..60, 5, { "$it $minUnit" }, { v -> alerts { copy(headsUpLeadMinutes = v) } })
            }
            ToggleRow(stringResource(R.string.system_health), alerts.notifyHealth, { v -> alerts { copy(notifyHealth = v) } }, subtitle = stringResource(R.string.system_health_sub), icon = Icons.Thermometer)
            AnimatedVisibility(alerts.notifyHealth, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
                SliderRow(stringResource(R.string.hot_inverter), alerts.inverterHotC, 50..85, 5, { "$it°C" }, { v -> alerts { copy(inverterHotC = v) } })
            }
            ToggleRow(stringResource(R.string.daily_summary), alerts.dailySummary, { v -> alerts { copy(dailySummary = v) } }, subtitle = stringResource(R.string.daily_summary_sub), icon = Icons.Calendar)
            AnimatedVisibility(alerts.dailySummary, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
                SliderRow(stringResource(R.string.send_at), alerts.summaryMinuteOfDay, 17 * 60..23 * 60 + 30, 30, ::minuteLabel, { v -> alerts { copy(summaryMinuteOfDay = v) } })
            }
        }

        ListGroup(stringResource(R.string.quiet_hours), footer = stringResource(R.string.quiet_hours_footer)) {
            ToggleRow(stringResource(R.string.dont_ring_at_night), alerts.quietHours, { v -> alerts { copy(quietHours = v) } }, icon = Icons.Moon)
            AnimatedVisibility(alerts.quietHours, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
                Column {
                    TimeRow(stringResource(R.string.from), alerts.quietStartMinute) { v -> alerts { copy(quietStartMinute = v) } }
                    TimeRow(stringResource(R.string.until), alerts.quietEndMinute) { v -> alerts { copy(quietEndMinute = v) } }
                }
            }
        }

        ListGroup(stringResource(R.string.data_source)) {
            val local = settings.source == DataSource.LOCAL
            val brandAccount = state.brandAccount?.takeIf { settings.cloudBrand != CloudBrand.SOLARMAN && it.brand == settings.cloudBrand }
            val otherBrand = !local && settings.cloudBrand != CloudBrand.SOLARMAN
            SettingRow(
                when {
                    settings.demoMode -> stringResource(R.string.demo_home)
                    local -> stringResource(R.string.source_local)
                    otherBrand -> brandAccount?.let { a -> a.label.ifBlank { null }?.let { stringResource(R.string.brand_account_title, settings.cloudBrand.title, it) } ?: settings.cloudBrand.title } ?: stringResource(R.string.not_connected)
                    else -> state.account?.email ?: stringResource(R.string.not_connected)
                },
                subtitle = when {
                    settings.demoMode -> stringResource(R.string.simulated_inverter)
                    local -> state.logger?.let { stringResource(R.string.logger_connected_sub, it.host, it.serial.toString()) } ?: stringResource(R.string.set_up_logger)
                    otherBrand -> brandAccount?.let { stringResource(R.string.inverter_sn, it.deviceName) } ?: stringResource(R.string.connect_brand, settings.cloudBrand.title)
                    else -> state.account?.let { stringResource(R.string.inverter_sn, it.deviceSn) } ?: stringResource(R.string.connect_account)
                },
                icon = if (local && !settings.demoMode) Icons.Wave else Icons.Account,
                onClick = { editingAccount = !editingAccount }
            ) { Icon(Icons.ChevronDown, null, tint = c.ink3, modifier = Modifier.size(20.dp)) }
            AnimatedVisibility(editingAccount, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
                var shown by remember(settings.source) { mutableStateOf(settings.source) }
                var shownBrand by remember(settings.cloudBrand) { mutableStateOf(settings.cloudBrand) }
                Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    SourcePicker(shown, { shown = it })
                    when (shown) {
                        DataSource.CLOUD -> {
                            CloudBrandPicker(shownBrand, { shownBrand = it })
                            val active = settings.source == DataSource.CLOUD && settings.cloudBrand == shownBrand
                            if (shownBrand == CloudBrand.SOLARMAN) {
                                AccountForm(vm = vm, initial = state.account, onDone = { editingAccount = false })
                                if (state.account != null) {
                                    if (!active) {
                                        TextButton(onClick = { vm.useCloudBrand(CloudBrand.SOLARMAN); editingAccount = false }) {
                                            Text(stringResource(R.string.use_source, CloudBrand.SOLARMAN.title), style = SolarType.bodyStrong)
                                        }
                                    }
                                    TextButton(onClick = { vm.signOut(); editingAccount = false }) { Text(stringResource(R.string.sign_out), color = c.down, style = SolarType.bodyStrong) }
                                }
                            } else {
                                val saved = state.brandAccount?.takeIf { it.brand == shownBrand }
                                BrandForm(vm = vm, brand = shownBrand, initial = saved, onDone = { editingAccount = false })
                                if (saved != null) {
                                    if (!active) {
                                        TextButton(onClick = { vm.useCloudBrand(shownBrand); editingAccount = false }) {
                                            Text(stringResource(R.string.use_source, shownBrand.title), style = SolarType.bodyStrong)
                                        }
                                    }
                                    TextButton(onClick = { vm.forgetBrandAccount(); editingAccount = false }) {
                                        Text(stringResource(R.string.forget_brand, shownBrand.title), color = c.down, style = SolarType.bodyStrong)
                                    }
                                }
                            }
                        }
                        DataSource.LOCAL -> {
                            LoggerForm(vm = vm, initial = state.logger, onDone = { editingAccount = false })
                            if (state.logger != null) {
                                TextButton(onClick = { vm.forgetLogger(); editingAccount = false }) { Text(stringResource(R.string.forget_logger), color = c.down, style = SolarType.bodyStrong) }
                            }
                        }
                    }
                }
            }
            if (!settings.demoMode && local) {
                SettingRow(stringResource(R.string.local_no_quota), icon = Icons.Wave, iconTint = c.ink3)
            } else if (!settings.demoMode && otherBrand && brandAccount != null) {
                SettingRow(
                    state.apiCallsYesterday?.let { stringResource(R.string.cloud_calls_yesterday, state.apiCallsToday, it) }
                        ?: stringResource(R.string.cloud_calls, state.apiCallsToday),
                    subtitle = stringResource(R.string.cloud_calls_sub, settings.cloudBrand.title, (settings.cloudBrand.minPollMs / 60_000L).toInt()),
                    icon = Icons.Wave,
                    iconTint = if (state.quotaExhausted) c.down else c.ink3
                )
            } else if (!settings.demoMode && state.account != null) {
                SettingRow(
                    state.apiCallsYesterday?.let { stringResource(R.string.api_calls_yesterday, state.apiCallsToday, it) }
                        ?: stringResource(R.string.api_calls, state.apiCallsToday),
                    subtitle = stringResource(if (state.quotaExhausted) R.string.quota_used_up else R.string.api_calls_sub),
                    icon = Icons.Wave,
                    iconTint = if (state.quotaExhausted) c.down else c.ink3
                )
            }
            ToggleRow(stringResource(R.string.demo_mode), settings.demoMode, vm::setDemo, subtitle = stringResource(R.string.demo_mode_sub), icon = Icons.Flask)
        }

        RelayGroup(vm, settings)

        ListGroup(stringResource(R.string.appearance)) {
            Segmented(
                options = listOf(
                    ThemeMode.SYSTEM to stringResource(R.string.theme_system),
                    ThemeMode.LIGHT to stringResource(R.string.theme_light),
                    ThemeMode.DARK to stringResource(R.string.theme_dark)
                ),
                selected = settings.theme,
                onSelect = { mode -> vm.updateSettings { it.copy(theme = mode) } },
                modifier = Modifier.padding(16.dp)
            )
            LanguageRow()
        }

        DataGroup(vm, state, system, onOpenDiagnostics)

        ListGroup(stringResource(R.string.about), footer = stringResource(R.string.about_footer)) {
            SettingRow(stringResource(R.string.app_version, BuildConfig.VERSION_NAME), subtitle = stringResource(R.string.open_source), icon = Icons.Info)
            SettingRow(stringResource(R.string.source_code), subtitle = stringResource(R.string.source_code_sub), icon = Icons.Code, onClick = { system.openUrl(BuildConfig.SOURCE_URL) }) {
                Icon(Icons.Chevron, null, tint = c.ink3, modifier = Modifier.size(20.dp))
            }
            SettingRow(stringResource(R.string.privacy_policy), subtitle = stringResource(R.string.privacy_policy_sub), icon = Icons.Shield, onClick = { system.openUrl(BuildConfig.PRIVACY_URL) }) {
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
        stringResource(R.string.reliability),
        footer = stringResource(R.string.reliability_footer)
    ) {
        CheckRow(stringResource(R.string.perm_notifications), stringResource(R.string.perm_notifications_sub), p.notifications, system::requestNotifications)
        CheckRow(stringResource(R.string.perm_full_screen), stringResource(if (p.fullScreen) R.string.perm_full_screen_sub else R.string.perm_full_screen_off), p.fullScreen, system::openFullScreenSettings)
        CheckRow(stringResource(R.string.perm_background), stringResource(if (p.unrestricted) R.string.perm_background_sub else R.string.perm_background_fix), p.unrestricted, system::openBatterySettings)
        CheckRow(stringResource(R.string.perm_exact), stringResource(R.string.perm_exact_sub), p.exactAlarms, system::openExactAlarmSettings, optional = true)
        SettingRow(stringResource(R.string.brand_steps), icon = Icons.Info, onClick = { system.openUrl("https://dontkillmyapp.com") }) {
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
        if (!ok) TextButton(onClick = onFix) { Text(stringResource(R.string.fix), style = SolarType.bodyStrong, color = c.brand) }
    }
}

@Composable
private fun TimeRow(label: String, minute: Int, onChange: (Int) -> Unit) {
    val c = Solar.colors
    Row(Modifier.fillMaxWidth().padding(start = 54.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = SolarType.body, color = c.ink2, modifier = Modifier.weight(1f))
        IconButton(onClick = { onChange((minute - 30 + 1440) % 1440) }) { Icon(Icons.Back, contentDescription = stringResource(R.string.earlier, label), tint = c.ink2) }
        Text(minuteLabel(minute), style = SolarType.figureS, color = c.ink, modifier = Modifier.width(56.dp), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        IconButton(onClick = { onChange((minute + 30) % 1440) }) { Icon(Icons.Chevron, contentDescription = stringResource(R.string.later, label), tint = c.ink2) }
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

    ListGroup(stringResource(R.string.whatsapp_relay)) {
        ToggleRow(
            stringResource(R.string.forward_whatsapp),
            relay.enabled,
            { v -> vm.updateSettings { it.copy(relay = it.relay.copy(enabled = v)) } },
            subtitle = stringResource(R.string.forward_whatsapp_sub),
            icon = Icons.Message
        )
        AnimatedVisibility(relay.enabled, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                SolarField(stringResource(R.string.bridge_url), url, { url = it }, placeholder = "http://100.64.0.2:8787", keyboard = KeyboardType.Uri)
                SolarField(stringResource(R.string.shared_secret), secret, { secret = it }, secret = true, placeholder = stringResource(R.string.at_least_16))
                val saved = stringResource(R.string.saved)
                val sending = stringResource(R.string.sending)
                val delivered = stringResource(R.string.delivered)
                val unreachable = stringResource(R.string.bridge_unreachable)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SecondaryButton(stringResource(R.string.save), onClick = {
                        vm.updateSettings { it.copy(relay = it.relay.copy(url = url, secret = secret)) }
                        result = saved
                    }, modifier = Modifier.weight(1f))
                    SecondaryButton(stringResource(R.string.send_test), onClick = {
                        vm.updateSettings { it.copy(relay = it.relay.copy(url = url, secret = secret)) }
                        scope.launch {
                            result = sending
                            result = if (vm.testRelay()) delivered else unreachable
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
    val nothingToExport = stringResource(R.string.nothing_to_export)

    ListGroup(stringResource(R.string.data), footer = message) {
        SettingRow(stringResource(R.string.export_cuts), subtitle = stringResource(R.string.export_cuts_sub), icon = Icons.Export, onClick = {
            scope.launch {
                val intent = vm.exportIntent()
                if (intent == null) message = nothingToExport else system.share(intent)
            }
        })
        if (!state.settings.demoMode && state.settings.source == DataSource.CLOUD && state.settings.cloudBrand == CloudBrand.SOLARMAN && state.account != null) {
            SettingRow(
                stringResource(R.string.import_solarman),
                subtitle = importing?.let { (done, total) -> stringResource(R.string.import_fetching, done + 1, total) }
                    ?: state.importNote
                    ?: stringResource(R.string.import_sub),
                icon = Icons.Calendar,
                onClick = if (importing == null) ({ vm.importHistory { message = it } }) else null
            )
        }
        SettingRow(stringResource(R.string.inverter_data), subtitle = stringResource(R.string.inverter_data_sub), icon = Icons.Code, onClick = onOpenDiagnostics) {
            Icon(Icons.Chevron, null, tint = c.ink3, modifier = Modifier.size(20.dp))
        }
        SettingRow(stringResource(R.string.clear_history), subtitle = stringResource(R.string.clear_history_sub), icon = Icons.Trash, iconTint = c.down, onClick = { confirmClear = true })
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text(stringResource(R.string.clear_history_q)) },
            text = { Text(stringResource(R.string.clear_history_body)) },
            confirmButton = { TextButton(onClick = { vm.clearHistory(); confirmClear = false }) { Text(stringResource(R.string.clear), color = c.down) } },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text(stringResource(R.string.cancel)) } }
        )
    }
}

/**
 * Android 13+ keeps a language per app, set from the system's own picker. Older phones follow
 * the phone's language.
 */
@Composable
private fun LanguageRow() {
    val c = Solar.colors
    val context = LocalContext.current
    val perApp = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
    val current = LocalConfiguration.current.locales[0].let { it.getDisplayLanguage(it).replaceFirstChar { ch -> ch.uppercase() } }
    SettingRow(
        stringResource(R.string.language),
        subtitle = if (perApp) "$current · ${stringResource(R.string.language_pick)}" else stringResource(R.string.language_follows_phone),
        icon = Icons.Globe,
        onClick = if (perApp) {
            {
                context.startActivity(
                    Intent(Settings.ACTION_APP_LOCALE_SETTINGS, Uri.fromParts("package", context.packageName, null))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            }
        } else {
            null
        }
    ) {
        if (perApp) Icon(Icons.Chevron, null, tint = c.ink3, modifier = Modifier.size(20.dp))
    }
}
