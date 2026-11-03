/*
 * SPDX-FileCopyrightText: 2026 Fakhri Adi Saputra
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lunaris.dolby.audio

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * A second order section, normalised so a0 = 1:
 * H(z) = (b0 + b1 z^-1 + b2 z^-2) / (1 + a1 z^-1 + a2 z^-2)
 */
data class Biquad(
    val b0: Double,
    val b1: Double,
    val b2: Double,
    val a1: Double,
    val a2: Double,
    val sampleRate: Double
) {
    fun magnitudeDb(frequency: Double): Double {
        val w = 2.0 * PI * frequency / sampleRate
        val c1 = cos(w); val s1 = sin(w)
        val c2 = cos(2.0 * w); val s2 = sin(2.0 * w)
        val numRe = b0 + b1 * c1 + b2 * c2
        val numIm = -(b1 * s1 + b2 * s2)
        val denRe = 1.0 + a1 * c1 + a2 * c2
        val denIm = -(a1 * s1 + a2 * s2)
        val num = numRe * numRe + numIm * numIm
        val den = denRe * denRe + denIm * denIm
        if (num <= 0.0 || den <= 0.0) return 0.0
        return 10.0 * log10(num / den)
    }

    companion object {
        // RBJ Audio EQ Cookbook, the same formulas AutoEQ uses (alpha from Q for the shelves too)
        fun peaking(frequency: Double, gainDb: Double, q: Double, sampleRate: Double): Biquad {
            val a = 10.0.pow(gainDb / 40.0)
            val w0 = 2.0 * PI * frequency / sampleRate
            val alpha = sin(w0) / (2.0 * q)
            val a0 = 1.0 + alpha / a
            return Biquad(
                (1.0 + alpha * a) / a0, -2.0 * cos(w0) / a0, (1.0 - alpha * a) / a0,
                -2.0 * cos(w0) / a0, (1.0 - alpha / a) / a0, sampleRate
            )
        }

        fun lowShelf(frequency: Double, gainDb: Double, q: Double, sampleRate: Double): Biquad {
            val a = 10.0.pow(gainDb / 40.0)
            val w0 = 2.0 * PI * frequency / sampleRate
            val cw = cos(w0)
            val k = 2.0 * sqrt(a) * sin(w0) / (2.0 * q)
            val a0 = (a + 1) + (a - 1) * cw + k
            return Biquad(
                a * ((a + 1) - (a - 1) * cw + k) / a0,
                2.0 * a * ((a - 1) - (a + 1) * cw) / a0,
                a * ((a + 1) - (a - 1) * cw - k) / a0,
                -2.0 * ((a - 1) + (a + 1) * cw) / a0,
                ((a + 1) + (a - 1) * cw - k) / a0,
                sampleRate
            )
        }

        fun highShelf(frequency: Double, gainDb: Double, q: Double, sampleRate: Double): Biquad {
            val a = 10.0.pow(gainDb / 40.0)
            val w0 = 2.0 * PI * frequency / sampleRate
            val cw = cos(w0)
            val k = 2.0 * sqrt(a) * sin(w0) / (2.0 * q)
            val a0 = (a + 1) - (a - 1) * cw + k
            return Biquad(
                a * ((a + 1) + (a - 1) * cw + k) / a0,
                -2.0 * a * ((a - 1) + (a + 1) * cw) / a0,
                a * ((a + 1) + (a - 1) * cw - k) / a0,
                2.0 * ((a - 1) - (a + 1) * cw) / a0,
                ((a + 1) - (a - 1) * cw - k) / a0,
                sampleRate
            )
        }
    }
}

/** A magnitude response in dB over frequency. */
fun interface ResponseCurve {
    fun gainDb(frequency: Double): Double
}

class BiquadCurve(private val sections: List<Biquad>) : ResponseCurve {
    override fun gainDb(frequency: Double) = sections.sumOf { it.magnitudeDb(frequency) }
}

/** Straight lines between (frequency, dB) points on a log frequency axis, flat outside them. */
class PointCurve(points: List<Pair<Double, Double>>) : ResponseCurve {
    private val freqs = points.map { it.first }.toDoubleArray()
    private val gains = points.map { it.second }.toDoubleArray()

    override fun gainDb(frequency: Double): Double {
        if (frequency <= freqs.first()) return gains.first()
        if (frequency >= freqs.last()) return gains.last()
        val hi = freqs.indexOfFirst { it >= frequency }
        if (freqs[hi] == frequency) return gains[hi]
        val lo = hi - 1
        val t = (log10(frequency) - log10(freqs[lo])) / (log10(freqs[hi]) - log10(freqs[lo]))
        return gains[lo] + (gains[hi] - gains[lo]) * t
    }
}
