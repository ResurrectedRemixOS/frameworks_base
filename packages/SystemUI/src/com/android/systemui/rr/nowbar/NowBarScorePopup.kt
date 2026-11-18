/*
 * SPDX-FileCopyrightText: The Resurrected Remix Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.systemui.rr.nowbar

import android.app.PendingIntent
import android.content.Context
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import com.android.internal.policy.SystemBarUtils

/** The game's card, dropped under the status bar from the Now Bar chip like One UI's. */
class NowBarScorePopup(private val context: Context, private val onDismiss: () -> Unit) {
    private val density = context.resources.displayMetrics.density
    private val windowManager = context.getSystemService(WindowManager::class.java)
    private val root = FrameLayout(context)
    private val card = FrameLayout(context)
    private var content: View? = null
    private var intent: PendingIntent? = null
    var key: String? = null
        private set

    fun show(item: NowBarItem, goal: NowBarGoal?) {
        root.setOnClickListener { dismiss() }
        card.background = GradientDrawable().apply {
            cornerRadius = dp(28f)
            setColor(0xF2201A1C.toInt())
            setStroke(dp(1f).toInt(), 0x1AFFFFFF)
        }
        card.clipToOutline = true
        card.setOnClickListener {
            intent?.let {
                NowBarRepository.instance?.activityStarter?.postStartActivityDismissingKeyguard(it)
            }
            dismiss()
        }
        root.addView(card, FrameLayout.LayoutParams(-1, -2, Gravity.TOP).apply {
            marginStart = dp(12f).toInt()
            marginEnd = dp(12f).toInt()
            topMargin = SystemBarUtils.getStatusBarHeight(context) + dp(6f).toInt()
        })
        if (!update(item, goal)) return
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
        lp.title = "RRNowBarScore"
        try {
            windowManager.addView(root, lp)
        } catch (e: RuntimeException) {
            onDismiss()
            return
        }
        card.alpha = 0f
        card.translationY = -dp(12f)
        card.scaleX = 0.96f
        card.scaleY = 0.96f
        card.animate().alpha(1f).translationY(0f).scaleX(1f).scaleY(1f).setDuration(200L).start()
    }

    /** The game's newest state; false, and closed, once it is no longer under way. */
    fun update(item: NowBarItem?, goal: NowBarGoal?): Boolean {
        val score = item?.score
        if (item == null || score == null || score.score1.isEmpty()) {
            dismiss()
            return false
        }
        key = item.key
        intent = item.contentIntent
        val views = NowBarScoreViews.card(context, score, goal, dark = true)
        val shown = content
        if (shown == null) {
            content = views.apply(context, card).also { card.addView(it) }
        } else {
            views.reapply(context, shown)
        }
        card.contentDescription = NowBarScoreViews.title(score)
        return true
    }

    fun dismiss() {
        if (root.isAttachedToWindow) windowManager.removeViewImmediate(root)
        onDismiss()
    }

    private fun dp(value: Float) = value * density
}
