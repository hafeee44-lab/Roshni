package io.github.hafeee44lab.solargrid.ui.onboarding

import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import io.github.hafeee44lab.solargrid.R
import io.github.hafeee44lab.solargrid.data.DiscoveredDevice
import io.github.hafeee44lab.solargrid.data.cloud.BrandAccount
import io.github.hafeee44lab.solargrid.data.cloud.CloudBrand
import io.github.hafeee44lab.solargrid.data.cloud.GrowattSource
import io.github.hafeee44lab.solargrid.data.cloud.HuaweiSource
import io.github.hafeee44lab.solargrid.ui.MainViewModel
import io.github.hafeee44lab.solargrid.ui.components.Icons
import io.github.hafeee44lab.solargrid.ui.components.ListGroup
import io.github.hafeee44lab.solargrid.ui.LocalSystem
import io.github.hafeee44lab.solargrid.ui.components.PrimaryButton
import io.github.hafeee44lab.solargrid.ui.components.SCard
import io.github.hafeee44lab.solargrid.ui.components.Segmented
import io.github.hafeee44lab.solargrid.ui.settings.SolarField
import io.github.hafeee44lab.solargrid.ui.theme.Solar
import io.github.hafeee44lab.solargrid.ui.theme.SolarType
import kotlinx.coroutines.launch

/** One thing a brand asks for. A [hash] field is turned into its hash on submit and stored as [storedAs]. */
private data class BrandField(
    val key: String,
    @StringRes val label: Int,
    val secret: Boolean = false,
    val keyboard: KeyboardType = KeyboardType.Text,
    val default: String = "",
    val storedAs: String = key,
    val hash: ((String) -> String)? = null
)

/** Some brands have more than one way in; each [BrandMode] shows only its own fields. */
private data class BrandMode(val value: String, @StringRes val label: Int, val fields: List<BrandField>)

private data class BrandSpec(
    val modeKey: String?,
    val modes: List<BrandMode>,
    @StringRes val body: Int,
    @StringRes val help: Int,
    val helpUrl: String? = null
)

private fun specOf(brand: CloudBrand): BrandSpec = when (brand) {
    CloudBrand.SOLARMAN -> error("Solarman uses AccountForm")
    CloudBrand.GROWATT -> {
        val server = BrandField(GrowattSource.SERVER, R.string.field_server, keyboard = KeyboardType.Uri, default = GrowattSource.DEFAULT_SERVER)
        BrandSpec(
            modeKey = GrowattSource.MODE,
            modes = listOf(
                BrandMode(
                    GrowattSource.MODE_TOKEN, R.string.growatt_mode_token,
                    listOf(BrandField(GrowattSource.TOKEN, R.string.field_api_token, secret = true), server)
                ),
                BrandMode(
                    GrowattSource.MODE_PASSWORD, R.string.growatt_mode_password,
                    listOf(
                        BrandField(GrowattSource.USER, R.string.field_username),
                        BrandField("password", R.string.field_password, secret = true, storedAs = GrowattSource.PASSWORD_HASH, hash = GrowattSource::hashPassword),
                        server
                    )
                )
            ),
            body = R.string.connect_growatt_body,
            help = R.string.growatt_help
        )
    }
    CloudBrand.HUAWEI -> BrandSpec(
        modeKey = null,
        modes = listOf(
            BrandMode(
                "northbound", R.string.huawei_mode,
                listOf(
                    BrandField(HuaweiSource.USER, R.string.field_api_username),
                    BrandField(HuaweiSource.SYSTEM_CODE, R.string.field_api_password, secret = true),
                    BrandField(HuaweiSource.SERVER, R.string.field_server, keyboard = KeyboardType.Uri, default = HuaweiSource.DEFAULT_SERVER)
                )
            )
        ),
        body = R.string.connect_huawei_body,
        help = R.string.huawei_help,
        helpUrl = "https://github.com/tijsverkoyen/HomeAssistant-FusionSolar#if-you-know-your-installer-or-you-have-the-installer-account"
    )
}

/** Which maker's cloud to read. Shared by onboarding and Settings. */
@Composable
fun CloudBrandPicker(selected: CloudBrand, onSelect: (CloudBrand) -> Unit) {
    val c = Solar.colors
    ListGroup(stringResource(R.string.choose_brand)) {
        CloudBrand.entries.forEach { brand ->
            val isSelected = brand == selected
            Row(
                Modifier
                    .fillMaxWidth()
                    .selectable(selected = isSelected, role = Role.RadioButton) { onSelect(brand) }
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                RadioButton(selected = isSelected, onClick = null, modifier = Modifier.padding(8.dp))
                Column(Modifier.weight(1f)) {
                    Text(brand.title, style = SolarType.body, color = c.ink)
                    Text(stringResource(brandSubtitle(brand)), style = SolarType.caption, color = c.ink3)
                }
            }
        }
    }
}

@StringRes
private fun brandSubtitle(brand: CloudBrand): Int = when (brand) {
    CloudBrand.SOLARMAN -> R.string.brand_solarman_sub
    CloudBrand.GROWATT -> R.string.brand_growatt_sub
    CloudBrand.HUAWEI -> R.string.brand_huawei_sub
}

/**
 * Sign in to a brand's cloud, list its inverters, pick one. Only the fields that brand needs are
 * shown; secrets stay in memory until saved, sealed, and passwords are hashed when the brand's
 * API accepts a hash.
 */
@Composable
fun BrandForm(vm: MainViewModel, brand: CloudBrand, initial: BrandAccount?, onDone: () -> Unit) {
    val c = Solar.colors
    val scope = rememberCoroutineScope()
    val system = LocalSystem.current
    val spec = remember(brand) { specOf(brand) }
    val editing = initial?.takeIf { it.brand == brand }

    var mode by rememberSaveable(brand) {
        mutableStateOf(spec.modeKey?.let { editing?.field(it) }?.takeIf { m -> spec.modes.any { it.value == m } } ?: spec.modes.first().value)
    }
    val values = remember(brand) {
        mutableStateMapOf<String, String>().apply {
            spec.modes.flatMap { it.fields }.forEach { f ->
                put(f.key, if (f.hash != null || f.secret) "" else editing?.field(f.storedAs)?.ifBlank { null } ?: f.default)
            }
        }
    }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var devices by remember(brand) { mutableStateOf<List<DiscoveredDevice>?>(null) }
    var chosen by remember(brand) { mutableStateOf<DiscoveredDevice?>(null) }
    var help by remember { mutableStateOf(false) }

    val current = spec.modes.first { it.value == mode }

    /** What gets stored: blank secrets keep the saved value when editing the same mode. */
    fun draft(): Map<String, String> {
        val out = mutableMapOf<String, String>()
        spec.modeKey?.let { out[it] = mode }
        val sameMode = spec.modeKey == null || editing?.field(spec.modeKey) == mode
        for (f in current.fields) {
            val typed = values[f.key].orEmpty().trim()
            out[f.storedAs] = when {
                typed.isEmpty() && (f.secret || f.hash != null) && sameMode -> editing?.field(f.storedAs).orEmpty()
                typed.isEmpty() -> f.default
                f.hash != null -> f.hash.invoke(typed)
                else -> typed
            }
        }
        return out
    }

    val complete = current.fields.all { f ->
        values[f.key].orEmpty().isNotBlank() || f.default.isNotBlank() ||
            ((f.secret || f.hash != null) && editing?.field(f.storedAs)?.isNotBlank() == true && (spec.modeKey == null || editing.field(spec.modeKey) == mode))
    }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(stringResource(spec.body), style = SolarType.body, color = c.ink2)
        if (spec.modes.size > 1) {
            Segmented(
                options = spec.modes.map { it.value to stringResource(it.label) },
                selected = mode,
                onSelect = { mode = it; devices = null; chosen = null; error = null }
            )
        }
        current.fields.forEach { f ->
            val keep = (f.secret || f.hash != null) && editing != null
            SolarField(
                label = if (keep) stringResource(R.string.keep_if_blank, stringResource(f.label)) else stringResource(f.label),
                value = values[f.key].orEmpty(),
                onChange = { values[f.key] = it; devices = null },
                placeholder = f.default.ifBlank { null },
                secret = f.secret,
                keyboard = f.keyboard
            )
        }

        TextButton(onClick = { help = !help }, contentPadding = PaddingValues(horizontal = 0.dp)) {
            Icon(Icons.Info, null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.logger_where), style = SolarType.bodyStrong)
        }
        AnimatedVisibility(help, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
            SCard(container = c.surfaceVariant) {
                Text(stringResource(spec.help), style = SolarType.caption, color = c.ink2)
                spec.helpUrl?.let { url ->
                    TextButton(onClick = { system?.openUrl(url) }, contentPadding = PaddingValues(horizontal = 0.dp)) {
                        Text(stringResource(R.string.brand_help_link), style = SolarType.bodyStrong)
                    }
                }
            }
        }

        error?.let { Text(it, style = SolarType.caption, color = c.down) }

        val list = devices
        val noInverters = stringResource(R.string.no_supported_inverters)
        if (list == null) {
            PrimaryButton(
                text = stringResource(if (busy) R.string.signing_in else R.string.sign_in_find),
                enabled = !busy && complete,
                onClick = {
                    busy = true
                    error = null
                    scope.launch {
                        vm.discoverBrand(brand, draft()).fold(
                            onSuccess = {
                                devices = it
                                chosen = it.firstOrNull()
                                if (it.isEmpty()) error = noInverters
                            },
                            onFailure = { error = it.message }
                        )
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
        } else if (list.isNotEmpty()) {
            ListGroup(stringResource(R.string.inverters_on_account)) {
                list.forEach { device ->
                    val selected = chosen == device
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .selectable(selected = selected, role = Role.RadioButton) { chosen = device }
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(selected = selected, onClick = null, modifier = Modifier.padding(8.dp))
                        Column(Modifier.weight(1f)) {
                            Text(device.sn, style = SolarType.body, color = c.ink)
                            Text(
                                when (device.online) {
                                    true -> stringResource(R.string.online_suffix, device.station)
                                    false -> stringResource(R.string.offline_suffix, device.station)
                                    null -> device.station
                                },
                                style = SolarType.caption,
                                color = c.ink3
                            )
                        }
                    }
                }
            }
            val pick = chosen
            PrimaryButton(
                stringResource(R.string.use_inverter),
                enabled = pick != null,
                onClick = {
                    pick ?: return@PrimaryButton
                    vm.saveBrandAccount(BrandAccount(brand, draft(), pick.ref, pick.sn, pick.station))
                    onDone()
                },
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp)
            )
        }
    }
}
