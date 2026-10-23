package io.github.hafeee44lab.solargrid.widget

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.LocalContext
import androidx.glance.LocalSize
import androidx.glance.action.actionStartActivity
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.LinearProgressIndicator
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.appWidgetBackground
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.updateAll
import androidx.glance.background
import androidx.glance.color.ColorProvider
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
import androidx.glance.unit.ColorProvider as GlanceColor
import io.github.hafeee44lab.solargrid.MainActivity
import io.github.hafeee44lab.solargrid.R
import io.github.hafeee44lab.solargrid.data.AppGraph
import io.github.hafeee44lab.solargrid.data.changes
import io.github.hafeee44lab.solargrid.data.phrases
import io.github.hafeee44lab.solargrid.domain.HomeUi
import io.github.hafeee44lab.solargrid.domain.StateRole
import io.github.hafeee44lab.solargrid.domain.buildHomeUi
import io.github.hafeee44lab.solargrid.domain.kw
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Home-screen widget. It reads the snapshot the monitor already saved, so it costs nothing
 * but a redraw — no polling of its own.
 */
class GridWidget : GlanceAppWidget() {

    override val sizeMode = SizeMode.Responsive(setOf(SMALL, TALL, WIDE, LARGE))

    @OptIn(ExperimentalCoroutinesApi::class)
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val graph = AppGraph.get(context)
        val settings = graph.settings.load()
        val initial = buildHomeUi(graph.state(settings.profile).snapshot, settings.monitoring, p = context.phrases())

        // Glance keeps a session alive between updates, so the reading has to be observed, not
        // read once: otherwise every refresh in that window redraws the first snapshot.
        val readings = graph.settings.prefs.changes()
            .map { graph.settings.load() }
            .flatMapLatest { s ->
                graph.state(s.profile).prefs.changes().map { buildHomeUi(graph.state(s.profile).snapshot, s.monitoring, p = context.phrases()) }
            }

        provideContent {
            val ui by readings.collectAsState(initial)
            Content(ui)
        }
    }

    @Composable
    private fun Content(ui: HomeUi) {
        val size = LocalSize.current
        val dot = when (ui.heroRole) {
            StateRole.UP -> ColorProvider(day = Color(0xFF1B8A4E), night = Color(0xFF5CC98C))
            StateRole.DOWN -> ColorProvider(day = Color(0xFFC62828), night = Color(0xFFF2726A))
            StateRole.NEUTRAL -> ColorProvider(day = Color(0xFF6A7180), night = Color(0xFF959CA9))
        }
        val down = ui.heroRole == StateRole.DOWN
        val ink = if (down) ColorProvider(day = Color(0xFF7A1212), night = Color(0xFFFFD9D6)) else ColorProvider(day = Color(0xFF16191F), night = Color(0xFFE7E9EE))
        val muted = if (down) ColorProvider(day = Color(0xFF7A1212), night = Color(0xFFFFD9D6)) else ColorProvider(day = Color(0xFF6A7180), night = Color(0xFF959CA9))
        val surface = if (down) ColorProvider(day = Color(0xFFFCEBEA), night = Color(0xFF3A1D1D)) else ColorProvider(day = Color(0xFFFFFFFF), night = Color(0xFF1B1F26))
        val track = ColorProvider(day = Color(0xFFEDF0F4), night = Color(0xFF262B34))
        val soc = ui.soc
        val battery = when {
            soc == null -> ColorProvider(day = Color(0xFF6A7180), night = Color(0xFF959CA9))
            soc <= 20 -> ColorProvider(day = Color(0xFFC62828), night = Color(0xFFF2726A))
            soc < 50 -> ColorProvider(day = Color(0xFFB7791F), night = Color(0xFFE6B45C))
            else -> ColorProvider(day = Color(0xFF1B8A4E), night = Color(0xFF5CC98C))
        }

        Column(
            modifier = GlanceModifier
                .fillMaxSize()
                .appWidgetBackground()
                .cornerRadius(20.dp)
                .background(surface)
                .padding(16.dp)
                .clickable(actionStartActivity<MainActivity>())
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = GlanceModifier.fillMaxWidth()) {
                Image(
                    provider = ImageProvider(if (down) R.drawable.ic_stat_home_off else R.drawable.ic_stat_home),
                    contentDescription = null,
                    modifier = GlanceModifier.size(16.dp),
                    colorFilter = androidx.glance.ColorFilter.tint(dot)
                )
                Spacer(GlanceModifier.width(6.dp))
                Text(ui.heroState, style = TextStyle(color = ink, fontSize = 14.sp, fontWeight = FontWeight.Medium), maxLines = 1)
            }

            Spacer(GlanceModifier.height(8.dp))

            Row(verticalAlignment = Alignment.Bottom) {
                Text(ui.socText, style = TextStyle(color = ink, fontSize = if (size.height >= TALL.height) 40.sp else 32.sp))
                Text("%", style = TextStyle(color = muted, fontSize = 14.sp), modifier = GlanceModifier.padding(start = 2.dp, bottom = 6.dp))
                if (size.width >= WIDE.width) {
                    Spacer(GlanceModifier.defaultWeight())
                    Column(horizontalAlignment = Alignment.End) {
                        Text(runtimeLine(LocalContext.current, ui), style = TextStyle(color = ink, fontSize = 13.sp), maxLines = 1)
                        Text(ui.freshPrimary, style = TextStyle(color = muted, fontSize = 11.sp), maxLines = 1)
                    }
                }
            }

            Spacer(GlanceModifier.height(6.dp))
            LinearProgressIndicator(
                progress = ui.socFraction,
                modifier = GlanceModifier.fillMaxWidth().height(6.dp).cornerRadius(3.dp),
                color = battery,
                backgroundColor = track
            )

            if (size.width < WIDE.width) {
                Spacer(GlanceModifier.height(6.dp))
                Text(runtimeLine(LocalContext.current, ui), style = TextStyle(color = muted, fontSize = 11.sp), maxLines = 1)
            }

            if (size.height >= TALL.height) {
                Spacer(GlanceModifier.height(12.dp))
                Row(modifier = GlanceModifier.fillMaxWidth()) {
                    val context = LocalContext.current
                    Metric(context.getString(R.string.solar), if (ui.trusted) kw(ui.powers.solarW) else "—", ink, muted, GlanceModifier.defaultWeight())
                    Metric(context.getString(R.string.home), ui.loadLabel, ink, muted, GlanceModifier.defaultWeight())
                    if (size.width >= WIDE.width) {
                        Metric(context.getString(if (down) R.string.off_since else R.string.grid), if (down) sinceLabel(ui) else ui.gridLabel, ink, muted, GlanceModifier.defaultWeight())
                    }
                }
            }
        }
    }

    @Composable
    private fun Metric(label: String, value: String, tone: GlanceColor, muted: GlanceColor, modifier: GlanceModifier) {
        Column(modifier = modifier) {
            Text(label, style = TextStyle(color = muted, fontSize = 11.sp))
            Text(value, style = TextStyle(color = tone, fontSize = 14.sp, fontWeight = FontWeight.Medium), maxLines = 1)
        }
    }

    /** "left" only while the battery is actually carrying the house; otherwise it is backup time. */
    private fun runtimeLine(context: Context, ui: HomeUi): String = when {
        ui.runtime != null && ui.heroRole == StateRole.DOWN -> context.getString(R.string.widget_left, ui.runtime)
        ui.fullIn != null -> context.getString(R.string.widget_full_in, ui.fullIn)
        ui.runtime != null -> context.getString(R.string.widget_backup, ui.runtime)
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
