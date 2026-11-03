/*
 * SPDX-FileCopyrightText: 2026 Fakhri Adi Saputra
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lunaris.dolby.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.AudioPlaybackConfiguration
import android.media.audiofx.DynamicsProcessing
import android.media.audiofx.LoudnessEnhancer
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.exp
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * Global post processing on top of the Dolby DAP, built from the AOSP DynamicsProcessing and
 * LoudnessEnhancer effects on the output mix (session 0). Both run in the audio HAL's AIDL effect
 * factory, so nothing native is shipped here.
 *
 * Chain: preamp -> EQ (10 band graphic EQ plus the headphone correction) -> multiband compressor
 * -> limiter, then the loudness boost.
 *
 * DynamicsProcessing runs its EQ on FFT bins and gives each bin the gain of the band it falls in,
 * so the EQ curve is sampled onto bands that follow the bins: one band per bin in the lows, log
 * spaced groups of bins higher up (at most 128 bands, the AIDL limit). A 10 ms frame gives 512
 * point FFTs (94 Hz bins at 48 kHz); precision mode uses 40 ms frames for 2048 points (23 Hz bins)
 * at the cost of more latency.
 *
 * A new effect is checked by reading a few values back. If the effect refuses a configuration,
 * the next simpler one is used, down to the 8 band, default frame setup the first Sound Lab ran.
 */
object SoundLab {

    data class Settings(
        val enabled: Boolean = false,
        val eqGains: List<Float> = FLAT,
        val autoPreamp: Boolean = true,
        val preampDb: Float = 0f,
        val correctionId: String? = null,
        val correctionAmount: Int = 100,
        val correctionHeadphonesOnly: Boolean = true,
        val precision: Boolean = false,
        val compressorEnabled: Boolean = false,
        val compressorAmount: Int = 40,
        val limiterEnabled: Boolean = true,
        val limiterCeilingDb: Int = -1,
        val loudnessMb: Int = 0
    )

    /** What the last apply actually did, for the UI. */
    data class Status(
        val preampDb: Float = 0f,
        val correctionActive: Boolean = false,
        val precision: Boolean = false,
        val onHeadphones: Boolean = false,
        val precisionUnavailable: Boolean = false
    )

    data class Correction(val id: String, val name: String, val format: CorrectionParser.Format)

    data class UserPreset(val name: String, val gains: List<Float>)

    // Starting points for the 10 band EQ (31 Hz .. 16 kHz), not measured targets
    enum class EqPreset(val gains: List<Float>) {
        FLAT(SoundLab.FLAT),
        WARM(listOf(3f, 2.5f, 1.5f, 0.5f, 0f, 0f, -0.5f, -1f, -1.5f, -2f)),
        BASS(listOf(6f, 5f, 3.5f, 1.5f, 0f, 0f, 0f, 0f, 0f, 0f)),
        V_SHAPE(listOf(4f, 3f, 1f, 0f, -1.5f, -1.5f, 0f, 1.5f, 3f, 3.5f)),
        VOCAL(listOf(-1f, -1f, -0.5f, 0f, 1f, 2.5f, 3f, 2f, 0.5f, 0f)),
        BRIGHT(listOf(0f, 0f, 0f, 0f, 0f, 0.5f, 1.5f, 2.5f, 3.5f, 4f));
    }

    val EQ_FREQUENCIES = floatArrayOf(31f, 62f, 125f, 250f, 500f, 1000f, 2000f, 4000f, 8000f, 16000f)
    val FLAT: List<Float> = List(10) { 0f }

    const val EQ_MAX_DB = 12f
    const val EQ_STEP_DB = 0.5f
    const val PREAMP_MIN_DB = -12f
    const val PREAMP_MAX_DB = 12f
    const val LIMITER_MIN_DB = -12
    const val LOUDNESS_MAX_MB = 1200
    const val MAX_USER_PRESETS = 20

    private const val TAG = "SoundLab"
    private const val PRIORITY = 100

    // About one octave per band
    private const val EQ_Q = 1.41
    private const val CURVE_RATE = 48000.0
    private const val ASSUMED_RATE = 48000f
    private const val TOP_CUTOFF_HZ = 24000f
    private const val BAND_POINTS = 8
    private const val BAND_MIN_DB = -40f
    private const val BAND_MAX_DB = 24f
    private const val APPLY_DELAY_MS = 40L
    private const val MAX_IMPORT_CHARS = 512 * 1024

    private const val PREFS = "dolby_prefs"
    private const val KEY_ENABLED = "lab_enabled"
    private const val KEY_EQ = "lab_eq_gains"
    private const val KEY_AUTO_PREAMP = "lab_auto_preamp"
    private const val KEY_PREAMP = "lab_preamp"
    private const val KEY_CORRECTION = "lab_correction_id"
    private const val KEY_CORRECTION_AMOUNT = "lab_correction_amount"
    private const val KEY_CORRECTION_HEADPHONES = "lab_correction_headphones"
    private const val KEY_PRECISION = "lab_precision"
    private const val KEY_COMPRESSOR = "lab_compressor"
    private const val KEY_COMPRESSOR_AMOUNT = "lab_compressor_amount"
    private const val KEY_LIMITER = "lab_limiter"
    private const val KEY_LIMITER_CEILING = "lab_limiter_ceiling_db"
    private const val KEY_LOUDNESS = "lab_loudness_mb"
    private const val KEY_USER_PRESETS = "lab_user_presets"

    // The first Sound Lab had bass and clarity sliders and an integer preamp
    private const val OLD_KEY_PREAMP = "lab_preamp_db"
    private const val OLD_KEY_BASS = "lab_bass_db"
    private const val OLD_KEY_CLARITY = "lab_clarity_db"
    private val OLD_BASS_WEIGHTS = floatArrayOf(1f, 1f, 0.7f, 0.25f, 0f, 0f, 0f, 0f, 0f, 0f)
    private val OLD_CLARITY_WEIGHTS = floatArrayOf(0f, 0f, 0f, 0f, 0f, 0f, 0.4f, 0.8f, 1f, 0.8f)

    private const val CORRECTIONS_DIR = "lab_corrections"

    private val MBC_CUTOFFS = floatArrayOf(250f, 4000f, 20000f)
    private val MBC_ATTACK_MS = floatArrayOf(20f, 8f, 3f)
    private val MBC_RELEASE_MS = floatArrayOf(200f, 120f, 80f)

    private val HEADPHONE_TYPES = setOf(
        AudioDeviceInfo.TYPE_WIRED_HEADSET,
        AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
        AudioDeviceInfo.TYPE_USB_HEADSET,
        AudioDeviceInfo.TYPE_USB_DEVICE,
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
        AudioDeviceInfo.TYPE_BLE_HEADSET
    )
    private val ATTRIBUTES_MEDIA = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_MEDIA)
        .build()

    private class EqLayout(val cutoffs: FloatArray, val low: FloatArray, val high: FloatArray) {
        val size get() = cutoffs.size
    }

    // Frame length (null: the effect's 10 ms default), log steps from 20 Hz to 20 kHz and the
    // cutoff of the top band. Simplest last; SAFE is what the first Sound Lab ran on onyx.
    private enum class Engine(val frameMs: Float?, val steps: Int, val topCutoffHz: Float) {
        PRECISION(40f, 127, TOP_CUTOFF_HZ),
        NORMAL(10f, 127, TOP_CUTOFF_HZ),
        SAFE(null, 8, 20000f)
    }

    private val lock = Any()
    private var dynamics: DynamicsProcessing? = null
    private var dynamicsEngine: Engine? = null
    private val refusedEngines = mutableSetOf<Engine>()
    private var appliedGains: FloatArray? = null
    private var appliedDynamics: Settings? = null
    private var loudness: LoudnessEnhancer? = null
    private var callbacksRegistered = false

    private val worker by lazy { Handler(HandlerThread(TAG).apply { start() }.looper) }
    private val applyPending = AtomicBoolean(false)
    private var appContext: Context? = null
    private var loaded = false

    @Volatile
    private var curves: Map<String, ResponseCurve> = emptyMap()
    private val layouts = mutableMapOf<Engine, EqLayout>()

    private val _state = MutableStateFlow(Settings())
    val state: StateFlow<Settings> = _state.asStateFlow()

    private val _status = MutableStateFlow(Status())
    val status: StateFlow<Status> = _status.asStateFlow()

    private val _corrections = MutableStateFlow<List<Correction>>(emptyList())
    val corrections: StateFlow<List<Correction>> = _corrections.asStateFlow()

    private val _userPresets = MutableStateFlow<List<UserPreset>>(emptyList())
    val userPresets: StateFlow<List<UserPreset>> = _userPresets.asStateFlow()

    /** Loads the saved settings and applies them; called at boot and by the app. */
    fun applySaved(context: Context) {
        ensureLoaded(context)
        scheduleApply()
    }

    fun update(context: Context, transform: (Settings) -> Settings) {
        ensureLoaded(context)
        _state.value = sanitize(transform(_state.value))
        scheduleApply()
    }

    fun applyGains(context: Context, gains: List<Float>) = update(context) {
        it.copy(enabled = true, eqGains = gains)
    }

    fun matches(gains: List<Float>, other: List<Float>) =
        gains.size == other.size && gains.indices.all { abs(gains[it] - other[it]) < 0.01f }

    /** EQ plus the selected correction at [frequency], before the preamp. */
    fun responseDb(settings: Settings, frequency: Double): Double =
        responseDb(eqCurve(settings.eqGains), settings, frequency)

    fun responseDb(eq: ResponseCurve, settings: Settings, frequency: Double): Double {
        var db = eq.gainDb(frequency)
        val curve = settings.correctionId?.let { curves[it] }
        if (curve != null) db += curve.gainDb(frequency) * settings.correctionAmount / 100.0
        return db
    }

    /** The 10 band EQ as peaking filters, about one octave wide each. */
    fun eqCurve(gains: List<Float>): ResponseCurve = BiquadCurve(gains.indices
        .filter { abs(gains[it]) >= 0.01f }
        .map { Biquad.peaking(EQ_FREQUENCIES[it].toDouble(), gains[it].toDouble(), EQ_Q, CURVE_RATE) })

    /** Parses and stores a correction file, then selects it. */
    fun importCorrection(context: Context, name: String, text: String): Result<Correction> {
        ensureLoaded(context)
        if (text.length > MAX_IMPORT_CHARS) {
            return Result.failure(CorrectionParser.ParseException("file too large"))
        }
        val parsed = try {
            CorrectionParser.parse(text)
        } catch (e: CorrectionParser.ParseException) {
            return Result.failure(e)
        }
        val correction = Correction(UUID.randomUUID().toString(), name.ifBlank { "Profile" }, parsed.format)
        try {
            val dir = File(context.filesDir, CORRECTIONS_DIR).apply { mkdirs() }
            File(dir, correction.id + ".json").writeText(JSONObject()
                .put("name", correction.name)
                .put("format", correction.format.name)
                .put("text", text)
                .toString())
        } catch (e: Exception) {
            return Result.failure(e)
        }
        curves = curves + (correction.id to parsed.curve)
        _corrections.value = (_corrections.value + correction).sortedBy { it.name.lowercase() }
        update(context) { it.copy(correctionId = correction.id) }
        return Result.success(correction)
    }

    fun deleteCorrection(context: Context, id: String) {
        ensureLoaded(context)
        File(File(context.filesDir, CORRECTIONS_DIR), "$id.json").delete()
        curves = curves - id
        _corrections.value = _corrections.value.filterNot { it.id == id }
        if (_state.value.correctionId == id) update(context) { it.copy(correctionId = null) }
    }

    fun saveUserPreset(context: Context, name: String) {
        ensureLoaded(context)
        val preset = UserPreset(name.trim(), _state.value.eqGains)
        if (preset.name.isEmpty()) return
        val list = (_userPresets.value.filterNot { it.name == preset.name } + preset)
            .takeLast(MAX_USER_PRESETS)
        _userPresets.value = list
        saveUserPresets(context, list)
    }

    fun deleteUserPreset(context: Context, name: String) {
        ensureLoaded(context)
        val list = _userPresets.value.filterNot { it.name == name }
        _userPresets.value = list
        saveUserPresets(context, list)
    }

    private fun ensureLoaded(context: Context) = synchronized(lock) {
        if (loaded) return@synchronized
        val app = context.applicationContext
        appContext = app
        _state.value = load(app)
        loadCorrections(app)
        _userPresets.value = loadUserPresets(app)
        loaded = true
    }

    private fun sanitize(s: Settings) = s.copy(
        eqGains = List(EQ_FREQUENCIES.size) { i ->
            val g = s.eqGains.getOrElse(i) { 0f }.coerceIn(-EQ_MAX_DB, EQ_MAX_DB)
            (g / EQ_STEP_DB).roundToInt() * EQ_STEP_DB
        },
        preampDb = ((s.preampDb.coerceIn(PREAMP_MIN_DB, PREAMP_MAX_DB) / EQ_STEP_DB).roundToInt()
            * EQ_STEP_DB),
        correctionId = s.correctionId?.takeIf { curves.containsKey(it) },
        correctionAmount = s.correctionAmount.coerceIn(0, 100),
        compressorAmount = s.compressorAmount.coerceIn(0, 100),
        limiterCeilingDb = s.limiterCeilingDb.coerceIn(LIMITER_MIN_DB, 0),
        loudnessMb = s.loudnessMb.coerceIn(0, LOUDNESS_MAX_MB)
    )

    private fun load(context: Context): Settings {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val d = Settings()
        var gains = p.getString(KEY_EQ, null)?.split(',')?.mapNotNull { it.toFloatOrNull() }
        var autoPreamp = p.getBoolean(KEY_AUTO_PREAMP, d.autoPreamp)
        var preamp = p.getFloat(KEY_PREAMP, d.preampDb)
        if (gains == null && (p.contains(OLD_KEY_BASS) || p.contains(OLD_KEY_CLARITY) ||
                    p.contains(OLD_KEY_PREAMP))) {
            val bass = p.getInt(OLD_KEY_BASS, 0)
            val clarity = p.getInt(OLD_KEY_CLARITY, 0)
            gains = List(EQ_FREQUENCIES.size) {
                bass * OLD_BASS_WEIGHTS[it] + clarity * OLD_CLARITY_WEIGHTS[it]
            }
            val oldPreamp = p.getInt(OLD_KEY_PREAMP, 0)
            if (oldPreamp != 0) {
                autoPreamp = false
                preamp = oldPreamp.toFloat()
            }
            p.edit().remove(OLD_KEY_BASS).remove(OLD_KEY_CLARITY).remove(OLD_KEY_PREAMP).apply()
        }
        return Settings(
            enabled = p.getBoolean(KEY_ENABLED, d.enabled),
            eqGains = gains ?: d.eqGains,
            autoPreamp = autoPreamp,
            preampDb = preamp,
            correctionId = p.getString(KEY_CORRECTION, null),
            correctionAmount = p.getInt(KEY_CORRECTION_AMOUNT, d.correctionAmount),
            correctionHeadphonesOnly = p.getBoolean(KEY_CORRECTION_HEADPHONES, d.correctionHeadphonesOnly),
            precision = p.getBoolean(KEY_PRECISION, d.precision),
            compressorEnabled = p.getBoolean(KEY_COMPRESSOR, d.compressorEnabled),
            compressorAmount = p.getInt(KEY_COMPRESSOR_AMOUNT, d.compressorAmount),
            limiterEnabled = p.getBoolean(KEY_LIMITER, d.limiterEnabled),
            limiterCeilingDb = p.getInt(KEY_LIMITER_CEILING, d.limiterCeilingDb),
            loudnessMb = p.getInt(KEY_LOUDNESS, d.loudnessMb)
        )
    }

    private fun save(context: Context, s: Settings) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_ENABLED, s.enabled)
            .putString(KEY_EQ, s.eqGains.joinToString(","))
            .putBoolean(KEY_AUTO_PREAMP, s.autoPreamp)
            .putFloat(KEY_PREAMP, s.preampDb)
            .putString(KEY_CORRECTION, s.correctionId)
            .putInt(KEY_CORRECTION_AMOUNT, s.correctionAmount)
            .putBoolean(KEY_CORRECTION_HEADPHONES, s.correctionHeadphonesOnly)
            .putBoolean(KEY_PRECISION, s.precision)
            .putBoolean(KEY_COMPRESSOR, s.compressorEnabled)
            .putInt(KEY_COMPRESSOR_AMOUNT, s.compressorAmount)
            .putBoolean(KEY_LIMITER, s.limiterEnabled)
            .putInt(KEY_LIMITER_CEILING, s.limiterCeilingDb)
            .putInt(KEY_LOUDNESS, s.loudnessMb)
            .apply()
    }

    private fun loadCorrections(context: Context) {
        val list = mutableListOf<Correction>()
        val map = mutableMapOf<String, ResponseCurve>()
        File(context.filesDir, CORRECTIONS_DIR).listFiles { f -> f.name.endsWith(".json") }
            ?.forEach { file ->
                try {
                    val json = JSONObject(file.readText())
                    val parsed = CorrectionParser.parse(json.getString("text"))
                    val id = file.name.removeSuffix(".json")
                    list += Correction(id, json.optString("name", id), parsed.format)
                    map[id] = parsed.curve
                } catch (e: Exception) {
                    Log.w(TAG, "Skipping correction ${file.name}: ${e.message}")
                }
            }
        curves = map
        _corrections.value = list.sortedBy { it.name.lowercase() }
    }

    private fun loadUserPresets(context: Context): List<UserPreset> = try {
        val array = JSONArray(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_USER_PRESETS, "[]"))
        List(array.length()) { i ->
            val o = array.getJSONObject(i)
            val g = o.getJSONArray("gains")
            UserPreset(o.getString("name"), List(g.length()) { g.getDouble(it).toFloat() })
        }
    } catch (e: Exception) {
        emptyList()
    }

    private fun saveUserPresets(context: Context, list: List<UserPreset>) {
        val array = JSONArray()
        list.forEach { p ->
            array.put(JSONObject().put("name", p.name).put("gains", JSONArray(p.gains.map { it.toDouble() })))
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_USER_PRESETS, array.toString())
            .apply()
    }

    // Slider drags send many updates; apply and save at most once per APPLY_DELAY_MS, always with
    // the latest settings, and keep the binder calls off the main thread
    private fun scheduleApply() {
        if (!applyPending.compareAndSet(false, true)) return
        worker.postDelayed({
            applyPending.set(false)
            val context = appContext ?: return@postDelayed
            val settings = _state.value
            save(context, settings)
            apply(context, settings)
        }, APPLY_DELAY_MS)
    }

    private fun apply(context: Context, s: Settings) = synchronized(lock) {
        if (!s.enabled) {
            releaseLocked()
            _status.value = Status()
            return@synchronized
        }
        registerCallbacks(context)
        val onHeadphones = isOnHeadphones(context)
        val correction = s.correctionId?.let { curves[it] }
            ?.takeIf { !s.correctionHeadphonesOnly || onHeadphones }
        val wanted = if (s.precision || s.correctionId != null) Engine.PRECISION else Engine.NORMAL
        val failed = mutableListOf<Engine>()
        var used: Engine? = null
        var preamp = 0f
        for (engine in Engine.entries.filter { it.ordinal >= wanted.ordinal && it !in refusedEngines }) {
            try {
                preamp = applyDynamicsLocked(engine, s, correction)
                used = engine
                break
            } catch (e: Exception) {
                Log.w(TAG, "Dynamics processing ${engine.name} failed: ${e.message}")
                releaseDynamicsLocked()
                failed += engine
            }
        }
        // A simpler engine worked, so the audio server is up and the effect refused the others
        if (used != null && failed.isNotEmpty()) {
            Log.w(TAG, "Effect refused ${failed.joinToString()}, using ${used.name}")
            refusedEngines += failed
        }
        _status.value = Status(
            preampDb = preamp,
            correctionActive = used != null && correction != null,
            precision = used == Engine.PRECISION,
            onHeadphones = onHeadphones,
            precisionUnavailable = wanted == Engine.PRECISION && Engine.PRECISION in refusedEngines
        )
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

    /** Sets everything on the effect for [engine]; returns the preamp. Throws if the effect refuses. */
    private fun applyDynamicsLocked(engine: Engine, s: Settings, correction: ResponseCurve?): Float {
        val fresh = dynamics == null || dynamicsEngine != engine ||
                !runCatching { dynamics!!.hasControl() }.getOrDefault(false)
        val dp = dynamicsLocked(engine)
        val layout = layout(engine)
        val eq = eqCurve(s.eqGains)
        val gains = FloatArray(layout.size) { bandGain(s, eq, correction, layout, it) }
        val preamp = if (s.autoPreamp) {
            -(((gains.maxOrNull() ?: 0f).coerceAtLeast(0f)) * 10f).roundToInt() / 10f
        } else {
            s.preampDb
        }
        val old = appliedGains
        if (fresh || old == null || old.size != gains.size) {
            val stage = DynamicsProcessing.Eq(true, true, layout.size)
            for (i in gains.indices) {
                stage.setBand(i, DynamicsProcessing.EqBand(true, layout.cutoffs[i], gains[i]))
            }
            dp.setPreEqAllChannelsTo(stage)
        } else {
            for (i in gains.indices) {
                if (abs(gains[i] - old[i]) < 0.005f) continue
                dp.setPreEqBandAllChannelsTo(i,
                    DynamicsProcessing.EqBand(true, layout.cutoffs[i], gains[i]))
            }
        }
        appliedGains = gains
        dp.setInputGainAllChannelsTo(preamp)
        val dynamicsKey = Settings(
            compressorEnabled = s.compressorEnabled, compressorAmount = s.compressorAmount,
            limiterEnabled = s.limiterEnabled, limiterCeilingDb = s.limiterCeilingDb
        )
        if (fresh || appliedDynamics != dynamicsKey) {
            dp.setMbcAllChannelsTo(compressor(s))
            dp.setLimiterAllChannelsTo(limiter(s))
            appliedDynamics = dynamicsKey
        }
        if (fresh) verifyLocked(dp, layout, gains, preamp)
        dp.enabled = true
        return preamp
    }

    // The effect reports refused parameters as exceptions, but read a middle band, the top band
    // and the preamp back as well: the getters query the effect, not a cache
    private fun verifyLocked(dp: DynamicsProcessing, layout: EqLayout, gains: FloatArray, preamp: Float) {
        for (i in intArrayOf(layout.size / 2, layout.size - 1)) {
            for (ch in 0 until 2) {
                val band = dp.getPreEqBandByChannelIndex(ch, i)
                check(abs(band.gain - gains[i]) < 0.05f && abs(band.cutoffFrequency - layout.cutoffs[i]) < 1f) {
                    "band $i on channel $ch reads ${band.gain} dB at ${band.cutoffFrequency} Hz"
                }
            }
        }
        val input = dp.getInputGainByChannelIndex(0)
        check(abs(input - preamp) < 0.05f) { "input gain reads $input dB" }
    }

    // Recreated when the audio server dropped it, another app took control of the session, or
    // the engine changed
    private fun dynamicsLocked(engine: Engine): DynamicsProcessing {
        dynamics?.let { dp ->
            if (dynamicsEngine == engine &&
                runCatching { dp.hasControl() }.getOrDefault(false)) return dp
        }
        releaseDynamicsLocked()
        val builder = DynamicsProcessing.Config.Builder(
            DynamicsProcessing.VARIANT_FAVOR_FREQUENCY_RESOLUTION, 2,
            true, layout(engine).size,
            true, MBC_CUTOFFS.size,
            false, 0,
            true
        )
        engine.frameMs?.let { builder.setPreferredFrameDuration(it) }
        val dp = DynamicsProcessing(PRIORITY, 0, builder.build())
        dynamics = dp
        dynamicsEngine = engine
        return dp
    }

    private fun releaseDynamicsLocked() {
        dynamics?.let { runCatching { it.release() } }
        dynamics = null
        dynamicsEngine = null
        appliedGains = null
        appliedDynamics = null
    }

    // Bands that follow the FFT bins DynamicsProcessing will use: band i ends at bin stop[i]. The
    // cutoff sits a quarter bin past it so the effect's rounding lands on that bin.
    private fun layout(engine: Engine): EqLayout = layouts.getOrPut(engine) {
        val frameMs = engine.frameMs ?: 10f
        var block = 1
        while (block < frameMs * ASSUMED_RATE / 1000f) block = block shl 1
        val binHz = ASSUMED_RATE / block
        val lastBin = block / 2
        val stops = ArrayList<Int>()
        for (i in 0 until engine.steps) {
            val f = 20.0 * 1000.0.pow(i / (engine.steps - 1.0))
            val bin = (f / binHz).roundToInt()
            if (stops.isEmpty() || bin > stops.last()) stops += bin
        }
        // The last band runs to Nyquist, so no bin is left without the preamp; SAFE keeps the
        // first Sound Lab's 20 kHz top band as it was
        if (stops.last() < lastBin && engine.topCutoffHz > 20000f) stops += lastBin
        val cutoffs = FloatArray(stops.size) {
            if (it == stops.lastIndex) engine.topCutoffHz else (stops[it] + 0.25f) * binHz
        }
        val low = FloatArray(stops.size) {
            val first = if (it == 0) 0 else stops[it - 1] + 1
            ((first - 0.5f) * binHz).coerceIn(20f, 20000f)
        }
        val high = FloatArray(stops.size) { ((stops[it] + 0.5f) * binHz).coerceIn(low[it], 20000f) }
        EqLayout(cutoffs, low, high)
    }

    // Mean of the curve in dB over the band, on a log frequency scale
    private fun bandGain(
        s: Settings,
        eq: ResponseCurve,
        correction: ResponseCurve?,
        layout: EqLayout,
        band: Int
    ): Float {
        val lo = layout.low[band].toDouble()
        val hi = layout.high[band].toDouble()
        var sum = 0.0
        for (p in 0 until BAND_POINTS) {
            val f = if (hi <= lo) lo else exp(ln(lo) + (ln(hi) - ln(lo)) * (p + 0.5) / BAND_POINTS)
            var db = eq.gainDb(f)
            if (correction != null) db += correction.gainDb(f) * s.correctionAmount / 100.0
            sum += db
        }
        return (sum / BAND_POINTS).toFloat().coerceIn(BAND_MIN_DB, BAND_MAX_DB)
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
        releaseDynamicsLocked()
        loudness?.let { runCatching { it.release() } }
        loudness = null
    }

    private fun isOnHeadphones(context: Context): Boolean = try {
        val audioManager = context.getSystemService(AudioManager::class.java)
        val device = audioManager?.getDevicesForAttributes(ATTRIBUTES_MEDIA)?.firstOrNull()
        device != null && device.type in HEADPHONE_TYPES
    } catch (e: Exception) {
        false
    }

    // The effects die with the audio server; put them back when playback starts again. The
    // correction follows the output, so a new device or route also re-applies.
    private fun registerCallbacks(context: Context) {
        if (callbacksRegistered) return
        val audioManager = context.getSystemService(AudioManager::class.java) ?: return
        val handler = Handler(Looper.getMainLooper())
        audioManager.registerAudioPlaybackCallback(object : AudioManager.AudioPlaybackCallback() {
            override fun onPlaybackConfigChanged(configs: MutableList<AudioPlaybackConfiguration>) {
                if (!_state.value.enabled || configs.none { it.isActive }) return
                val alive = synchronized(lock) {
                    runCatching { dynamics?.hasControl() == true }.getOrDefault(false)
                }
                if (!alive || isOnHeadphones(context) != _status.value.onHeadphones) scheduleApply()
            }
        }, handler)
        audioManager.registerAudioDeviceCallback(object : AudioDeviceCallback() {
            override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) {
                if (_state.value.enabled) scheduleApply()
            }

            override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) {
                if (_state.value.enabled) scheduleApply()
            }
        }, handler)
        callbacksRegistered = true
    }
}
