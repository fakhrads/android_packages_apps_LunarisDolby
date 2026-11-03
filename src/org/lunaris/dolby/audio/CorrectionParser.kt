/*
 * SPDX-FileCopyrightText: 2026 Fakhri Adi Saputra
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lunaris.dolby.audio

/**
 * Reads headphone correction files into a magnitude response:
 *  - AutoEQ / Equalizer APO parametric EQ ("Filter 1: ON PK Fc 105 Hz Gain 6.4 dB Q 0.70")
 *  - AutoEQ / Equalizer APO graphic EQ ("GraphicEQ: 20 -0.2; 21 -0.2; ...")
 *  - ViPER / JamesDSP DDC (.vdc, "SR_44100:" and "SR_48000:" followed by b0,b1,b2,a1,a2 per
 *    section, with a1 and a2 stored negated as JamesDSP's vdc.c reads them)
 */
object CorrectionParser {

    enum class Format { PARAMETRIC, GRAPHIC, DDC }

    class Parsed(val format: Format, val curve: ResponseCurve)

    class ParseException(message: String) : Exception(message)

    private const val SAMPLE_RATE = 48000.0
    private const val DEFAULT_SHELF_Q = 0.7071
    private const val DEFAULT_PEAK_Q = 1.41

    private val FILTER = Regex(
        """Filter\s*\d*\s*:\s*ON\s+(PK|PEQ|LSC|HSC|LS|HS)\s+Fc\s+([\d.]+)\s*Hz\s+Gain\s+([-+]?[\d.]+)\s*dB(?:\s+Q\s+([\d.]+))?""",
        RegexOption.IGNORE_CASE
    )
    private val NUMBER = Regex("""[-+]?(?:\d+\.?\d*|\.\d+)(?:[eE][-+]?\d+)?""")

    fun parse(text: String): Parsed = when {
        text.contains("SR_48000") || text.contains("SR_44100") -> Parsed(Format.DDC, parseDdc(text))
        text.contains("GraphicEQ:", ignoreCase = true) -> Parsed(Format.GRAPHIC, parseGraphic(text))
        FILTER.containsMatchIn(text) -> Parsed(Format.PARAMETRIC, parseParametric(text))
        else -> throw ParseException("unknown format")
    }

    fun parseParametric(text: String): ResponseCurve {
        val sections = FILTER.findAll(text).mapNotNull { m ->
            val type = m.groupValues[1].uppercase()
            val fc = m.groupValues[2].toDoubleOrNull() ?: return@mapNotNull null
            val gain = m.groupValues[3].toDoubleOrNull() ?: return@mapNotNull null
            val q = m.groupValues[4].toDoubleOrNull()
            if (fc <= 0.0 || fc >= SAMPLE_RATE / 2) return@mapNotNull null
            when (type) {
                "PK", "PEQ" -> Biquad.peaking(fc, gain, q ?: DEFAULT_PEAK_Q, SAMPLE_RATE)
                "LSC", "LS" -> Biquad.lowShelf(fc, gain, q ?: DEFAULT_SHELF_Q, SAMPLE_RATE)
                else -> Biquad.highShelf(fc, gain, q ?: DEFAULT_SHELF_Q, SAMPLE_RATE)
            }
        }.toList()
        if (sections.isEmpty()) throw ParseException("no filters")
        return BiquadCurve(sections)
    }

    fun parseGraphic(text: String): ResponseCurve {
        val body = text.substringAfter("GraphicEQ:", "").ifEmpty {
            text.substringAfter("graphiceq:", "")
        }
        val points = body.split(';').mapNotNull { entry ->
            val nums = NUMBER.findAll(entry).map { it.value.toDouble() }.toList()
            if (nums.size >= 2 && nums[0] > 0.0) nums[0] to nums[1] else null
        }.sortedBy { it.first }.distinctBy { it.first }
        if (points.size < 2) throw ParseException("not enough points")
        // AutoEQ bakes its preamp into GraphicEQ files; take it out so the curve peaks at 0 dB and
        // gives the same result as the ParametricEQ file once the auto preamp has run
        val peak = points.filter { it.first in 20.0..20000.0 }.maxOfOrNull { it.second }
            ?: points.maxOf { it.second }
        return PointCurve(points.map { it.first to it.second - peak })
    }

    fun parseDdc(text: String): ResponseCurve {
        // The 48 kHz set matches the output mix; 44.1 kHz files are evaluated at their own rate
        val (rate, block) = when {
            text.contains("SR_48000") -> 48000.0 to text.substringAfter("SR_48000")
            else -> 44100.0 to text.substringAfter("SR_44100")
        }
        val values = NUMBER.findAll(block.substringAfter(':').substringBefore("SR_"))
            .map { it.value.toDouble() }.toList()
        val sections = values.chunked(5).filter { it.size == 5 }.map { c ->
            Biquad(c[0], c[1], c[2], -c[3], -c[4], rate)
        }
        if (sections.isEmpty()) throw ParseException("no sections")
        return BiquadCurve(sections)
    }
}
