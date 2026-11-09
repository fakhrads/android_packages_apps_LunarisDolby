/*
 * SPDX-FileCopyrightText: 2026 Fakhri Adi Saputra
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lunaris.dolby.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import kotlinx.coroutines.launch
import org.lunaris.dolby.R
import android.bluetooth.BluetoothCodecConfig
import org.lunaris.dolby.audio.BluetoothAudio
import org.lunaris.dolby.audio.CorrectionParser
import org.lunaris.dolby.audio.SoundLab
import org.lunaris.dolby.ui.components.*
import org.lunaris.dolby.ui.viewmodel.LabViewModel
import org.lunaris.dolby.utils.*
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SoundLabScreen(
    viewModel: LabViewModel,
    navController: NavController
) {
    val state by viewModel.state.collectAsState()
    val status by viewModel.status.collectAsState()
    val corrections by viewModel.corrections.collectAsState()
    val userPresets by viewModel.userPresets.collectAsState()
    val bluetooth by viewModel.bluetoothState.collectAsState()
    val currentRoute by navController.currentBackStackEntryFlow.collectAsState(null)
    val layoutDirection = LocalLayoutDirection.current
    val cutoutInsets = WindowInsets.displayCutout.asPaddingValues()
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(viewModel) {
        viewModel.messages.collect { snackbarHostState.showSnackbar(it) }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        stringResource(R.string.lab_title),
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent)
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        containerColor = MaterialTheme.colorScheme.surfaceContainer
    ) { paddingValues ->
        Box(modifier = Modifier.fillMaxSize()) {
            SoundLabContent(
                state = state,
                status = status,
                corrections = corrections,
                userPresets = userPresets,
                bluetooth = bluetooth,
                viewModel = viewModel,
                modifier = Modifier.padding(paddingValues)
            )

            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .height(130.dp)
                    .background(
                        brush = Brush.verticalGradient(
                            colors = listOf(
                                Color.Transparent,
                                MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.95f)
                            )
                        )
                    )
            )

            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .padding(
                        start = cutoutInsets.calculateStartPadding(layoutDirection),
                        end = cutoutInsets.calculateEndPadding(layoutDirection),
                        bottom = paddingValues.calculateBottomPadding()
                    ),
                contentAlignment = Alignment.Center
            ) {
                FloatingNavToolbar(
                    currentRoute = currentRoute?.destination?.route ?: Screen.Lab.route,
                    onNavigate = { route ->
                        if (currentRoute?.destination?.route != route) {
                            navController.navigate(route) {
                                popUpTo("settings") { saveState = true }
                                launchSingleTop = true
                                restoreState = true
                            }
                        }
                    }
                )
            }
        }
    }
}

@Composable
private fun SoundLabContent(
    state: SoundLab.Settings,
    status: SoundLab.Status,
    corrections: List<SoundLab.Correction>,
    userPresets: List<SoundLab.UserPreset>,
    bluetooth: BluetoothAudio.State,
    viewModel: LabViewModel,
    modifier: Modifier = Modifier
) {
    val correction = corrections.firstOrNull { it.id == state.correctionId }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item {
            LabHeroCard(
                state = state,
                status = status,
                correctionName = correction?.name,
                onEnabledChange = viewModel::setEnabled
            )
        }

        item {
            LabSection(visible = state.enabled) {
                LabPresetRow(
                    state = state,
                    userPresets = userPresets,
                    onApply = viewModel::applyGains,
                    onSave = viewModel::saveUserPreset,
                    onDelete = viewModel::deleteUserPreset
                )
            }
        }

        item {
            LabSection(visible = state.enabled) {
                EqualizerCard(state = state, status = status, viewModel = viewModel)
            }
        }

        item {
            LabSection(visible = state.enabled) {
                CorrectionCard(
                    state = state,
                    status = status,
                    corrections = corrections,
                    viewModel = viewModel
                )
            }
        }

        item {
            LabSection(visible = state.enabled) {
                DynamicsCard(state = state, viewModel = viewModel)
            }
        }

        item {
            LabSection(visible = state.enabled) {
                LoudnessCard(state = state, viewModel = viewModel)
            }
        }

        item {
            BluetoothCard(state = bluetooth, viewModel = viewModel)
        }

        item {
            Row(
                modifier = Modifier.padding(horizontal = 8.dp),
                verticalAlignment = Alignment.Top
            ) {
                Icon(
                    imageVector = Icons.Default.Info,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = stringResource(R.string.lab_footer),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        item {
            Spacer(modifier = Modifier.height(70.dp))
        }
    }
}

@Composable
private fun LabSection(visible: Boolean, content: @Composable () -> Unit) {
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn() + expandVertically(),
        exit = fadeOut() + shrinkVertically()
    ) {
        content()
    }
}

private fun dbLabel(db: Float) = formatDb(db) + " dB"

@Composable
private fun LabHeroCard(
    state: SoundLab.Settings,
    status: SoundLab.Status,
    correctionName: String?,
    onEnabledChange: (Boolean) -> Unit
) {
    val haptic = rememberHapticFeedback()
    val scope = rememberCoroutineScope()
    val colors = MaterialTheme.colorScheme
    val container = if (state.enabled) colors.secondaryContainer else colors.surfaceBright
    val content = if (state.enabled) colors.onSecondaryContainer else colors.onSurface

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.extraLarge,
        colors = CardDefaults.cardColors(containerColor = container)
    ) {
        Column(modifier = Modifier.padding(24.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .background(colors.secondary, MaterialTheme.shapes.large),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Default.Science, contentDescription = null, tint = colors.onSecondary)
                }
                Spacer(modifier = Modifier.width(16.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.lab_enable),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = content
                    )
                    Text(
                        text = stringResource(R.string.lab_summary),
                        style = MaterialTheme.typography.bodySmall,
                        color = content.copy(alpha = 0.8f)
                    )
                }
                Spacer(modifier = Modifier.width(12.dp))
                Switch(
                    checked = state.enabled,
                    onCheckedChange = {
                        scope.launch {
                            haptic.performHaptic(HapticFeedbackHelper.HapticIntensity.HEAVY_CLICK)
                        }
                        onEnabledChange(it)
                    }
                )
            }

            AnimatedVisibility(visible = state.enabled) {
                Column {
                    Spacer(modifier = Modifier.height(20.dp))
                    LabResponseCurve(
                        settings = state,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(150.dp)
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = buildList {
                            add(stringResource(
                                if (state.autoPreamp) R.string.lab_status_preamp_auto
                                else R.string.lab_status_preamp,
                                dbLabel(status.preampDb)
                            ))
                            if (correctionName != null) {
                                add(if (status.correctionActive) correctionName
                                    else stringResource(R.string.lab_status_correction_paused, correctionName))
                            }
                            if (status.precision) add(stringResource(R.string.lab_precision))
                        }.joinToString("  ·  "),
                        style = MaterialTheme.typography.bodySmall,
                        color = content.copy(alpha = 0.85f),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = stringResource(R.string.lab_chain),
                        style = MaterialTheme.typography.labelLarge,
                        color = content.copy(alpha = 0.8f)
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(
                        modifier = Modifier.horizontalScroll(rememberScrollState()),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        val stages = listOf(
                            stringResource(R.string.lab_preamp) to (status.preampDb != 0f),
                            stringResource(R.string.lab_stage_eq) to state.eqGains.any { it != 0f },
                            stringResource(R.string.lab_stage_correction) to status.correctionActive,
                            stringResource(R.string.lab_stage_compressor) to state.compressorEnabled,
                            stringResource(R.string.lab_limiter) to state.limiterEnabled,
                            stringResource(R.string.lab_stage_boost) to (state.loudnessMb > 0)
                        )
                        stages.forEachIndexed { index, (label, active) ->
                            ChainStage(label = label, active = active)
                            if (index < stages.lastIndex) {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                                    contentDescription = null,
                                    tint = content.copy(alpha = 0.5f),
                                    modifier = Modifier
                                        .padding(horizontal = 4.dp)
                                        .size(16.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ChainStage(label: String, active: Boolean) {
    val colors = MaterialTheme.colorScheme
    Surface(
        shape = CircleShape,
        color = if (active) colors.secondary else colors.surfaceContainerHighest,
        contentColor = if (active) colors.onSecondary else colors.onSurfaceVariant
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
        )
    }
}

@Composable
private fun LabPresetRow(
    state: SoundLab.Settings,
    userPresets: List<SoundLab.UserPreset>,
    onApply: (List<Float>) -> Unit,
    onSave: (String) -> Unit,
    onDelete: (String) -> Unit
) {
    val haptic = rememberHapticFeedback()
    val scope = rememberCoroutineScope()
    var showSave by remember { mutableStateOf(false) }
    val presets = listOf(
        SoundLab.EqPreset.FLAT to (R.string.lab_preset_flat to Icons.Default.Hearing),
        SoundLab.EqPreset.WARM to (R.string.lab_preset_warm to Icons.Default.LocalFireDepartment),
        SoundLab.EqPreset.BASS to (R.string.lab_preset_bass to Icons.Default.Speaker),
        SoundLab.EqPreset.V_SHAPE to (R.string.lab_preset_v_shape to Icons.Default.GraphicEq),
        SoundLab.EqPreset.VOCAL to (R.string.lab_preset_vocal to Icons.Default.RecordVoiceOver),
        SoundLab.EqPreset.BRIGHT to (R.string.lab_preset_bright to Icons.Default.WbSunny)
    )
    val builtIn = SoundLab.EqPreset.entries.firstOrNull { SoundLab.matches(it.gains, state.eqGains) }
    val user = userPresets.firstOrNull { SoundLab.matches(it.gains, state.eqGains) }

    fun click(gains: List<Float>) {
        scope.launch { haptic.performHaptic(HapticFeedbackHelper.HapticIntensity.CLICK) }
        onApply(gains)
    }

    Column {
        Row(
            modifier = Modifier.padding(start = 8.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = stringResource(R.string.lab_presets),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (builtIn == null && user == null) {
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "· " + stringResource(R.string.lab_preset_custom),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }
        Row(
            modifier = Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            presets.forEach { (preset, labelIcon) ->
                val (label, icon) = labelIcon
                FilterChip(
                    selected = preset == builtIn,
                    onClick = { click(preset.gains) },
                    label = { Text(stringResource(label)) },
                    leadingIcon = {
                        Icon(imageVector = icon, contentDescription = null, modifier = Modifier.size(18.dp))
                    },
                    shape = CircleShape
                )
            }
            userPresets.forEach { preset ->
                InputChip(
                    selected = preset == user,
                    onClick = { click(preset.gains) },
                    label = { Text(preset.name) },
                    leadingIcon = {
                        Icon(Icons.Default.Person, contentDescription = null, modifier = Modifier.size(18.dp))
                    },
                    trailingIcon = {
                        Icon(
                            Icons.Default.Close,
                            contentDescription = stringResource(R.string.lab_preset_delete),
                            modifier = Modifier
                                .size(18.dp)
                                .clip(CircleShape)
                                .clickable { onDelete(preset.name) }
                        )
                    },
                    shape = CircleShape
                )
            }
            AssistChip(
                onClick = { showSave = true },
                label = { Text(stringResource(R.string.lab_preset_save)) },
                leadingIcon = {
                    Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                },
                shape = CircleShape
            )
        }
    }

    if (showSave) {
        var name by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showSave = false },
            icon = { Icon(Icons.Default.Save, contentDescription = null) },
            title = { Text(stringResource(R.string.lab_preset_save_title)) },
            text = {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it.take(32) },
                    label = { Text(stringResource(R.string.lab_preset_name)) },
                    singleLine = true
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        onSave(name)
                        showSave = false
                    },
                    enabled = name.isNotBlank()
                ) { Text(stringResource(R.string.lab_preset_save)) }
            },
            dismissButton = {
                TextButton(onClick = { showSave = false }) { Text(stringResource(R.string.cancel)) }
            }
        )
    }
}

@Composable
private fun EqualizerCard(state: SoundLab.Settings, status: SoundLab.Status, viewModel: LabViewModel) {
    ModernSettingsCard(title = stringResource(R.string.lab_eq), icon = Icons.Default.Equalizer) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = stringResource(R.string.lab_eq_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f)
            )
            TextButton(
                onClick = viewModel::resetEq,
                enabled = state.eqGains.any { it != 0f }
            ) { Text(stringResource(R.string.lab_eq_reset)) }
        }
        Spacer(modifier = Modifier.height(8.dp))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(220.dp)
        ) {
            SoundLab.EQ_FREQUENCIES.forEachIndexed { band, hz ->
                VerticalGainSlider(
                    value = state.eqGains[band],
                    onValueChange = { viewModel.setBandGain(band, it) },
                    label = formatHz(hz),
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                )
            }
        }
        Spacer(modifier = Modifier.height(16.dp))
        ModernSettingSwitch(
            title = stringResource(R.string.lab_auto_preamp),
            subtitle = if (state.autoPreamp) {
                stringResource(R.string.lab_auto_preamp_summary_value, dbLabel(status.preampDb))
            } else {
                stringResource(R.string.lab_auto_preamp_summary)
            },
            checked = state.autoPreamp,
            onCheckedChange = viewModel::setAutoPreamp,
            icon = Icons.Default.AutoAwesome
        )
        AnimatedVisibility(visible = !state.autoPreamp) {
            Column {
                Spacer(modifier = Modifier.height(12.dp))
                ModernSettingSlider(
                    title = stringResource(R.string.lab_preamp),
                    value = (state.preampDb * 2f).roundToInt(),
                    onValueChange = { viewModel.setPreamp(it.roundToInt() / 2f) },
                    valueRange = SoundLab.PREAMP_MIN_DB * 2f..SoundLab.PREAMP_MAX_DB * 2f,
                    steps = ((SoundLab.PREAMP_MAX_DB - SoundLab.PREAMP_MIN_DB) * 2f).toInt() - 1,
                    valueLabel = { dbLabel(it / 2f) }
                )
            }
        }
        Spacer(modifier = Modifier.height(12.dp))
        ModernSettingSwitch(
            title = stringResource(R.string.lab_precision),
            subtitle = stringResource(
                when {
                    status.precisionUnavailable -> R.string.lab_precision_unavailable
                    state.correctionId != null -> R.string.lab_precision_summary_forced
                    else -> R.string.lab_precision_summary
                }
            ),
            checked = state.precision || state.correctionId != null,
            onCheckedChange = viewModel::setPrecision,
            icon = Icons.Default.HighQuality
        )
    }
}

@Composable
private fun CorrectionCard(
    state: SoundLab.Settings,
    status: SoundLab.Status,
    corrections: List<SoundLab.Correction>,
    viewModel: LabViewModel
) {
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let(viewModel::importCorrection)
    }
    var pendingDelete by remember { mutableStateOf<SoundLab.Correction?>(null) }

    ModernSettingsCard(title = stringResource(R.string.lab_correction), icon = Icons.Default.Headphones) {
        Text(
            text = stringResource(R.string.lab_correction_summary),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(12.dp))
        CorrectionRow(
            title = stringResource(R.string.lab_correction_none),
            subtitle = null,
            selected = state.correctionId == null,
            onSelect = { viewModel.selectCorrection(null) },
            onDelete = null
        )
        corrections.forEach { correction ->
            CorrectionRow(
                title = correction.name,
                subtitle = stringResource(when (correction.format) {
                    CorrectionParser.Format.PARAMETRIC -> R.string.lab_correction_format_parametric
                    CorrectionParser.Format.GRAPHIC -> R.string.lab_correction_format_graphic
                    CorrectionParser.Format.DDC -> R.string.lab_correction_format_ddc
                }),
                selected = state.correctionId == correction.id,
                onSelect = { viewModel.selectCorrection(correction.id) },
                onDelete = { pendingDelete = correction }
            )
        }
        Spacer(modifier = Modifier.height(8.dp))
        FilledTonalButton(
            onClick = { picker.launch(arrayOf("*/*")) },
            modifier = Modifier.fillMaxWidth()
        ) {
            Icon(Icons.Default.UploadFile, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(modifier = Modifier.width(8.dp))
            Text(stringResource(R.string.lab_correction_import))
        }

        AnimatedVisibility(visible = state.correctionId != null) {
            Column {
                Spacer(modifier = Modifier.height(16.dp))
                ModernSettingSlider(
                    title = stringResource(R.string.lab_correction_amount),
                    value = state.correctionAmount,
                    onValueChange = { viewModel.setCorrectionAmount(it.roundToInt()) },
                    valueRange = 0f..100f,
                    steps = 19,
                    valueLabel = { "$it%" }
                )
                Spacer(modifier = Modifier.height(12.dp))
                ModernSettingSwitch(
                    title = stringResource(R.string.lab_correction_headphones),
                    subtitle = stringResource(
                        if (state.correctionHeadphonesOnly && !status.onHeadphones)
                            R.string.lab_correction_headphones_paused
                        else R.string.lab_correction_headphones_summary
                    ),
                    checked = state.correctionHeadphonesOnly,
                    onCheckedChange = viewModel::setCorrectionHeadphonesOnly,
                    icon = Icons.Default.HeadsetMic
                )
            }
        }
        Spacer(modifier = Modifier.height(12.dp))
        Text(
            text = stringResource(R.string.lab_correction_help),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }

    pendingDelete?.let { correction ->
        ModernConfirmDialog(
            title = stringResource(R.string.lab_correction_delete_title),
            message = stringResource(R.string.lab_correction_delete_message, correction.name),
            icon = Icons.Default.Delete,
            onConfirm = {
                viewModel.deleteCorrection(correction.id)
                pendingDelete = null
            },
            onDismiss = { pendingDelete = null }
        )
    }
}

@Composable
private fun CorrectionRow(
    title: String,
    subtitle: String?,
    selected: Boolean,
    onSelect: () -> Unit,
    onDelete: (() -> Unit)?
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.large)
            .clickable(onClick = onSelect)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(selected = selected, onClick = onSelect)
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            subtitle?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        onDelete?.let {
            IconButton(onClick = it) {
                Icon(
                    Icons.Default.Delete,
                    contentDescription = stringResource(R.string.lab_correction_delete_title),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun DynamicsCard(state: SoundLab.Settings, viewModel: LabViewModel) {
    ModernSettingsCard(
        title = stringResource(R.string.lab_dynamics),
        icon = Icons.Default.Compress
    ) {
        ModernSettingSwitch(
            title = stringResource(R.string.lab_compressor),
            subtitle = stringResource(R.string.lab_compressor_summary),
            checked = state.compressorEnabled,
            onCheckedChange = viewModel::setCompressor,
            icon = Icons.Default.Waves
        )
        AnimatedVisibility(visible = state.compressorEnabled) {
            Column {
                Spacer(modifier = Modifier.height(12.dp))
                ModernSettingSlider(
                    title = stringResource(R.string.lab_compressor_amount),
                    value = state.compressorAmount,
                    onValueChange = { viewModel.setCompressorAmount(it.roundToInt()) },
                    valueRange = 0f..100f,
                    steps = 19,
                    valueLabel = { "$it%" }
                )
            }
        }
        Spacer(modifier = Modifier.height(12.dp))
        ModernSettingSwitch(
            title = stringResource(R.string.lab_limiter),
            subtitle = stringResource(R.string.lab_limiter_summary),
            checked = state.limiterEnabled,
            onCheckedChange = viewModel::setLimiter,
            icon = Icons.Default.Shield
        )
        AnimatedVisibility(visible = state.limiterEnabled) {
            Column {
                Spacer(modifier = Modifier.height(12.dp))
                ModernSettingSlider(
                    title = stringResource(R.string.lab_limiter_ceiling),
                    value = state.limiterCeilingDb,
                    onValueChange = { viewModel.setLimiterCeiling(it.roundToInt()) },
                    valueRange = SoundLab.LIMITER_MIN_DB.toFloat()..0f,
                    steps = -SoundLab.LIMITER_MIN_DB - 1,
                    valueLabel = { dbLabel(it.toFloat()) }
                )
            }
        }
    }
}

@Composable
private fun LoudnessCard(state: SoundLab.Settings, viewModel: LabViewModel) {
    ModernSettingsCard(
        title = stringResource(R.string.lab_loudness),
        icon = Icons.AutoMirrored.Filled.VolumeUp
    ) {
        ModernSettingSlider(
            title = stringResource(R.string.lab_loudness_amount),
            value = state.loudnessMb / 100,
            onValueChange = { viewModel.setLoudness(it.roundToInt() * 100) },
            valueRange = 0f..(SoundLab.LOUDNESS_MAX_MB / 100).toFloat(),
            steps = SoundLab.LOUDNESS_MAX_MB / 100 - 1,
            valueLabel = { dbLabel(it.toFloat()) }
        )
        AnimatedVisibility(visible = state.loudnessMb > 0) {
            Row(
                modifier = Modifier.padding(top = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Default.Warning,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = stringResource(R.string.lab_loudness_warning),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun BluetoothCard(state: BluetoothAudio.State, viewModel: LabViewModel) {
    ModernSettingsCard(
        title = stringResource(R.string.bt_title),
        icon = Icons.Default.Bluetooth
    ) {
        val current = state.current
        if (current == null) {
            Text(
                text = stringResource(R.string.bt_none),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            return@ModernSettingsCard
        }

        val capability = state.capability
        val summary = btFormat(current)
        Text(
            text = listOfNotNull(state.deviceName, BluetoothAudio.codecName(current), summary)
                .filter { it.isNotBlank() }
                .joinToString(" · "),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        if (state.optionalSupported) {
            Spacer(modifier = Modifier.height(12.dp))
            ModernSettingSwitch(
                title = stringResource(R.string.bt_optional),
                subtitle = stringResource(R.string.bt_optional_summary),
                checked = state.optionalEnabled,
                onCheckedChange = viewModel::setBluetoothOptionalCodecs,
                icon = Icons.Default.HighQuality
            )
        }

        BtGroup(title = stringResource(R.string.bt_codec)) {
            AssistChip(
                onClick = viewModel::selectBluetoothAutomatic,
                label = { Text(stringResource(R.string.bt_codec_auto)) },
                leadingIcon = {
                    Icon(Icons.Default.AutoAwesome, contentDescription = null, modifier = Modifier.size(18.dp))
                },
                shape = CircleShape
            )
            state.selectable.forEach { codec ->
                FilterChip(
                    selected = BluetoothAudio.sameCodec(codec, current),
                    onClick = { viewModel.selectBluetoothCodec(codec) },
                    label = { Text(BluetoothAudio.codecName(codec)) },
                    shape = CircleShape
                )
            }
        }

        if (capability != null) {
            val rates = BluetoothAudio.supported(capability.sampleRate, BluetoothAudio.SAMPLE_RATES)
            if (rates.size > 1) {
                BtGroup(title = stringResource(R.string.bt_sample_rate)) {
                    rates.forEach { (hz, mask) ->
                        FilterChip(
                            selected = current.sampleRate == mask,
                            onClick = { viewModel.setBluetoothSampleRate(mask) },
                            label = { Text(khz(hz)) },
                            shape = CircleShape
                        )
                    }
                }
            }

            val depths = BluetoothAudio.supported(capability.bitsPerSample, BluetoothAudio.BIT_DEPTHS)
            if (depths.size > 1) {
                BtGroup(title = stringResource(R.string.bt_bits)) {
                    depths.forEach { (bits, mask) ->
                        FilterChip(
                            selected = current.bitsPerSample == mask,
                            onClick = { viewModel.setBluetoothBitsPerSample(mask) },
                            label = { Text(stringResource(R.string.bt_bits_value, bits)) },
                            shape = CircleShape
                        )
                    }
                }
            }

            val channels = listOf(
                R.string.bt_channel_mono to BluetoothCodecConfig.CHANNEL_MODE_MONO,
                R.string.bt_channel_stereo to BluetoothCodecConfig.CHANNEL_MODE_STEREO
            ).filter { (capability.channelMode and it.second) != 0 }
            if (channels.size > 1) {
                BtGroup(title = stringResource(R.string.bt_channels)) {
                    channels.forEach { (label, mask) ->
                        FilterChip(
                            selected = current.channelMode == mask,
                            onClick = { viewModel.setBluetoothChannelMode(mask) },
                            label = { Text(stringResource(label)) },
                            shape = CircleShape
                        )
                    }
                }
            }
        }

        if (BluetoothAudio.isLdac(current)) {
            val quality = BluetoothAudio.ldacQuality(current)
            BtGroup(title = stringResource(R.string.bt_ldac_quality)) {
                listOf(
                    R.string.bt_ldac_audio to BluetoothAudio.LDAC_QUALITY_AUDIO,
                    R.string.bt_ldac_balanced to BluetoothAudio.LDAC_QUALITY_BALANCED,
                    R.string.bt_ldac_connection to BluetoothAudio.LDAC_QUALITY_CONNECTION,
                    R.string.bt_ldac_adaptive to BluetoothAudio.LDAC_QUALITY_ADAPTIVE
                ).forEach { (label, value) ->
                    FilterChip(
                        selected = quality == value,
                        onClick = { viewModel.setBluetoothLdacQuality(value) },
                        label = { Text(stringResource(label)) },
                        shape = CircleShape
                    )
                }
            }
        }

        if (BluetoothAudio.isLhdc(current)) {
            val quality = BluetoothAudio.lhdcQuality(current)
            val v5 = BluetoothAudio.isLhdcV5(current)
            // kbps per quality mode of the stack; LHDC V5 has 1000 and 160, V3 has 128 and no 1000
            val modes = buildList {
                add(BluetoothAudio.LHDC_QUALITY_ADAPTIVE to null)
                if (v5) add(8 to 1000)
                add(7 to 900)
                add(6 to 500)
                add(5 to 400)
                add(4 to 320)
                add(3 to 256)
                add(2 to 192)
                add(1 to if (v5) 160 else 128)
                add(0 to 64)
            }
            BtGroup(title = stringResource(R.string.bt_lhdc_quality)) {
                modes.forEach { (mode, kbps) ->
                    FilterChip(
                        selected = quality == mode,
                        onClick = { viewModel.setBluetoothLhdcQuality(mode) },
                        label = {
                            Text(
                                if (kbps == null) stringResource(R.string.bt_ldac_adaptive)
                                else stringResource(R.string.bt_kbps, kbps)
                            )
                        },
                        shape = CircleShape
                    )
                }
            }
            if (v5) {
                Spacer(modifier = Modifier.height(12.dp))
                ModernSettingSwitch(
                    title = stringResource(R.string.bt_lhdc_ll),
                    subtitle = stringResource(R.string.bt_lhdc_ll_summary),
                    checked = BluetoothAudio.lhdcLowLatency(current),
                    onCheckedChange = viewModel::setBluetoothLhdcLowLatency,
                    icon = Icons.Default.Speed
                )
            }
        }

        if (BluetoothAudio.isAac(current) && BluetoothAudio.aacVbrSupported(current)) {
            Spacer(modifier = Modifier.height(12.dp))
            ModernSettingSwitch(
                title = stringResource(R.string.bt_aac_vbr),
                subtitle = stringResource(R.string.bt_aac_vbr_summary),
                checked = BluetoothAudio.aacVbrEnabled(current),
                onCheckedChange = viewModel::setBluetoothAacVbr,
                icon = Icons.Default.Tune
            )
        }

        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = stringResource(R.string.bt_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun BtGroup(title: String, chips: @Composable RowScope.() -> Unit) {
    Spacer(modifier = Modifier.height(16.dp))
    Text(
        text = title,
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onSurface
    )
    Spacer(modifier = Modifier.height(8.dp))
    Row(
        modifier = Modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        content = chips
    )
}

@Composable
private fun btFormat(config: BluetoothCodecConfig): String {
    val rate = BluetoothAudio.SAMPLE_RATES.firstOrNull { it.second == config.sampleRate }?.first
    val bits = BluetoothAudio.BIT_DEPTHS.firstOrNull { it.second == config.bitsPerSample }?.first
    return listOfNotNull(
        rate?.let { khz(it) },
        bits?.let { stringResource(R.string.bt_bits_value, it) }
    ).joinToString(" · ")
}

@Composable
private fun khz(hz: Int): String {
    val value = hz / 1000f
    val text = if (value % 1f == 0f) value.toInt().toString() else value.toString()
    return stringResource(R.string.bt_khz, text)
}
