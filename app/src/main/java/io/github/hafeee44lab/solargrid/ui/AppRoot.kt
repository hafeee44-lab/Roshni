package io.github.hafeee44lab.solargrid.ui

import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsTopHeight
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.hafeee44lab.solargrid.domain.StateRole
import io.github.hafeee44lab.solargrid.ui.components.Atmosphere
import io.github.hafeee44lab.solargrid.ui.components.Icons
import io.github.hafeee44lab.solargrid.ui.insights.InsightsScreen
import io.github.hafeee44lab.solargrid.ui.live.LiveScreen
import io.github.hafeee44lab.solargrid.ui.onboarding.OnboardingScreen
import io.github.hafeee44lab.solargrid.ui.settings.DiagnosticsScreen
import io.github.hafeee44lab.solargrid.ui.settings.SettingsScreen
import io.github.hafeee44lab.solargrid.ui.theme.Solar
import io.github.hafeee44lab.solargrid.ui.theme.SolarType

/** Things only the activity can do: permission prompts and system settings screens. */
interface SystemActions {
    fun requestNotifications()
    fun openNotificationSettings()
    fun openFullScreenSettings()
    fun requestUnrestricted()
    fun openExactAlarmSettings()
    fun openUrl(url: String)
    fun share(intent: Intent)
}

val LocalSystem = staticCompositionLocalOf<SystemActions?> { null }

enum class Tab(val label: String, val icon: ImageVector) {
    LIVE("Live", Icons.Live),
    INSIGHTS("Insights", Icons.Insights),
    SETTINGS("Settings", Icons.Settings)
}

enum class Page { DIAGNOSTICS }

/** Bottom padding every scrolling screen needs so its last card clears the floating nav bar. */
val NavClearance = 104.dp

@Composable
fun AppRoot(vm: MainViewModel, system: SystemActions) {
    val state by vm.state.collectAsStateWithLifecycle()
    val colors = Solar.colors

    if (!state.loaded) {
        Atmosphere(tint = colors.ink3, intensity = 0.4f, pulse = false)
        return
    }

    CompositionLocalProvider(LocalSystem provides system) {
    AnimatedContent(
        targetState = state.settings.onboarded,
        transitionSpec = { fadeIn(tween(500)) togetherWith fadeOut(tween(300)) },
        label = "onboarding"
    ) { onboarded ->
        if (!onboarded) {
            OnboardingScreen(vm = vm, state = state, system = system)
        } else {
            MainShell(vm, state, system)
        }
    }
    }
}

@Composable
private fun MainShell(vm: MainViewModel, state: UiState, system: SystemActions) {
    val colors = Solar.colors
    var tab by rememberSaveable { mutableStateOf(Tab.LIVE) }
    var page by rememberSaveable { mutableStateOf<Page?>(null) }

    BackHandler(enabled = page != null) { page = null }
    BackHandler(enabled = page == null && tab != Tab.LIVE) { tab = Tab.LIVE }

    val role = state.home.heroRole
    val tint = when (role) {
        StateRole.UP -> colors.ok
        StateRole.DOWN -> colors.down
        StateRole.NEUTRAL -> colors.ink3
    }

    Box(Modifier.fillMaxSize()) {
        Atmosphere(
            tint = tint,
            intensity = if (tab == Tab.LIVE) (if (role == StateRole.DOWN) 1f else 0.7f) else 0.35f,
            pulse = role == StateRole.DOWN && tab == Tab.LIVE
        )

        // On tablets and unfolded foldables, keep reading width sensible instead of stretching cards edge to edge.
        AnimatedContent(
            targetState = tab,
            modifier = Modifier.align(Alignment.TopCenter).widthIn(max = 720.dp).fillMaxSize(),
            transitionSpec = {
                (fadeIn(tween(260, delayMillis = 60)) + scaleIn(tween(320, easing = FastOutSlowInEasing), initialScale = 0.965f)) togetherWith
                    fadeOut(tween(140))
            },
            label = "tabs"
        ) { current ->
            when (current) {
                Tab.LIVE -> LiveScreen(vm, state, system, onOpenInsights = { tab = Tab.INSIGHTS }, onOpenSettings = { tab = Tab.SETTINGS })
                Tab.INSIGHTS -> InsightsScreen(vm, state, system)
                Tab.SETTINGS -> SettingsScreen(vm, state, system, onOpenDiagnostics = { page = Page.DIAGNOSTICS })
            }
        }

        // Content scrolls under the transparent status bar; a soft scrim keeps the clock legible.
        Box(
            Modifier
                .fillMaxWidth()
                .windowInsetsTopHeight(WindowInsets.statusBars)
                .background(Brush.verticalGradient(listOf(colors.bg, colors.bg.copy(alpha = 0.85f))))
        )

        NavBar(
            current = tab,
            onSelect = { tab = it },
            alert = role == StateRole.DOWN,
            modifier = Modifier.align(Alignment.BottomCenter)
        )

        AnimatedVisibility(
            visible = page != null,
            enter = slideInHorizontally(tween(340, easing = FastOutSlowInEasing)) { it } + fadeIn(),
            exit = slideOutHorizontally(tween(280)) { it } + fadeOut()
        ) {
            Box(Modifier.fillMaxSize().background(colors.bg)) {
                when (page) {
                    Page.DIAGNOSTICS -> DiagnosticsScreen(state = state, onBack = { page = null })
                    null -> Unit
                }
            }
        }
    }
}

/** Floating pill navigation with a sliding, springy highlight. */
@Composable
private fun NavBar(current: Tab, onSelect: (Tab) -> Unit, alert: Boolean, modifier: Modifier = Modifier) {
    val colors = Solar.colors
    val haptics = LocalHapticFeedback.current
    val bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()

    Box(
        modifier
            .padding(bottom = bottom + 14.dp, start = 24.dp, end = 24.dp)
            .widthIn(max = 420.dp)
            .fillMaxWidth()
            .height(64.dp)
            .shadow(24.dp, CircleShape, ambientColor = colors.bgDeep, spotColor = colors.bgDeep)
            .clip(CircleShape)
            .background(colors.surfaceHigh.copy(alpha = 0.96f))
            .border(1.dp, colors.hairlineStrong, CircleShape)
            .padding(6.dp)
    ) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val itemW = maxWidth / Tab.entries.size
            val x by animateDpAsState(itemW * current.ordinal, spring(dampingRatio = 0.72f, stiffness = 420f), label = "nav")

            Box(
                Modifier
                    .offset(x = x)
                    .width(itemW)
                    .fillMaxHeight()
                    .clip(CircleShape)
                    .background(colors.ink.copy(alpha = if (colors.isDark) 0.09f else 0.06f))
            )

            Row(Modifier.fillMaxSize()) {
                Tab.entries.forEach { tab ->
                    val active = tab == current
                    val tint by animateColorAsState(if (active) colors.ink else colors.ink2, tween(250), label = "navTint")

                    Row(
                        Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .clip(CircleShape)
                            .semantics {
                                selected = active
                                contentDescription = tab.label
                            }
                            .clickable(role = Role.Tab) {
                                if (!active) haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
                                onSelect(tab)
                            },
                        horizontalArrangement = androidx.compose.foundation.layout.Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box {
                            Icon(tab.icon, contentDescription = null, tint = tint, modifier = Modifier.size(21.dp))
                            if (tab == Tab.LIVE && alert) {
                                Box(
                                    Modifier
                                        .align(Alignment.TopEnd)
                                        .offset(3.dp, (-2).dp)
                                        .size(8.dp)
                                        .clip(CircleShape)
                                        .background(colors.down)
                                )
                            }
                        }
                        AnimatedVisibility(
                            visible = active,
                            enter = fadeIn(tween(200)) + slideInVertically { it / 3 },
                            exit = fadeOut(tween(120)) + slideOutVertically { it / 3 }
                        ) {
                            Row {
                                Spacer(Modifier.width(8.dp))
                                Text(tab.label, style = SolarType.bodyStrong, color = tint)
                            }
                        }
                    }
                }
            }
        }
    }
}

/** Standard content padding for a tab: status bar on top, nav bar clearance below. */
@Composable
fun screenPadding(): PaddingValues {
    val bars = WindowInsets.navigationBars.asPaddingValues()
    return PaddingValues(start = 18.dp, end = 18.dp, bottom = NavClearance + bars.calculateBottomPadding())
}
