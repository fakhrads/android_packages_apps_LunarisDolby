/*
 * SPDX-FileCopyrightText: 2026 Fakhri Adi Saputra
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lunaris.dolby.audio

import android.content.Context
import android.media.AudioManager
import android.media.AudioPlaybackConfiguration
import android.media.audiofx.DynamicsProcessing
import android.media.audiofx.LoudnessEnhancer
import android.os.Handler
import android.os.Looper
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.min

/**
 * Global post processing on top of the Dolby DAP, built from the AOSP DynamicsProcessing and
 * LoudnessEnhancer effects on the output mix (session 0). Both run in the audio HAL's AIDL effect
 * factory, so nothing native is shipped here.
 *
 * Chain: preamp -> pre EQ (bass, clarity) -> multiband compressor -> limiter, then the loudness
 * boost.
 */
object SoundLab {

    data class Settings(
        val enabled: Boolean = false,
        val preampDb: Int = 0,
        val bassDb: Int = 0,
        val clarityDb: Int = 0,
        val compressorEnabled: Boolean = false,
        val compressorAmount: Int = 40,
        val limiterEnabled: Boolean = true,
        val limiterCeilingDb: Int = -1,
        val loudnessMb: Int = 0
    )

    enum class Preset(val settings: Settings) {
        NATURAL(Settings(enabled = true)),
        PUNCHY(Settings(enabled = true, bassDb = 6, clarityDb = 2, compressorEnabled = true,
            compressorAmount = 35)),
        VOCAL(Settings(enabled = true, bassDb = 0, clarityDb = 5, compressorEnabled = true,
            compressorAmount = 25)),
        NIGHT(Settings(enabled = true, preampDb = 2, compressorEnabled = true,
            compressorAmount = 80, limiterCeilingDb = -3)),
        LOUD(Settings(enabled = true, bassDb = 3, clarityDb = 2, compressorEnabled = true,
            compressorAmount = 50, limiterCeilingDb = -1, loudnessMb = 600));

        /** Matches on the sound, the master switch is not part of a preset. */
        fun matches(s: Settings) = settings.copy(enabled = s.enabled) == s
    }

    const val PREAMP_MIN_DB = -12
    const val PREAMP_MAX_DB = 12
    const val BASS_MAX_DB = 12
    const val CLARITY_MAX_DB = 10
    const val LIMITER_MIN_DB = -12
    const val LOUDNESS_MAX_MB = 1200

    private const val TAG = "SoundLab"
    private const val PRIORITY = 100

    private const val PREFS = "dolby_prefs"
    private const val KEY_ENABLED = "lab_enabled"
    private const val KEY_PREAMP = "lab_preamp_db"
    private const val KEY_BASS = "lab_bass_db"
    private const val KEY_CLARITY = "lab_clarity_db"
    private const val KEY_COMPRESSOR = "lab_compressor"
    private const val KEY_COMPRESSOR_AMOUNT = "lab_compressor_amount"
    private const val KEY_LIMITER = "lab_limiter"
    private const val KEY_LIMITER_CEILING = "lab_limiter_ceiling_db"
    private const val KEY_LOUDNESS = "lab_loudness_mb"

    private val EQ_CUTOFFS = floatArrayOf(60f, 150f, 400f, 1000f, 3000f, 6000f, 12000f, 20000f)
    private val BASS_WEIGHTS = floatArrayOf(1f, 0.7f, 0.25f, 0f, 0f, 0f, 0f, 0f)
    private val CLARITY_WEIGHTS = floatArrayOf(0f, 0f, 0f, 0f, 0.4f, 0.8f, 1f, 0.8f)
    private val MBC_CUTOFFS = floatArrayOf(250f, 4000f, 20000f)
    private val MBC_ATTACK_MS = floatArrayOf(20f, 8f, 3f)
    private val MBC_RELEASE_MS = floatArrayOf(200f, 120f, 80f)

    private val lock = Any()
    private var dynamics: DynamicsProcessing? = null
    private var loudness: LoudnessEnhancer? = null
    private var playbackCallbackRegistered = false

    private val _state = MutableStateFlow(Settings())
    val state: StateFlow<Settings> = _state.asStateFlow()

    /** Loads the saved settings and applies them; called at boot and by the effect service. */
    fun applySaved(context: Context) {
        val settings = load(context)
        _state.value = settings
        apply(context.applicationContext, settings)
    }

    fun update(context: Context, transform: (Settings) -> Settings) {
        val settings = sanitize(transform(_state.value))
        _state.value = settings
        save(context, settings)
        apply(context.applicationContext, settings)
    }

    fun applyPreset(context: Context, preset: Preset) = update(context) {
        preset.settings.copy(enabled = true)
    }

    private fun sanitize(s: Settings) = s.copy(
        preampDb = s.preampDb.coerceIn(PREAMP_MIN_DB, PREAMP_MAX_DB),
        bassDb = s.bassDb.coerceIn(0, BASS_MAX_DB),
        clarityDb = s.clarityDb.coerceIn(0, CLARITY_MAX_DB),
        compressorAmount = s.compressorAmount.coerceIn(0, 100),
        limiterCeilingDb = s.limiterCeilingDb.coerceIn(LIMITER_MIN_DB, 0),
        loudnessMb = s.loudnessMb.coerceIn(0, LOUDNESS_MAX_MB)
    )

    private fun load(context: Context): Settings {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val d = Settings()
        return sanitize(Settings(
            enabled = p.getBoolean(KEY_ENABLED, d.enabled),
            preampDb = p.getInt(KEY_PREAMP, d.preampDb),
            bassDb = p.getInt(KEY_BASS, d.bassDb),
            clarityDb = p.getInt(KEY_CLARITY, d.clarityDb),
            compressorEnabled = p.getBoolean(KEY_COMPRESSOR, d.compressorEnabled),
            compressorAmount = p.getInt(KEY_COMPRESSOR_AMOUNT, d.compressorAmount),
            limiterEnabled = p.getBoolean(KEY_LIMITER, d.limiterEnabled),
            limiterCeilingDb = p.getInt(KEY_LIMITER_CEILING, d.limiterCeilingDb),
            loudnessMb = p.getInt(KEY_LOUDNESS, d.loudnessMb)
        ))
    }

    private fun save(context: Context, s: Settings) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_ENABLED, s.enabled)
            .putInt(KEY_PREAMP, s.preampDb)
            .putInt(KEY_BASS, s.bassDb)
            .putInt(KEY_CLARITY, s.clarityDb)
            .putBoolean(KEY_COMPRESSOR, s.compressorEnabled)
            .putInt(KEY_COMPRESSOR_AMOUNT, s.compressorAmount)
            .putBoolean(KEY_LIMITER, s.limiterEnabled)
            .putInt(KEY_LIMITER_CEILING, s.limiterCeilingDb)
            .putInt(KEY_LOUDNESS, s.loudnessMb)
            .apply()
    }

    private fun apply(context: Context, s: Settings) = synchronized(lock) {
        if (!s.enabled) {
            releaseLocked()
            return@synchronized
        }
        registerPlaybackCallback(context)
        try {
            val dp = dynamicsLocked()
            dp.setInputGainAllChannelsTo(s.preampDb.toFloat())
            dp.setPreEqAllChannelsTo(preEq(s))
            dp.setMbcAllChannelsTo(compressor(s))
            dp.setLimiterAllChannelsTo(limiter(s))
            dp.enabled = true
        } catch (e: Exception) {
            Log.w(TAG, "Failed to apply dynamics processing: ${e.message}")
            dynamics?.let { runCatching { it.release() } }
            dynamics = null
        }
        try {
            if (s.loudnessMb > 0) {
                val le = loudness?.takeIf { it.hasControl() } ?: LoudnessEnhancer(0).also {
                    loudness?.let { old -> runCatching { old.release() } }
                    loudness = it
                }
                le.setTargetGain(s.loudnessMb)
                le.enabled = true
            } else {
                loudness?.let { runCatching { it.release() } }
                loudness = null
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to apply loudness boost: ${e.message}")
            loudness?.let { runCatching { it.release() } }
            loudness = null
        }
    }

    // Recreated when the audio server dropped it or another app took control of the session
    private fun dynamicsLocked(): DynamicsProcessing {
        dynamics?.let { dp ->
            if (runCatching { dp.hasControl() }.getOrDefault(false)) return dp
            runCatching { dp.release() }
        }
        val config = DynamicsProcessing.Config.Builder(
            DynamicsProcessing.VARIANT_FAVOR_FREQUENCY_RESOLUTION, 2,
            true, EQ_CUTOFFS.size,
            true, MBC_CUTOFFS.size,
            false, 0,
            true
        ).build()
        return DynamicsProcessing(PRIORITY, 0, config).also { dynamics = it }
    }

    private fun preEq(s: Settings): DynamicsProcessing.Eq {
        val eq = DynamicsProcessing.Eq(true, s.bassDb > 0 || s.clarityDb > 0, EQ_CUTOFFS.size)
        for (i in EQ_CUTOFFS.indices) {
            val gain = s.bassDb * BASS_WEIGHTS[i] + s.clarityDb * CLARITY_WEIGHTS[i]
            eq.setBand(i, DynamicsProcessing.EqBand(true, EQ_CUTOFFS[i], gain))
        }
        return eq
    }

    // Amount 0..100 moves the threshold from -10 to -30 dB and the ratio from 1.5:1 to 5:1, with
    // half of the gain reduction made up again so the level stays about the same
    private fun compressor(s: Settings): DynamicsProcessing.Mbc {
        val f = s.compressorAmount / 100f
        val threshold = -10f - 20f * f
        val ratio = 1.5f + 3.5f * f
        val makeup = min(9f, -threshold * (1f - 1f / ratio) * 0.5f)
        val mbc = DynamicsProcessing.Mbc(true, s.compressorEnabled, MBC_CUTOFFS.size)
        for (i in MBC_CUTOFFS.indices) {
            mbc.setBand(i, DynamicsProcessing.MbcBand(
                true, MBC_CUTOFFS[i], MBC_ATTACK_MS[i], MBC_RELEASE_MS[i], ratio, threshold,
                6f, -90f, 1f, 0f, makeup
            ))
        }
        return mbc
    }

    private fun limiter(s: Settings) = DynamicsProcessing.Limiter(
        true, s.limiterEnabled, 0, 1f, 60f, 10f, s.limiterCeilingDb.toFloat(), 0f
    )

    private fun releaseLocked() {
        dynamics?.let { runCatching { it.release() } }
        dynamics = null
        loudness?.let { runCatching { it.release() } }
        loudness = null
    }

    // The effects die with the audio server; put them back when playback starts again
    private fun registerPlaybackCallback(context: Context) {
        if (playbackCallbackRegistered) return
        val audioManager = context.getSystemService(AudioManager::class.java) ?: return
        audioManager.registerAudioPlaybackCallback(object : AudioManager.AudioPlaybackCallback() {
            override fun onPlaybackConfigChanged(configs: MutableList<AudioPlaybackConfiguration>) {
                if (!_state.value.enabled || configs.none { it.isActive }) return
                val alive = synchronized(lock) {
                    runCatching { dynamics?.hasControl() == true }.getOrDefault(false)
                }
                if (!alive) apply(context, _state.value)
            }
        }, Handler(Looper.getMainLooper()))
        playbackCallbackRegistered = true
    }
}
