/*
 * SPDX-FileCopyrightText: The Resurrected Remix Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.systemui.rr.charging

import android.content.Context
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.view.animation.PathInterpolator
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

class PulseRingView(context: Context) : RrChargingView(context) {

    override val durationMs = 5200L

    private val ring = RectF(38f, 128f, 162f, 252f)
    private val ecg =
        Path().apply {
            moveTo(62f, 219f)
            for (b in 0..120 step 40) {
                for (i in BEAT.indices step 2) lineTo(62f + b + BEAT[i], 204f + BEAT[i + 1])
            }
            lineTo(338f, 219f)
        }

    private val beam = Paint(Paint.ANTI_ALIAS_FLAG)
    private val track = stroke(6f).apply { color = 0x24FFFFFF }
    private val glow = stroke(6f).apply { maskFilter = blur(3f) }
    private val arc = stroke(6f)
    private val halo = Paint(Paint.ANTI_ALIAS_FLAG).apply { maskFilter = blur(3f) }
    private val spark = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val trace =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 1.6f
        }
    private var beamColor = 0

    private val accent
        get() =
            when (speed) {
                SPEED_SUPER -> 0xFFFF8A3D.toInt()
                SPEED_FAST -> 0xFFFF1F4F.toInt()
                else -> 0xFFFF6F91.toInt()
            }

    override fun drawFrame(canvas: Canvas) {
        val c = accent
        canvas.save()
        canvas.translate(left, tileTop)
        canvas.scale(u, u)

        val reach = (portYPx - tileTop) / u - 254f
        val grow = css(780f, 960f)
        val fade = 1f - css(960f, 1440f)
        if (reach > 0f && grow > 0f && fade > 0f) {
            if (beamColor != c) {
                beam.shader = LinearGradient(0f, 0f, 0f, -1f, c, c and 0xFFFFFF, Shader.TileMode.CLAMP)
                beamColor = c
            }
            beam.alpha = (255 * fade).roundToInt()
            canvas.save()
            canvas.translate((portXPx - left) / u, 254f + reach)
            canvas.scale(1f, reach * grow)
            canvas.drawRect(-1f, -1f, 1f, 0f, beam)
            canvas.restore()
        }

        val beat = tempo(1400f, 900f, 550f)
        val phase = t % beat / beat
        val thump =
            when {
                phase < 0.12f -> EASE_OUT.getInterpolation(phase / 0.12f)
                phase < 0.3f -> 1f - EASE_OUT.getInterpolation((phase - 0.12f) / 0.18f)
                else -> 0f
            }
        val s = 1f + 0.05f * thump
        canvas.save()
        canvas.scale(s, s, 100f, 190f)
        canvas.drawCircle(100f, 190f, 62f, track)
        val sweep = 3.6f * level.coerceIn(0, 100) * css(1200f, 2400f)
        if (sweep > 0f) {
            glow.color = c
            arc.color = c
            canvas.drawArc(ring, -90f, sweep, false, glow)
            canvas.drawArc(ring, -90f, sweep, false, arc)
        }
        canvas.restore()

        val beats = css(900f, 1020f)
        if (beats > 0f) {
            val a = t / tempo(4000f, 2400f, 1300f) % 1f * TAU
            val x = 100f + 62f * sin(a)
            val y = 190f - 62f * cos(a)
            halo.tint(c, beats)
            spark.alpha = (255 * beats).roundToInt()
            canvas.drawCircle(x, y, 4f, halo)
            canvas.drawCircle(x, y, 4f, spark)
            trace.tint(c, beats)
            canvas.save()
            canvas.clipRect(62f, 204f, 138f, 234f)
            canvas.translate(-200f * (t / tempo(3200f, 2000f, 1100f) % 1f), 0f)
            canvas.drawPath(ecg, trace)
            canvas.restore()
        }
        canvas.restore()

        val pin = css(900f, 1200f)
        if (pin > 0f) {
            val ps = 1.18f - 0.18f * pin
            canvas.save()
            canvas.scale(ps, ps, width / 2f, tileTop + 182.8f * u)
            drawPercent(canvas, width / 2f, tileTop + 198f * u, 38f * u, (level * css(960f, 2160f)).roundToInt(), pin)
            canvas.restore()
        }
        drawLabel(canvas, width / 2f, tileTop + 283f * u, 11f * u, css(1320f, 1680f))
    }

    private fun css(start: Float, end: Float) = EASE.getInterpolation(ramp(start, end))

    private fun Paint.tint(c: Int, a: Float) {
        color = c
        alpha = (255 * a).roundToInt()
    }

    private companion object {
        val EASE = PathInterpolator(0.25f, 0.1f, 0.25f, 1f)
        val EASE_OUT = PathInterpolator(0f, 0f, 0.58f, 1f)
        val TAU = (2 * PI).toFloat()
        val BEAT = floatArrayOf(12f, 15f, 15f, 13f, 18f, 15f, 22f, 15f, 24f, 18f, 27f, 3f, 30f, 26f, 33f, 15f, 40f, 15f)

        fun stroke(w: Float) =
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeWidth = w
                strokeCap = Paint.Cap.ROUND
            }

        fun blur(sigma: Float) = BlurMaskFilter((sigma - 0.5f) / 0.57735f, BlurMaskFilter.Blur.NORMAL)
    }
}
