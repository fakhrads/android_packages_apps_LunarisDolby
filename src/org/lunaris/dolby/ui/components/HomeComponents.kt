/*
 * SPDX-FileCopyrightText: 2026 Fakhri Adi Saputra
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lunaris.dolby.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import org.lunaris.dolby.R
import org.lunaris.dolby.utils.*

/** Hero card on the home screen: master switch, live bars and what is shaping the sound now. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun DolbyHeroCard(
    enabled: Boolean,
    profile: Int,
    isOnSpeaker: Boolean,
    labEnabled: Boolean,
    onEnabledChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    val haptic = rememberHapticFeedback()
    val scope = rememberCoroutineScope()
    val colors = MaterialTheme.colorScheme
    val start by animateColorAsState(
        if (enabled) colors.primaryContainer else colors.surfaceContainerHighest,
        label = "hero_start"
    )
    val end by animateColorAsState(
        if (enabled) colors.tertiaryContainer else colors.surfaceContainerHigh,
        label = "hero_end"
    )
    val content = if (enabled) colors.onPrimaryContainer else colors.onSurfaceVariant
    val barsAlpha by animateFloatAsState(if (enabled) 1f else 0.35f, label = "hero_bars")

    val profileNames = stringArrayResource(R.array.dolby_profile_entries)
    val profileValues = stringArrayResource(R.array.dolby_profile_values)
    val profileName = profileValues.indexOfFirst { it.toIntOrNull() == profile }
        .let { profileNames.getOrNull(it) } ?: ""

    Card(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.extraLarge,
        colors = CardDefaults.cardColors(containerColor = Color.Transparent),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(Brush.linearGradient(listOf(start, end)))
                .padding(24.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.dolby_enable),
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                        color = content
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = if (enabled) stringResource(R.string.dolby_on)
                        else stringResource(R.string.dolby_off),
                        style = MaterialTheme.typography.bodyMedium,
                        color = content.copy(alpha = 0.8f)
                    )
                }
                Switch(
                    checked = enabled,
                    onCheckedChange = {
                        scope.launch {
                            haptic.performHaptic(HapticFeedbackHelper.HapticIntensity.HEAVY_CLICK)
                        }
                        onEnabledChange(it)
                    },
                    thumbContent = {
                        Icon(
                            imageVector = if (enabled) Icons.Rounded.Check else Icons.Rounded.Close,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                )
            }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 20.dp)
                    .alpha(barsAlpha),
                contentAlignment = Alignment.Center
            ) {
                AnimatedEqualizerHeader(
                    color = content,
                    width = 200.dp,
                    height = 72.dp,
                    barCount = 15
                )
            }

            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                StatusChip(
                    icon = if (isOnSpeaker) Icons.Default.Speaker else Icons.Default.Headphones,
                    label = stringResource(
                        if (isOnSpeaker) R.string.dolby_status_speaker
                        else R.string.dolby_status_headphones
                    ),
                    contentColor = content
                )
                if (enabled && profileName.isNotEmpty()) {
                    StatusChip(
                        icon = Icons.Default.SurroundSound,
                        label = profileName,
                        contentColor = content
                    )
                }
                if (labEnabled) {
                    StatusChip(
                        icon = Icons.Default.Science,
                        label = stringResource(R.string.dolby_status_lab),
                        contentColor = content
                    )
                }
            }
        }
    }
}

@Composable
private fun StatusChip(icon: ImageVector, label: String, contentColor: Color) {
    Surface(
        shape = CircleShape,
        color = contentColor.copy(alpha = 0.12f),
        contentColor = contentColor
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(imageVector = icon, contentDescription = null, modifier = Modifier.size(16.dp))
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge,
                maxLines = 1
            )
        }
    }
}

data class FeatureTileItem(
    val icon: ImageVector,
    val title: String,
    val checked: Boolean,
    val onToggle: (Boolean) -> Unit
)

/** Two column grid of toggle tiles, laid out without a nested lazy list. */
@Composable
fun FeatureTileGrid(
    title: String,
    tiles: List<FeatureTileItem>,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 8.dp, bottom = 12.dp)
        )
        tiles.chunked(2).forEach { row ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                row.forEach { tile ->
                    FeatureTile(tile, modifier = Modifier.weight(1f))
                }
                if (row.size == 1) Spacer(modifier = Modifier.weight(1f))
            }
            Spacer(modifier = Modifier.height(12.dp))
        }
    }
}

@Composable
private fun FeatureTile(tile: FeatureTileItem, modifier: Modifier = Modifier) {
    val haptic = rememberHapticFeedback()
    val scope = rememberCoroutineScope()
    val colors = MaterialTheme.colorScheme
    val container by animateColorAsState(
        if (tile.checked) colors.primaryContainer else colors.surfaceBright,
        label = "tile_container"
    )
    val content = if (tile.checked) colors.onPrimaryContainer else colors.onSurface
    val iconContainer by animateColorAsState(
        if (tile.checked) colors.primary else colors.surfaceContainerHighest,
        label = "tile_icon"
    )
    val iconColor = if (tile.checked) colors.onPrimary else colors.onSurfaceVariant

    Surface(
        onClick = {
            scope.launch { haptic.performHaptic(HapticFeedbackHelper.HapticIntensity.CLICK) }
            tile.onToggle(!tile.checked)
        },
        modifier = modifier
            .squishable()
            .height(128.dp),
        shape = MaterialTheme.shapes.extraLarge,
        color = container,
        contentColor = content
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(iconContainer),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = tile.icon,
                    contentDescription = null,
                    tint = iconColor,
                    modifier = Modifier.size(22.dp)
                )
            }
            Spacer(modifier = Modifier.weight(1f))
            Text(
                text = tile.title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = stringResource(if (tile.checked) R.string.dolby_tile_on else R.string.dolby_tile_off),
                style = MaterialTheme.typography.bodySmall,
                color = content.copy(alpha = 0.75f)
            )
        }
    }
}

/** Entry to Sound Lab from the home screen, with its own switch. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun SoundLabEntryCard(
    enabled: Boolean,
    onEnabledChange: (Boolean) -> Unit,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier
) {
    val haptic = rememberHapticFeedback()
    val scope = rememberCoroutineScope()
    Card(
        onClick = onOpen,
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.extraLarge,
        colors = CardDefaults.cardColors(
            containerColor = if (enabled) MaterialTheme.colorScheme.secondaryContainer
            else MaterialTheme.colorScheme.surfaceBright
        )
    ) {
        Row(
            modifier = Modifier.padding(20.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(MaterialTheme.shapes.large)
                    .background(MaterialTheme.colorScheme.secondary),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Science,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSecondary
                )
            }
            Spacer(modifier = Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.lab_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = stringResource(R.string.lab_summary),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(modifier = Modifier.width(12.dp))
            Switch(
                checked = enabled,
                onCheckedChange = {
                    scope.launch {
                        haptic.performHaptic(HapticFeedbackHelper.HapticIntensity.DOUBLE_CLICK)
                    }
                    onEnabledChange(it)
                }
            )
        }
    }
}
