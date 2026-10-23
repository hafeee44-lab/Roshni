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
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
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
import io.github.hafeee44lab.solargrid.domain.RuntimeEstimator
import io.github.hafeee44lab.solargrid.domain.StateRole
import io.github.hafeee44lab.solargrid.domain.buildHomeUi
import io.github.hafeee44lab.solargrid.service.AlarmPlayer
import io.github.hafeee44lab.solargrid.service.Notifier
import io.github.hafeee44lab.solargrid.ui.components.Icons
import io.github.hafeee44lab.solargrid.ui.components.RollingText
import io.github.hafeee44lab.solargrid.ui.components.SectionLabel
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

/**
 * The full-screen alarm. It only shows the alarm — the sound belongs to the service — so
 * stopping here, from the notification, or by the grid coming back all end the same ring.
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
                AlarmScreen(
                    kind = kind,
                    test = test,
                    snapshot = snapshot,
                    onStop = ::stop
                )
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
    val colors = Solar.colors
    val ui = buildHomeUi(snapshot, running = true)
    val restored = !test && kind == AlertKind.OUTAGE && ui.heroRole == StateRole.UP
    val ringing by AlarmPlayer.ringing.collectAsState()

    // The grid came back while this was on screen: say so, then get out of the way.
    LaunchedEffect(restored) {
        if (restored) {
            AlarmPlayer.stop()
            delay(4000)
            onStop()
        }
    }

    val tone by animateColorAsState(if (restored) colors.ok else colors.down, tween(800), label = "tone")
    val since = snapshot.outageSince.takeIf { it > 0L }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            now = System.currentTimeMillis()
            delay(1000L - now % 1000L)
        }
    }
    val clock = SimpleDateFormat("HH:mm", Locale.getDefault())

    Box(Modifier.fillMaxSize().background(Color(0xFF0B0306))) {
        ShockWaves(tone, active = ringing || !restored)

        Column(
            Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .padding(horizontal = 26.dp, vertical = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(clock.format(Date(now)), style = SolarType.display, color = colors.ink.copy(alpha = 0.9f), modifier = Modifier.padding(top = 18.dp))
            SectionLabel(
                when {
                    test -> "Test · nothing is wrong"
                    restored -> "Mains are back"
                    since != null -> "Mains lost at ${clock.format(Date(since))}"
                    else -> "Mains lost"
                },
                color = tone
            )

            Spacer(Modifier.weight(1f))

            Box(
                Modifier
                    .size(112.dp)
                    .clip(CircleShape)
                    .background(tone.copy(alpha = 0.16f))
                    .border(1.5.dp, tone.copy(alpha = 0.7f), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    if (restored) Icons.Live else if (kind == AlertKind.CRITICAL_BATTERY) Icons.Battery else Icons.Pylon,
                    null,
                    tint = tone,
                    modifier = Modifier.size(48.dp)
                )
            }

            AnimatedContent(
                targetState = restored,
                transitionSpec = { fadeIn(tween(500)) togetherWith fadeOut(tween(300)) },
                label = "title"
            ) { back ->
                Text(
                    when {
                        back -> "Grid restored"
                        test -> "Test alarm"
                        kind == AlertKind.CRITICAL_BATTERY -> "Battery critical"
                        else -> "Grid down"
                    },
                    style = SolarType.display.copy(fontSize = SolarType.display.fontSize * 1.25f),
                    color = colors.ink,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = 26.dp)
                )
            }
            Text(
                when {
                    restored -> "Everything is back on mains power."
                    test -> "This is what an outage looks and sounds like."
                    else -> "Your home is running on the battery."
                },
                style = SolarType.body.copy(fontSize = SolarType.body.fontSize * 1.1f),
                color = colors.ink2,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 8.dp)
            )

            Row(Modifier.fillMaxWidth().padding(top = 34.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
                AlarmFact("Battery", "${ui.socText}%")
                AlarmFact("Lasts", snapshot.runtimeMinutes?.takeIf { it > 0 }?.let { RuntimeEstimator.describe(it) } ?: "—")
                AlarmFact(
                    "Out for",
                    since?.let {
                        val s = ((now - it) / 1000L).coerceAtLeast(0L)
                        "%d:%02d:%02d".format(s / 3600, (s % 3600) / 60, s % 60)
                    } ?: "—"
                )
            }

            Spacer(Modifier.weight(1f))

            SlideToStop(tone = tone, label = if (ringing) "Slide to stop the alarm" else "Slide to close", onStop = onStop)
            Text(
                if (ringing) "Stops by itself after 90 seconds" else "Alarm stopped",
                style = SolarType.caption,
                color = colors.ink3,
                modifier = Modifier.padding(top = 12.dp)
            )
        }
    }
}

@Composable
private fun AlarmFact(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        SectionLabel(label)
        RollingText(value, SolarType.number, Solar.colors.ink, modifier = Modifier.padding(top = 4.dp))
    }
}

/** Rings expanding from the centre, like a shock wave — urgent without flashing. */
@Composable
private fun ShockWaves(tone: Color, active: Boolean) {
    val still = Solar.reduceMotion
    val transition = rememberInfiniteTransition(label = "waves")
    val t by transition.animateFloat(0f, 1f, infiniteRepeatable(tween(2600, easing = LinearEasing)), label = "t")
    val glow by transition.animateFloat(0.55f, 1f, infiniteRepeatable(tween(1300, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "glow")

    Canvas(Modifier.fillMaxSize()) {
        val center = Offset(size.width / 2, size.height * 0.42f)
        val max = size.maxDimension * 0.75f
        drawRect(
            Brush.radialGradient(
                listOf(tone.copy(alpha = 0.38f * (if (still) 0.8f else glow)), Color.Transparent),
                center = center,
                radius = max * 0.9f
            )
        )
        if (!active || still) return@Canvas
        for (i in 0 until 4) {
            val p = (t + i / 4f) % 1f
            drawCircle(
                color = tone.copy(alpha = 0.45f * (1f - p)),
                radius = 60.dp.toPx() + max * p,
                center = center,
                style = Stroke(width = (2.5f * (1f - p) + 0.5f).dp.toPx())
            )
        }
    }
}

/** A deliberate gesture so a pocket or a pillow can't silence the alarm by accident. */
@Composable
private fun SlideToStop(tone: Color, label: String, onStop: () -> Unit) {
    val colors = Solar.colors
    val haptics = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val offset = remember { Animatable(0f) }
    val shimmer = rememberInfiniteTransition(label = "shimmer")
    val hint by shimmer.animateFloat(0.35f, 1f, infiniteRepeatable(tween(1100), RepeatMode.Reverse), label = "hint")

    BoxWithConstraints(
        Modifier
            .fillMaxWidth()
            .height(72.dp)
            .clip(CircleShape)
            .background(Color.White.copy(alpha = 0.07f))
            .border(1.dp, Color.White.copy(alpha = 0.12f), CircleShape)
            .semantics {
                contentDescription = "Stop alarm"
                onClick { onStop(); true }
            }
    ) {
        val thumb = 60.dp
        val travel = with(density) { (maxWidth - thumb - 12.dp).toPx() }
        val progress = (offset.value / travel).coerceIn(0f, 1f)

        Text(
            label,
            style = SolarType.bodyStrong,
            color = colors.ink.copy(alpha = hint * (1f - progress)),
            modifier = Modifier.align(Alignment.Center).padding(start = 40.dp)
        )

        Box(
            Modifier
                .padding(6.dp)
                .offset { IntOffset(offset.value.roundToInt(), 0) }
                .size(thumb)
                .clip(CircleShape)
                .background(tone)
                .graphicsLayer { scaleX = 1f + progress * 0.06f; scaleY = 1f + progress * 0.06f }
                .pointerInput(travel) {
                    detectHorizontalDragGestures(
                        onDragEnd = {
                            scope.launch {
                                if (offset.value > travel * 0.82f) {
                                    haptics.performHapticFeedback(HapticFeedbackType.Confirm)
                                    offset.animateTo(travel, tween(120))
                                    onStop()
                                } else {
                                    offset.animateTo(0f, spring(dampingRatio = 0.55f, stiffness = 400f))
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
            Icon(Icons.Chevron, null, tint = Color.White, modifier = Modifier.size(26.dp))
        }
    }
}
