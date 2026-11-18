/*
 * SPDX-FileCopyrightText: The Resurrected Remix Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.systemui.rr.nowbar

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Bundle
import android.os.UserHandle
import android.service.notification.StatusBarNotification
import com.android.systemui.res.R

/**
 * A score card in the shade for each game under way, like One UI's. The lock screen leaves it
 * out, since the Now Bar has the game there.
 */
class NowBarScoreNotifier(private val context: Context) {
    private val manager = context.getSystemService(NotificationManager::class.java)
    /** What each posted card shows, by game. */
    private val posted = HashMap<String, String>()
    private var started = false
    private var channelMade = false

    fun update(games: List<NowBarItem>, goalFor: (String) -> NowBarGoal?) {
        val live = games.filter { it.score?.score1?.isNotEmpty() == true }
        if (!started) {
            started = true
            // Cards left from before a restart of System UI.
            try {
                manager.activeNotifications.filter { isScoreCard(it) }
                    .forEach { it.tag?.let { tag -> posted[tag] = "" } }
            } catch (e: RuntimeException) {
                // Nothing to clear.
            }
        }
        val keys = live.map { it.key }.toSet()
        for (key in posted.keys.filter { it !in keys }) {
            manager.cancelAsUser(key, ID, UserHandle.CURRENT)
            posted.remove(key)
        }
        if (live.isEmpty()) return
        ensureChannel()
        for (item in live) {
            val score = item.score ?: continue
            val goal = goalFor(item.key)
            val shows = listOf(score.team1, score.score1, score.score2, score.team2, score.status,
                score.league, goal?.at).joinToString("|")
            if (posted[item.key] == shows) continue
            posted[item.key] = shows
            manager.notifyAsUser(item.key, ID, build(item, score, goal), UserHandle.CURRENT)
        }
    }

    private fun build(item: NowBarItem, score: NowBarScore, goal: NowBarGoal?): Notification {
        val name = context.getString(R.string.now_bar_scores_channel)
        return Notification.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.rr_nowbar_sports)
            .setContentTitle(NowBarScoreViews.title(score))
            .setContentText(listOfNotNull(score.status, score.league).joinToString(" · "))
            .setStyle(Notification.DecoratedCustomViewStyle())
            .setCustomContentView(NowBarScoreViews.row(context, score))
            .setCustomBigContentView(NowBarScoreViews.card(context, score, goal, dark = false))
            .setContentIntent(item.contentIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setShowWhen(false)
            .setLocalOnly(true)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .addExtras(Bundle().apply { putString(Notification.EXTRA_SUBSTITUTE_APP_NAME, name) })
            .build()
    }

    private fun ensureChannel() {
        if (channelMade) return
        manager.createNotificationChannel(NotificationChannel(CHANNEL,
            context.getString(R.string.now_bar_scores_channel), NotificationManager.IMPORTANCE_LOW
        ).apply { setShowBadge(false) })
        channelMade = true
    }

    companion object {
        const val CHANNEL = "now_bar_scores"
        private const val ID = 0x5C0E

        /** A game's card from here, which the lock screen leaves to the Now Bar. */
        @JvmStatic
        fun isScoreCard(sbn: StatusBarNotification) =
            sbn.packageName == "com.android.systemui" && sbn.notification.channelId == CHANNEL
    }
}
