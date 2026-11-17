/*
 * SPDX-FileCopyrightText: The Resurrected Remix Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.systemui.rr.nowbar

/** How the Now Bar looks, set from Remixer › Lock screen › Now Bar. Sizes are in dp and sp. */
data class NowBarLook(
    val glass: Boolean = false,
    val color: Int = COLOR_ART,
    val opacity: Int = 94,
    val outline: Int = OUTLINE_THIN,
    val shadow: Int = SHADOW_OFF,
    val more: Int = MORE_SWIPE,
    val height: Int = 64,
    val width: Int = WIDTH_NORMAL,
    val corners: Int = 100,
    val raise: Int = 0,
    val aboveFingerprint: Boolean = false,
    val art: Int = ART_CIRCLE,
    val buttons: Int = BUTTONS_ALL,
    val text: Int = TEXT_ONE_LINE,
    val textSize: Int = 13,
) {
    companion object {
        const val COLOR_ART = 0
        const val COLOR_ACCENT = 1
        const val COLOR_DARK = 2
        const val OUTLINE_NONE = 0
        const val OUTLINE_THIN = 1
        const val OUTLINE_ACCENT = 2
        const val SHADOW_OFF = 0
        const val SHADOW_SOFT = 1
        const val SHADOW_GLOW = 2
        const val MORE_SWIPE = 0
        const val MORE_DOTS = 1
        const val MORE_PEEK = 2
        const val WIDTH_NARROW = 0
        const val WIDTH_NORMAL = 1
        const val WIDTH_FULL = 2
        const val ART_CIRCLE = 0
        const val ART_ROUNDED = 1
        const val ART_SQUARE = 2
        const val ART_OFF = 3
        const val BUTTONS_ALL = 0
        const val BUTTONS_PLAY = 1
        const val BUTTONS_NONE = 2
        const val TEXT_ONE_LINE = 0
        const val TEXT_TWO_LINES = 1
        const val TEXT_SONG = 2
    }
}
