package io.github.hafeee44lab.solargrid.ui.settings

import android.content.ClipData
import android.content.ClipboardManager
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import io.github.hafeee44lab.solargrid.data.describeAge
import io.github.hafeee44lab.solargrid.ui.UiState
import io.github.hafeee44lab.solargrid.ui.components.GhostButton
import io.github.hafeee44lab.solargrid.ui.components.Icons
import io.github.hafeee44lab.solargrid.ui.components.Panel
import io.github.hafeee44lab.solargrid.ui.settings.SolarField
import io.github.hafeee44lab.solargrid.ui.theme.Solar
import io.github.hafeee44lab.solargrid.ui.theme.SolarType

/** Every key the inverter reported, searchable. The ground truth when a reading looks wrong. */
@Composable
fun DiagnosticsScreen(state: UiState, onBack: () -> Unit) {
    val colors = Solar.colors
    val context = LocalContext.current
    var query by remember { mutableStateOf("") }
    val lines = remember(state.dump, query) {
        state.dump.lines().filter { it.isNotBlank() && (query.isBlank() || it.contains(query, ignoreCase = true)) }
    }

    Column(
        Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.statusBars)
            .windowInsetsPadding(WindowInsets.navigationBars)
            .padding(horizontal = 18.dp)
    ) {
        Row(Modifier.fillMaxWidth().padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .background(colors.surface)
                    .clickable(onClick = onBack)
                    .semantics { contentDescription = "Back" },
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Back, null, tint = colors.ink, modifier = Modifier.size(20.dp))
            }
            Spacer(Modifier.width(12.dp))
            Text("Inverter data", style = SolarType.title, color = colors.ink, modifier = Modifier.weight(1f))
            GhostButton("Copy", onClick = {
                context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText("Inverter data", state.dump))
                // Android 13+ shows its own clipboard confirmation; don't double it.
                if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.TIRAMISU) {
                    Toast.makeText(context, "Copied", Toast.LENGTH_SHORT).show()
                }
            })
        }

        Text(
            "Every value from the last successful reading. Attach this when reporting a wrong detection — it is what decides when the grid is down.",
            style = SolarType.caption,
            color = colors.ink3,
            modifier = Modifier.padding(top = 12.dp)
        )

        Panel(Modifier.padding(top = 12.dp), padding = androidx.compose.foundation.layout.PaddingValues(16.dp)) {
            Fact("Decided by", state.snapshot.basis.ifBlank { "—" })
            Fact("Last check", describeAge(state.snapshot.ageMillis()))
            Fact("Inverter upload", describeAge(state.snapshot.inverterAgeMillis()))
            Fact("Solarman alarm flag", if (state.snapshot.inverterAlarm) "raised" else "clear")
            state.snapshot.error?.let { Fact("Last error", it) }
        }

        SolarField("Filter", query, { query = it }, modifier = Modifier.padding(top = 12.dp), placeholder = "grid, battery, PG_F1…")

        Panel(Modifier.padding(top = 12.dp, bottom = 16.dp).weight(1f), padding = androidx.compose.foundation.layout.PaddingValues(14.dp)) {
            Column(
                Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .horizontalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(3.dp)
            ) {
                if (lines.isEmpty()) {
                    Text(if (state.dump.isBlank()) "No successful reading yet." else "Nothing matches.", style = SolarType.body, color = colors.ink2)
                }
                lines.forEach {
                    Text(it, style = SolarType.caption.copy(fontFamily = FontFamily.Monospace), color = colors.ink, softWrap = false)
                }
                Spacer(Modifier.height(4.dp))
            }
        }
    }
}

@Composable
private fun Fact(key: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        Text(key, style = SolarType.caption, color = Solar.colors.ink3, modifier = Modifier.weight(1f))
        Text(value, style = SolarType.caption, color = Solar.colors.ink)
    }
}
