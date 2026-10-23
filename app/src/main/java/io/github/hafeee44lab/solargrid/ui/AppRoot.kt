package io.github.hafeee44lab.solargrid.ui

import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsTopHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.hafeee44lab.solargrid.domain.StateRole
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
    HOME("Home", Icons.Home),
    HISTORY("History", Icons.Insights),
    SETTINGS("Settings", Icons.Settings)
}

enum class Page { DIAGNOSTICS }

@Composable
fun AppRoot(vm: MainViewModel, system: SystemActions) {
    val state by vm.state.collectAsStateWithLifecycle()
    val c = Solar.colors

    if (!state.loaded) {
        Box(Modifier.fillMaxSize().background(c.bg))
        return
    }

    CompositionLocalProvider(LocalSystem provides system) {
        AnimatedContent(
            targetState = state.settings.onboarded,
            transitionSpec = { fadeIn(tween(400)) togetherWith fadeOut(tween(250)) },
            label = "onboarding"
        ) { onboarded ->
            if (!onboarded) OnboardingScreen(vm = vm, state = state, system = system) else MainShell(vm, state, system)
        }
    }
}

@Composable
private fun MainShell(vm: MainViewModel, state: UiState, system: SystemActions) {
    val c = Solar.colors
    var tab by rememberSaveable { mutableStateOf(Tab.HOME) }
    var page by rememberSaveable { mutableStateOf<Page?>(null) }

    BackHandler(enabled = page != null) { page = null }
    BackHandler(enabled = page == null && tab != Tab.HOME) { tab = Tab.HOME }

    Box(Modifier.fillMaxSize().background(c.bg)) {
        Column(Modifier.fillMaxSize()) {
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
                // On tablets and unfolded foldables keep a readable width instead of stretching cards.
                AnimatedContent(
                    targetState = tab,
                    transitionSpec = { fadeIn(tween(220, delayMillis = 60)) togetherWith fadeOut(tween(120)) },
                    modifier = Modifier.widthIn(max = 720.dp).fillMaxSize(),
                    label = "tabs"
                ) { current ->
                    when (current) {
                        Tab.HOME -> LiveScreen(vm, state, system, onOpenHistory = { tab = Tab.HISTORY }, onOpenSettings = { tab = Tab.SETTINGS })
                        Tab.HISTORY -> InsightsScreen(vm, state, system)
                        Tab.SETTINGS -> SettingsScreen(vm, state, system, onOpenDiagnostics = { page = Page.DIAGNOSTICS })
                    }
                }
            }
            BottomBar(tab, onSelect = { tab = it }, alert = state.home.heroRole == StateRole.DOWN)
        }

        // Screens scroll under the transparent status bar; keep the clock on a clean ground.
        Box(Modifier.fillMaxWidth().windowInsetsTopHeight(WindowInsets.statusBars).background(c.bg))

        AnimatedVisibility(
            visible = page != null,
            enter = slideInHorizontally(tween(300, easing = FastOutSlowInEasing)) { it } + fadeIn(),
            exit = slideOutHorizontally(tween(240)) { it } + fadeOut()
        ) {
            Box(Modifier.fillMaxSize().background(c.bg)) {
                when (page) {
                    Page.DIAGNOSTICS -> DiagnosticsScreen(state = state, onBack = { page = null })
                    null -> Unit
                }
            }
        }
    }
}

@Composable
private fun BottomBar(current: Tab, onSelect: (Tab) -> Unit, alert: Boolean) {
    val c = Solar.colors
    val haptics = LocalHapticFeedback.current
    NavigationBar(containerColor = c.surface, tonalElevation = 0.dp) {
        Tab.entries.forEach { tab ->
            NavigationBarItem(
                selected = tab == current,
                onClick = {
                    if (tab != current) haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
                    onSelect(tab)
                },
                icon = {
                    BadgedBox(badge = { if (tab == Tab.HOME && alert) Badge(containerColor = c.down) }) {
                        Icon(tab.icon, contentDescription = null, modifier = Modifier.size(24.dp))
                    }
                },
                label = { Text(tab.label, style = SolarType.caption) },
                colors = NavigationBarItemDefaults.colors(
                    selectedIconColor = c.brand,
                    selectedTextColor = c.brand,
                    indicatorColor = c.brandContainer,
                    unselectedIconColor = c.ink2,
                    unselectedTextColor = c.ink2
                )
            )
        }
    }
}

/** Side gutters and a little bottom breathing room for every scrolling screen. */
fun screenPadding(): PaddingValues = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 24.dp)
