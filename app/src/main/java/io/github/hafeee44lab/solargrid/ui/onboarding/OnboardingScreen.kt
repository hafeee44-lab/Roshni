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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.hafeee44lab.solargrid.R
import io.github.hafeee44lab.solargrid.data.Account
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
        PrimaryButton("Connect my inverter", onConnect, Modifier.fillMaxWidth())
        SecondaryButton("Try with demo data", onDemo, Modifier.fillMaxWidth())
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
        Text("Roshni", style = SolarType.title, color = c.ink)
    }
    Text(
        "Know the moment the mains go off.",
        style = SolarType.headline,
        color = c.ink,
        modifier = Modifier.padding(top = 20.dp).semantics { heading() }
    )
    Text(
        "Roshni watches your solar inverter around the clock, rings like an alarm clock when the mains fail, and tells you how long your battery will last.",
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
    Feature(Icons.Bell, "An alarm, not just a notification", "It shows over the lock screen and keeps ringing until you stop it.")
    Feature(Icons.Battery, "Runtime that learns your battery", "It measures real discharges, not the number on the label.")
    Feature(Icons.Clock, "Warns you before load-shedding", "It spots the schedule in your own history.")
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
private fun Connect(vm: MainViewModel, state: UiState, onBack: () -> Unit, onDone: () -> Unit) = StepScaffold(
    actions = {
        if (state.account != null) PrimaryButton("Continue", onDone, Modifier.fillMaxWidth())
        TextButton(onClick = onBack, modifier = Modifier.fillMaxWidth()) { Text("Back") }
    }
) {
    val c = Solar.colors
    Text("Connect Solarman", style = SolarType.headline, color = c.ink, modifier = Modifier.padding(top = 8.dp).semantics { heading() })
    Text(
        "Readings come from Solarman's OpenAPI, the same cloud your inverter's Wi-Fi stick already reports to.",
        style = SolarType.body,
        color = c.ink2,
        modifier = Modifier.padding(top = 8.dp, bottom = 16.dp)
    )

    val existing = state.account
    if (existing != null) {
        ListGroup(null) {
            SettingRow("Connected", subtitle = "${existing.email} · inverter ${existing.deviceSn}", icon = Icons.Check, iconTint = c.ok)
        }
    } else {
        AccountForm(vm = vm, initial = null, onDone = onDone)
    }
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
        SolarField("App ID", appId, { appId = it }, keyboard = KeyboardType.Number)
        SolarField("App secret", appSecret, { appSecret = it }, secret = true)
        SolarField("Solarman email", email, { email = it }, keyboard = KeyboardType.Email)
        SolarField(if (initial != null) "Password (leave blank to keep)" else "Solarman password", password, { password = it }, secret = true)

        TextButton(onClick = { help = !help }, contentPadding = PaddingValues(horizontal = 0.dp)) {
            Icon(Icons.Info, null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text("Where do I get an App ID?", style = SolarType.bodyStrong)
        }
        AnimatedVisibility(help, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
            SCard(container = c.surfaceVariant) {
                Text(
                    "Solarman gives OpenAPI credentials (an App ID and App Secret) to plant owners who ask. " +
                        "Contact Solarman support, say it's for monitoring your own plant, and include the email you sign in with. " +
                        "Your email and password are the ones you already use in the Solarman app.",
                    style = SolarType.caption,
                    color = c.ink2
                )
                TextButton(onClick = { system?.openUrl(OPENAPI_HELP) }, contentPadding = PaddingValues(horizontal = 0.dp)) {
                    Text("Solarman's OpenAPI FAQ", style = SolarType.bodyStrong)
                }
            }
        }

        error?.let { Text(it, style = SolarType.caption, color = c.down) }

        val list = devices
        if (list == null) {
            PrimaryButton(
                text = if (busy) "Signing in…" else "Sign in and find inverters",
                enabled = !busy && appId.isNotBlank() && appSecret.isNotBlank() && email.isNotBlank() && (password.isNotEmpty() || initial != null),
                onClick = {
                    busy = true
                    error = null
                    scope.launch {
                        vm.discover(draft("")).fold(
                            onSuccess = {
                                devices = it
                                chosen = it.firstOrNull()?.sn
                                if (it.isEmpty()) error = "Signed in, but no inverters were listed. Enter the serial number below."
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
                ListGroup("Inverters on this account") {
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
                                    device.station + when (device.online) {
                                        true -> " · online"
                                        false -> " · offline"
                                        null -> ""
                                    },
                                    style = SolarType.caption,
                                    color = c.ink3
                                )
                            }
                        }
                    }
                }
            }
            SolarField("Or enter the serial number", manualSn, { manualSn = it; if (it.isNotBlank()) chosen = null })
            val sn = chosen ?: manualSn
            PrimaryButton(
                "Use this inverter",
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

    StepScaffold(actions = { PrimaryButton(if (p.blocking == 0) "Continue" else "Continue anyway", onNext, Modifier.fillMaxWidth()) }) {
        Text("Let it wake you", style = SolarType.headline, color = c.ink, modifier = Modifier.padding(top = 8.dp).semantics { heading() })
        Text(
            "Android tries hard to stop apps working in the background. These keep the alarm reliable.",
            style = SolarType.body,
            color = c.ink2,
            modifier = Modifier.padding(top = 8.dp, bottom = 16.dp)
        )
        ListGroup(null) {
            PermissionRow("Notifications", "So any alert can reach you", p.notifications, system::requestNotifications)
            PermissionRow("Full-screen alarm", "Shows the alarm over a locked screen", p.fullScreen, system::openFullScreenSettings)
            PermissionRow("Run in background", "Keeps checking while the phone sleeps", p.unrestricted, system::requestUnrestricted)
            PermissionRow("Precise timing", "Optional. Checks land right after each upload.", p.exactAlarms, system::openExactAlarmSettings)
        }
    }
}

@Composable
private fun PermissionRow(title: String, subtitle: String, granted: Boolean, onGrant: () -> Unit) {
    val c = Solar.colors
    SettingRow(title, subtitle = subtitle, icon = if (granted) Icons.Check else Icons.Shield, iconTint = if (granted) c.ok else c.ink2, onClick = if (granted) null else onGrant) {
        if (!granted) TextButton(onClick = onGrant) { Text("Allow", style = SolarType.bodyStrong) }
    }
}

@Composable
private fun Ready(state: UiState, onStart: () -> Unit, onLater: () -> Unit) = StepScaffold(
    actions = {
        PrimaryButton("Start watching", onStart, Modifier.fillMaxWidth())
        TextButton(onClick = onLater, modifier = Modifier.fillMaxWidth()) { Text("Just look around") }
    }
) {
    val c = Solar.colors
    Spacer(Modifier.height(32.dp))
    Icon(Icons.Check, null, tint = c.ok, modifier = Modifier.size(40.dp))
    Text("You're set", style = SolarType.headline, color = c.ink, modifier = Modifier.padding(top = 16.dp).semantics { heading() })
    Text(
        if (state.settings.demoMode) {
            "You're looking at a simulated home with two weeks of history. Connect your own inverter any time in Settings."
        } else {
            "Roshni runs quietly in the background with a small ongoing notification. A cut rings; everything else just notifies."
        },
        style = SolarType.body,
        color = c.ink2,
        modifier = Modifier.padding(top = 8.dp)
    )
}
