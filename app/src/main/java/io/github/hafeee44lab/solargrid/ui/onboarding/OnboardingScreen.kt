package io.github.hafeee44lab.solargrid.ui.onboarding

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.hafeee44lab.solargrid.data.Account
import io.github.hafeee44lab.solargrid.data.DiscoveredDevice
import io.github.hafeee44lab.solargrid.domain.FlowPowers
import io.github.hafeee44lab.solargrid.ui.LocalSystem
import io.github.hafeee44lab.solargrid.ui.MainViewModel
import io.github.hafeee44lab.solargrid.ui.SystemActions
import io.github.hafeee44lab.solargrid.ui.UiState
import io.github.hafeee44lab.solargrid.ui.components.Atmosphere
import io.github.hafeee44lab.solargrid.ui.components.GhostButton
import io.github.hafeee44lab.solargrid.ui.components.Icons
import io.github.hafeee44lab.solargrid.ui.components.Panel
import io.github.hafeee44lab.solargrid.ui.components.PrimaryButton
import io.github.hafeee44lab.solargrid.ui.components.SectionLabel
import io.github.hafeee44lab.solargrid.ui.components.SettingRow
import io.github.hafeee44lab.solargrid.ui.live.PowerFlow
import io.github.hafeee44lab.solargrid.ui.settings.SolarField
import io.github.hafeee44lab.solargrid.ui.theme.Solar
import io.github.hafeee44lab.solargrid.ui.theme.SolarType
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.sin

private const val OPENAPI_HELP = "https://helpcenter.solarmanpv.com/portal/en/kb/articles/open-api-faq-summary"

@Composable
fun OnboardingScreen(vm: MainViewModel, state: UiState, system: SystemActions) {
    val colors = Solar.colors
    var step by rememberSaveable { mutableIntStateOf(0) }

    Box(Modifier.fillMaxSize()) {
        Atmosphere(tint = if (step == 0) colors.solar else colors.ok, intensity = 0.8f, pulse = false)

        Column(
            Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.statusBars)
                .windowInsetsPadding(WindowInsets.navigationBars)
                .imePadding()
        ) {
            StepDots(step, total = 4, modifier = Modifier.padding(top = 18.dp).align(Alignment.CenterHorizontally))

            AnimatedContent(
                targetState = step,
                transitionSpec = {
                    val forward = targetState > initialState
                    (slideInHorizontally(tween(420, easing = FastOutSlowInEasing)) { if (forward) it / 3 else -it / 3 } + fadeIn(tween(300))) togetherWith
                        (slideOutHorizontally(tween(300)) { if (forward) -it / 3 else it / 3 } + fadeOut(tween(200)))
                },
                label = "step",
                modifier = Modifier.weight(1f)
            ) { current ->
                Column(
                    Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 22.dp, vertical = 16.dp)
                ) {
                    when (current) {
                        0 -> Welcome(
                            onConnect = { step = 1 },
                            onDemo = {
                                vm.setDemo(true)
                                step = 2
                            }
                        )
                        1 -> Connect(vm, state, onBack = { step = 0 }, onDone = { step = 2 })
                        2 -> Permissions(vm, system, onNext = { step = 3 })
                        else -> Ready(state, onStart = { vm.finishOnboarding(startMonitoring = true) }, onLater = { vm.finishOnboarding(startMonitoring = false) })
                    }
                }
            }
        }
    }
}

@Composable
private fun StepDots(step: Int, total: Int, modifier: Modifier = Modifier) {
    val colors = Solar.colors
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        repeat(total) { i ->
            val width by animateDpAsState(if (i == step) 22.dp else 6.dp, tween(350), label = "dot")
            val tint by animateColorAsState(if (i <= step) colors.ink else colors.hairlineStrong, label = "dotTint")
            Box(Modifier.height(6.dp).width(width).clip(CircleShape).background(tint))
        }
    }
}

@Composable
private fun Welcome(onConnect: () -> Unit, onDemo: () -> Unit) {
    val colors = Solar.colors
    // Numbers step every few seconds, like a real inverter, so the digits roll instead of blur.
    val t by produceState(0f) {
        while (true) {
            delay(2600)
            value += 0.9f
        }
    }

    Spacer(Modifier.height(8.dp))
    Panel(padding = PaddingValues(horizontal = 6.dp, vertical = 10.dp)) {
        PowerFlow(
            powers = FlowPowers(
                solarW = 3200.0 + 900 * sin(t),
                loadW = 1150.0 + 250 * sin(t * 3),
                gridW = 180.0,
                batteryW = -(2230.0 + 650 * sin(t))
            ),
            soc = 64.0 + 6 * sin(t / 2),
            gridDown = false
        )
    }
    Spacer(Modifier.height(26.dp))
    Text("Know the moment\nthe grid goes down.", style = SolarType.display, color = colors.ink)
    Text(
        "Solar Grid watches your inverter around the clock. When the mains fail it rings like an alarm clock and tells you exactly how long your battery will last.",
        style = SolarType.body.copy(fontSize = SolarType.body.fontSize * 1.07f),
        color = colors.ink2,
        modifier = Modifier.padding(top = 12.dp)
    )
    Spacer(Modifier.height(20.dp))
    Feature(Icons.Bell, colors.down, "An alarm, not just a notification", "Full-screen, over the lock screen, until you stop it.")
    Feature(Icons.Battery, colors.battery, "Runtime that learns your battery", "Calibrates against real discharges, not the label.")
    Feature(Icons.Sparkle, colors.solar, "Forecasts your load-shedding", "Spots the schedule in your history and warns you first.")
    Spacer(Modifier.height(28.dp))
    PrimaryButton("Connect my inverter", onConnect, Modifier.fillMaxWidth(), icon = Icons.Live)
    Spacer(Modifier.height(10.dp))
    GhostButton("Explore with demo data", onDemo, Modifier.fillMaxWidth(), icon = Icons.Flask)
}

@Composable
private fun Feature(icon: androidx.compose.ui.graphics.vector.ImageVector, tone: androidx.compose.ui.graphics.Color, title: String, body: String) {
    val colors = Solar.colors
    Row(Modifier.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(40.dp).clip(RoundedCornerShape(14.dp)).background(tone.copy(alpha = 0.14f)), contentAlignment = Alignment.Center) {
            Icon(icon, null, tint = tone, modifier = Modifier.size(20.dp))
        }
        Spacer(Modifier.width(14.dp))
        Column {
            Text(title, style = SolarType.bodyStrong, color = colors.ink)
            Text(body, style = SolarType.caption, color = colors.ink3)
        }
    }
}

@Composable
private fun Connect(vm: MainViewModel, state: UiState, onBack: () -> Unit, onDone: () -> Unit) {
    val colors = Solar.colors
    Text("Connect Solarman", style = SolarType.hero, color = colors.ink, modifier = Modifier.padding(top = 8.dp))
    Text(
        "Readings come from Solarman's OpenAPI — the same cloud your inverter's Wi-Fi logger already reports to.",
        style = SolarType.body,
        color = colors.ink2,
        modifier = Modifier.padding(top = 8.dp, bottom = 18.dp)
    )

    val existing = state.account
    if (existing != null) {
        Panel(glow = colors.ok) {
            SettingRow(
                title = "Connected",
                subtitle = "${existing.email} · inverter ${existing.deviceSn}",
                icon = Icons.Check,
                iconTint = colors.ok
            )
        }
        Spacer(Modifier.height(16.dp))
        PrimaryButton("Continue", onDone, Modifier.fillMaxWidth())
        Spacer(Modifier.height(10.dp))
        GhostButton("Back", onBack, Modifier.fillMaxWidth())
        return
    }

    AccountForm(vm = vm, initial = null, onDone = onDone)
    Spacer(Modifier.height(10.dp))
    GhostButton("Back", onBack, Modifier.fillMaxWidth())
}

/**
 * Sign in, list the inverters on the account, pick one. Shared by onboarding and Settings.
 * The password is hashed the moment it is submitted and never stored as typed.
 */
@Composable
fun AccountForm(vm: MainViewModel, initial: Account?, onDone: () -> Unit) {
    val colors = Solar.colors
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

        Row(
            Modifier
                .clip(RoundedCornerShape(12.dp))
                .clickable { help = !help }
                .padding(vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Info, null, tint = colors.grid, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(8.dp))
            Text("Where do I get an App ID?", style = SolarType.bodyStrong, color = colors.grid)
        }
        AnimatedVisibility(help, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
            Column(
                Modifier
                    .clip(RoundedCornerShape(16.dp))
                    .background(colors.grid.copy(alpha = 0.08f))
                    .padding(14.dp)
            ) {
                Text(
                    "Solarman issues OpenAPI credentials (an App ID and App Secret) to plant owners on request. " +
                        "Contact Solarman support, say it's for monitoring your own plant, and include the email you sign in with. " +
                        "Your email and password are the ones you already use in the Solarman app.",
                    style = SolarType.caption,
                    color = colors.ink2
                )
                Text(
                    "Solarman's OpenAPI FAQ ›",
                    style = SolarType.bodyStrong,
                    color = colors.grid,
                    modifier = Modifier.padding(top = 8.dp).clickable { system?.openUrl(OPENAPI_HELP) }
                )
            }
        }

        error?.let {
            Text(it, style = SolarType.caption, color = colors.down)
        }

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
                modifier = Modifier.fillMaxWidth().padding(top = 6.dp)
            )
            if (busy) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                    CircularProgressIndicator(color = colors.ok, strokeWidth = 2.dp, modifier = Modifier.size(22.dp))
                }
            }
        } else {
            if (list.isNotEmpty()) {
                SectionLabel("Inverters on this account", modifier = Modifier.padding(top = 6.dp))
                list.forEach { device ->
                    val selected = chosen == device.sn
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(16.dp))
                            .background(if (selected) colors.ok.copy(alpha = 0.10f) else colors.track)
                            .border(1.dp, if (selected) colors.ok else colors.hairline, RoundedCornerShape(16.dp))
                            .clickable(role = Role.RadioButton) { chosen = device.sn }
                            .padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(device.sn, style = SolarType.bodyStrong, color = colors.ink)
                            Text(
                                device.station + when (device.online) {
                                    true -> " · online"
                                    false -> " · offline"
                                    null -> ""
                                },
                                style = SolarType.caption,
                                color = colors.ink3
                            )
                        }
                        if (selected) Icon(Icons.Check, null, tint = colors.ok, modifier = Modifier.size(20.dp))
                    }
                }
            }
        }

        if (devices != null) {
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
                modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                color = colors.ok,
                textColor = colors.onAccent
            )
        }
    }
}

@Composable
private fun Permissions(vm: MainViewModel, system: SystemActions, onNext: () -> Unit) {
    val colors = Solar.colors
    val p by vm.permissions.collectAsStateWithLifecycle()

    Text("Let it wake you", style = SolarType.hero, color = colors.ink, modifier = Modifier.padding(top = 8.dp))
    Text(
        "Android tries hard to stop apps working in the background. Three switches keep the alarm reliable.",
        style = SolarType.body,
        color = colors.ink2,
        modifier = Modifier.padding(top = 8.dp, bottom = 18.dp)
    )

    Panel {
        PermissionRow("Notifications", "So any alert can reach you", p.notifications, system::requestNotifications)
        PermissionRow("Full-screen alarm", "Lets the alarm take over a locked screen", p.fullScreen, system::openFullScreenSettings)
        PermissionRow("Run in background", "Keeps checking while the phone sleeps", p.unrestricted, system::requestUnrestricted)
        PermissionRow("Precise timing", "Optional, for checks right after each upload", p.exactAlarms, system::openExactAlarmSettings)
    }

    Spacer(Modifier.height(20.dp))
    PrimaryButton(if (p.blocking == 0) "Continue" else "Continue anyway", onNext, Modifier.fillMaxWidth())
}

@Composable
private fun PermissionRow(title: String, subtitle: String, granted: Boolean, onGrant: () -> Unit) {
    val colors = Solar.colors
    SettingRow(
        title = title,
        subtitle = subtitle,
        icon = if (granted) Icons.Check else Icons.Shield,
        iconTint = if (granted) colors.ok else colors.warn,
        onClick = if (granted) null else onGrant
    ) {
        if (!granted) {
            Box(
                Modifier
                    .clip(CircleShape)
                    .background(colors.ink)
                    .clickable(onClick = onGrant)
                    .padding(horizontal = 14.dp, vertical = 8.dp)
            ) {
                Text("Allow", style = SolarType.bodyStrong, color = colors.bg)
            }
        }
    }
}

@Composable
private fun Ready(state: UiState, onStart: () -> Unit, onLater: () -> Unit) {
    val colors = Solar.colors
    Spacer(Modifier.height(40.dp))
    Box(
        Modifier
            .size(84.dp)
            .clip(CircleShape)
            .background(colors.ok.copy(alpha = 0.14f)),
        contentAlignment = Alignment.Center
    ) {
        Icon(Icons.Check, null, tint = colors.ok, modifier = Modifier.size(40.dp))
    }
    Spacer(Modifier.height(24.dp))
    Text("You're set.", style = SolarType.display, color = colors.ink)
    Text(
        if (state.settings.demoMode) {
            "You're exploring a simulated system with two weeks of history. Connect your own inverter any time in Settings."
        } else {
            "Monitoring runs quietly in the background with a small ongoing notification. Outages ring; everything else just notifies."
        },
        style = SolarType.body,
        color = colors.ink2,
        modifier = Modifier.padding(top = 10.dp)
    )
    Spacer(Modifier.height(32.dp))
    PrimaryButton("Start monitoring", onStart, Modifier.fillMaxWidth(), color = colors.ok, textColor = colors.onAccent, icon = Icons.Live)
    Spacer(Modifier.height(10.dp))
    GhostButton("Just look around", onLater, Modifier.fillMaxWidth())
}
