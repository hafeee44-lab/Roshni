package io.github.hafeee44lab.solargrid.ui.onboarding

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.hafeee44lab.solargrid.R
import io.github.hafeee44lab.solargrid.data.Account
import io.github.hafeee44lab.solargrid.data.DataSource
import io.github.hafeee44lab.solargrid.data.DiscoveredDevice
import io.github.hafeee44lab.solargrid.domain.FlowPowers
import io.github.hafeee44lab.solargrid.ui.LocalSystem
import io.github.hafeee44lab.solargrid.ui.MainViewModel
import io.github.hafeee44lab.solargrid.ui.SystemActions
import io.github.hafeee44lab.solargrid.ui.UiState
import io.github.hafeee44lab.solargrid.ui.components.Icons
import io.github.hafeee44lab.solargrid.ui.components.ListGroup
import io.github.hafeee44lab.solargrid.ui.components.PrimaryButton
import io.github.hafeee44lab.solargrid.ui.components.SCard
import io.github.hafeee44lab.solargrid.ui.components.SecondaryButton
import io.github.hafeee44lab.solargrid.ui.components.Segmented
import io.github.hafeee44lab.solargrid.ui.components.SettingRow
import io.github.hafeee44lab.solargrid.ui.live.PowerFlow
import io.github.hafeee44lab.solargrid.ui.settings.SolarField
import io.github.hafeee44lab.solargrid.ui.theme.Solar
import io.github.hafeee44lab.solargrid.ui.theme.SolarType
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.sin

private const val OPENAPI_HELP = "https://helpcenter.solarmanpv.com/portal/en/kb/articles/open-api-faq-summary"
private const val STEPS = 4

@Composable
fun OnboardingScreen(vm: MainViewModel, state: UiState, system: SystemActions) {
    val c = Solar.colors
    var step by rememberSaveable { mutableIntStateOf(0) }
    val progress by animateFloatAsState((step + 1f) / STEPS, tween(400, easing = FastOutSlowInEasing), label = "steps")

    Column(
        Modifier
            .fillMaxSize()
            .background(c.bg)
            .windowInsetsPadding(WindowInsets.statusBars)
            .windowInsetsPadding(WindowInsets.navigationBars)
            .imePadding(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        LinearProgressIndicator(
            progress = { progress },
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp).widthIn(max = 560.dp).fillMaxWidth(),
            trackColor = c.surfaceVariant,
            drawStopIndicator = {}
        )

        AnimatedContent(
            targetState = step,
            transitionSpec = {
                val forward = targetState > initialState
                (slideInHorizontally(tween(320, easing = FastOutSlowInEasing)) { if (forward) it / 4 else -it / 4 } + fadeIn(tween(240))) togetherWith
                    (slideOutHorizontally(tween(240)) { if (forward) -it / 4 else it / 4 } + fadeOut(tween(160)))
            },
            label = "step",
            modifier = Modifier.weight(1f).widthIn(max = 560.dp)
        ) { current ->
            Box(Modifier.fillMaxSize()) {
                when (current) {
                    0 -> Welcome(onConnect = { step = 1 }, onDemo = { vm.setDemo(true); step = 2 })
                    1 -> Connect(vm, state, onBack = { step = 0 }, onDone = { step = 2 })
                    2 -> Permissions(vm, system, onNext = { step = 3 })
                    else -> Ready(state, onStart = { vm.finishOnboarding(startMonitoring = true) }, onLater = { vm.finishOnboarding(startMonitoring = false) })
                }
            }
        }
    }
}

/** Scrolling content with the step's buttons held at the bottom, where the thumb is. */
@Composable
private fun StepScaffold(actions: @Composable ColumnScope.() -> Unit, body: @Composable ColumnScope.() -> Unit) {
    val scroll = rememberScrollState()
    Column(Modifier.fillMaxSize()) {
        Column(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(scroll)
                .padding(horizontal = 20.dp, vertical = 12.dp),
            content = body
        )
        // A hairline only while there is more to read under the buttons.
        Box(Modifier.fillMaxWidth().height(1.dp).background(if (scroll.canScrollForward) Solar.colors.outline else Color.Transparent))
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp), content = actions)
    }
}

@Composable
private fun Welcome(onConnect: () -> Unit, onDemo: () -> Unit) = StepScaffold(
    actions = {
        PrimaryButton(stringResource(R.string.connect_inverter), onConnect, Modifier.fillMaxWidth())
        SecondaryButton(stringResource(R.string.try_demo), onDemo, Modifier.fillMaxWidth())
    }
) {
    val c = Solar.colors
    // A live preview that steps like a real inverter, every few seconds.
    val t by produceState(0f) {
        while (true) {
            delay(2600)
            value += 0.9f
        }
    }

    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp)) {
        Image(painterResource(R.drawable.ic_brand), contentDescription = null, modifier = Modifier.size(40.dp))
        Spacer(Modifier.width(8.dp))
        Text(stringResource(R.string.app_name), style = SolarType.title, color = c.ink)
    }
    Text(
        stringResource(R.string.welcome_headline),
        style = SolarType.headline,
        color = c.ink,
        modifier = Modifier.padding(top = 20.dp).semantics { heading() }
    )
    Text(
        stringResource(R.string.welcome_body),
        style = SolarType.body,
        color = c.ink2,
        modifier = Modifier.padding(top = 8.dp)
    )
    Spacer(Modifier.height(16.dp))
    SCard {
        PowerFlow(
            powers = FlowPowers(
                solarW = 3200.0 + 900 * sin(t),
                loadW = 1150.0 + 250 * sin(t * 3),
                gridW = 180.0,
                batteryW = -(2230.0 + 650 * sin(t))
            ),
            soc = 64.0 + 6 * sin(t / 2),
            gridDown = false,
            lowBatteryPercent = 20
        )
    }
    Spacer(Modifier.height(16.dp))
    Feature(Icons.Bell, stringResource(R.string.feature_alarm), stringResource(R.string.feature_alarm_sub))
    Feature(Icons.Battery, stringResource(R.string.feature_runtime), stringResource(R.string.feature_runtime_sub))
    Feature(Icons.Clock, stringResource(R.string.feature_warn), stringResource(R.string.feature_warn_sub))
}

@Composable
private fun Feature(icon: ImageVector, title: String, body: String) {
    val c = Solar.colors
    Row(Modifier.padding(vertical = 8.dp)) {
        Icon(icon, null, tint = c.brand, modifier = Modifier.size(22.dp).padding(top = 1.dp))
        Spacer(Modifier.width(14.dp))
        Column {
            Text(title, style = SolarType.bodyStrong, color = c.ink)
            Text(body, style = SolarType.caption, color = c.ink3)
        }
    }
}

@Composable
private fun Connect(vm: MainViewModel, state: UiState, onBack: () -> Unit, onDone: () -> Unit) {
    var source by rememberSaveable { mutableStateOf(state.settings.source) }
    val ready = when (source) {
        DataSource.CLOUD -> state.account != null
        DataSource.LOCAL -> state.logger != null
    }

    StepScaffold(
        actions = {
            if (ready) PrimaryButton(stringResource(R.string.continue_), { vm.useSource(source); onDone() }, Modifier.fillMaxWidth())
            TextButton(onClick = onBack, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.back)) }
        }
    ) {
        val c = Solar.colors
        Text(stringResource(R.string.connect_inverter), style = SolarType.headline, color = c.ink, modifier = Modifier.padding(top = 8.dp).semantics { heading() })
        SourcePicker(source, { source = it }, Modifier.padding(top = 12.dp, bottom = 16.dp))

        when (source) {
            DataSource.CLOUD -> {
                Text(
                    stringResource(R.string.connect_solarman_body),
                    style = SolarType.body,
                    color = c.ink2,
                    modifier = Modifier.padding(bottom = 16.dp)
                )
                val existing = state.account
                if (existing != null) {
                    ListGroup(null) {
                        SettingRow(stringResource(R.string.connected), subtitle = stringResource(R.string.connected_sub, existing.email, existing.deviceSn), icon = Icons.Check, iconTint = c.ok)
                    }
                } else {
                    AccountForm(vm = vm, initial = null, onDone = onDone)
                }
            }
            DataSource.LOCAL -> {
                val logger = state.logger
                if (logger != null) {
                    ListGroup(null) {
                        SettingRow(stringResource(R.string.connected), subtitle = stringResource(R.string.logger_connected_sub, logger.host, logger.serial.toString()), icon = Icons.Check, iconTint = c.ok)
                    }
                } else {
                    LoggerForm(vm = vm, initial = null, onDone = onDone)
                }
            }
        }
    }
}

/** Solarman's cloud, or the logger itself on the home Wi-Fi. Shared by onboarding and Settings. */
@Composable
fun SourcePicker(selected: DataSource, onSelect: (DataSource) -> Unit, modifier: Modifier = Modifier) {
    Segmented(
        options = listOf(
            DataSource.CLOUD to stringResource(R.string.source_cloud),
            DataSource.LOCAL to stringResource(R.string.source_local)
        ),
        selected = selected,
        onSelect = onSelect,
        modifier = modifier
    )
}

/**
 * Sign in, list the inverters on the account, pick one. Shared by onboarding and Settings.
 * The password is hashed the moment it is submitted and never stored as typed.
 */
@Composable
fun AccountForm(vm: MainViewModel, initial: Account?, onDone: () -> Unit) {
    val c = Solar.colors
    val scope = rememberCoroutineScope()
    val system = LocalSystem.current

    var appId by rememberSaveable { mutableStateOf(initial?.appId ?: "") }
    var appSecret by remember { mutableStateOf(initial?.appSecret ?: "") }
    var email by rememberSaveable { mutableStateOf(initial?.email ?: "") }
    var password by remember { mutableStateOf("") }
    var manualSn by rememberSaveable { mutableStateOf(initial?.deviceSn ?: "") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var devices by remember { mutableStateOf<List<DiscoveredDevice>?>(null) }
    var chosen by remember { mutableStateOf<String?>(null) }
    var help by remember { mutableStateOf(false) }

    fun draft(sn: String) = Account(
        appId = appId.trim(),
        appSecret = appSecret.trim(),
        email = email.trim(),
        passwordSha256 = if (password.isNotEmpty()) Account.sha256(password) else initial?.passwordSha256 ?: "",
        deviceSn = sn.trim()
    )

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        SolarField(stringResource(R.string.app_id), appId, { appId = it }, keyboard = KeyboardType.Number)
        SolarField(stringResource(R.string.app_secret), appSecret, { appSecret = it }, secret = true)
        SolarField(stringResource(R.string.solarman_email), email, { email = it }, keyboard = KeyboardType.Email)
        SolarField(stringResource(if (initial != null) R.string.password_keep else R.string.solarman_password), password, { password = it }, secret = true)

        TextButton(onClick = { help = !help }, contentPadding = PaddingValues(horizontal = 0.dp)) {
            Icon(Icons.Info, null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.where_app_id), style = SolarType.bodyStrong)
        }
        AnimatedVisibility(help, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
            SCard(container = c.surfaceVariant) {
                Text(
                    stringResource(R.string.app_id_help),
                    style = SolarType.caption,
                    color = c.ink2
                )
                TextButton(onClick = { system?.openUrl(OPENAPI_HELP) }, contentPadding = PaddingValues(horizontal = 0.dp)) {
                    Text(stringResource(R.string.openapi_faq), style = SolarType.bodyStrong)
                }
            }
        }

        error?.let { Text(it, style = SolarType.caption, color = c.down) }

        val list = devices
        val noInverters = stringResource(R.string.no_inverters_listed)
        if (list == null) {
            PrimaryButton(
                text = stringResource(if (busy) R.string.signing_in else R.string.sign_in_find),
                enabled = !busy && appId.isNotBlank() && appSecret.isNotBlank() && email.isNotBlank() && (password.isNotEmpty() || initial != null),
                onClick = {
                    busy = true
                    error = null
                    scope.launch {
                        vm.discover(draft("")).fold(
                            onSuccess = {
                                devices = it
                                chosen = it.firstOrNull()?.sn
                                if (it.isEmpty()) error = noInverters
                            },
                            onFailure = { error = it.message }
                        )
                        busy = false
                    }
                },
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp)
            )
            if (busy) {
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(24.dp))
                }
            }
        } else {
            if (list.isNotEmpty()) {
                ListGroup(stringResource(R.string.inverters_on_account)) {
                    list.forEach { device ->
                        val selected = chosen == device.sn
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .selectable(selected = selected, role = Role.RadioButton) { chosen = device.sn; manualSn = "" }
                                .padding(horizontal = 8.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(selected = selected, onClick = null, modifier = Modifier.padding(8.dp))
                            Column(Modifier.weight(1f)) {
                                Text(device.sn, style = SolarType.body, color = c.ink)
                                Text(
                                    when (device.online) {
                                        true -> stringResource(R.string.online_suffix, device.station)
                                        false -> stringResource(R.string.offline_suffix, device.station)
                                        null -> device.station
                                    },
                                    style = SolarType.caption,
                                    color = c.ink3
                                )
                            }
                        }
                    }
                }
            }
            SolarField(stringResource(R.string.enter_serial), manualSn, { manualSn = it; if (it.isNotBlank()) chosen = null })
            val sn = chosen ?: manualSn
            PrimaryButton(
                stringResource(R.string.use_inverter),
                enabled = sn.isNotBlank(),
                onClick = {
                    val label = devices?.firstOrNull { it.sn == sn }?.station ?: ""
                    vm.saveAccount(draft(sn).copy(label = label))
                    onDone()
                },
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp)
            )
        }
    }
}

@Composable
private fun Permissions(vm: MainViewModel, system: SystemActions, onNext: () -> Unit) {
    val c = Solar.colors
    val p by vm.permissions.collectAsStateWithLifecycle()

    StepScaffold(actions = { PrimaryButton(stringResource(if (p.blocking == 0) R.string.continue_ else R.string.continue_anyway), onNext, Modifier.fillMaxWidth()) }) {
        Text(stringResource(R.string.let_it_wake_you), style = SolarType.headline, color = c.ink, modifier = Modifier.padding(top = 8.dp).semantics { heading() })
        Text(
            stringResource(R.string.perms_body),
            style = SolarType.body,
            color = c.ink2,
            modifier = Modifier.padding(top = 8.dp, bottom = 16.dp)
        )
        ListGroup(null) {
            PermissionRow(stringResource(R.string.perm_notifications), stringResource(R.string.perm_notifications_ob), p.notifications, system::requestNotifications)
            PermissionRow(stringResource(R.string.perm_full_screen), stringResource(R.string.perm_full_screen_ob), p.fullScreen, system::openFullScreenSettings)
            PermissionRow(stringResource(R.string.perm_background), stringResource(R.string.perm_background_ob), p.unrestricted, system::requestUnrestricted)
            PermissionRow(stringResource(R.string.perm_exact), stringResource(R.string.perm_exact_sub), p.exactAlarms, system::openExactAlarmSettings)
        }
    }
}

@Composable
private fun PermissionRow(title: String, subtitle: String, granted: Boolean, onGrant: () -> Unit) {
    val c = Solar.colors
    SettingRow(title, subtitle = subtitle, icon = if (granted) Icons.Check else Icons.Shield, iconTint = if (granted) c.ok else c.ink2, onClick = if (granted) null else onGrant) {
        if (!granted) TextButton(onClick = onGrant) { Text(stringResource(R.string.allow), style = SolarType.bodyStrong) }
    }
}

@Composable
private fun Ready(state: UiState, onStart: () -> Unit, onLater: () -> Unit) = StepScaffold(
    actions = {
        PrimaryButton(stringResource(R.string.start_watching), onStart, Modifier.fillMaxWidth())
        TextButton(onClick = onLater, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.just_look_around)) }
    }
) {
    val c = Solar.colors
    Spacer(Modifier.height(32.dp))
    Icon(Icons.Check, null, tint = c.ok, modifier = Modifier.size(40.dp))
    Text(stringResource(R.string.youre_set), style = SolarType.headline, color = c.ink, modifier = Modifier.padding(top = 16.dp).semantics { heading() })
    Text(
        if (state.settings.demoMode) {
            stringResource(R.string.ready_demo)
        } else {
            stringResource(R.string.ready_live)
        },
        style = SolarType.body,
        color = c.ink2,
        modifier = Modifier.padding(top = 8.dp)
    )
}
