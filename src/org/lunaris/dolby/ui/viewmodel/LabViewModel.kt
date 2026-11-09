/*
 * SPDX-FileCopyrightText: 2026 Fakhri Adi Saputra
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lunaris.dolby.ui.viewmodel

import android.app.Application
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.lunaris.dolby.R
import android.bluetooth.BluetoothCodecConfig
import org.lunaris.dolby.audio.BluetoothAudio
import org.lunaris.dolby.audio.SoundLab

class LabViewModel(application: Application) : AndroidViewModel(application) {

    val state: StateFlow<SoundLab.Settings> = SoundLab.state
    val status: StateFlow<SoundLab.Status> = SoundLab.status
    val corrections: StateFlow<List<SoundLab.Correction>> = SoundLab.corrections
    val userPresets: StateFlow<List<SoundLab.UserPreset>> = SoundLab.userPresets

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val messages: SharedFlow<String> = _messages.asSharedFlow()

    private val bluetooth = BluetoothAudio(application, viewModelScope)
    val bluetoothState: StateFlow<BluetoothAudio.State> = bluetooth.state

    init {
        SoundLab.applySaved(application)
        bluetooth.start()
    }

    override fun onCleared() {
        bluetooth.stop()
    }

    fun selectBluetoothAutomatic() = bluetooth.selectAutomatic()
    fun selectBluetoothCodec(codec: BluetoothCodecConfig) = bluetooth.selectCodec(codec)
    fun setBluetoothSampleRate(rate: Int) = bluetooth.setSampleRate(rate)
    fun setBluetoothBitsPerSample(bits: Int) = bluetooth.setBitsPerSample(bits)
    fun setBluetoothChannelMode(mode: Int) = bluetooth.setChannelMode(mode)
    fun setBluetoothLdacQuality(value: Long) = bluetooth.setLdacQuality(value)
    fun setBluetoothLhdcQuality(value: Int) = bluetooth.setLhdcQuality(value)
    fun setBluetoothLhdcLowLatency(on: Boolean) = bluetooth.setLhdcLowLatency(on)
    fun setBluetoothAacVbr(on: Boolean) = bluetooth.setAacVbr(on)
    fun setBluetoothOptionalCodecs(on: Boolean) = bluetooth.setOptionalCodecs(on)
    fun refreshBluetooth() = bluetooth.refresh()

    private fun update(transform: (SoundLab.Settings) -> SoundLab.Settings) =
        SoundLab.update(getApplication(), transform)

    fun setEnabled(enabled: Boolean) = update { it.copy(enabled = enabled) }
    fun setBandGain(band: Int, gain: Float) = update {
        it.copy(eqGains = it.eqGains.toMutableList().apply { this[band] = gain })
    }
    fun resetEq() = update { it.copy(eqGains = SoundLab.FLAT) }
    fun setAutoPreamp(enabled: Boolean) = update { it.copy(autoPreamp = enabled) }
    fun setPreamp(db: Float) = update { it.copy(preampDb = db) }
    fun selectCorrection(id: String?) = update { it.copy(correctionId = id) }
    fun setCorrectionAmount(amount: Int) = update { it.copy(correctionAmount = amount) }
    fun setCorrectionHeadphonesOnly(enabled: Boolean) =
        update { it.copy(correctionHeadphonesOnly = enabled) }
    fun setPrecision(enabled: Boolean) = update { it.copy(precision = enabled) }
    fun setCompressor(enabled: Boolean) = update { it.copy(compressorEnabled = enabled) }
    fun setCompressorAmount(amount: Int) = update { it.copy(compressorAmount = amount) }
    fun setLimiter(enabled: Boolean) = update { it.copy(limiterEnabled = enabled) }
    fun setLimiterCeiling(db: Int) = update { it.copy(limiterCeilingDb = db) }
    fun setLoudness(mb: Int) = update { it.copy(loudnessMb = mb) }

    fun applyGains(gains: List<Float>) = SoundLab.applyGains(getApplication(), gains)
    fun saveUserPreset(name: String) = SoundLab.saveUserPreset(getApplication(), name)
    fun deleteUserPreset(name: String) = SoundLab.deleteUserPreset(getApplication(), name)
    fun deleteCorrection(id: String) = SoundLab.deleteCorrection(getApplication(), id)

    fun importCorrection(uri: Uri) {
        val app = getApplication<Application>()
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val text = app.contentResolver.openInputStream(uri)?.use {
                        it.readBytes().toString(Charsets.UTF_8)
                    } ?: error("cannot open")
                    SoundLab.importCorrection(app, profileName(uri), text).getOrThrow()
                }
            }
            _messages.tryEmit(result.fold(
                { app.getString(R.string.lab_correction_imported, it.name) },
                { app.getString(R.string.lab_correction_import_failed) }
            ))
        }
    }

    // "Sennheiser HD 650 ParametricEQ.txt" -> "Sennheiser HD 650"
    private fun profileName(uri: Uri): String {
        val app = getApplication<Application>()
        val display = runCatching {
            app.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
        }.getOrNull() ?: uri.lastPathSegment ?: ""
        return display.substringBeforeLast('.')
            .removeSuffix(" ParametricEQ")
            .removeSuffix(" GraphicEQ")
            .trim()
    }
}
