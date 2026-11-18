/*
 * SPDX-FileCopyrightText: The Resurrected Remix Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.systemui.rr.nowbar

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.SystemClock
import android.util.AttributeSet
import android.widget.Chronometer
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.android.systemui.Dependency
import com.android.systemui.plugins.statusbar.StatusBarStateController
import com.android.systemui.res.R
import com.android.systemui.statusbar.StatusBarState

/**
 * The Now Bar's current activity as a chip next to the clock while unlocked. Calls, screen
 * recording and Live Updates already get the system's own chips, so this one covers music,
 * timers, voice recording and games, whose chip drops their score card like One UI's.
 */
class NowBarChipView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : LinearLayout(context, attrs), NowBarRepository.Listener, StatusBarStateController.StateListener {

    private lateinit var icon: ImageView
    private lateinit var icon2: ImageView
    private var popup: NowBarScorePopup? = null
    private lateinit var label: TextView
    private lateinit var chronometer: Chronometer
    private var item: NowBarItem? = null
    private var state = StatusBarState.SHADE
    private var items: List<NowBarItem> = emptyList()

    override fun onFinishInflate() {
        super.onFinishInflate()
        icon = requireViewById(R.id.now_bar_chip_icon)
        icon2 = requireViewById(R.id.now_bar_chip_icon2)
        label = requireViewById(R.id.now_bar_chip_text)
        chronometer = requireViewById(R.id.now_bar_chip_chronometer)
        icon.imageTintList = ColorStateList.valueOf(Color.WHITE)
        background = GradientDrawable().apply {
            cornerRadius = 100f
            setColor(context.getColor(android.R.color.system_accent1_600))
        }
        setOnClickListener {
            val shown = item ?: return@setOnClickListener
            if (shown.type == NowBarType.SPORTS) {
                openScore(shown)
                return@setOnClickListener
            }
            shown.contentIntent?.let {
                NowBarRepository.instance?.activityStarter?.postStartActivityDismissingKeyguard(it)
            }
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        val controller = Dependency.get(StatusBarStateController::class.java)
        state = controller.state
        controller.addCallback(this)
        NowBarRepository.instance?.addListener(this)
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        Dependency.get(StatusBarStateController::class.java).removeCallback(this)
        NowBarRepository.instance?.removeListener(this)
        chronometer.stop()
        popup?.dismiss()
    }

    override fun onStateChanged(newState: Int) {
        state = newState
        if (newState != StatusBarState.SHADE) popup?.dismiss()
        update()
    }

    override fun onNowBarChanged(items: List<NowBarItem>) {
        this.items = items
        popup?.let { open ->
            val key = open.key ?: return@let
            open.update(items.firstOrNull { it.key == key }, NowBarRepository.instance?.goalFor(key))
        }
        update()
    }

    private fun openScore(game: NowBarItem) {
        popup?.dismiss()
        popup = NowBarScorePopup(context) {
            popup = null
            update()
        }.also { it.show(game, NowBarRepository.instance?.goalFor(game.key)) }
        update()
    }

    private fun update() {
        val repository = NowBarRepository.instance
        val top = items.firstOrNull()
        item = top?.takeIf {
            repository?.settings?.chip == true && state == StatusBarState.SHADE &&
                ((it.type == NowBarType.MUSIC && it.playing) || it.type == NowBarType.TIMER ||
                    it.type == NowBarType.LIVE ||
                    (it.type == NowBarType.SPORTS && it.score?.score1?.isNotEmpty() == true) ||
                    (it.type == NowBarType.RECORDING && it.packageName != context.packageName))
        }
        val shown = item
        // The card stands in for the chip while it's open.
        if (shown == null || popup != null) {
            visibility = GONE
            chronometer.stop()
            return
        }
        visibility = VISIBLE
        val score = shown.score
        if (shown.type == NowBarType.SPORTS && score != null) {
            chronometer.stop()
            chronometer.visibility = GONE
            icon.imageTintList = null
            icon.setImageDrawable(score.logo1)
            icon2.setImageDrawable(score.logo2)
            icon2.visibility = if (score.logo2 != null) VISIBLE else GONE
            label.visibility = VISIBLE
            label.text = "${score.score1}-${score.score2}"
            contentDescription = NowBarScoreViews.title(score)
            return
        }
        icon.imageTintList = ColorStateList.valueOf(Color.WHITE)
        icon2.visibility = GONE
        icon.setImageDrawable(
            if (shown.type == NowBarType.MUSIC) {
                context.getDrawable(if (shown.playing) R.drawable.rr_nowbar_pause else R.drawable.rr_nowbar_play)
            } else {
                shown.icon?.mutate()
            }
        )
        if (shown.chronometerBase != 0L) {
            chronometer.base =
                SystemClock.elapsedRealtime() + (shown.chronometerBase - System.currentTimeMillis())
            chronometer.isCountDown = shown.countDown
            chronometer.start()
            chronometer.visibility = VISIBLE
            label.visibility = GONE
        } else {
            chronometer.stop()
            chronometer.visibility = GONE
            label.visibility = VISIBLE
            label.text = shown.live?.shortText ?: shown.title
        }
        contentDescription = shown.title
    }
}
