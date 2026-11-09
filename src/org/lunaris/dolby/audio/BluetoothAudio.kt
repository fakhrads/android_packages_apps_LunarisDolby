/*
 * SPDX-FileCopyrightText: 2026 Fakhri Adi Saputra
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lunaris.dolby.audio

import android.bluetooth.BluetoothA2dp
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothCodecConfig
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * The A2DP codec settings of Developer options (codec, sample rate, bits per sample, channel mode,
 * LDAC quality) for the headphones that are playing.
 *
 * The Bluetooth service keeps the codec status of a device: the codec in use and, per codec, the
 * sample rates, bit depths and channel modes the headphones and this phone can both do. A change is
 * a codec config with exactly one value per field (or none, for what the stack should choose) that
 * has to match one of those codecs, and the stack renegotiates the stream. It needs
 * BLUETOOTH_PRIVILEGED, which the system uid of this app has.
 */
class BluetoothAudio(context: Context, private val scope: CoroutineScope) {

    data class State(
        val deviceName: String? = null,
        val current: BluetoothCodecConfig? = null,
        /** One entry per codec the headphones and the phone both support. */
        val selectable: List<BluetoothCodecConfig> = emptyList(),
        /** Whether the headphones can use more than the mandatory SBC, and whether that is on. */
        val optionalSupported: Boolean = false,
        val optionalEnabled: Boolean = false,
    ) {
        val connected: Boolean get() = current != null
        /** What the selectable list says about the codec in use. */
        val capability: BluetoothCodecConfig?
            get() = current?.let { now -> selectable.firstOrNull { sameCodec(it, now) } }
    }

    private val app = context.applicationContext
    private val adapter: BluetoothAdapter? = app.getSystemService(BluetoothManager::class.java)?.adapter

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    @Volatile private var a2dp: BluetoothA2dp? = null
    private var registered = false

    private val serviceListener =
        object : BluetoothProfile.ServiceListener {
            override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
                a2dp = proxy as? BluetoothA2dp
                refresh()
            }

            override fun onServiceDisconnected(profile: Int) {
                a2dp = null
                _state.value = State()
            }
        }

    private val receiver =
        object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                refresh()
            }
        }

    fun start() {
        if (registered) return
        registered = true
        val filter =
            IntentFilter().apply {
                addAction(BluetoothA2dp.ACTION_CODEC_CONFIG_CHANGED)
                addAction(BluetoothA2dp.ACTION_ACTIVE_DEVICE_CHANGED)
                addAction(BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED)
                addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
            }
        // These are sent by the Bluetooth app, so the receiver has to be exported
        app.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
        adapter?.getProfileProxy(app, serviceListener, BluetoothProfile.A2DP)
    }

    fun stop() {
        if (!registered) return
        registered = false
        runCatching { app.unregisterReceiver(receiver) }
        a2dp?.let { runCatching { adapter?.closeProfileProxy(BluetoothProfile.A2DP, it) } }
        a2dp = null
    }

    /** BluetoothA2dp.getActiveDevice is hidden; the adapter's system API gives the same device. */
    private fun activeDevice(): BluetoothDevice? = adapter?.getActiveDevices(BluetoothProfile.A2DP)?.firstOrNull()

    fun refresh() {
        val proxy = a2dp ?: return
        scope.launch(Dispatchers.IO) {
            _state.value =
                runCatching {
                        val device = activeDevice()
                        val status = device?.let { proxy.getCodecStatus(it) }
                        val current = status?.codecConfig
                        if (device == null || status == null || current == null) {
                            State()
                        } else {
                            State(
                                deviceName = runCatching { device.alias ?: device.name }.getOrNull(),
                                current = current,
                                selectable = status.codecsSelectableCapabilities,
                                optionalSupported =
                                    proxy.isOptionalCodecsSupported(device) ==
                                        BluetoothA2dp.OPTIONAL_CODECS_SUPPORTED,
                                optionalEnabled =
                                    proxy.isOptionalCodecsEnabled(device) ==
                                        BluetoothA2dp.OPTIONAL_CODECS_PREF_ENABLED,
                            )
                        }
                    }
                    .getOrElse {
                        Log.w(TAG, "Cannot read the codec status", it)
                        State()
                    }
        }
    }

    /** The codec the stack picks without a user choice: the selectable one with the top priority. */
    fun selectAutomatic() {
        val best = _state.value.selectable.maxByOrNull { it.codecPriority } ?: return
        apply(build(best, 0, 0, 0, Specific()))
    }

    /** A codec with the stack's own choice of sample rate, bit depth, channels and quality. */
    fun selectCodec(codec: BluetoothCodecConfig) = apply(build(codec, 0, 0, 0, Specific()))

    fun setSampleRate(rate: Int) = change { cap, now ->
        build(cap, rate, now.bitsPerSample, now.channelMode, specificOf(now))
    }

    fun setBitsPerSample(bits: Int) = change { cap, now ->
        build(cap, now.sampleRate, bits, now.channelMode, specificOf(now))
    }

    fun setChannelMode(mode: Int) = change { cap, now ->
        build(cap, now.sampleRate, now.bitsPerSample, mode, specificOf(now))
    }

    fun setLdacQuality(value: Long) = change { cap, now ->
        build(cap, now.sampleRate, now.bitsPerSample, now.channelMode, specificOf(now, ldac = value))
    }

    fun setLhdcQuality(value: Int) = change { cap, now ->
        build(cap, now.sampleRate, now.bitsPerSample, now.channelMode, specificOf(now, lhdcQuality = value))
    }

    fun setLhdcLowLatency(on: Boolean) = change { cap, now ->
        build(cap, now.sampleRate, now.bitsPerSample, now.channelMode, specificOf(now, lhdcLowLatency = on))
    }

    fun setAacVbr(on: Boolean) = change { cap, now ->
        build(cap, now.sampleRate, now.bitsPerSample, now.channelMode, specificOf(now, aacVbr = on))
    }

    /** The "use high quality audio" switch of the device: off keeps the stream on SBC. */
    fun setOptionalCodecs(on: Boolean) {
        val proxy = a2dp ?: return
        scope.launch(Dispatchers.IO) {
            runCatching {
                    val device = activeDevice() ?: return@runCatching
                    if (on) proxy.enableOptionalCodecs(device) else proxy.disableOptionalCodecs(device)
                }
                .onFailure { Log.w(TAG, "Cannot switch the optional codecs", it) }
            refresh()
        }
    }

    private fun change(make: (BluetoothCodecConfig, BluetoothCodecConfig) -> BluetoothCodecConfig) {
        val now = _state.value.current ?: return
        val cap = _state.value.capability ?: return
        apply(make(cap, now))
    }

    /** codec_specific_1..3 of a codec config; 0 leaves the choice to the stack. */
    data class Specific(val s1: Long = 0, val s2: Long = 0, val s3: Long = 0)

    private fun build(
        codec: BluetoothCodecConfig,
        rate: Int,
        bits: Int,
        channels: Int,
        specific: Specific,
    ): BluetoothCodecConfig =
        BluetoothCodecConfig.Builder()
            .setExtendedCodecType(codec.extendedCodecType)
            .setCodecPriority(BluetoothCodecConfig.CODEC_PRIORITY_HIGHEST)
            .setSampleRate(rate)
            .setBitsPerSample(bits)
            .setChannelMode(channels)
            .setCodecSpecific1(specific.s1)
            .setCodecSpecific2(specific.s2)
            .setCodecSpecific3(specific.s3)
            .build()

    private fun apply(config: BluetoothCodecConfig) {
        val proxy = a2dp ?: return
        scope.launch(Dispatchers.IO) {
            runCatching {
                    val device: BluetoothDevice = activeDevice() ?: return@runCatching
                    proxy.setCodecConfigPreference(device, config)
                }
                .onFailure { Log.w(TAG, "Cannot set the codec config", it) }
            // The new config arrives as ACTION_CODEC_CONFIG_CHANGED; read it again in case it does not
            refresh()
        }
    }

    companion object {
        private const val TAG = "BluetoothAudio"

        /** codec_specific_1 of LDAC: 1000 + the quality index of the stack. */
        const val LDAC_QUALITY_AUDIO = 1000L
        const val LDAC_QUALITY_BALANCED = 1001L
        const val LDAC_QUALITY_CONNECTION = 1002L
        const val LDAC_QUALITY_ADAPTIVE = 1003L

        // LHDC: codec_specific_1 is 0x8000 | quality mode, in the low byte
        private const val LHDC_CMD_MASK = 0xC000L
        private const val LHDC_QUALITY_TAG = 0x8000L
        private const val LHDC_QUALITY_MASK = 0xFFL
        // codec_specific_3 of LHDC V5 carries this tag in its top byte, or the stack resets the features
        private const val LHDC_V5_FEATURE_TAG = 0x5C000000L
        const val LHDC_QUALITY_ADAPTIVE = 9

        // AAC: the bit rate mode, 0x80 is VBR switched off, 0x85 the best VBR mode
        private const val AAC_VBR_FLAG = 0x80L
        private const val AAC_VBR_OFF = 0x80L
        private const val AAC_VBR_5 = 0x85L

        val SAMPLE_RATES =
            listOf(
                44100 to BluetoothCodecConfig.SAMPLE_RATE_44100,
                48000 to BluetoothCodecConfig.SAMPLE_RATE_48000,
                88200 to BluetoothCodecConfig.SAMPLE_RATE_88200,
                96000 to BluetoothCodecConfig.SAMPLE_RATE_96000,
                176400 to BluetoothCodecConfig.SAMPLE_RATE_176400,
                192000 to BluetoothCodecConfig.SAMPLE_RATE_192000,
            )

        val BIT_DEPTHS =
            listOf(
                16 to BluetoothCodecConfig.BITS_PER_SAMPLE_16,
                24 to BluetoothCodecConfig.BITS_PER_SAMPLE_24,
                32 to BluetoothCodecConfig.BITS_PER_SAMPLE_32,
            )

        fun codecName(config: BluetoothCodecConfig): String =
            config.extendedCodecType?.codecName ?: "Codec ${config.codecType}"

        fun isLdac(config: BluetoothCodecConfig) = codecName(config).contains("LDAC", ignoreCase = true)

        fun isLhdcV5(config: BluetoothCodecConfig) =
            codecName(config).let { it.contains("LHDC", ignoreCase = true) && it.contains("V5", ignoreCase = true) }

        fun isLhdcV3(config: BluetoothCodecConfig) =
            codecName(config).let { it.contains("LHDC", ignoreCase = true) && it.contains("V3", ignoreCase = true) }

        fun isLhdc(config: BluetoothCodecConfig) = isLhdcV5(config) || isLhdcV3(config)

        fun isAac(config: BluetoothCodecConfig) = codecName(config).equals("AAC", ignoreCase = true)

        fun sameCodec(a: BluetoothCodecConfig, b: BluetoothCodecConfig) = a.extendedCodecType == b.extendedCodecType

        /** The LDAC quality in the codec config, the adaptive one while the stack has none set. */
        fun ldacQuality(config: BluetoothCodecConfig): Long =
            config.codecSpecific1.takeIf { it in LDAC_QUALITY_AUDIO..LDAC_QUALITY_ADAPTIVE } ?: LDAC_QUALITY_ADAPTIVE

        /** The LHDC quality mode (see LHDC_QUALITY_*), tagged in codec_specific_1; adaptive when untagged. */
        fun lhdcQuality(config: BluetoothCodecConfig): Int =
            if ((config.codecSpecific1 and LHDC_CMD_MASK) == LHDC_QUALITY_TAG) {
                (config.codecSpecific1 and LHDC_QUALITY_MASK).toInt()
            } else {
                LHDC_QUALITY_ADAPTIVE
            }

        /** LHDC V5 low latency: bit 0 of codec_specific_2. */
        fun lhdcLowLatency(config: BluetoothCodecConfig) = (config.codecSpecific2 and 1L) != 0L

        /** AAC reports no bit rate mode (0) when the headphones or the phone cannot do VBR. */
        fun aacVbrSupported(config: BluetoothCodecConfig) = config.codecSpecific1 != 0L

        fun aacVbrEnabled(config: BluetoothCodecConfig) = (config.codecSpecific1 and AAC_VBR_FLAG) != 0L && config.codecSpecific1 != AAC_VBR_OFF

        /**
         * The codec specific values that keep what the codec runs with now, with one thing changed.
         * Anything not set here is left to the stack.
         */
        private fun specificOf(
            now: BluetoothCodecConfig,
            ldac: Long? = null,
            lhdcQuality: Int? = null,
            lhdcLowLatency: Boolean? = null,
            aacVbr: Boolean? = null,
        ): Specific =
            when {
                isLdac(now) -> Specific(s1 = ldac ?: ldacQuality(now))
                isLhdc(now) -> {
                    val quality = lhdcQuality ?: lhdcQuality(now)
                    val tagged = LHDC_QUALITY_TAG or quality.toLong()
                    if (isLhdcV5(now)) {
                        val lowLatency = lhdcLowLatency ?: lhdcLowLatency(now)
                        Specific(s1 = tagged, s2 = if (lowLatency) 1L else 0L, s3 = LHDC_V5_FEATURE_TAG)
                    } else {
                        Specific(s1 = tagged)
                    }
                }
                isAac(now) && aacVbrSupported(now) ->
                    Specific(s1 = if (aacVbr ?: aacVbrEnabled(now)) AAC_VBR_5 else AAC_VBR_OFF)
                else -> Specific()
            }

        /** The values of a capability bit mask, in the order of [table]. */
        fun <T> supported(mask: Int, table: List<Pair<T, Int>>): List<Pair<T, Int>> = table.filter { (mask and it.second) != 0 }
    }
}
