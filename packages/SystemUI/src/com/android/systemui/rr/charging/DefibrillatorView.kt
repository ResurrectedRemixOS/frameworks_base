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
import android.graphics.RadialGradient
import android.graphics.Shader
import android.view.animation.PathInterpolator
import kotlin.math.roundToInt

class DefibrillatorView(context: Context) : RrChargingView(context) {

    override val durationMs = 5200L

    private val ecg =
        Path().apply {
            moveTo(-20f, 230f)
            for (b in 0..200 step 100) {
                for (i in BEAT.indices step 2) lineTo(b + BEAT[i], 190f + BEAT[i + 1])
            }
            lineTo(320f, 230f)
        }

    private val flash =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = RadialGradient(0f, 0f, 1f, 0x8CFFFFFF.toInt(), 0x00FFFFFF, Shader.TileMode.CLAMP)
        }
    private val beam = Paint(Paint.ANTI_ALIAS_FLAG)
    private val flat = stroke(1.5f).apply { color = Color.WHITE }
    private val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val haze = stroke(3.5f).apply { maskFilter = blur(5f) }
    private val glow = stroke(2.2f).apply { maskFilter = blur(2f) }
    private val trace = stroke(2.2f)
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

        val shock = if (t < 930f) css(840f, 930f) else 1f - css(930f, 1440f)
        if (shock > 0f) {
            flash.alpha = (255 * shock).roundToInt()
            canvas.save()
            canvas.translate(100f, 294f)
            canvas.scale(120f, 189f)
            canvas.drawCircle(0f, 0f, 1f, flash)
            canvas.restore()
        }

        val reach = (portYPx - tileTop) / u - 228f
        val grow = css(780f, 960f)
        val fade = 1f - css(960f, 1440f)
        if (reach > 0f && grow > 0f && fade > 0f) {
            if (beamColor != c) {
                beam.shader =
                    LinearGradient(
                        0f,
                        0f,
                        0f,
                        -1f,
                        intArrayOf(c, 0xE6FFFFFF.toInt(), 0x00FFFFFF),
                        floatArrayOf(0f, 0.7f, 1f),
                        Shader.TileMode.CLAMP,
                    )
                beamColor = c
            }
            beam.alpha = (255 * fade).roundToInt()
            canvas.save()
            canvas.translate((portXPx - left) / u, 228f + reach)
            canvas.scale(1f, reach * grow)
            canvas.drawRect(-1f, -1f, 1f, 0f, beam)
            canvas.restore()
        }

        val line = 1f - css(840f, 960f)
        if (line > 0f) {
            flat.alpha = (255 * 0.525f * line).roundToInt()
            canvas.drawLine(0f, 230f, 200f, 230f, flat)
        }

        val scan = 1f - css(0f, 846f)
        if (scan > 0f) {
            dot.alpha = (255 * scan).roundToInt()
            canvas.drawCircle(200f * css(0f, 840f), 230f, 2.5f, dot)
        }

        val beats = css(900f, 1020f)
        if (beats > 0f) {
            haze.tint(c, beats)
            glow.tint(c, beats)
            trace.tint(c, beats)
            canvas.save()
            canvas.translate(-(t * 200f / tempo(3200f, 2000f, 1100f) % 100f * u).roundToInt() / u, 0f)
            canvas.drawPath(ecg, haze)
            canvas.drawPath(ecg, glow)
            canvas.drawPath(ecg, trace)
            canvas.restore()
        }
        canvas.restore()

        val pin = css(900f, 1200f)
        if (pin > 0f) {
            val s = 1.18f - 0.18f * pin
            canvas.save()
            canvas.scale(s, s, width / 2f, tileTop + 140.4f * u)
            drawPercent(canvas, width / 2f, tileTop + 162f * u, 54f * u, (level * css(960f, 2160f)).roundToInt(), pin)
            canvas.restore()
        }
        drawLabel(canvas, width / 2f, tileTop + 181f * u, 11f * u, css(1320f, 1680f))
    }

    private fun css(start: Float, end: Float) = EASE.getInterpolation(ramp(start, end))

    private fun Paint.tint(c: Int, a: Float) {
        color = c
        alpha = (255 * a).roundToInt()
    }

    private companion object {
        val EASE = PathInterpolator(0.25f, 0.1f, 0.25f, 1f)
        val BEAT =
            floatArrayOf(
                30f, 40f, 35f, 36f, 40f, 40f, 46f, 40f, 49f, 46f, 53f, 12f,
                57f, 66f, 61f, 40f, 70f, 40f, 76f, 34f, 83f, 40f, 100f, 40f,
            )

        fun stroke(w: Float) =
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeWidth = w
                strokeJoin = Paint.Join.ROUND
            }

        fun blur(sigma: Float) = BlurMaskFilter((sigma - 0.5f) / 0.57735f, BlurMaskFilter.Blur.NORMAL)
    }
}
