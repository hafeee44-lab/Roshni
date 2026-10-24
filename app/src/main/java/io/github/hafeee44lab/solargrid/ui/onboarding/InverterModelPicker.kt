package io.github.hafeee44lab.solargrid.ui.onboarding

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import io.github.hafeee44lab.solargrid.R
import io.github.hafeee44lab.solargrid.data.local.Detection
import io.github.hafeee44lab.solargrid.data.local.InverterProfile
import io.github.hafeee44lab.solargrid.data.local.LocalLogger
import io.github.hafeee44lab.solargrid.data.local.ProfileCatalog
import io.github.hafeee44lab.solargrid.ui.MainViewModel
import io.github.hafeee44lab.solargrid.ui.components.Icons
import io.github.hafeee44lab.solargrid.ui.components.SecondaryButton
import io.github.hafeee44lab.solargrid.ui.components.SettingRow
import io.github.hafeee44lab.solargrid.ui.settings.SolarField
import io.github.hafeee44lab.solargrid.ui.theme.Solar
import io.github.hafeee44lab.solargrid.ui.theme.SolarType
import kotlinx.coroutines.launch

/**
 * The inverter-model row in the logger form: shows the picked profile (or automatic detection),
 * opens a searchable list grouped by brand, and can ask the inverter itself. Only Deye-made
 * inverters (Deye, Sunsynk, Sol-Ark) say what they are; other brands are picked by hand.
 */
@Composable
fun InverterModelRow(vm: MainViewModel, draft: LocalLogger?, profileId: String?, onPick: (String?) -> Unit) {
    val c = Solar.colors
    val scope = rememberCoroutineScope()
    val catalog = vm.inverterProfiles
    var picking by remember { mutableStateOf(false) }
    var detecting by remember { mutableStateOf(false) }
    var note by remember { mutableStateOf<String?>(null) }

    val picked = profileId?.let(catalog::byId)
    val notFound = stringResource(R.string.model_not_detected)
    val foundFormat = stringResource(R.string.model_detected)

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(c.surfaceVariant)) {
            SettingRow(
                stringResource(R.string.inverter_model),
                subtitle = picked?.let { modelLine(catalog, it) } ?: stringResource(R.string.inverter_model_auto),
                icon = Icons.Code,
                onClick = { picking = true }
            ) {
                Icon(Icons.Chevron, null, tint = c.ink3, modifier = Modifier.size(20.dp))
            }
        }
        Text(
            picked?.let { stringResource(if (it.verified == InverterProfile.Verification.DEVICE) R.string.model_verified else R.string.model_from_docs) }
                ?: stringResource(R.string.inverter_model_auto_sub),
            style = SolarType.caption,
            color = c.ink3
        )
        SecondaryButton(
            stringResource(if (detecting) R.string.detecting_model else R.string.detect_model),
            onClick = {
                val logger = draft ?: return@SecondaryButton
                if (detecting) return@SecondaryButton
                detecting = true
                note = null
                scope.launch {
                    note = when (val d = vm.detectInverter(logger)) {
                        is Detection.Found -> {
                            onPick(d.profile.id)
                            foundFormat.format(d.profile.label)
                        }
                        is Detection.Unknown -> notFound
                        is Detection.Failed -> d.message
                    }
                    detecting = false
                }
            },
            icon = Icons.Wave,
            modifier = Modifier.fillMaxWidth()
        )
        if (draft == null) Text(stringResource(R.string.detect_model_needs), style = SolarType.caption, color = c.ink3)
        note?.let { Text(it, style = SolarType.caption, color = c.ink2) }
    }

    if (picking) {
        ModelPickerDialog(catalog, profileId, onDismiss = { picking = false }, onPick = { onPick(it); note = null; picking = false })
    }
}

private fun modelLine(catalog: ProfileCatalog, profile: InverterProfile): String {
    val first = catalog.entries.first { it.profile.id == profile.id }
    return "${first.brand} · ${profile.label}"
}

@Composable
private fun ModelPickerDialog(catalog: ProfileCatalog, selected: String?, onDismiss: () -> Unit, onPick: (String?) -> Unit) {
    val c = Solar.colors
    var query by remember { mutableStateOf("") }
    val matches = catalog.entries.filter { it.matches(query) }
    val byBrand = matches.groupBy { it.brand }

    Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .fillMaxWidth()
                .heightIn(max = 640.dp)
                .clip(RoundedCornerShape(24.dp))
                .background(c.surface)
                .padding(top = 20.dp, bottom = 8.dp)
        ) {
            Text(stringResource(R.string.pick_model_title), style = SolarType.cardTitle, color = c.ink, modifier = Modifier.padding(horizontal = 20.dp))
            SolarField(stringResource(R.string.search_models), query, { query = it }, modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp))

            LazyColumn(Modifier.weight(1f, fill = false)) {
                if (query.isBlank()) {
                    item {
                        SettingRow(
                            stringResource(R.string.inverter_model_auto),
                            subtitle = stringResource(R.string.inverter_model_auto_sub),
                            icon = if (selected == null) Icons.Check else Icons.Wave,
                            iconTint = if (selected == null) c.ok else c.ink3,
                            onClick = { onPick(null) }
                        )
                    }
                }
                byBrand.forEach { (brand, entries) ->
                    item(key = "brand-$brand") {
                        Text(brand, style = SolarType.bodyStrong, color = c.brand, modifier = Modifier.padding(start = 20.dp, top = 12.dp, bottom = 4.dp))
                    }
                    items(entries, key = { "${it.brand}/${it.model}" }) { entry ->
                        val on = entry.profile.id == selected
                        SettingRow(
                            entry.model,
                            subtitle = entry.profile.label,
                            icon = if (on) Icons.Check else null,
                            iconTint = c.ok,
                            onClick = { onPick(entry.profile.id) }
                        )
                    }
                }
                if (matches.isEmpty()) {
                    item {
                        Text(stringResource(R.string.no_models_found), style = SolarType.caption, color = c.ink3, modifier = Modifier.padding(20.dp))
                    }
                }
            }

            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
            }
        }
    }
}
