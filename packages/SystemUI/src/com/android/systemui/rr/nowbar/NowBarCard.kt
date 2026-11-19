/*
 * SPDX-FileCopyrightText: The Resurrected Remix Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.systemui.rr.nowbar

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.MediaMetadata
import android.media.session.PlaybackState
import android.os.BatteryManager
import android.os.SystemClock
import android.text.TextUtils
import android.text.format.DateFormat
import android.text.format.DateUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Chronometer
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import com.android.settingslib.media.MediaOutputConstants
import com.android.systemui.plugins.ActivityStarter
import com.android.systemui.res.R
import java.text.NumberFormat
import java.util.Date
import kotlin.math.roundToInt

/**
 * The opened Now Bar item, in a window above the lock screen: the full player for music, the
 * details and actions for everything else. A tap outside closes it.
 */
class NowBarCard(
    private val context: Context,
    private var item: NowBarItem,
    private val waving: Boolean,
    private val activityStarter: ActivityStarter,
    private val send: (PendingIntent) -> Unit,
    private val onDismiss: () -> Unit,
) {
    val key = item.key
    private val density = context.resources.displayMetrics.density
    private val accent = context.getColor(android.R.color.system_accent1_400)
    private val windowManager = context.getSystemService(WindowManager::class.java)
    private val root = FrameLayout(context)
    private val card = LinearLayout(context)
    private var seek: NowBarSeekView? = null
    private var times: TextView? = null
    private var playButton: ImageView? = null
    private val ticker = object : Runnable {
        override fun run() {
            updatePosition()
            root.postDelayed(this, 500L)
        }
    }

    fun show() {
        root.setBackgroundColor(0x99000000.toInt())
        root.setOnClickListener { dismiss() }
        card.orientation = LinearLayout.VERTICAL
        card.background = GradientDrawable().apply {
            cornerRadius = dp(28f)
            setColor(0xF20D0809.toInt())
            setStroke(dp(1f).toInt(), 0x1AFFFFFF)
        }
        card.clipToOutline = true
        card.isClickable = true
        root.addView(card, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM).apply {
            marginStart = dp(16f).toInt()
            marginEnd = dp(16f).toInt()
            bottomMargin = (context.resources.displayMetrics.heightPixels * 0.16f).toInt()
        })
        build()
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_STATUS_BAR_SUB_PANEL,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.TRANSLUCENT,
        )
        lp.setFitInsetsTypes(0)
        lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        lp.title = "RRNowBarCard"
        try {
            windowManager.addView(root, lp)
        } catch (e: RuntimeException) {
            onDismiss()
            return
        }
        card.alpha = 0f
        card.translationY = dp(40f)
        card.animate().alpha(1f).translationY(0f).setDuration(220L).start()
        if (item.type == NowBarType.MUSIC) root.post(ticker)
    }

    fun dismiss() {
        root.removeCallbacks(ticker)
        if (root.isAttachedToWindow) windowManager.removeViewImmediate(root)
        onDismiss()
    }

    /** New state for the same item, or null when it ended. */
    fun update(newItem: NowBarItem?) {
        if (newItem == null) {
            dismiss()
            return
        }
        val rebuild = newItem.title != item.title || newItem.art != item.art ||
            newItem.actions.size != item.actions.size
        item = newItem
        if (rebuild) {
            card.removeAllViews()
            build()
        } else {
            playButton?.setImageResource(
                if (item.playing) R.drawable.rr_nowbar_pause else R.drawable.rr_nowbar_play
            )
            seek?.waving = waving && item.playing
        }
    }

    private fun build() {
        when (item.type) {
            NowBarType.MUSIC -> buildPlayer()
            NowBarType.CHARGING -> buildBattery()
            else -> buildDetails()
        }
    }

    private fun buildBattery() {
        val pad = dp(24f).toInt()
        card.setPadding(pad, dp(20f).toInt(), pad, pad)
        val level = (item.progress * 100f).roundToInt().coerceIn(0, 100)
        val top = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL }
        top.addView(ImageView(context).apply {
            setImageResource(R.drawable.rr_nowbar_bolt)
            imageTintList = ColorStateList.valueOf(Color.WHITE)
            val inset = dp(11f).toInt()
            setPadding(inset, inset, inset, inset)
            background = GradientDrawable().apply {
                cornerRadius = dp(14f)
                setColor(0x1AFFFFFF)
            }
        }, LinearLayout.LayoutParams(dp(44f).toInt(), dp(44f).toInt()))
        val column = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        column.addView(text(NumberFormat.getPercentInstance().format(level / 100.0), 36f, bold = true))
        column.addView(text(item.text ?: context.getString(R.string.now_bar_battery_charging),
            14f, alpha = 0.75f))
        top.addView(column, LinearLayout.LayoutParams(0, -2, 1f).apply {
            marginStart = dp(12f).toInt()
        })
        card.addView(top)

        val track = LinearLayout(context).apply {
            background = GradientDrawable().apply {
                cornerRadius = dp(5f)
                setColor(0x1FFFFFFF)
            }
            clipToOutline = true
            addView(View(context).apply {
                background = GradientDrawable().apply {
                    cornerRadius = dp(5f)
                    setColor(accent)
                }
            }, LinearLayout.LayoutParams(0, -1, level.toFloat()))
            addView(View(context), LinearLayout.LayoutParams(0, -1, (100 - level).toFloat()))
        }
        card.addView(track, LinearLayout.LayoutParams(-1, dp(10f).toInt()).apply {
            topMargin = dp(18f).toInt()
        })

        val remaining = context.getSystemService(BatteryManager::class.java)
            ?.computeChargeTimeRemaining() ?: -1L
        if (remaining > 0L && level < 100) {
            val full = DateFormat.getTimeFormat(context)
                .format(Date(System.currentTimeMillis() + remaining))
            card.addView(text(context.getString(R.string.now_bar_full_at, full), 12f, alpha = 0.6f)
                .apply { gravity = Gravity.END },
                LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8f).toInt() })
        }
    }

    private fun buildPlayer() {
        val cover = FrameLayout(context)
        val art = ImageView(context).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            if (item.art != null) setImageBitmap(item.art) else setBackgroundColor(accent)
        }
        cover.addView(art, FrameLayout.LayoutParams(-1, -1))
        cover.addView(View(context).apply {
            background = GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(0x00000000, 0xF20D0809.toInt())
            )
        }, FrameLayout.LayoutParams(-1, -1))
        val meta = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        meta.addView(text(item.title, 18f, bold = true))
        item.text?.let { meta.addView(text(it, 13f, alpha = 0.75f)) }
        cover.addView(meta, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM).apply {
            setMargins(dp(18f).toInt(), 0, dp(18f).toInt(), dp(12f).toInt())
        })
        val output = text(context.getString(R.string.now_bar_output), 11f, bold = true).apply {
            background = GradientDrawable().apply {
                cornerRadius = dp(12f)
                setColor(0x99000000.toInt())
            }
            setPadding(dp(10f).toInt(), dp(5f).toInt(), dp(10f).toInt(), dp(5f).toInt())
            setOnClickListener {
                context.sendBroadcast(
                    Intent(MediaOutputConstants.ACTION_LAUNCH_MEDIA_OUTPUT_DIALOG)
                        .setPackage(context.packageName)
                        .putExtra(MediaOutputConstants.EXTRA_PACKAGE_NAME, item.packageName)
                )
                dismiss()
            }
        }
        cover.addView(output, FrameLayout.LayoutParams(-2, -2, Gravity.END or Gravity.TOP).apply {
            setMargins(0, dp(12f).toInt(), dp(12f).toInt(), 0)
        })
        cover.setOnClickListener { item.contentIntent?.let(send); dismiss() }
        card.addView(cover, LinearLayout.LayoutParams(-1, dp(190f).toInt()))

        seek = NowBarSeekView(context).apply {
            color = accent
            waving = this@NowBarCard.waving && item.playing
            onSeek = { fraction ->
                val duration = item.controller?.metadata?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 0L
                if (duration > 0) item.controller?.transportControls?.seekTo((duration * fraction).toLong())
            }
        }
        card.addView(seek, LinearLayout.LayoutParams(-1, dp(28f).toInt()).apply {
            setMargins(dp(10f).toInt(), dp(8f).toInt(), dp(10f).toInt(), 0)
        })
        times = text("", 11f, alpha = 0.6f).apply { fontFeatureSettings = "tnum" }
        card.addView(times, LinearLayout.LayoutParams(-1, -2).apply {
            setMargins(dp(18f).toInt(), 0, dp(18f).toInt(), 0)
        })

        val controls = LinearLayout(context).apply { gravity = Gravity.CENTER }
        item.actions.forEachIndexed { i, action ->
            val big = i == 1
            val button = ImageView(context).apply {
                setImageResource(action.iconRes)
                imageTintList = ColorStateList.valueOf(Color.WHITE)
                val pad = dp(if (big) 14f else 11f).toInt()
                setPadding(pad, pad, pad, pad)
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(if (big) accent else 0x1FFFFFFF)
                }
                contentDescription = action.title
                setOnClickListener { action.run?.invoke() }
            }
            if (big) playButton = button
            val size = dp(if (big) 58f else 44f).toInt()
            controls.addView(button, LinearLayout.LayoutParams(size, size).apply {
                setMargins(dp(12f).toInt(), 0, dp(12f).toInt(), 0)
            })
        }
        card.addView(controls, LinearLayout.LayoutParams(-1, -2).apply {
            setMargins(0, dp(10f).toInt(), 0, dp(18f).toInt())
        })
        updatePosition()
    }

    private fun updatePosition() {
        val controller = item.controller ?: return
        val duration = controller.metadata?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 0L
        val state = controller.playbackState ?: return
        var position = state.position
        if (state.state == PlaybackState.STATE_PLAYING) {
            position += ((SystemClock.elapsedRealtime() - state.lastPositionUpdateTime) *
                state.playbackSpeed).toLong()
        }
        if (duration > 0) {
            seek?.progress = position.toFloat() / duration
            times?.text = DateUtils.formatElapsedTime(position / 1000) + " / " +
                DateUtils.formatElapsedTime(duration / 1000)
        }
    }

    private fun buildDetails() {
        val pad = dp(20f).toInt()
        card.setPadding(pad, pad, pad, pad)
        val header = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL }
        header.addView(ImageView(context).apply {
            setImageDrawable(item.icon?.mutate())
            imageTintList = ColorStateList.valueOf(Color.WHITE)
        }, LinearLayout.LayoutParams(dp(20f).toInt(), dp(20f).toInt()))
        header.addView(text(appLabel() ?: "", 12f, alpha = 0.7f),
            LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(8f).toInt() })
        card.addView(header)

        if (item.chronometerBase != 0L) {
            card.addView(Chronometer(context).apply {
                setTextColor(Color.WHITE)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 34f)
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                fontFeatureSettings = "tnum"
                base = SystemClock.elapsedRealtime() + (item.chronometerBase - System.currentTimeMillis())
                isCountDown = item.countDown
                start()
            }, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(10f).toInt() })
            card.addView(text(item.title, 15f, bold = true))
        } else {
            card.addView(text(item.title, 20f, bold = true).apply { maxLines = 2 },
                LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10f).toInt() })
        }
        item.text?.let { card.addView(text(it, 14f, alpha = 0.75f).apply { maxLines = 3 }) }
        if (item.progress >= 0f) {
            card.addView(ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal).apply {
                max = 1000
                progress = (item.progress * 1000).toInt()
                progressTintList = ColorStateList.valueOf(accent)
            }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12f).toInt() })
        }

        val buttons = LinearLayout(context).apply { gravity = Gravity.END }
        for (action in item.actions) {
            buttons.addView(button(action.title, filled = false) {
                action.run?.invoke()
                action.intent?.let(send)
                dismiss()
            })
        }
        item.contentIntent?.let { intent ->
            buttons.addView(button(context.getString(R.string.now_bar_open), filled = true) {
                send(intent)
                dismiss()
            })
        }
        card.addView(buttons, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(16f).toInt() })
    }

    private fun appLabel(): CharSequence? = item.packageName?.let {
        try {
            context.packageManager.getApplicationLabel(context.packageManager.getApplicationInfo(it, 0))
        } catch (e: Exception) {
            null
        }
    }

    private fun text(value: CharSequence, sp: Float, bold: Boolean = false, alpha: Float = 1f) =
        TextView(context).apply {
            text = value
            setTextColor(Color.WHITE)
            this.alpha = alpha
            setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
            if (bold) typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            isSingleLine = true
            ellipsize = TextUtils.TruncateAt.END
        }

    private fun button(label: CharSequence, filled: Boolean, onClick: () -> Unit) =
        text(label, 13f, bold = true).apply {
            gravity = Gravity.CENTER
            setPadding(dp(16f).toInt(), dp(9f).toInt(), dp(16f).toInt(), dp(9f).toInt())
            background = GradientDrawable().apply {
                cornerRadius = dp(20f)
                if (filled) setColor(accent) else setStroke(dp(1f).toInt(), 0x40FFFFFF)
            }
            layoutParams = LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(8f).toInt() }
            setOnClickListener { onClick() }
        }

    private fun dp(value: Float) = value * density
}
