/*
 * SPDX-FileCopyrightText: The Resurrected Remix Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.systemui.rr.charging

import android.content.Context
import android.content.Intent
import android.os.BatteryManager
import android.os.UserHandle
import android.provider.Settings
import com.android.settingslib.fuelgauge.BatteryStatus
import com.android.systemui.bliss.charging.BlissChargingAnimationView
import com.android.systemui.res.R

object RrChargingStyles {
    const val SETTING = "rr_charging_animation"
    const val PREVIEW = "rr_charging_animation_preview"
    private const val LEGACY_RING = "rr_charging_ring"

    const val NONE = "none"
    const val PIXEL = "pixel"
    const val ONEPLUS = "oneplus"
    const val NEON = "rr_neon"
    const val DEFIB = "rr_defib"
    const val PULSE = "rr_pulse"
    const val BLISS_FLASH = "bliss_flash"
    const val BLISS_GLOW = "bliss_glow"
    const val BLISS_BATTERY = "bliss_battery"
    const val MOTO = "moto"
    const val NOTHING = "nothing"
    const val ONEUI = "oneui"
    const val HYPEROS = "hyperos"
    const val ORIGINOS = "originos"
    const val SUPERCHARGE = "supercharge"

    val OVERLAYS =
        mapOf(
            MOTO to "com.android.systemui.charging_animation.moto",
            NOTHING to "com.android.systemui.charging_animation.nothing",
        )

    fun selected(context: Context): String {
        val cr = context.contentResolver
        val style = Settings.System.getStringForUser(cr, SETTING, UserHandle.USER_CURRENT)
        if (!style.isNullOrEmpty()) return style
        return if (Settings.System.getIntForUser(cr, LEGACY_RING, 1, UserHandle.USER_CURRENT) == 0) {
            PIXEL
        } else {
            ""
        }
    }

    fun drawn(context: Context, style: String): RrChargingView? =
        when (style) {
            NEON -> NeonRevivalView(context)
            DEFIB -> DefibrillatorView(context)
            PULSE -> PulseRingView(context)
            ONEUI -> OneUiCapsuleView(context)
            HYPEROS -> HyperBubblesView(context)
            ORIGINOS -> OriginOrbView(context)
            SUPERCHARGE -> SuperChargeView(context)
            else -> null
        }

    fun blissStyle(style: String) =
        when (style) {
            BLISS_FLASH -> BlissChargingAnimationView.STYLE_FLASH
            BLISS_GLOW -> BlissChargingAnimationView.STYLE_GLOW_PULSE
            BLISS_BATTERY -> BlissChargingAnimationView.STYLE_BATTERY_FILL
            else -> 0
        }

    fun watts(sticky: Intent?): Int {
        if (sticky == null) return 0
        val oem = sticky.getIntExtra(BatteryManager.EXTRA_OEM_CHARGER_WATTS, 0)
        return if (oem > 0) oem else BatteryStatus(sticky).maxChargingWattage / 1_000_000
    }

    fun speed(context: Context, sticky: Intent?): Int {
        if (sticky == null) return RrChargingView.SPEED_NORMAL
        val w = watts(sticky)
        val s = BatteryStatus.getChargingSpeed(context, sticky)
        val fast = context.resources.getInteger(com.android.internal.R.integer.config_chargingFastWatts)
        val max = context.resources.getInteger(com.android.internal.R.integer.config_chargingMaxWatts)
        return when {
            s == BatteryStatus.CHARGING_OEM -> RrChargingView.SPEED_SUPER
            if (max > 0) w >= max * 0.6f else w >= 45 -> RrChargingView.SPEED_SUPER
            s == BatteryStatus.CHARGING_FAST -> RrChargingView.SPEED_FAST
            w >= (if (fast > 0) fast * 0.6f else 18f) -> RrChargingView.SPEED_FAST
            else -> RrChargingView.SPEED_NORMAL
        }
    }

    fun label(context: Context, speed: Int, watts: Int): String {
        val brand = context.getString(com.android.internal.R.string.config_chargingMaxLabel)
        val state =
            if (speed == RrChargingView.SPEED_SUPER && brand.isNotEmpty()) {
                brand
            } else {
                context.getString(
                    when (speed) {
                        RrChargingView.SPEED_SUPER -> R.string.rr_charging_anim_super
                        RrChargingView.SPEED_FAST -> R.string.rr_charging_anim_fast
                        else -> R.string.rr_charging_anim_normal
                    }
                )
            }
        return if (watts > 0) context.getString(R.string.rr_charging_anim_watts, state, watts) else state
    }
}
