package io.github.hafeee44lab.solargrid.widget

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.LocalSize
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.LinearProgressIndicator
import androidx.glance.appwidget.SizeMode
import androidx.glance.action.actionStartActivity
import androidx.glance.appwidget.appWidgetBackground
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.updateAll
import androidx.glance.background
import androidx.glance.color.ColorProvider
import androidx.glance.unit.ColorProvider as GlanceColor
import androidx.glance.layout.Alignment
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import io.github.hafeee44lab.solargrid.MainActivity
import io.github.hafeee44lab.solargrid.R
import io.github.hafeee44lab.solargrid.data.AppGraph
import io.github.hafeee44lab.solargrid.domain.HomeUi
import io.github.hafeee44lab.solargrid.domain.StateRole
import io.github.hafeee44lab.solargrid.domain.buildHomeUi
import io.github.hafeee44lab.solargrid.domain.kw
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Home-screen widget. It reads the snapshot the monitor already saved, so it costs nothing
 * but a redraw — no polling of its own.
 */
class GridWidget : GlanceAppWidget() {

    override val sizeMode = SizeMode.Responsive(setOf(SMALL, TALL, WIDE, LARGE))

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val graph = AppGraph.get(context)
        val settings = graph.settings.load()
        val ui = buildHomeUi(graph.state(settings.profile).snapshot, settings.monitoring)

        provideContent { Content(ui) }
    }

    @Composable
    private fun Content(ui: HomeUi) {
        val size = LocalSize.current
        val accent = when (ui.heroRole) {
            StateRole.UP -> ColorProvider(day = Color(0xFF08875D), night = Color(0xFF2BD99F))
            StateRole.DOWN -> ColorProvider(day = Color(0xFFD9203F), night = Color(0xFFFF4D6A))
            StateRole.NEUTRAL -> ColorProvider(day = Color(0xFF626A79), night = Color(0xFF8590A2))
        }
        val ink = ColorProvider(day = Color(0xFF12161D), night = Color(0xFFEEF2F8))
        val muted = ColorProvider(day = Color(0xFF626A79), night = Color(0xFF8C97A8))
        val surface = ColorProvider(day = Color(0xFFFFFFFF), night = Color(0xFF0F141D))
        val track = ColorProvider(day = Color(0x14000000), night = Color(0x1FFFFFFF))
        val battery = ColorProvider(day = Color(0xFF6A55F2), night = Color(0xFF9B8CFF))
        val solar = ColorProvider(day = Color(0xFFA86400), night = Color(0xFFFFB23F))

        Column(
            modifier = GlanceModifier
                .fillMaxSize()
                .appWidgetBackground()
                .cornerRadius(24.dp)
                .background(surface)
                .padding(16.dp)
                .clickable(actionStartActivity<MainActivity>())
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = GlanceModifier.fillMaxWidth()) {
                Image(
                    provider = ImageProvider(if (ui.heroRole == StateRole.DOWN) R.drawable.ic_stat_outage else R.drawable.ic_stat_bolt),
                    contentDescription = null,
                    modifier = GlanceModifier.size(16.dp),
                    colorFilter = androidx.glance.ColorFilter.tint(accent)
                )
                Spacer(GlanceModifier.width(6.dp))
                Text(
                    text = ui.heroState,
                    style = TextStyle(color = accent, fontSize = 14.sp, fontWeight = FontWeight.Bold),
                    maxLines = 1
                )
            }

            Spacer(GlanceModifier.height(8.dp))

            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    text = ui.socText,
                    style = TextStyle(color = ink, fontSize = if (size.height >= TALL.height) 38.sp else 32.sp, fontWeight = FontWeight.Bold)
                )
                Text(
                    text = "%",
                    style = TextStyle(color = muted, fontSize = 15.sp, fontWeight = FontWeight.Medium),
                    modifier = GlanceModifier.padding(start = 2.dp, bottom = 5.dp)
                )
                if (size.width >= WIDE.width) {
                    Spacer(GlanceModifier.defaultWeight())
                    Column(horizontalAlignment = Alignment.End) {
                        Text(
                            text = runtimeLine(ui),
                            style = TextStyle(color = ink, fontSize = 13.sp, fontWeight = FontWeight.Medium),
                            maxLines = 1
                        )
                        Text(text = ui.freshPrimary, style = TextStyle(color = muted, fontSize = 11.sp), maxLines = 1)
                    }
                }
            }

            Spacer(GlanceModifier.height(6.dp))
            LinearProgressIndicator(
                progress = ui.socFraction,
                modifier = GlanceModifier.fillMaxWidth().height(6.dp).cornerRadius(3.dp),
                color = if (ui.heroRole == StateRole.DOWN) accent else battery,
                backgroundColor = track
            )

            if (size.width < WIDE.width) {
                Spacer(GlanceModifier.height(6.dp))
                Text(text = runtimeLine(ui), style = TextStyle(color = muted, fontSize = 11.sp), maxLines = 1)
            }

            if (size.height >= TALL.height) {
                Spacer(GlanceModifier.height(12.dp))
                Row(modifier = GlanceModifier.fillMaxWidth()) {
                    Metric("SOLAR", if (ui.trusted) kw(ui.powers.solarW) else "—", solar, muted, GlanceModifier.defaultWeight())
                    Metric("HOME", ui.loadLabel, ink, muted, GlanceModifier.defaultWeight())
                    if (size.width >= WIDE.width) {
                        Metric(
                            if (ui.heroRole == StateRole.DOWN) "SINCE" else "GRID",
                            if (ui.heroRole == StateRole.DOWN) sinceLabel(ui) else ui.gridLabel,
                            accent,
                            muted,
                            GlanceModifier.defaultWeight()
                        )
                    }
                }
            }
        }
    }

    @Composable
    private fun Metric(label: String, value: String, tone: GlanceColor, muted: GlanceColor, modifier: GlanceModifier) {
        Column(modifier = modifier) {
            Text(label, style = TextStyle(color = muted, fontSize = 10.sp, fontWeight = FontWeight.Medium))
            Text(value, style = TextStyle(color = tone, fontSize = 14.sp, fontWeight = FontWeight.Bold), maxLines = 1)
        }
    }

    /** "left" only while the battery is actually carrying the house; otherwise it is backup time. */
    private fun runtimeLine(ui: HomeUi): String = when {
        ui.runtime != null && ui.heroRole == StateRole.DOWN -> "${ui.runtime} left"
        ui.fullIn != null -> "full in ${ui.fullIn}"
        ui.runtime != null -> "${ui.runtime} backup"
        else -> ui.freshPrimary
    }

    private fun sinceLabel(ui: HomeUi): String =
        ui.outageSince?.let { java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault()).format(java.util.Date(it)) } ?: "—"

    companion object {
        val SMALL = DpSize(110.dp, 110.dp)
        val TALL = DpSize(140.dp, 150.dp)
        val WIDE = DpSize(220.dp, 110.dp)
        val LARGE = DpSize(220.dp, 150.dp)
    }
}

class GridWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = GridWidget()
}

object Widgets {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    fun refresh(context: Context) {
        val app = context.applicationContext
        scope.launch {
            try {
                GridWidget().updateAll(app)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }
}
