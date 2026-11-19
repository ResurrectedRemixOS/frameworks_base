/*
 * SPDX-FileCopyrightText: The Resurrected Remix Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.systemui.rr.nowbar

import android.app.PendingIntent
import android.graphics.Bitmap
import android.graphics.drawable.Drawable
import android.graphics.drawable.Icon
import android.media.session.MediaController

/** What the Now Bar can show, in the order it shows them. */
enum class NowBarType {
    CALL,
    NAVIGATION,
    TIMER,
    RECORDING,
    MUSIC,
    SPORTS,
    LIVE,
    CHARGING,
    FLASHLIGHT,
    /** Connected earbuds or a watch and their battery. */
    BUDS,
    MODE,
}

/** A button on a Now Bar item: a notification action or one of ours. */
class NowBarAction(
    val title: CharSequence,
    val icon: Icon? = null,
    val iconRes: Int = 0,
    val intent: PendingIntent? = null,
    val run: (() -> Unit)? = null,
)

/** A game: both sides, their scores and where the game is at. */
class NowBarScore(
    val team1: CharSequence,
    val team2: CharSequence,
    val score1: CharSequence,
    val score2: CharSequence,
    val logo1: Drawable?,
    val logo2: Drawable?,
    val status: CharSequence?,
    /** The competition, as the card names it. */
    val league: CharSequence? = null,
    /** Each team's color from its crest, dark enough under white text. */
    val color1: Int? = null,
    val color2: Int? = null,
)

/** A team just scored in a game the Now Bar shows, for the bar to celebrate once. */
class NowBarGoal(
    val key: String,
    /** 1 or 2, the team that scored. */
    val team: Int,
    val label: CharSequence,
    /** A ball flies into a net with it. */
    val net: Boolean,
    /** The game's clock when it went in. */
    val minute: CharSequence?,
    /** [android.os.SystemClock.elapsedRealtime] when it was seen. */
    val at: Long,
)

/** A Live Update's progress bar: Android 16's segments and points, as fractions of the bar. */
class NowBarLive(
    /** Each segment's share of the bar and its color, 0 for the default. */
    val segments: List<Pair<Float, Int>>,
    /** Each point's position along the bar and its color, 0 for the default. */
    val points: List<Pair<Float, Int>>,
    /** 0 to 1, or -1 while indeterminate. */
    val progress: Float,
    /** Only the part behind the progress takes the segment colors. */
    val styledByProgress: Boolean,
    /** The notification's short critical text, like "4 min". */
    val shortText: String?,
)

/** One activity in the Now Bar. */
class NowBarItem(
    /** Stable across updates: the notification key, "media:<package>", "charging"... */
    val key: String,
    val type: NowBarType,
    val packageName: String?,
    val icon: Drawable?,
    val art: Bitmap?,
    /** The capsule tint and wave colors drawn from [art], made with it. */
    val artColors: IntArray? = null,
    val title: CharSequence,
    val text: CharSequence?,
    /** Wall clock time a chronometer counts from or to, or 0. */
    val chronometerBase: Long = 0L,
    val countDown: Boolean = false,
    /** 0 to 1, or -1 for none. */
    val progress: Float = -1f,
    val color: Int = 0,
    val playing: Boolean = false,
    val actions: List<NowBarAction> = emptyList(),
    val contentIntent: PendingIntent? = null,
    val controller: MediaController? = null,
    val score: NowBarScore? = null,
    val live: NowBarLive? = null,
    val automation: Boolean = false,
)
