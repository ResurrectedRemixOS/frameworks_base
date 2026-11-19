/*
 * SPDX-FileCopyrightText: The Resurrected Remix Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.systemui.rr.nowbar

import android.graphics.drawable.Icon
import android.os.Bundle

class NowBarOneUi private constructor(
    val title: CharSequence?,
    val text: CharSequence?,
    val icon: Icon?,
    val live: NowBarLive?,
    val automation: Boolean,
) {
    companion object {
        private const val PREFIX = "android.ongoingActivityNoti."
        private const val STYLE = PREFIX + "style"
        private const val AUTOMATION = PREFIX + "automation"
        private const val PRIMARY = PREFIX + "primaryInfo"
        private const val SECONDARY = PREFIX + "secondaryInfo"
        private const val BAR_PRIMARY = PREFIX + "nowbarPrimaryInfo"
        private const val BAR_SECONDARY = PREFIX + "nowbarSecondaryInfo"
        private const val BAR_ICON = PREFIX + "nowbarIcon"
        private const val CHIP_ICON = PREFIX + "chipIcon"
        private const val PROGRESS = PREFIX + "progress"
        private const val PROGRESS_MAX = PREFIX + "progressMax"
        private const val INDETERMINATE = PREFIX + "progressIndeterminate"
        private const val SEGMENTS = PREFIX + "progressSegments"
        private const val SEGMENT_START = "$SEGMENTS.segmentStart"
        private const val SEGMENT_COLOR = "$SEGMENTS.segmentColor"

        fun of(extras: Bundle): NowBarOneUi? =
            try {
                val automation = extras.getBoolean(AUTOMATION)
                if (extras.getInt(STYLE) == 0 && !automation) null else NowBarOneUi(
                    title = extras.getCharSequence(BAR_PRIMARY) ?: extras.getCharSequence(PRIMARY),
                    text = extras.getCharSequence(BAR_SECONDARY) ?: extras.getCharSequence(SECONDARY),
                    icon = extras.getParcelable(BAR_ICON, Icon::class.java)
                        ?: extras.getParcelable(CHIP_ICON, Icon::class.java),
                    live = liveOf(extras),
                    automation = automation,
                )
            } catch (e: RuntimeException) {
                null
            }

        private fun liveOf(extras: Bundle): NowBarLive? {
            val max = extras.getInt(PROGRESS_MAX)
            val at = extras.getInt(PROGRESS, -1)
            val indeterminate = extras.getBoolean(INDETERMINATE)
            if (max <= 0 && !indeterminate) return null
            @Suppress("DEPRECATION")
            val parts = extras.getParcelableArray(SEGMENTS)?.mapNotNull { it as? Bundle }.orEmpty()
                .map { it.getFloat(SEGMENT_START).coerceIn(0f, 1f) to it.getInt(SEGMENT_COLOR) }
                .sortedBy { it.first }
            val starts = if (parts.isNotEmpty() && parts[0].first > 0f) listOf(0f to 0) + parts else parts
            val segments = starts.mapIndexed { i, (start, color) ->
                ((starts.getOrNull(i + 1)?.first ?: 1f) - start) to color
            }.filter { it.first > 0f }
            return NowBarLive(
                segments = segments.ifEmpty { listOf(1f to 0) },
                points = emptyList(),
                progress = if (indeterminate || max <= 0 || at < 0) -1f
                    else (at.toFloat() / max).coerceIn(0f, 1f),
                styledByProgress = false,
                shortText = null,
            )
        }
    }
}
