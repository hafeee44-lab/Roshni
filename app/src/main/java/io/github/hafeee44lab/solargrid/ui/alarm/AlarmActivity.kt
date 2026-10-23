package io.github.hafeee44lab.solargrid.ui.alarm

import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import io.github.hafeee44lab.solargrid.data.AppGraph
import io.github.hafeee44lab.solargrid.data.SolarSnapshot
import io.github.hafeee44lab.solargrid.data.ThemeMode
import io.github.hafeee44lab.solargrid.data.changes
import io.github.hafeee44lab.solargrid.domain.AlertKind
import io.github.hafeee44lab.solargrid.domain.AlertSettings
import io.github.hafeee44lab.solargrid.domain.RuntimeEstimator
import io.github.hafeee44lab.solargrid.domain.StateRole
import io.github.hafeee44lab.solargrid.domain.buildHomeUi
import io.github.hafeee44lab.solargrid.service.AlarmPlayer
import io.github.hafeee44lab.solargrid.service.Notifier
import io.github.hafeee44lab.solargrid.ui.components.Icons
import io.github.hafeee44lab.solargrid.ui.components.RollingText
import io.github.hafeee44lab.solargrid.ui.theme.Solar
import io.github.hafeee44lab.solargrid.ui.theme.SolarTheme
import io.github.hafeee44lab.solargrid.ui.theme.SolarType
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

private val AlarmRed = Color(0xFF8E1B1B)
private val AlarmRedLift = Color(0xFF9E2525)
private val RestoredGreen = Color(0xFF1B6B43)
private val OnAlarm = Color(0xFFFFFFFF)
private val OnAlarmSoft = Color(0xD9FFFFFF)

/**
 * The full-screen alarm. It only shows the alarm — the sound belongs to the service — so
 * stopping here, from the notification, or by the mains coming back all end the same ring.
 */
class AlarmActivity : ComponentActivity() {

    companion object {
        const val EXTRA_KIND = "kind"
        const val EXTRA_TEST = "test"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        showOverLockScreen()

        val graph = AppGraph.get(this)
        val kind = intent?.getStringExtra(EXTRA_KIND)?.let { k -> AlertKind.entries.firstOrNull { it.name == k } } ?: AlertKind.OUTAGE
        val test = intent?.getBooleanExtra(EXTRA_TEST, false) == true
        val state = graph.state()
        val snapshots = state.prefs.changes().map { state.snapshot }

        setContent {
            val snapshot by snapshots.collectAsState(initial = state.snapshot)
            SolarTheme(mode = ThemeMode.DARK) {
                AlarmScreen(kind = kind, test = test, snapshot = snapshot, onStop = ::stop)
            }
        }
    }

    private fun stop() {
        AlarmPlayer.stop()
        Notifier.cancelAlarm(this)
        finish()
    }

    private fun showOverLockScreen() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON)
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }
}

@Composable
private fun AlarmScreen(kind: AlertKind, test: Boolean, snapshot: SolarSnapshot, onStop: () -> Unit) {
    val ui = buildHomeUi(snapshot, running = true)
    val restored = !test && kind == AlertKind.OUTAGE && ui.heroRole == StateRole.UP
    val ringing by AlarmPlayer.ringing.collectAsState()
    val ringMs by AlarmPlayer.durationMs.collectAsState()
    val still = Solar.reduceMotion

    // The mains came back while this was on screen: say so, then get out of the way.
    LaunchedEffect(restored) {
        if (restored) {
            AlarmPlayer.stop()
            delay(4000)
            onStop()
        }
    }

    // A slow, small breath in the red while it rings — present, never a strobe.
    val breath by rememberInfiniteTransition(label = "breath").animateFloat(0f, 1f, infiniteRepeatable(tween(1800, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "b")
    val ground by animateColorAsState(if (restored) RestoredGreen else AlarmRed, tween(800), label = "ground")

    val since = snapshot.outageSince.takeIf { it > 0L }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            now = System.currentTimeMillis()
            delay(1000L - now % 1000L)
        }
    }
    val clock = remember { SimpleDateFormat("HH:mm", Locale.getDefault()) }

    Box(
        Modifier
            .fillMaxSize()
            // Read in the draw phase so the breath repaints without recomposing the screen.
            .drawBehind { drawRect(if (restored || still || !ringing) ground else lerp(ground, AlarmRedLift, breath)) }
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .padding(horizontal = 24.dp, vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(clock.format(Date(now)), style = SolarType.figureL, color = OnAlarmSoft, modifier = Modifier.padding(top = 24.dp))
            Text(
                when {
                    test -> "Test alarm · nothing is wrong"
                    restored -> "The mains are back"
                    since != null -> "Mains went off at ${clock.format(Date(since))}"
                    else -> "The mains went off"
                },
                style = SolarType.body,
                color = OnAlarmSoft
            )

            Spacer(Modifier.weight(1f))

            Icon(
                if (restored) Icons.Check else if (kind == AlertKind.CRITICAL_BATTERY) Icons.Battery else Icons.Tower,
                null,
                tint = OnAlarm,
                modifier = Modifier.size(44.dp)
            )
            AnimatedContent(restored, transitionSpec = { fadeIn(tween(400)) togetherWith fadeOut(tween(250)) }, label = "title") { back ->
                Text(
                    when {
                        back -> "Mains back on"
                        test -> "Test alarm"
                        kind == AlertKind.CRITICAL_BATTERY -> "Battery critical"
                        else -> "Mains off"
                    },
                    style = SolarType.figureXL,
                    color = OnAlarm,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = 16.dp)
                )
            }
            Text(
                when {
                    restored -> "Everything is back on mains power."
                    test -> "This is what a cut looks and sounds like."
                    else -> "Your home is running on the battery."
                },
                style = SolarType.body,
                color = OnAlarmSoft,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 6.dp)
            )

            Row(Modifier.fillMaxWidth().padding(top = 32.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
                AlarmFact("Battery", "${ui.socText}%")
                AlarmFact("Time left", snapshot.runtimeMinutes?.takeIf { it > 0 }?.let { RuntimeEstimator.describe(it) } ?: "—")
                AlarmFact(
                    "Off for",
                    since?.let {
                        val s = ((now - it) / 1000L).coerceAtLeast(0L)
                        "%d:%02d:%02d".format(s / 3600, (s % 3600) / 60, s % 60)
                    } ?: "—"
                )
            }

            Spacer(Modifier.weight(1f))

            SlideToStop(if (ringing) "Slide to stop the alarm" else "Slide to close", onStop)
            Text(
                when {
                    !ringing -> "Alarm stopped"
                    ringMs <= 0 -> "Rings until you stop it or the mains return"
                    else -> "Stops by itself after ${AlertSettings.describeRing((ringMs / 1000).toInt())}"
                },
                style = SolarType.caption,
                color = OnAlarmSoft,
                modifier = Modifier.padding(top = 12.dp)
            )
        }
    }
}

@Composable
private fun AlarmFact(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        RollingText(value, SolarType.figure, OnAlarm)
        Text(label, style = SolarType.caption, color = OnAlarmSoft)
    }
}

/** A deliberate gesture, so a pocket or a pillow can't silence the alarm by accident. */
@Composable
private fun SlideToStop(label: String, onStop: () -> Unit) {
    val haptics = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val offset = remember { Animatable(0f) }

    BoxWithConstraints(
        Modifier
            .fillMaxWidth()
            .height(64.dp)
            .clip(CircleShape)
            .background(Color(0x33FFFFFF))
            .semantics {
                contentDescription = "Stop alarm"
                onClick { onStop(); true }
            }
    ) {
        val thumb = 56.dp
        val travel = with(density) { (maxWidth - thumb - 8.dp).toPx() }
        val progress = (offset.value / travel).coerceIn(0f, 1f)

        Text(label, style = SolarType.bodyStrong, color = OnAlarm.copy(alpha = 1f - progress), modifier = Modifier.align(Alignment.Center).padding(start = 32.dp))

        Box(
            Modifier
                .padding(4.dp)
                .offset { IntOffset(offset.value.roundToInt(), 0) }
                .size(thumb)
                .clip(CircleShape)
                .background(OnAlarm)
                .pointerInput(travel) {
                    detectHorizontalDragGestures(
                        onDragEnd = {
                            scope.launch {
                                if (offset.value > travel * 0.8f) {
                                    haptics.performHapticFeedback(HapticFeedbackType.Confirm)
                                    offset.animateTo(travel, tween(120))
                                    onStop()
                                } else {
                                    offset.animateTo(0f, spring(dampingRatio = 0.6f, stiffness = 400f))
                                }
                            }
                        },
                        onHorizontalDrag = { change, delta ->
                            change.consume()
                            scope.launch { offset.snapTo((offset.value + delta).coerceIn(0f, travel)) }
                        }
                    )
                },
            contentAlignment = Alignment.Center
        ) {
            Icon(Icons.Chevron, null, tint = AlarmRed, modifier = Modifier.size(26.dp))
        }
    }
}
