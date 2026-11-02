/*
 * SPDX-FileCopyrightText: 2026 Fakhri Adi Saputra
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lunaris.dolby.ui.screens

import androidx.compose.animation.*
import androidx.compose.foundation.background
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import kotlinx.coroutines.launch
import org.lunaris.dolby.R
import org.lunaris.dolby.audio.SoundLab
import org.lunaris.dolby.ui.components.*
import org.lunaris.dolby.ui.viewmodel.LabViewModel
import org.lunaris.dolby.utils.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SoundLabScreen(
    viewModel: LabViewModel,
    navController: NavController
) {
    val state by viewModel.state.collectAsState()
    val currentRoute by navController.currentBackStackEntryFlow.collectAsState(null)
    val layoutDirection = LocalLayoutDirection.current
    val cutoutInsets = WindowInsets.displayCutout.asPaddingValues()

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
        containerColor = MaterialTheme.colorScheme.surfaceContainer
    ) { paddingValues ->
        Box(modifier = Modifier.fillMaxSize()) {
            SoundLabContent(
                state = state,
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
    viewModel: LabViewModel,
    modifier: Modifier = Modifier
) {
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item {
            LabHeroCard(state = state, onEnabledChange = viewModel::setEnabled)
        }

        item {
            AnimatedVisibility(
                visible = state.enabled,
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically()
            ) {
                LabPresetRow(state = state, onPreset = viewModel::applyPreset)
            }
        }

        item {
            AnimatedVisibility(
                visible = state.enabled,
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically()
            ) {
                ModernSettingsCard(title = stringResource(R.string.lab_tone), icon = Icons.Default.Tune) {
                    ModernSettingSlider(
                        title = stringResource(R.string.lab_preamp),
                        value = state.preampDb,
                        onValueChange = { viewModel.setPreamp(it.toInt()) },
                        valueRange = SoundLab.PREAMP_MIN_DB.toFloat()..SoundLab.PREAMP_MAX_DB.toFloat(),
                        steps = SoundLab.PREAMP_MAX_DB - SoundLab.PREAMP_MIN_DB - 1,
                        valueLabel = { signedDb(it) }
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    ModernSettingSlider(
                        title = stringResource(R.string.lab_bass),
                        value = state.bassDb,
                        onValueChange = { viewModel.setBass(it.toInt()) },
                        valueRange = 0f..SoundLab.BASS_MAX_DB.toFloat(),
                        steps = SoundLab.BASS_MAX_DB - 1,
                        valueLabel = { signedDb(it) }
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    ModernSettingSlider(
                        title = stringResource(R.string.lab_clarity),
                        value = state.clarityDb,
                        onValueChange = { viewModel.setClarity(it.toInt()) },
                        valueRange = 0f..SoundLab.CLARITY_MAX_DB.toFloat(),
                        steps = SoundLab.CLARITY_MAX_DB - 1,
                        valueLabel = { signedDb(it) }
                    )
                }
            }
        }

        item {
            AnimatedVisibility(
                visible = state.enabled,
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically()
            ) {
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
                                onValueChange = { viewModel.setCompressorAmount(it.toInt()) },
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
                                onValueChange = { viewModel.setLimiterCeiling(it.toInt()) },
                                valueRange = SoundLab.LIMITER_MIN_DB.toFloat()..0f,
                                steps = -SoundLab.LIMITER_MIN_DB - 1,
                                valueLabel = { signedDb(it) }
                            )
                        }
                    }
                }
            }
        }

        item {
            AnimatedVisibility(
                visible = state.enabled,
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically()
            ) {
                ModernSettingsCard(
                    title = stringResource(R.string.lab_loudness),
                    icon = Icons.AutoMirrored.Filled.VolumeUp
                ) {
                    ModernSettingSlider(
                        title = stringResource(R.string.lab_loudness_amount),
                        value = state.loudnessMb / 100,
                        onValueChange = { viewModel.setLoudness(it.toInt() * 100) },
                        valueRange = 0f..(SoundLab.LOUDNESS_MAX_MB / 100).toFloat(),
                        steps = SoundLab.LOUDNESS_MAX_MB / 100 - 1,
                        valueLabel = { signedDb(it) }
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

private fun signedDb(value: Int) = if (value > 0) "+$value dB" else "$value dB"

@Composable
private fun LabHeroCard(state: SoundLab.Settings, onEnabledChange: (Boolean) -> Unit) {
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
                            stringResource(R.string.lab_preamp) to (state.preampDb != 0),
                            stringResource(R.string.lab_stage_eq) to (state.bassDb > 0 || state.clarityDb > 0),
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

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun LabPresetRow(state: SoundLab.Settings, onPreset: (SoundLab.Preset) -> Unit) {
    val haptic = rememberHapticFeedback()
    val scope = rememberCoroutineScope()
    val presets = listOf(
        SoundLab.Preset.NATURAL to (R.string.lab_preset_natural to Icons.Default.Hearing),
        SoundLab.Preset.PUNCHY to (R.string.lab_preset_punchy to Icons.Default.Bolt),
        SoundLab.Preset.VOCAL to (R.string.lab_preset_vocal to Icons.Default.RecordVoiceOver),
        SoundLab.Preset.NIGHT to (R.string.lab_preset_night to Icons.Default.NightsStay),
        SoundLab.Preset.LOUD to (R.string.lab_preset_loud to Icons.Default.Campaign)
    )
    val selected = SoundLab.Preset.entries.firstOrNull { it.matches(state) }

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
            if (selected == null) {
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
                    selected = preset == selected,
                    onClick = {
                        scope.launch { haptic.performHaptic(HapticFeedbackHelper.HapticIntensity.CLICK) }
                        onPreset(preset)
                    },
                    label = { Text(stringResource(label)) },
                    leadingIcon = {
                        Icon(imageVector = icon, contentDescription = null, modifier = Modifier.size(18.dp))
                    },
                    shape = CircleShape
                )
            }
        }
    }
}
