/*
 * SPDX-FileCopyrightText: 2026 Fakhri Adi Saputra
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lunaris.dolby.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import org.lunaris.dolby.audio.SoundLab
import org.lunaris.dolby.utils.HapticFeedbackHelper
import org.lunaris.dolby.utils.rememberHapticFeedback
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.roundToInt

private const val CURVE_POINTS = 160
private const val CURVE_MIN_HZ = 20.0
private const val CURVE_MAX_HZ = 20000.0
private val GRID_HZ = listOf(31.0, 62.0, 125.0, 250.0, 500.0, 1000.0, 2000.0, 4000.0, 8000.0, 16000.0)

fun formatHz(hz: Float) = if (hz >= 1000f) "${(hz / 1000f).let { if (it % 1f == 0f) it.toInt().toString() else it.toString() }}k"
    else hz.roundToInt().toString()

fun formatDb(db: Float): String {
    val v = (db * 10f).roundToInt() / 10f
    val s = if (v % 1f == 0f) v.toInt().toString() else v.toString()
    return if (v > 0f) "+$s" else s
}

/**
 * The EQ and the headphone correction on a log frequency axis. The thin line is the EQ alone,
 * the filled one everything together (before the preamp).
 */
@Composable
fun LabResponseCurve(settings: SoundLab.Settings, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    val textMeasurer = rememberTextMeasurer()
    val labelStyle = MaterialTheme.typography.labelSmall.copy(
        color = colors.onSurfaceVariant, fontSize = 10.sp
    )
    val hasCorrection = settings.correctionId != null
    val (total, eqOnly) = remember(settings.eqGains, settings.correctionId, settings.correctionAmount) {
        val freqs = DoubleArray(CURVE_POINTS) {
            CURVE_MIN_HZ * (CURVE_MAX_HZ / CURVE_MIN_HZ).pow(it / (CURVE_POINTS - 1.0))
        }
        val eq = SoundLab.eqCurve(settings.eqGains)
        freqs.map { SoundLab.responseDb(eq, settings, it).toFloat() } to
            freqs.map { eq.gainDb(it).toFloat() }
    }
    val peak = (total + eqOnly).maxOfOrNull { abs(it) } ?: 0f
    val range = (ceil(peak / 6f) * 6f).coerceIn(12f, 24f)

    Canvas(modifier = modifier) {
        val labelHeight = 14.dp.toPx()
        val plotHeight = size.height - labelHeight
        fun x(hz: Double) = (log10(hz / CURVE_MIN_HZ) / log10(CURVE_MAX_HZ / CURVE_MIN_HZ)).toFloat() * size.width
        fun y(db: Float) = plotHeight / 2f - db / range * (plotHeight / 2f)

        drawRoundRect(
            color = colors.surfaceContainerHighest.copy(alpha = 0.5f),
            size = Size(size.width, plotHeight),
            cornerRadius = CornerRadius(16.dp.toPx())
        )
        for (db in listOf(-range / 2f, range / 2f)) {
            drawLine(colors.outlineVariant.copy(alpha = 0.5f), Offset(0f, y(db)), Offset(size.width, y(db)), 1f)
        }
        drawLine(colors.outline.copy(alpha = 0.7f), Offset(0f, y(0f)), Offset(size.width, y(0f)), 1.5f)
        GRID_HZ.forEach { hz ->
            val gx = x(hz)
            drawLine(colors.outlineVariant.copy(alpha = 0.35f), Offset(gx, 0f), Offset(gx, plotHeight), 1f)
            val layout = textMeasurer.measure(formatHz(hz.toFloat()), labelStyle)
            drawText(layout, topLeft = Offset(
                (gx - layout.size.width / 2f).coerceIn(0f, size.width - layout.size.width),
                plotHeight + 2.dp.toPx()
            ))
        }
        val dbLabel = textMeasurer.measure("±${range.toInt()} dB", labelStyle)
        drawText(dbLabel, topLeft = Offset(6.dp.toPx(), 4.dp.toPx()))

        fun path(values: List<Float>) = Path().apply {
            values.forEachIndexed { i, db ->
                val px = i / (CURVE_POINTS - 1f) * size.width
                val py = y(db.coerceIn(-range, range))
                if (i == 0) moveTo(px, py) else lineTo(px, py)
            }
        }
        val totalPath = path(total)
        val fill = Path().apply {
            addPath(totalPath)
            lineTo(size.width, y(0f))
            lineTo(0f, y(0f))
            close()
        }
        drawPath(fill, Brush.verticalGradient(
            listOf(colors.primary.copy(alpha = 0.30f), colors.primary.copy(alpha = 0.04f)),
            startY = 0f, endY = plotHeight
        ))
        if (hasCorrection) {
            drawPath(path(eqOnly), colors.tertiary.copy(alpha = 0.8f),
                style = Stroke(width = 1.5.dp.toPx(), cap = StrokeCap.Round))
        }
        drawPath(totalPath, colors.primary, style = Stroke(width = 2.5.dp.toPx(), cap = StrokeCap.Round))
    }
}

/**
 * One band of the graphic EQ: drag up or down in 0.5 dB steps, double tap for 0 dB.
 */
@Composable
fun VerticalGainSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    maxDb: Float = SoundLab.EQ_MAX_DB,
    stepDb: Float = SoundLab.EQ_STEP_DB
) {
    val colors = MaterialTheme.colorScheme
    val haptic = rememberHapticFeedback()
    val scope = rememberCoroutineScope()
    val currentValue by rememberUpdatedState(value)
    val currentOnChange by rememberUpdatedState(onValueChange)

    fun valueAt(y: Float, height: Float): Float {
        val raw = maxDb - (y / height).coerceIn(0f, 1f) * 2f * maxDb
        return (raw / stepDb).roundToInt() * stepDb
    }

    fun set(newValue: Float) {
        if (newValue == currentValue) return
        scope.launch { haptic.performHaptic(HapticFeedbackHelper.HapticIntensity.TEXTURE_TICK) }
        currentOnChange(newValue)
    }

    Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = formatDb(value),
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold,
            color = if (value == 0f) colors.onSurfaceVariant else colors.primary,
            maxLines = 1
        )
        Spacer(modifier = Modifier.height(6.dp))
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .pointerInput(maxDb) {
                    detectVerticalDragGestures(
                        onDragStart = { set(valueAt(it.y, size.height.toFloat())) }
                    ) { change, _ ->
                        change.consume()
                        set(valueAt(change.position.y, size.height.toFloat()))
                    }
                }
                .pointerInput(maxDb) {
                    detectTapGestures(onDoubleTap = { set(0f) })
                }
        ) {
            val cx = size.width / 2f
            val track = 6.dp.toPx()
            fun y(db: Float) = (maxDb - db) / (2f * maxDb) * size.height
            drawRoundRect(
                color = colors.surfaceContainerHighest,
                topLeft = Offset(cx - track / 2f, 0f),
                size = Size(track, size.height),
                cornerRadius = CornerRadius(track / 2f)
            )
            val zero = y(0f)
            val pos = y(value)
            drawRoundRect(
                color = colors.primary,
                topLeft = Offset(cx - track / 2f, minOf(zero, pos)),
                size = Size(track, abs(pos - zero).coerceAtLeast(1f)),
                cornerRadius = CornerRadius(track / 2f)
            )
            drawLine(colors.outline, Offset(cx - track, zero), Offset(cx + track, zero), 1.5f)
            drawCircle(colors.primary, radius = 9.dp.toPx(), center = Offset(cx, pos))
            drawCircle(colors.onPrimary, radius = 3.5.dp.toPx(), center = Offset(cx, pos))
        }
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = colors.onSurfaceVariant,
            maxLines = 1
        )
    }
}
