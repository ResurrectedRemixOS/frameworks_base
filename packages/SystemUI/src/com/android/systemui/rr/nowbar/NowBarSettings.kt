/*
 * SPDX-FileCopyrightText: The Resurrected Remix Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.systemui.rr.nowbar

import android.content.Context
import android.database.ContentObserver
import android.os.UserHandle
import android.provider.Settings
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

/** Now Bar switches, kept in Settings.System and set from Remixer › Lock screen › Now Bar. */
class NowBarSettings(private val context: Context) {
    var enabled = true
        private set
    var wave = true
        private set
    var aod = true
        private set
    var chip = true
        private set
    var hideCall = true
        private set
    /** Live scores from At a Glance: [NowBarScores.SOURCE_GLANCE], or [NowBarScores.SOURCE_OFF]. */
    var scores = NowBarScores.SOURCE_GLANCE
        private set
    var look = NowBarLook()
        private set
    /** Packages whose Live Updates show; empty means every app. */
    var liveApps: Set<String> = emptySet()
        private set
    /** The package whose Live Update stays first and on the always-on display. */
    var pinned: String? = null
        private set
    private val shown = HashMap<NowBarType, Boolean>()

    init {
        read()
    }

    fun read() {
        enabled = get(KEY_ENABLED, true)
        wave = get(KEY_WAVE, true)
        aod = get(KEY_AOD, true)
        chip = get(KEY_CHIP, true)
        hideCall = get(KEY_HIDE_CALL, true)
        scores = Settings.System.getIntForUser(context.contentResolver, KEY_SCORES,
            NowBarScores.SOURCE_GLANCE, UserHandle.USER_CURRENT)
        shown[NowBarType.SPORTS] = scores != NowBarScores.SOURCE_OFF
        shown[NowBarType.MUSIC] = get(KEY_MUSIC, true)
        shown[NowBarType.TIMER] = get(KEY_TIMERS, true)
        shown[NowBarType.CALL] = get(KEY_CALLS, true)
        val live = get(KEY_LIVE, true)
        shown[NowBarType.NAVIGATION] = live
        shown[NowBarType.LIVE] = live
        liveApps = Settings.System.getStringForUser(context.contentResolver, KEY_LIVE_APPS,
            UserHandle.USER_CURRENT)?.split(',')?.filter { it.isNotBlank() }?.toSet() ?: emptySet()
        pinned = Settings.System.getStringForUser(context.contentResolver, KEY_PIN,
            UserHandle.USER_CURRENT)?.takeIf { it.isNotBlank() }
        shown[NowBarType.RECORDING] = get(KEY_RECORDING, true)
        shown[NowBarType.CHARGING] = get(KEY_CHARGING, false)
        shown[NowBarType.FLASHLIGHT] = get(KEY_FLASHLIGHT, false)
        shown[NowBarType.BUDS] = get(KEY_BUDS, true)
        shown[NowBarType.MODE] = get(KEY_MODES, true)
        val base = NowBarLook()
        look = NowBarLook(
            glass = int(KEY_MATERIAL, 0) == 1,
            color = int(KEY_COLOR, base.color),
            opacity = int(KEY_OPACITY, base.opacity).coerceIn(0, 100),
            outline = int(KEY_OUTLINE, base.outline),
            shadow = int(KEY_SHADOW, base.shadow),
            more = int(KEY_MORE, base.more),
            height = int(KEY_HEIGHT, base.height).coerceIn(48, 96),
            width = int(KEY_WIDTH, base.width),
            corners = int(KEY_CORNERS, base.corners).coerceIn(20, 100),
            raise = int(KEY_RAISE, base.raise).coerceIn(0, 40),
            aboveFingerprint = get(KEY_ABOVE_FINGERPRINT, base.aboveFingerprint),
            art = int(KEY_ART, base.art),
            buttons = int(KEY_BUTTONS, base.buttons),
            text = int(KEY_TEXT, base.text),
            textSize = int(KEY_TEXT_SIZE, base.textSize).coerceIn(11, 17),
        )
    }

    private fun int(key: String, def: Int) =
        Settings.System.getIntForUser(context.contentResolver, key, def, UserHandle.USER_CURRENT)

    fun shows(type: NowBarType) = enabled && shown[type] == true

    fun showsLiveFrom(packageName: String) = liveApps.isEmpty() || packageName in liveApps

    /** The lock screen call row steps aside while that call is in the Now Bar. */
    fun hidesLockscreenCalls() = shows(NowBarType.CALL) && hideCall

    private fun get(key: String, def: Boolean) = get(context, key, def)

    companion object {
        const val KEY_ENABLED = "now_bar"
        const val KEY_MUSIC = "now_bar_music"
        const val KEY_TIMERS = "now_bar_timers"
        const val KEY_CALLS = "now_bar_calls"
        const val KEY_HIDE_CALL = "now_bar_hide_call"
        const val KEY_LIVE = "now_bar_live_updates"
        const val KEY_RECORDING = "now_bar_recording"
        const val KEY_CHARGING = "now_bar_charging"
        const val KEY_FLASHLIGHT = "now_bar_flashlight"
        const val KEY_BUDS = "now_bar_buds"
        const val KEY_MODES = "now_bar_modes"
        const val KEY_WAVE = "now_bar_wave"
        const val KEY_AOD = "now_bar_aod"
        const val KEY_CHIP = "now_bar_chip"
        const val KEY_SCORES = "now_bar_scores"
        const val KEY_MATERIAL = "now_bar_material"
        const val KEY_COLOR = "now_bar_color"
        const val KEY_OPACITY = "now_bar_opacity"
        const val KEY_OUTLINE = "now_bar_outline"
        const val KEY_SHADOW = "now_bar_shadow"
        const val KEY_MORE = "now_bar_more"
        const val KEY_HEIGHT = "now_bar_height"
        const val KEY_WIDTH = "now_bar_width"
        const val KEY_CORNERS = "now_bar_corners"
        const val KEY_RAISE = "now_bar_raise"
        const val KEY_ABOVE_FINGERPRINT = "now_bar_above_fingerprint"
        const val KEY_ART = "now_bar_art"
        const val KEY_BUTTONS = "now_bar_buttons"
        const val KEY_TEXT = "now_bar_text"
        const val KEY_TEXT_SIZE = "now_bar_text_size"
        const val KEY_LIVE_APPS = "now_bar_live_apps"
        const val KEY_PIN = "now_bar_pin"
        val KEYS = arrayOf(
            KEY_ENABLED, KEY_MUSIC, KEY_TIMERS, KEY_CALLS, KEY_HIDE_CALL, KEY_LIVE,
            KEY_RECORDING, KEY_CHARGING,
            KEY_FLASHLIGHT, KEY_BUDS, KEY_MODES, KEY_WAVE, KEY_AOD, KEY_CHIP, KEY_SCORES,
            KEY_MATERIAL, KEY_COLOR, KEY_OPACITY, KEY_OUTLINE, KEY_SHADOW, KEY_MORE, KEY_HEIGHT,
            KEY_WIDTH, KEY_CORNERS, KEY_RAISE, KEY_ART, KEY_BUTTONS, KEY_TEXT, KEY_TEXT_SIZE,
            KEY_LIVE_APPS,
            KEY_PIN,
            KEY_ABOVE_FINGERPRINT,
        )

        private fun get(context: Context, key: String, def: Boolean) =
            Settings.System.getIntForUser(
                context.contentResolver, key, if (def) 1 else 0, UserHandle.USER_CURRENT
            ) == 1

        /** With music in the Now Bar, the lock screen player steps aside. */
        @JvmStatic
        fun hidesLockscreenMedia(context: Context) =
            get(context, KEY_ENABLED, true) && get(context, KEY_MUSIC, true)

        /** Fires when [hidesLockscreenMedia] may have changed. */
        @JvmStatic
        fun lockscreenMediaChanges(context: Context): Flow<Unit> = callbackFlow {
            val observer = object : ContentObserver(null) {
                override fun onChange(selfChange: Boolean) {
                    trySend(Unit)
                }
            }
            for (key in arrayOf(KEY_ENABLED, KEY_MUSIC)) {
                context.contentResolver.registerContentObserver(
                    Settings.System.getUriFor(key), false, observer, UserHandle.USER_ALL
                )
            }
            awaitClose { context.contentResolver.unregisterContentObserver(observer) }
        }
    }
}
