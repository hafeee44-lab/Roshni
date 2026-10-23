package io.github.hafeee44lab.solargrid.ui.settings

import android.content.ClipData
import android.content.ClipboardManager
import android.widget.Toast
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import io.github.hafeee44lab.solargrid.R
import io.github.hafeee44lab.solargrid.data.describeAge
import io.github.hafeee44lab.solargrid.ui.UiState
import io.github.hafeee44lab.solargrid.ui.components.Icons
import io.github.hafeee44lab.solargrid.ui.components.SCard
import io.github.hafeee44lab.solargrid.ui.components.phrases
import io.github.hafeee44lab.solargrid.ui.theme.Solar
import io.github.hafeee44lab.solargrid.ui.theme.SolarType

/** Every key the inverter reported, searchable. The ground truth when a reading looks wrong. */
@Composable
fun DiagnosticsScreen(state: UiState, onBack: () -> Unit) {
    val colors = Solar.colors
    val context = LocalContext.current
    val copiedLabel = stringResource(R.string.copied)
    var query by remember { mutableStateOf("") }
    val lines = remember(state.dump, query) {
        state.dump.lines().filter { it.isNotBlank() && (query.isBlank() || it.contains(query, ignoreCase = true)) }
    }

    Column(
        Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.statusBars)
            .windowInsetsPadding(WindowInsets.navigationBars)
            .padding(horizontal = 16.dp)
    ) {
        Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.Back, contentDescription = stringResource(R.string.back), tint = colors.ink) }
            Spacer(Modifier.width(4.dp))
            Text(stringResource(R.string.inverter_data), style = SolarType.title, color = colors.ink, modifier = Modifier.weight(1f))
            TextButton(onClick = {
                context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText("Inverter data", state.dump))
                // Android 13+ shows its own clipboard confirmation; don't double it.
                if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.TIRAMISU) {
                    Toast.makeText(context, copiedLabel, Toast.LENGTH_SHORT).show()
                }
            }) { Text(stringResource(R.string.copy), style = SolarType.bodyStrong) }
        }

        Text(
            stringResource(R.string.diagnostics_body),
            style = SolarType.caption,
            color = colors.ink3,
            modifier = Modifier.padding(top = 12.dp)
        )

        SCard(Modifier.padding(top = 12.dp)) {
            val p = phrases()
            Fact(stringResource(R.string.decided_by), state.snapshot.basis.ifBlank { "—" })
            Fact(stringResource(R.string.last_check), describeAge(state.snapshot.ageMillis(), p))
            Fact(stringResource(R.string.inverter_upload), describeAge(state.snapshot.inverterAgeMillis(), p))
            Fact(stringResource(R.string.alarm_flag), stringResource(if (state.snapshot.inverterAlarm) R.string.raised else R.string.clear_flag))
            state.snapshot.error?.let { Fact(stringResource(R.string.last_error), it) }
        }

        SolarField(stringResource(R.string.filter), query, { query = it }, modifier = Modifier.padding(top = 12.dp), placeholder = "grid, battery, PG_F1…")

        SCard(Modifier.padding(top = 12.dp, bottom = 16.dp).weight(1f)) {
            Column(
                Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .horizontalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(3.dp)
            ) {
                if (lines.isEmpty()) {
                    Text(stringResource(if (state.dump.isBlank()) R.string.no_reading_yet else R.string.nothing_matches), style = SolarType.body, color = colors.ink2)
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
