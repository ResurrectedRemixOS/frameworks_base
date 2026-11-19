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
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PathMeasure
import android.graphics.RadialGradient
import android.graphics.Shader
import android.util.PathParser
import kotlin.math.max
import kotlin.math.roundToInt

class NeonRevivalView(context: Context) : RrChargingView(context) {

    override val durationMs = 5200L

    private val outlines = arrayOf(PathParser.createPathFromPathData(LOWER), PathParser.createPathFromPathData(UPPER))
    private val centres = arrayOf(PathParser.createPathFromPathData(LOWER_C), PathParser.createPathFromPathData(UPPER_C))
    private val outlineLen = FloatArray(2)
    private val centreLen = FloatArray(2)
    private val outlineMeasure = arrayOf(PathMeasure(), PathMeasure())
    private val centreMeasure = arrayOf(PathMeasure(), PathMeasure())
    private val clip = Path()
    private val seg = Path()
    private val matrix = Matrix()
    private var scale = 1f
    private var beamTop = 0f
    private var haloX = 0f
    private var haloY = 0f

    private val glow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeJoin = Paint.Join.ROUND
        strokeCap = Paint.Cap.ROUND
        color = CRIMSON
    }
    private val core = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeJoin = Paint.Join.ROUND
        strokeCap = Paint.Cap.ROUND
        color = NEON
    }
    private val spark = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        color = Color.WHITE
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.BUTT
    }
    private val crest = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.BUTT
        color = CREST
    }
    private val halo = Paint(Paint.ANTI_ALIAS_FLAG)
    private val beam = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeCap = Paint.Cap.ROUND }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        scale = 0.2f * u
        matrix.setTranslate(-48f, -48f)
        matrix.postScale(scale, scale)
        matrix.postTranslate(left + 28.5f * u, y(180f))
        clip.reset()
        for (i in 0..1) {
            outlines[i] = PathParser.createPathFromPathData(if (i == 0) LOWER else UPPER)
            centres[i] = PathParser.createPathFromPathData(if (i == 0) LOWER_C else UPPER_C)
            outlines[i].transform(matrix)
            centres[i].transform(matrix)
            clip.addPath(outlines[i])
            outlineMeasure[i].setPath(outlines[i], true)
            centreMeasure[i].setPath(centres[i], false)
            outlineLen[i] = outlineMeasure[i].length
            centreLen[i] = centreMeasure[i].length
        }
        beamTop = y(254f)
        haloX = width / 2f
        haloY = y(235f)
        glow.strokeWidth = 18f * scale
        glow.maskFilter = BlurMaskFilter(7f * u, BlurMaskFilter.Blur.NORMAL)
        core.strokeWidth = 12f * scale
        spark.strokeWidth = 15f * scale
        fill.strokeWidth = 190f * scale
        crest.strokeWidth = 190f * scale
        fill.shader = LinearGradient(
            left + 28.5f * u, y(289f), left + 171.5f * u, y(180f),
            intArrayOf(DEEP, 0xFFC4002F.toInt(), CRIMSON), floatArrayOf(0f, 0.55f, 1f),
            Shader.TileMode.CLAMP,
        )
        halo.shader = RadialGradient(
            haloX, haloY, 90f * u,
            intArrayOf(0x52FF003F, 0x14FF003F, Color.TRANSPARENT), floatArrayOf(0f, 0.6f, 1f),
            Shader.TileMode.CLAMP,
        )
    }

    private fun y(tileY: Float) = tileTop + tileY * u

    override fun drawFrame(canvas: Canvas) {
        val flicker = flicker()
        val haloA = when {
            t < 1800f -> 0f
            t < 2520f -> flicker * ramp(1800f, 2160f)
            else -> 1f
        }
        if (haloA > 0f) {
            halo.alpha = (255 * haloA).roundToInt()
            canvas.drawCircle(haloX, haloY, 90f * u, halo)
        }
        drawBeam(canvas)
        drawFill(canvas)
        drawNeon(canvas, flicker)
        val pin = ramp(900f, 1200f)
        if (pin > 0f) {
            val s = 1.18f - 0.18f * pin
            val baseline = y(131f)
            canvas.save()
            canvas.scale(s, s, width / 2f, baseline - 18f * u)
            drawPercent(canvas, width / 2f, baseline, 46f * u, counted(960f, 2160f), pin)
            canvas.restore()
        }
        drawLabel(canvas, width / 2f, y(159.5f), 11f * u, ramp(1320f, 1680f))
    }

    private fun flicker() = when {
        t < 2100f -> 1f
        t < 2160f -> 0.25f
        t < 2220f -> 1f
        t < 2280f -> 0.4f
        else -> 1f
    }

    private fun drawBeam(canvas: Canvas) {
        if (t < 780f || t > 1440f) return
        val grow = easeOut(ramp(780f, 960f))
        val a = if (t < 960f) 1f else 1f - ramp(960f, 1440f)
        val top = portYPx - (portYPx - beamTop) * grow
        beam.shader = LinearGradient(
            0f, portYPx, 0f, top,
            intArrayOf(CRIMSON, 0xE6FF7896.toInt(), Color.TRANSPARENT), floatArrayOf(0f, 0.75f, 1f),
            Shader.TileMode.CLAMP,
        )
        beam.strokeWidth = 2f * u
        beam.alpha = (255 * a).roundToInt()
        canvas.drawLine(portXPx, portYPx, portXPx, top, beam)
    }

    private fun drawFill(canvas: Canvas) {
        if (t < 2400f) return
        val f = level / 100f * easeInOut(ramp(2400f, 3720f))
        if (f <= 0f) return
        canvas.save()
        canvas.clipPath(clip)
        val crestA = 0.95f - 0.5f * ramp(3720f, 4320f)
        crest.alpha = (255 * crestA).roundToInt()
        for (i in 0..1) {
            val end = centreLen[i] * f
            seg.reset()
            centreMeasure[i].getSegment(0f, end, seg, true)
            canvas.drawPath(seg, fill)
            seg.reset()
            centreMeasure[i].getSegment(max(0f, end - centreLen[i] * 0.016f), end, seg, true)
            canvas.drawPath(seg, crest)
        }
        canvas.restore()
    }

    private fun drawNeon(canvas: Canvas, flicker: Float) {
        val flow = ramp(3480f, 3840f)
        val lap = tempo(4000f, 2400f, 1300f)
        for (i in 0..1) {
            val start = if (i == 0) 900f else 1140f
            val p = easeInOut(ramp(start, start + 960f))
            if (p <= 0f) continue
            val len = outlineLen[i]
            val path = if (p < 1f) {
                seg.reset()
                outlineMeasure[i].getSegment(0f, len * p, seg, true)
                seg
            } else {
                outlines[i]
            }
            glow.alpha = (200 * flicker).roundToInt()
            canvas.drawPath(path, glow)
            core.alpha = (255 * flicker).roundToInt()
            canvas.drawPath(path, core)
            if (p < 1f) {
                seg.reset()
                outlineMeasure[i].getSegment(max(0f, len * (p - 0.04f)), len * p, seg, true)
                spark.alpha = 255
                canvas.drawPath(seg, spark)
            }
            if (flow > 0f) {
                val head = ((t - 3480f) / lap + i * 0.5f) % 1f * len
                val tail = head + len * 0.07f
                spark.alpha = (220 * flow).roundToInt()
                seg.reset()
                outlineMeasure[i].getSegment(head, minOf(tail, len), seg, true)
                if (tail > len) outlineMeasure[i].getSegment(0f, tail - len, seg, true)
                canvas.drawPath(seg, spark)
            }
        }
    }

    companion object {
        private const val CRIMSON = 0xFFFF003F.toInt()
        private const val NEON = 0xFFFF1F55.toInt()
        private const val DEEP = 0xFF840009.toInt()
        private const val CREST = 0xFFFF8FAA.toInt()
        private const val UPPER =
            "M478.36 48L751.08 48A12.19 12.19 0 0 1 762.61 64.15C745.66 113.54 716.26 166 680.32 166" +
                "L485.25 166C419.97 166 369.79 221.63 369.47 222.02L185.48 445.99C180.57 451.96 132.64 489.96 " +
                "51.71 438.79A7.96 7.96 0 0 1 49.81 427.01L265.01 165.04C324.04 93.19 404.2 48 478.36 48Z"
        private const val LOWER =
            "M531.45 235L745.59 235A11.06 11.06 0 0 1 756 249.82C729.84 322.3 702.39 331 689.22 331" +
                "L530.86 331C502.09 331 462.29 349.82 435.71 382.18L293.5 555.28C293.2 555.65 256.16 592.98 " +
                "187.21 550.03A8.24 8.24 0 0 1 185.2 537.81L354.98 331.14C409.77 264.45 474.64 235 531.45 235Z"
        private const val UPPER_C = "M92.6 467.5L317 193.5Q388 107 481.5 107L790 107"
        private const val LOWER_C = "M214 577L395.3 356.7Q455.8 283 531 283L790 283"
    }
}
