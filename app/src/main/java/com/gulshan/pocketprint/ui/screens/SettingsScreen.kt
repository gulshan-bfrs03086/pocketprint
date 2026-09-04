package com.gulshan.pocketprint.ui.screens

import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gulshan.pocketprint.BuildConfig
import com.gulshan.pocketprint.R
import com.gulshan.pocketprint.model.MediaSize
import com.gulshan.pocketprint.ui.components.ChipRow
import com.gulshan.pocketprint.ui.components.InfoBanner
import com.gulshan.pocketprint.ui.components.SectionHeader
import com.gulshan.pocketprint.ui.vm.PrintersViewModel

@Composable
fun SettingsScreen(viewModel: PrintersViewModel) {
    val context = LocalContext.current
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    var converterUrl by rememberSaveable(settings.officeConverterUrl) {
        mutableStateOf(settings.officeConverterUrl)
    }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        SectionHeader(stringResource(R.string.settings_defaults))
        ChipRow(
            items = MediaSize.ALL,
            selected = MediaSize.byId(settings.defaultMediaId),
            label = { it.label },
            onSelect = { size ->
                viewModel.updateSettings { it.copy(defaultMediaId = size.id) }
            },
        )
        ChipRow(
            items = listOf(150, 203, 300, 600),
            selected = settings.defaultDpi,
            // getString rather than stringResource: a ChipRow label is a plain
            // lambda, not a composable one.
            label = { context.getString(R.string.settings_dpi, it) },
            onSelect = { dpi -> viewModel.updateSettings { it.copy(defaultDpi = dpi) } },
            modifier = Modifier.padding(top = 8.dp),
        )

        ToggleRow(
            label = stringResource(R.string.settings_default_colour),
            checked = settings.defaultColor,
            onChange = { value -> viewModel.updateSettings { it.copy(defaultColor = value) } },
        )
        ToggleRow(
            label = stringResource(R.string.settings_dither),
            checked = settings.ditherImages,
            onChange = { value -> viewModel.updateSettings { it.copy(ditherImages = value) } },
        )

        SectionHeader(stringResource(R.string.settings_office_title))
        InfoBanner(stringResource(R.string.settings_office_body))
        OutlinedTextField(
            value = converterUrl,
            onValueChange = { converterUrl = it },
            label = { Text(stringResource(R.string.settings_converter_url)) },
            placeholder = { Text(stringResource(R.string.settings_converter_hint)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Button(
            onClick = {
                viewModel.updateSettings { it.copy(officeConverterUrl = converterUrl.trim()) }
            },
        ) { Text(stringResource(R.string.settings_save_converter)) }

        SectionHeader(stringResource(R.string.settings_print_service_title))
        InfoBanner(stringResource(R.string.settings_print_service_body))
        Button(
            onClick = {
                runCatching {
                    context.startActivity(
                        Intent("android.settings.ACTION_PRINT_SETTINGS")
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    )
                }
            },
        ) { Text(stringResource(R.string.settings_open_android_print)) }

        Text(
            // Was hardcoded "PocketPrint 1.0", which stopped being true at 1.0.1.
            stringResource(R.string.settings_version, BuildConfig.VERSION_NAME),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 24.dp, bottom = 24.dp),
        )
    }
}

@Composable
private fun ToggleRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
