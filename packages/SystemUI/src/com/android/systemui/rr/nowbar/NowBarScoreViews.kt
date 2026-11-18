/*
 * SPDX-FileCopyrightText: The Resurrected Remix Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.systemui.rr.nowbar

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.view.View
import android.widget.RemoteViews
import com.android.systemui.res.R

/** A game's score card, the same in the shade and under the status bar chip, like One UI's. */
object NowBarScoreViews {

    /** The whole card; [dark] for a card on its own dark background rather than the shade's. */
    fun card(context: Context, score: NowBarScore, line: CharSequence?, dark: Boolean): RemoteViews =
        RemoteViews(context.packageName, R.layout.now_bar_score_card).apply {
            setTextViewText(R.id.score_card_team1, score.team1)
            setTextViewText(R.id.score_card_team2, score.team2)
            setTextViewText(R.id.score_card_score1, score.score1)
            setTextViewText(R.id.score_card_score2, score.score2)
            logo(R.id.score_card_logo1, score.logo1)
            logo(R.id.score_card_logo2, score.logo2)
            clock(R.id.score_card_clock, score.status)
            val league = score.league
            setTextViewText(R.id.score_card_league, league ?: "")
            setViewVisibility(R.id.score_card_league, if (league.isNullOrEmpty()) View.GONE else View.VISIBLE)
            setTextViewText(R.id.score_card_moment, line ?: "")
            val shown = if (line.isNullOrEmpty()) View.GONE else View.VISIBLE
            setViewVisibility(R.id.score_card_divider, shown)
            setViewVisibility(R.id.score_card_moment, shown)
            if (dark) {
                for (id in intArrayOf(R.id.score_card_score1, R.id.score_card_score2,
                        R.id.score_card_moment)) {
                    setTextColor(id, PRIMARY_ON_DARK)
                }
                for (id in intArrayOf(R.id.score_card_source, R.id.score_card_team1,
                        R.id.score_card_team2, R.id.score_card_league)) {
                    setTextColor(id, SECONDARY_ON_DARK)
                }
            }
        }

    /** One line, for the shade's collapsed card. */
    fun row(context: Context, score: NowBarScore): RemoteViews =
        RemoteViews(context.packageName, R.layout.now_bar_score_row).apply {
            setTextViewText(R.id.score_row_team1, score.team1)
            setTextViewText(R.id.score_row_team2, score.team2)
            setTextViewText(R.id.score_row_score, "${score.score1} – ${score.score2}")
            logo(R.id.score_row_logo1, score.logo1)
            logo(R.id.score_row_logo2, score.logo2)
            clock(R.id.score_row_clock, score.status)
        }

    /** The score the chip and the card's title read out. */
    fun title(score: NowBarScore) = "${score.team1} ${score.score1} – ${score.score2} ${score.team2}"

    /** A goal seen here, for when Google's own line can't be read. */
    fun goalLine(context: Context, goal: NowBarGoal, score: NowBarScore): String {
        val label = goal.label.toString().uppercase(context.resources.configuration.locales[0])
        val team = if (goal.team == 1) score.team1 else score.team2
        return listOfNotNull("$label! $team", goal.minute?.takeIf { it.isNotEmpty() })
            .joinToString(" · ")
    }

    private fun RemoteViews.logo(id: Int, logo: Drawable?) {
        val bitmap = logo?.let(::bitmapOf)
        if (bitmap != null) setImageViewBitmap(id, bitmap)
        setViewVisibility(id, if (bitmap != null) View.VISIBLE else View.INVISIBLE)
    }

    private fun RemoteViews.clock(id: Int, status: CharSequence?) {
        setTextViewText(id, status ?: "")
        setViewVisibility(id, if (status.isNullOrEmpty()) View.GONE else View.VISIBLE)
        // A minute or a game clock while it's on; Half time, Final and the like after.
        setInt(id, "setBackgroundResource",
            if (status?.any { it.isDigit() } == true) R.drawable.now_bar_score_clock
            else R.drawable.now_bar_score_clock_over)
    }

    private fun bitmapOf(drawable: Drawable): Bitmap? {
        (drawable as? BitmapDrawable)?.bitmap?.let { return it }
        val w = drawable.intrinsicWidth.takeIf { it > 0 } ?: LOGO_SIZE
        val h = drawable.intrinsicHeight.takeIf { it > 0 } ?: LOGO_SIZE
        return try {
            Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also {
                val canvas = Canvas(it)
                drawable.setBounds(0, 0, w, h)
                drawable.draw(canvas)
            }
        } catch (e: RuntimeException) {
            null
        }
    }

    private const val LOGO_SIZE = 96
    private const val PRIMARY_ON_DARK = 0xFFFFFFFF.toInt()
    private const val SECONDARY_ON_DARK = 0xB8FFFFFF.toInt()
}
