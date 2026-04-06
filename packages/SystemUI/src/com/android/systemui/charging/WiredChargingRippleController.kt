/*
 * Copyright (C) 2021 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.systemui.charging

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ObjectAnimator
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.BatteryManager
import android.os.Handler
import android.os.Looper
import android.os.SystemProperties
import android.os.UserHandle
import android.provider.Settings
import android.text.format.Formatter
import android.util.Log
import android.util.TypedValue
import android.view.MotionEvent
import android.view.Surface
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.view.animation.LinearInterpolator
import android.widget.FrameLayout
import com.android.internal.annotations.VisibleForTesting
import com.android.internal.logging.UiEvent
import com.android.internal.logging.UiEventLogger
import com.android.settingslib.Utils
import com.android.settingslib.fuelgauge.BatteryStatus
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.flags.FeatureFlags
import com.android.systemui.flags.Flags
import com.android.systemui.res.R
import com.android.systemui.statusbar.commandline.Command
import com.android.systemui.statusbar.commandline.CommandRegistry
import com.android.systemui.statusbar.policy.BatteryController
import com.android.systemui.statusbar.policy.ConfigurationController
import com.android.systemui.surfaceeffects.view.ripple.RippleView
import com.android.systemui.util.time.SystemClock
import com.oplus.vfxsdk.charge.charging.IChargingEngineControl
import com.oplus.vfxsdk.charge.charging.VFXChargingTextureView
import java.io.File
import java.io.PrintWriter
import javax.inject.Inject
import kotlin.math.min
import kotlin.math.pow

private const val MAX_DEBOUNCE_LEVEL = 3
private const val BASE_DEBOUNCE_TIME = 2000
private const val ACTION_BOOST_CHARGING = "org.lineageos.device.settings.action.BOOST_CHARGING"
private const val DEVICE_SETTINGS_PACKAGE = "org.lineageos.device.settings"
private const val SETTINGS_CHARGE_BOOST_AVAILABLE = "device_settings_charge_boost_available"
private const val SETTINGS_CHARGE_HUD_MODE = "device_settings_charge_hud_mode"
private const val HUD_MODE_UNLIMITED = 0
private const val HUD_MODE_STANDARD = 1
private const val HUD_MODE_NIGHT = 2
/** cool_down=5 → svooc_2_0_curr_table 3000 mA at ~10 V SuperVOOC. */
private const val STANDARD_CAP_WATTS = 30
private const val UI_SOC_DECIMAL_PATH = "/proc/ui_soc_decimal"
private const val UI_SOC_DECIMAL_POLL_MS = 250L

/**
 * Controls the ripple effect that shows when wired charging begins. The ripple uses the accent
 * color of the current theme.
 *
 * On Oplus devices that ship libnativeChargingRing, plug-in shows the stock SuperVOOC ring
 * instead ([ChargingVfx]): it waits for the charger handshake, then shows the ring with a HUD
 * (SUPERVOOC + watts, or the plug source). A tap or unplug dismisses it. While DeviceSettings
 * caps the current, a long press inside the ring boosts this plug session. The window is
 * capped at the native 20 s clip; the AOSP ripple is the fallback.
 */
@SysUISingleton
class WiredChargingRippleController
@Inject
constructor(
    commandRegistry: CommandRegistry,
    private val batteryController: BatteryController,
    private val configurationController: ConfigurationController,
    featureFlags: FeatureFlags,
    private val context: Context,
    private val windowManager: WindowManager,
    private val systemClock: SystemClock,
    private val uiEventLogger: UiEventLogger,
) {
    private var pluggedIn: Boolean = false
    private var batteryLevel: Int = 0
    private val rippleEnabled: Boolean =
        featureFlags.isEnabled(Flags.CHARGING_RIPPLE) &&
            !SystemProperties.getBoolean("persist.debug.suppress-charging-ripple", false)
    private var normalizedPortPosX: Float =
        context.resources.getFloat(R.dimen.physical_charger_port_location_normalized_x)
    private var normalizedPortPosY: Float =
        context.resources.getFloat(R.dimen.physical_charger_port_location_normalized_y)
    private val windowLayoutParams =
        WindowManager.LayoutParams().apply {
            width = WindowManager.LayoutParams.MATCH_PARENT
            height = WindowManager.LayoutParams.MATCH_PARENT
            layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            format = PixelFormat.TRANSLUCENT
            type = WindowManager.LayoutParams.TYPE_KEYGUARD_DIALOG
            fitInsetsTypes = 0 // Ignore insets from all system bars
            title = "Wired Charging Animation"
            flags = PASS_THROUGH_WINDOW_FLAGS
            setTrustedOverlay()
        }
    private var lastTriggerTime: Long? = null
    private var debounceLevel = 0
    private var handshakePending = false
    private var handshakeAttempts = 0
    private val mainHandler = Handler(Looper.getMainLooper())
    private val handshakePoll = Runnable { pollHandshakeAndStart() }
    private var vfxContainer: FrameLayout? = null
    private val vfxWindowEnd = Runnable { fadeOutVfxWindow() }
    private val vfxShowHud = Runnable { revealVfxHud() }
    private val vfxLongPress = Runnable { onVfxLongPress() }
    private val vfxSpeedUpTimeout = Runnable { fadeOutVfxWindow() }
    private val vfxDecimalPoll = Runnable { pollUiSocDecimal() }
    private var vfxBranding: ChargingVfxBrandingView? = null
    private var vfxHudShown = false
    private var vfxCanAcceptLongClick = false
    private var vfxLongPressTriggered = false
    private var vfxSessionBoosted = false
    private var vfxTouchInsideCircle = false
    private var vfxDownX = 0f
    private var vfxDownY = 0f
    private var vfxFading = false
    private var vfxFadeAnimator: ObjectAnimator? = null
    private var vfxBatteryReceiver: BroadcastReceiver? = null
    private var vfxStartedElapsed: Long = 0L

    @VisibleForTesting
    var rippleView: RippleView = RippleView(context, attrs = null).also { it.setupShader() }
    @VisibleForTesting
    var axRippleView: AXRippleView = AXRippleView(context, attrs = null)
    @VisibleForTesting
    var axChargingCircleView: AXChargingCircleView = AXChargingCircleView(context, attrs = null)

    init {
        pluggedIn = batteryController.isPluggedIn
        commandRegistry.registerCommand("charging-ripple") { ChargingRippleCommand() }
        updateRippleColor()
    }

    fun registerCallbacks() {
        val batteryStateChangeCallback =
            object : BatteryController.BatteryStateChangeCallback {
                override fun onBatteryLevelChanged(
                    level: Int,
                    nowPluggedIn: Boolean,
                    charging: Boolean,
                ) {
                    // Suppresses the ripple when the state change comes from wireless charging or
                    // its dock.
                    if (
                        batteryController.isPluggedInWireless ||
                            batteryController.isChargingSourceDock
                    ) {
                        return
                    }

                    batteryLevel = level
                    if (!pluggedIn && nowPluggedIn) {
                        startRippleWithDebounce()
                    } else if (pluggedIn && !nowPluggedIn) {
                        dismissChargingAnim("unplug")
                    }
                    pluggedIn = nowPluggedIn
                }
            }
        batteryController.addCallback(batteryStateChangeCallback)

        val configurationChangedListener =
            object : ConfigurationController.ConfigurationListener {
                override fun onUiModeChanged() {
                    updateRippleColor()
                }

                override fun onThemeChanged() {
                    updateRippleColor()
                }

                override fun onConfigChanged(newConfig: Configuration?) {
                    normalizedPortPosX =
                        context.resources.getFloat(
                            R.dimen.physical_charger_port_location_normalized_x
                        )
                    normalizedPortPosY =
                        context.resources.getFloat(
                            R.dimen.physical_charger_port_location_normalized_y
                        )
                }
            }
        configurationController.addCallback(configurationChangedListener)
    }

    // Lazily debounce ripple to avoid triggering ripple constantly (e.g. from flaky chargers).
    internal fun startRippleWithDebounce() {
        val now = systemClock.elapsedRealtime()
        // Debounce wait time = 2 ^ debounce level
        if (
            lastTriggerTime == null ||
                (now - lastTriggerTime!!) > BASE_DEBOUNCE_TIME * (2.0.pow(debounceLevel))
        ) {
            // Not waiting for debounce. Start ripple.
            startRipple()
            debounceLevel = 0
        } else {
            // Still waiting for debounce. Ignore ripple and bump debounce level.
            debounceLevel = min(MAX_DEBOUNCE_LEVEL, debounceLevel + 1)
        }
        lastTriggerTime = now
    }

    fun startRipple() {
        if (ChargingVfx.isEnabled(context)) {
            if (handshakePending || vfxContainer?.parent != null) return
            // fast_chg_type often reads 0 at plug-in; wait for the handshake.
            handshakePending = true
            handshakeAttempts = 0
            pollHandshakeAndStart()
        } else if (context.resources.getBoolean(R.bool.config_useCustomChargingAnim)) {
            startCustomRipple()
        } else {
            startAospRipple()
        }
    }

    private fun startCustomRipple() {
        val mode = context.resources.getInteger(R.integer.config_chargingAnimMode)
        if (mode == CHARGING_ANIM_MODE_CIRCLE) {
            startCircleAnimation()
        } else {
            startRippleAnimation()
        }
    }

    private fun startRippleAnimation() {
        if (axRippleView.rippleInProgress() || axRippleView.parent != null) {
            // Skip if ripple is still playing, or not playing but already added the parent
            // (which might happen just before the animation starts or right after
            // the animation ends.)
            return
        }
        axRippleView.preloadRes()
        windowLayoutParams.packageName = context.opPackageName
        axRippleView.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewDetachedFromWindow(view: View) {}

            override fun onViewAttachedToWindow(view: View) {
                axRippleView.startRipple(Runnable {
                    windowManager.removeView(axRippleView)
                })
                axRippleView.removeOnAttachStateChangeListener(this)
            }
        })
        windowManager.addView(axRippleView, windowLayoutParams)
        uiEventLogger.log(WiredChargingRippleEvent.CHARGING_RIPPLE_PLAYED)
    }

    private fun startCircleAnimation() {
        if (axChargingCircleView.animationInProgress() || axChargingCircleView.parent != null) {
            // Skip if animation is still playing, or not playing but already added the parent
            // (which might happen just before the animation starts or right after
            // the animation ends.)
            return
        }
        axChargingCircleView.setBatteryLevel(batteryLevel)
        axChargingCircleView.preloadRes()
        windowLayoutParams.packageName = context.opPackageName
        axChargingCircleView.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewDetachedFromWindow(view: View) {}

            override fun onViewAttachedToWindow(view: View) {
                axChargingCircleView.startAnimation(Runnable {
                    windowManager.removeView(axChargingCircleView)
                })
                axChargingCircleView.removeOnAttachStateChangeListener(this)
            }
        })
        windowManager.addView(axChargingCircleView, windowLayoutParams)
        uiEventLogger.log(WiredChargingRippleEvent.CHARGING_RIPPLE_PLAYED)
    }

    private fun startAospRipple() {
        if (rippleView.rippleInProgress() || rippleView.parent != null) {
            // Skip if ripple is still playing, or not playing but already added the parent
            // (which might happen just before the animation starts or right after
            // the animation ends.)
            return
        }
        windowLayoutParams.packageName = context.opPackageName
        rippleView.addOnAttachStateChangeListener(
            object : View.OnAttachStateChangeListener {
                override fun onViewDetachedFromWindow(view: View) {}

                override fun onViewAttachedToWindow(view: View) {
                    layoutRipple()
                    rippleView.startRipple(Runnable { removeWindowViewIfAttached(rippleView) })
                    rippleView.removeOnAttachStateChangeListener(this)
                }
            }
        )
        windowManager.addView(rippleView, windowLayoutParams)
        uiEventLogger.log(WiredChargingRippleEvent.CHARGING_RIPPLE_PLAYED)
    }

    private fun pollHandshakeAndStart() {
        if (!batteryController.isPluggedIn) {
            handshakePending = false
            return
        }
        val session = resolveChargingSession()
        handshakeAttempts++
        if (session.handshakeReady || handshakeAttempts >= HANDSHAKE_ATTEMPTS) {
            handshakePending = false
            playChargingAnim(session.withVoocFallback())
        } else {
            mainHandler.postDelayed(handshakePoll, HANDSHAKE_POLL_MS)
        }
    }

    private fun playChargingAnim(session: ChargingSession) {
        try {
            playVfx(session)
        } catch (t: Throwable) {
            Log.w(TAG, "stock VFX failed, falling back to the ripple", t)
            finishVfxWindow()
            startAospRipple()
        }
    }

    private fun playVfx(session: ChargingSession) {
        if (vfxContainer?.parent != null || !batteryController.isPluggedIn) {
            return
        }
        val chargineType =
            when {
                session.showSuperVooc -> VFXChargingTextureView.ChargineType.HIGH
                session.fastChgType == CHARGER_SUBTYPE_FASTCHG_VOOC ->
                    VFXChargingTextureView.ChargineType.MEDIUM
                else -> VFXChargingTextureView.ChargineType.LOW
            }
        val ringView = VFXChargingTextureView(context)
        if (!ringView.isEngineReady) {
            throw IllegalStateException("VFX engine failed to init")
        }
        ringView.setChargineType(chargineType)
        val branding =
            ChargingVfxBrandingView(context).apply {
                visibility = View.INVISIBLE
                setBatteryLevel(batteryLevel)
            }
        vfxBranding = branding
        vfxHudShown = false
        vfxCanAcceptLongClick = false
        vfxLongPressTriggered = false
        vfxSessionBoosted = false
        vfxTouchInsideCircle = false
        applyVfxHud(session)
        ringView.setChargingEngineControl(
            object : IChargingEngineControl {
                override fun onFlashAnimationFinishedCallBack() {
                    mainHandler.post { revealVfxHud() }
                }
            }
        )
        ringView.setResourceCallback { success ->
            if (success) return@setResourceCallback
            Log.w(TAG, "stock VFX textures failed, falling back to the ripple")
            finishVfxWindow()
            startAospRipple()
        }

        val container =
            FrameLayout(context).apply {
                clipChildren = false
                clipToPadding = false
            }
        val dim =
            View(context).apply {
                setBackgroundColor(Color.BLACK)
                alpha = 0f
            }
        for (child in listOf(dim, ringView, branding)) {
            container.addView(
                child,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT,
                ),
            )
        }
        container.addOnAttachStateChangeListener(
            object : View.OnAttachStateChangeListener {
                override fun onViewDetachedFromWindow(view: View) {}

                override fun onViewAttachedToWindow(view: View) {
                    ObjectAnimator.ofFloat(dim, View.ALPHA, 0f, 0.6f).apply {
                        duration = 1000
                        start()
                    }
                    container.removeOnAttachStateChangeListener(this)
                }
            }
        )
        vfxContainer = container
        vfxFading = false
        windowLayoutParams.packageName = context.opPackageName
        applyVfxWindowFlags()
        container.isClickable = true
        container.setOnTouchListener { view, event -> handleVfxTouch(view, event) }
        mainHandler.removeCallbacks(vfxWindowEnd)
        mainHandler.removeCallbacks(vfxShowHud)
        mainHandler.removeCallbacks(vfxLongPress)
        mainHandler.removeCallbacks(vfxSpeedUpTimeout)
        vfxStartedElapsed = systemClock.elapsedRealtime()
        mainHandler.postDelayed(vfxShowHud, VFX_FLASH_MS)
        // Native setAnimationDuration is 20 s; past it the engine wraps, so be gone by then.
        mainHandler.postDelayed(vfxWindowEnd, vfxMsUntilNativeCap())
        windowManager.addView(container, windowLayoutParams)
        startVfxOemUpdates()
        uiEventLogger.log(WiredChargingRippleEvent.CHARGING_RIPPLE_PLAYED)
    }

    /** BatteryService extras can land after the window opens; keep the HUD in sync. */
    private fun startVfxOemUpdates() {
        stopVfxOemUpdates()
        val receiver =
            object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) {
                    applyVfxHud(resolveChargingSession(intent))
                }
            }
        vfxBatteryReceiver = receiver
        context.registerReceiver(
            receiver,
            IntentFilter(Intent.ACTION_BATTERY_CHANGED),
            Context.RECEIVER_NOT_EXPORTED,
        )
        mainHandler.post(vfxDecimalPoll)
    }

    private fun stopVfxOemUpdates() {
        mainHandler.removeCallbacks(vfxDecimalPoll)
        vfxBranding?.setBatteryLevelCenti(0)
        val receiver = vfxBatteryReceiver ?: return
        vfxBatteryReceiver = null
        try {
            context.unregisterReceiver(receiver)
        } catch (_: IllegalArgumentException) {}
    }

    /** Stock ring: the first read after a SuperVOOC plug-in starts the kernel's decimal SoC. */
    private fun pollUiSocDecimal() {
        vfxBranding?.setBatteryLevelCenti(readUiSocDecimal())
        mainHandler.postDelayed(vfxDecimalPoll, UI_SOC_DECIMAL_POLL_MS)
    }

    /** "start, current" in hundredths of a percent; 0 when idle or unreadable. */
    private fun readUiSocDecimal(): Int {
        return try {
            File(UI_SOC_DECIMAL_PATH)
                .takeIf { it.canRead() }
                ?.readText()
                ?.substringAfter(',')
                ?.trim()
                ?.toIntOrNull() ?: 0
        } catch (_: Exception) {
            0
        }
    }

    private fun applyVfxHud(session: ChargingSession) {
        val branding = vfxBranding ?: return
        branding.setBatteryLevel(batteryLevel)
        if (vfxHudShown) {
            branding.setBoostTip(canAcceptChargeBoost(session))
            vfxCanAcceptLongClick = canAcceptChargeBoost(session)
        }
        if (batteryLevel >= 100) {
            vfxCanAcceptLongClick = false
            branding.setBoostTip(false)
            branding.setShowLogo(false)
            branding.setRatedWatts(0)
            branding.setSourceLabel(null)
            branding.setTimeToFull(null)
            branding.setStatusLabel(context.getString(R.string.keyguard_charged))
            return
        }
        if (session.showSuperVooc) {
            when (chargeHudMode()) {
                HUD_MODE_NIGHT -> {
                    branding.setShowLogo(false)
                    branding.setRatedWatts(0)
                    branding.setSourceLabel(null)
                    branding.setTimeToFull(null)
                    branding.setStatusLabel(context.getString(R.string.charging_vfx_night_mode))
                }
                HUD_MODE_STANDARD -> {
                    branding.setStatusLabel(null)
                    branding.setSourceLabel(null)
                    branding.setShowLogo(true)
                    branding.setRatedWatts(STANDARD_CAP_WATTS)
                    branding.setTimeToFull(timeToFullLabel())
                }
                else -> {
                    branding.setStatusLabel(null)
                    branding.setSourceLabel(null)
                    branding.setShowLogo(true)
                    branding.setRatedWatts(session.ratedWatts)
                    branding.setTimeToFull(timeToFullLabel())
                }
            }
            return
        }
        branding.setShowLogo(false)
        branding.setRatedWatts(0)
        branding.setStatusLabel(null)
        branding.setTimeToFull(timeToFullLabel())
        branding.setSourceLabel(
            when {
                session.showVooc -> context.getString(R.string.charging_vfx_source_vooc)
                session.showPps -> context.getString(R.string.charging_vfx_source_pps)
                else -> plugSourceLabel(session.plugged)
            }
        )
    }

    /** Health HAL time_to_full_now; null when unknown. */
    private fun timeToFullLabel(): String? {
        val ms =
            context.getSystemService(BatteryManager::class.java)?.computeChargeTimeRemaining()
                ?: -1L
        if (ms <= 0L) return null
        return context.getString(
            R.string.charging_vfx_time_to_full,
            Formatter.formatShortElapsedTimeRoundingUpToMinutes(context, ms),
        )
    }

    private fun plugSourceLabel(plugged: Int): String {
        return when (plugged) {
            BatteryManager.BATTERY_PLUGGED_WIRELESS ->
                context.getString(R.string.charging_vfx_source_wireless)
            BatteryManager.BATTERY_PLUGGED_DOCK ->
                context.getString(R.string.charging_vfx_source_dock)
            else -> context.getString(R.string.charging_vfx_source_usb)
        }
    }

    /** DeviceSettings publishes this; SystemUI doesn't read oplus_chg. */
    private fun chargeHudMode(): Int {
        if (vfxSessionBoosted) return HUD_MODE_UNLIMITED
        return Settings.System.getInt(
            context.contentResolver,
            SETTINGS_CHARGE_HUD_MODE,
            HUD_MODE_UNLIMITED,
        )
    }

    private fun revealVfxHud() {
        if (vfxHudShown) return
        vfxHudShown = true
        mainHandler.removeCallbacks(vfxShowHud)
        applyVfxHud(resolveChargingSession())
        vfxBranding?.fadeIn()
    }

    private fun canAcceptChargeBoost(session: ChargingSession): Boolean {
        if (vfxSessionBoosted || batteryLevel >= 100) return false
        if (!session.showSuperVooc && !session.showVooc) return false
        val cr = context.contentResolver
        return Settings.System.getInt(cr, SETTINGS_CHARGE_BOOST_AVAILABLE, 0) == 1
    }

    /** Long press only after the HUD, only inside the ring, once. A tap dismisses. */
    private fun handleVfxTouch(view: View, event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                vfxTouchInsideCircle = isInsideVfxCircle(view, event)
                mainHandler.removeCallbacks(vfxLongPress)
                if (vfxCanAcceptLongClick && vfxTouchInsideCircle) {
                    vfxDownX = event.x
                    vfxDownY = event.y
                    mainHandler.postDelayed(
                        vfxLongPress,
                        ViewConfiguration.getLongPressTimeout().toLong(),
                    )
                }
            }
            MotionEvent.ACTION_MOVE -> {
                vfxTouchInsideCircle = isInsideVfxCircle(view, event)
                if (mainHandler.hasCallbacks(vfxLongPress)) {
                    val slop = ViewConfiguration.get(context).scaledTouchSlop.toFloat()
                    val dx = event.x - vfxDownX
                    val dy = event.y - vfxDownY
                    if (!vfxTouchInsideCircle || dx * dx + dy * dy > slop * slop) {
                        mainHandler.removeCallbacks(vfxLongPress)
                    }
                }
            }
            MotionEvent.ACTION_UP,
            MotionEvent.ACTION_CANCEL -> {
                mainHandler.removeCallbacks(vfxLongPress)
                if (vfxLongPressTriggered) {
                    // This UP is the long-press release, not a dismiss.
                    vfxLongPressTriggered = false
                } else if (event.pointerCount <= 1) {
                    fadeOutVfxWindow()
                }
            }
        }
        return true
    }

    private fun isInsideVfxCircle(view: View, event: MotionEvent): Boolean {
        val radius =
            TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP,
                VFX_CIRCLE_DP / 2f,
                context.resources.displayMetrics,
            )
        val dx = event.x - view.width / 2f
        val dy = event.y - view.height / 2f
        return dx * dx + dy * dy <= radius * radius
    }

    private fun onVfxLongPress() {
        if (vfxFading || vfxContainer == null) return
        if (!vfxCanAcceptLongClick || !vfxTouchInsideCircle) return
        vfxLongPressTriggered = true
        vfxCanAcceptLongClick = false
        vfxSessionBoosted = true
        vfxBranding?.setBoostTip(false)
        vfxBranding?.setSpeedUpGradient(true)
        applyVfxHud(resolveChargingSession())
        requestSessionChargeBoost()
        // A lap on the original show clock, not a reset: min(now + 14 s, start + 20 s).
        mainHandler.removeCallbacks(vfxSpeedUpTimeout)
        val lapMs = min(VFX_SPEED_UP_HOLD_MS, vfxMsUntilNativeCap())
        if (lapMs <= 0L) {
            fadeOutVfxWindow()
        } else {
            mainHandler.postDelayed(vfxSpeedUpTimeout, lapMs)
        }
    }

    private fun vfxMsUntilNativeCap(): Long {
        val elapsed = systemClock.elapsedRealtime() - vfxStartedElapsed
        return (VFX_NATIVE_MS - VFX_FADE_OUT_MS - elapsed).coerceAtLeast(0L)
    }

    /** DeviceSettings owns cool_down; this asks it to lift the cap for this plug session. */
    private fun requestSessionChargeBoost() {
        val intent =
            Intent(ACTION_BOOST_CHARGING).apply {
                setPackage(DEVICE_SETTINGS_PACKAGE)
                addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
            }
        context.sendBroadcastAsUser(intent, UserHandle.CURRENT)
    }

    private fun fadeOutVfxWindow() {
        val container = vfxContainer
        if (container == null) {
            finishVfxWindow()
            return
        }
        if (vfxFading) return
        vfxFading = true
        mainHandler.removeCallbacks(vfxWindowEnd)
        mainHandler.removeCallbacks(vfxSpeedUpTimeout)
        mainHandler.removeCallbacks(vfxLongPress)
        val anim =
            ObjectAnimator.ofFloat(container, View.ALPHA, container.alpha, 0f).apply {
                duration = VFX_FADE_OUT_MS
                interpolator = LinearInterpolator()
                addListener(
                    object : AnimatorListenerAdapter() {
                        override fun onAnimationEnd(animation: Animator) {
                            finishVfxWindow()
                        }
                    }
                )
            }
        vfxFadeAnimator = anim
        anim.start()
    }

    private fun finishVfxWindow() {
        mainHandler.removeCallbacks(vfxWindowEnd)
        mainHandler.removeCallbacks(vfxShowHud)
        mainHandler.removeCallbacks(vfxLongPress)
        mainHandler.removeCallbacks(vfxSpeedUpTimeout)
        stopVfxOemUpdates()
        vfxFadeAnimator?.removeAllListeners()
        vfxFadeAnimator?.cancel()
        vfxFadeAnimator = null
        vfxFading = false
        vfxBranding = null
        vfxHudShown = false
        vfxCanAcceptLongClick = false
        vfxLongPressTriggered = false
        vfxSessionBoosted = false
        vfxTouchInsideCircle = false
        vfxStartedElapsed = 0L
        val container = vfxContainer
        vfxContainer = null
        if (container != null) {
            container.setOnTouchListener(null)
            removeWindowViewIfAttached(container)
        }
        windowLayoutParams.flags = PASS_THROUGH_WINDOW_FLAGS
    }

    /** Tap or unplug. A late handshake read must not open a window after the charger is gone. */
    private fun dismissChargingAnim(reason: String) {
        Log.i(TAG, "dismiss $reason")
        handshakePending = false
        mainHandler.removeCallbacks(handshakePoll)
        if (vfxContainer != null) fadeOutVfxWindow()
    }

    private fun applyVfxWindowFlags() {
        windowLayoutParams.flags =
            (PASS_THROUGH_WINDOW_FLAGS and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()) or
                WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
    }

    /**
     * SuperVOOC and wattage from the BatteryService extras:
     * [BatteryManager.EXTRA_OEM_CHARGER_WATTS], [BatteryManager.EXTRA_OEM_CHARGER] and
     * [BatteryManager.EXTRA_OEM_FAST_CHG_TYPE].
     */
    private fun resolveChargingSession(intent: Intent? = null): ChargingSession {
        val sticky =
            intent ?: context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val type = sticky?.getIntExtra(BatteryManager.EXTRA_OEM_FAST_CHG_TYPE, 0) ?: 0
        val extraWatts = sticky?.getIntExtra(BatteryManager.EXTRA_OEM_CHARGER_WATTS, 0) ?: 0
        val oemCharger =
            sticky != null &&
                (sticky.getBooleanExtra(BatteryManager.EXTRA_OEM_CHARGER, false) ||
                    BatteryStatus(sticky).getChargingSpeed(context) == BatteryStatus.CHARGING_OEM)
        val plugged = sticky?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0
        val extraLevel = sticky?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        if (extraLevel in 0..100) batteryLevel = extraLevel
        val hasVooc = hasVoocCharger()
        val protocolSvooc = type == CHARGER_SUBTYPE_FASTCHG_SVOOC || type >= OPLUS_SVOOC_ID_MIN
        val protocolVooc = type == CHARGER_SUBTYPE_FASTCHG_VOOC
        val watts =
            when {
                extraWatts > 0 -> extraWatts
                protocolSvooc -> ratedWattsFromType(type)
                else -> 0
            }
        // Protocol VOOC (fast_chg_type=1) is not SuperVOOC even if voocchg_ing sets
        // EXTRA_OEM_CHARGER, or a VOOC brick inherits the 100W mark.
        val showSuperVooc =
            protocolSvooc || (!protocolVooc && hasVooc && (watts > 0 || oemCharger))
        val showVooc = !showSuperVooc && protocolVooc
        // PPS is a separate oplus path; cool_down never caps it, so no boost tip.
        val showPps = !showSuperVooc && !showVooc && readSysfsInt(PPS_CHG_ING) == 1
        return ChargingSession(
            fastChgType = type,
            ratedWatts = if (showSuperVooc) (if (watts > 0) watts else 100) else 0,
            showSuperVooc = showSuperVooc,
            showVooc = showVooc,
            showPps = showPps,
            plugged = plugged,
            oemCharger = oemCharger,
            handshakeReady = showSuperVooc || showVooc || showPps || oemCharger || watts > 0,
        )
    }

    /** Handshake timed out: only assume SuperVOOC when something suggests an OEM brick. */
    private fun ChargingSession.withVoocFallback(): ChargingSession {
        if (showSuperVooc || showVooc || !hasVoocCharger()) return this
        if (!oemCharger && ratedWatts <= 0 && fastChgType <= 0) return this
        return copy(showSuperVooc = true, ratedWatts = if (ratedWatts > 0) ratedWatts else 100)
    }

    private fun ratedWattsFromType(type: Int): Int {
        // 100W SuperVOOC adapter ids (oplus table) + dodge 0x65 / aston 105.
        if (type == 101 || type == 105 || (type in 0x3b..0x3e) || type == 0x69 || type == 0x6a) {
            return 100
        }
        return 0
    }

    private fun hasVoocCharger(): Boolean =
        context.resources.getBoolean(com.android.internal.R.bool.config_hasVoocCharger)

    private fun readSysfsInt(path: String): Int? {
        return try {
            File(path).takeIf { it.canRead() }?.readText()?.trim()?.toIntOrNull()
        } catch (_: Exception) {
            null
        }
    }

    private fun removeWindowViewIfAttached(view: View) {
        if (view.isAttachedToWindow) {
            windowManager.removeView(view)
        }
    }

    private fun layoutRipple() {
        val bounds = windowManager.currentWindowMetrics.bounds
        val width = bounds.width()
        val height = bounds.height()
        val maxDiameter = Integer.max(width, height) * 2f
        rippleView.setMaxSize(maxDiameter, maxDiameter)
        when (context.display?.rotation) {
            Surface.ROTATION_0 -> {
                rippleView.setCenter(width * normalizedPortPosX, height * normalizedPortPosY)
            }

            Surface.ROTATION_90 -> {
                rippleView.setCenter(width * normalizedPortPosY, height * (1 - normalizedPortPosX))
            }

            Surface.ROTATION_180 -> {
                rippleView.setCenter(
                    width * (1 - normalizedPortPosX),
                    height * (1 - normalizedPortPosY),
                )
            }

            Surface.ROTATION_270 -> {
                rippleView.setCenter(width * (1 - normalizedPortPosY), height * normalizedPortPosX)
            }
        }
    }

    private fun updateRippleColor() {
        rippleView.setColor(Utils.getColorAttr(context, android.R.attr.colorAccent).defaultColor)
    }

    private data class ChargingSession(
        val fastChgType: Int,
        val ratedWatts: Int,
        val showSuperVooc: Boolean,
        val showVooc: Boolean,
        val showPps: Boolean,
        val plugged: Int,
        val oemCharger: Boolean,
        val handshakeReady: Boolean,
    )

    inner class ChargingRippleCommand : Command {
        override fun execute(pw: PrintWriter, args: List<String>) {
            startRipple()
        }

        override fun help(pw: PrintWriter) {
            pw.println("Usage: adb shell cmd statusbar charging-ripple")
        }
    }

    enum class WiredChargingRippleEvent(private val _id: Int) : UiEventLogger.UiEventEnum {
        @UiEvent(doc = "Wired charging ripple effect played") CHARGING_RIPPLE_PLAYED(829);

        override fun getId() = _id
    }

    companion object {
        private const val TAG = "WiredChargingRipple"
        private const val CHARGING_ANIM_MODE_CIRCLE = 1
        private const val HANDSHAKE_POLL_MS = 150L
        // 2.2 s, as on Alpha.
        private const val HANDSHAKE_ATTEMPTS = 15
        // Kernel CHARGER_SUBTYPE_FASTCHG_VOOC / SVOOC / OPLUS_SVOOC_ID_MIN.
        private const val CHARGER_SUBTYPE_FASTCHG_VOOC = 1
        private const val CHARGER_SUBTYPE_FASTCHG_SVOOC = 2
        private const val OPLUS_SVOOC_ID_MIN = 10
        // Native setAnimationDuration.x. The window must be gone by this or the .so wraps.
        private const val VFX_NATIVE_MS = 20_000L
        private const val VFX_FADE_OUT_MS = 450L
        private const val VFX_FLASH_MS = 1_280L
        // Stock CircleView oplus_charge_circle_view_w.
        private const val VFX_CIRCLE_DP = 275f
        private const val VFX_SPEED_UP_HOLD_MS = 14_000L
        private const val PPS_CHG_ING = "/sys/class/oplus_chg/battery/ppschg_ing"
        // The ripple passes touches through; the ring clears FLAG_NOT_TOUCHABLE so a tap
        // can dismiss it. TextureView needs the window hardware accelerated.
        private const val PASS_THROUGH_WINDOW_FLAGS =
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
    }
}
