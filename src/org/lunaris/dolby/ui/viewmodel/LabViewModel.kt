/*
 * SPDX-FileCopyrightText: 2026 Fakhri Adi Saputra
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lunaris.dolby.ui.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import kotlinx.coroutines.flow.StateFlow
import org.lunaris.dolby.audio.SoundLab

class LabViewModel(application: Application) : AndroidViewModel(application) {

    val state: StateFlow<SoundLab.Settings> = SoundLab.state

    init {
        SoundLab.applySaved(application)
    }

    private fun update(transform: (SoundLab.Settings) -> SoundLab.Settings) =
        SoundLab.update(getApplication(), transform)

    fun setEnabled(enabled: Boolean) = update { it.copy(enabled = enabled) }
    fun setPreamp(db: Int) = update { it.copy(preampDb = db) }
    fun setBass(db: Int) = update { it.copy(bassDb = db) }
    fun setClarity(db: Int) = update { it.copy(clarityDb = db) }
    fun setCompressor(enabled: Boolean) = update { it.copy(compressorEnabled = enabled) }
    fun setCompressorAmount(amount: Int) = update { it.copy(compressorAmount = amount) }
    fun setLimiter(enabled: Boolean) = update { it.copy(limiterEnabled = enabled) }
    fun setLimiterCeiling(db: Int) = update { it.copy(limiterCeilingDb = db) }
    fun setLoudness(mb: Int) = update { it.copy(loudnessMb = mb) }
    fun applyPreset(preset: SoundLab.Preset) = SoundLab.applyPreset(getApplication(), preset)
}
