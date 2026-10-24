package io.github.hafeee44lab.solargrid.ui.onboarding

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import io.github.hafeee44lab.solargrid.R
import io.github.hafeee44lab.solargrid.data.FetchResult
import io.github.hafeee44lab.solargrid.data.local.FoundLogger
import io.github.hafeee44lab.solargrid.data.local.LocalLogger
import io.github.hafeee44lab.solargrid.ui.MainViewModel
import io.github.hafeee44lab.solargrid.ui.components.Icons
import io.github.hafeee44lab.solargrid.ui.components.ListGroup
import io.github.hafeee44lab.solargrid.ui.components.PrimaryButton
import io.github.hafeee44lab.solargrid.ui.components.SCard
import io.github.hafeee44lab.solargrid.ui.components.SecondaryButton
import io.github.hafeee44lab.solargrid.ui.components.SettingRow
import io.github.hafeee44lab.solargrid.ui.settings.SolarField
import io.github.hafeee44lab.solargrid.ui.theme.Solar
import io.github.hafeee44lab.solargrid.ui.theme.SolarType
import kotlinx.coroutines.launch

/**
 * The logger's IP address and serial, filled in by hand or by a broadcast scan, and the inverter
 * model behind it (detected for Deye-made inverters, picked for the rest). Connecting does
 * one trial read first; if the logger can't be reached right now it can still be saved.
 * Shared by onboarding and Settings.
 */
@Composable
fun LoggerForm(vm: MainViewModel, initial: LocalLogger?, onDone: () -> Unit) {
    val c = Solar.colors
    val scope = rememberCoroutineScope()

    var host by rememberSaveable { mutableStateOf(initial?.host ?: "") }
    var serial by rememberSaveable { mutableStateOf(initial?.serial?.toString() ?: "") }
    var profileId by rememberSaveable { mutableStateOf(initial?.profileId) }
    var busy by remember { mutableStateOf(false) }
    var scanning by remember { mutableStateOf(false) }
    var found by remember { mutableStateOf<List<FoundLogger>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var failedDraft by remember { mutableStateOf<LocalLogger?>(null) }
    var help by remember { mutableStateOf(false) }

    val hostOk = LocalLogger.isPlausibleHost(host)
    val parsedSerial = LocalLogger.parseSerial(serial)

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(stringResource(R.string.connect_local_body), style = SolarType.body, color = c.ink2)

        SecondaryButton(
            stringResource(if (scanning) R.string.finding_logger else R.string.find_logger),
            onClick = {
                if (scanning) return@SecondaryButton
                scanning = true
                scope.launch {
                    val list = vm.scanForLoggers()
                    found = list
                    list.singleOrNull()?.let { host = it.ip; serial = it.serial.toString() }
                    scanning = false
                }
            },
            icon = Icons.Wave,
            modifier = Modifier.fillMaxWidth()
        )

        found?.let { list ->
            if (list.isEmpty()) {
                Text(stringResource(R.string.no_logger_found), style = SolarType.caption, color = c.ink3)
            } else {
                ListGroup(stringResource(R.string.loggers_found)) {
                    list.forEach { logger ->
                        val picked = host == logger.ip && serial == logger.serial.toString()
                        SettingRow(
                            stringResource(R.string.logger_found_row, logger.ip, logger.serial.toString()),
                            icon = if (picked) Icons.Check else Icons.Wave,
                            iconTint = if (picked) c.ok else c.ink3,
                            onClick = { host = logger.ip; serial = logger.serial.toString() }
                        )
                    }
                }
            }
        }

        SolarField(stringResource(R.string.logger_ip), host, { host = it; failedDraft = null }, placeholder = "192.168.1.40", keyboard = KeyboardType.Uri)
        if (host.isNotBlank() && !hostOk) Text(stringResource(R.string.invalid_ip), style = SolarType.caption, color = c.down)
        SolarField(stringResource(R.string.logger_serial), serial, { serial = it; failedDraft = null }, keyboard = KeyboardType.Number)
        if (serial.isNotBlank() && parsedSerial == null) Text(stringResource(R.string.invalid_serial), style = SolarType.caption, color = c.down)

        InverterModelRow(
            vm = vm,
            draft = parsedSerial?.takeIf { hostOk }?.let { LocalLogger(host = host.trim(), serial = it) },
            profileId = profileId,
            onPick = { profileId = it; failedDraft = null }
        )

        TextButton(onClick = { help = !help }, contentPadding = PaddingValues(horizontal = 0.dp)) {
            Icon(Icons.Info, null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.logger_where), style = SolarType.bodyStrong)
        }
        AnimatedVisibility(help, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
            SCard(container = c.surfaceVariant) {
                Text(stringResource(R.string.logger_help), style = SolarType.caption, color = c.ink2)
            }
        }

        error?.let { Text(it, style = SolarType.caption, color = c.down) }

        PrimaryButton(
            text = stringResource(if (busy) R.string.connecting_logger else R.string.connect_logger),
            enabled = !busy && hostOk && parsedSerial != null,
            onClick = {
                val draft = LocalLogger(host = host.trim(), serial = parsedSerial ?: return@PrimaryButton, profileId = profileId)
                busy = true
                error = null
                failedDraft = null
                scope.launch {
                    when (val result = vm.testLogger(draft)) {
                        is FetchResult.Ok -> {
                            vm.saveLogger(draft)
                            onDone()
                        }
                        is FetchResult.Failed -> {
                            error = result.message
                            failedDraft = draft
                        }
                        FetchResult.LoggerOffline -> failedDraft = draft
                    }
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
        failedDraft?.let { draft ->
            TextButton(onClick = { vm.saveLogger(draft); onDone() }, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.save_anyway), style = SolarType.bodyStrong)
            }
        }
    }
}
