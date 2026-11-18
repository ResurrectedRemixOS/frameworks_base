/*
 * SPDX-FileCopyrightText: The Resurrected Remix Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.systemui.rr.nowbar

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.os.SystemClock
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewOutlineProvider
import android.view.WindowManager
import android.view.animation.AccelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.view.animation.LinearInterpolator
import android.widget.Chronometer
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.TextView
import androidx.core.graphics.ColorUtils
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.android.internal.graphics.drawable.BackgroundBlurDrawable
import com.android.internal.util.catalyxx.GlassSettings
import com.android.systemui.lifecycle.repeatWhenAttached
import com.android.systemui.plugins.ActivityStarter
import com.android.systemui.plugins.statusbar.StatusBarStateController
import com.android.systemui.res.R
import com.android.systemui.statusbar.StatusBarState
import java.util.function.Consumer
import kotlinx.coroutines.flow.Flow
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * The Now Bar on the lock screen, between the shortcuts like One UI's: a capsule with the current
 * activity, styled from Remixer. Swipe between activities, swipe a paused song away, tap to open
 * the card, long-press to hide one. On the always-on display it is a dim outline.
 */
class NowBarView(
    context: Context,
    private val repository: NowBarRepository,
    private val statusBarStateController: StatusBarStateController,
    private val activityStarter: ActivityStarter,
    /** The always-on display's minute tick, a wakeup alarm, unlike ACTION_TIME_TICK. */
    private val dozeTimeTick: Flow<Long>,
    private val onLayoutChanged: () -> Unit,
) : FrameLayout(context), NowBarRepository.Listener, StatusBarStateController.StateListener {

    private val density = resources.displayMetrics.density
    private val accent = context.getColor(android.R.color.system_accent1_400)
    private val accentFill = context.getColor(android.R.color.system_accent1_800)
    private val semibold = Typeface.create(Typeface.DEFAULT, 600, false)
    private val medium = Typeface.create(Typeface.DEFAULT, 500, false)
    private var look = repository.settings.look
    private var radius = 0f
    private var contentStart = 0
    private var artShaped = false
    private val peekBg = GradientDrawable()
    private val peek = View(context)
    private val capsuleBg = GradientDrawable()
    private val capsule = FrameLayout(context)
    private val row = FrameLayout(context)
    private val icon = ImageView(context)
    private val dots = Dots(context)
    private var blur: BackgroundBlurDrawable? = null
    private var blurEnabled = false
    private var rootAlpha = 1f
    private val blurListener = Consumer<Boolean> { enabled ->
        blurEnabled = enabled
        bind()
    }

    // Music
    private val musicColumn = LinearLayout(context)
    private val songTitle = TextView(context)
    private val songArtist = TextView(context)
    private val controls = LinearLayout(context)
    private val controlViews = List(3) { ImageView(context) }
    private val inlinePlay = ImageView(context)
    private val wave = NowBarWaveView(context)

    // Everything else
    private val texts = LinearLayout(context)
    private val label = TextView(context)
    private val title = TextView(context)
    private val chronometer = Chronometer(context)
    private val subtitle = TextView(context)
    private val actionRow = LinearLayout(context)
    private val actionViews = List(2) { ImageView(context) }
    private val progress = ProgressLine(context)

    // Games
    private val scoreRow = LinearLayout(context)
    private val logo1 = ImageView(context)
    private val logo2 = ImageView(context)
    private val team1 = TextView(context)
    private val team2 = TextView(context)
    private val scoreText = TextView(context)
    private val statusText = TextView(context)

    // A goal's moment: the scorer's color floods the bar under a banner.
    private val flood = FrameLayout(context)
    private val floodBg = GradientDrawable()
    private val floodClip = Rect()
    private val net = GoalNet(context)
    private val ball = View(context)
    private val banner = LinearLayout(context)
    private val bannerLogo = ImageView(context)
    private val bannerLabel = TextView(context)
    private val bannerSub = TextView(context)
    private val decelerate = DecelerateInterpolator(1.6f)
    private val accelerate = AccelerateInterpolator(1.4f)
    private var celebrated: NowBarGoal? = null
    private var goalKey: String? = null
    private var goalAnim: ValueAnimator? = null

    private var items: List<NowBarItem> = emptyList()
    private var currentKey: String? = null
    private var dozing = false
    private var card: NowBarCard? = null
    private var popup: PopupMenu? = null
    private var burnIn = 0

    private var downX = 0f
    private var downY = 0f
    private var dragging = false
    private var longPressed = false
    private var velocity: VelocityTracker? = null
    private val slop = ViewConfiguration.get(context).scaledTouchSlop
    private val minFling = ViewConfiguration.get(context).scaledMinimumFlingVelocity * 4
    private val longPress = Runnable {
        longPressed = true
        performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
        showMenu()
    }

    private fun onDozeTimeTick() {
        if (!dozing) return
        // A few pixels each minute against burn-in.
        burnIn = (burnIn + 1) % 8
        translationX = dp(BURN_IN_STEPS_X[burnIn])
        translationY = dp(BURN_IN_STEPS_Y[burnIn])
        bind()
    }

    private val compact get() = look.height < COMPACT_BELOW

    init {
        clipChildren = false
        clipToPadding = false
        visibility = GONE
        peek.background = peekBg
        peek.visibility = GONE
        addView(peek, LayoutParams(LayoutParams.MATCH_PARENT, 0, Gravity.TOP).apply {
            marginStart = dp(14f).toInt()
            marginEnd = dp(14f).toInt()
        })
        capsule.background = capsuleBg
        capsule.outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) =
                outline.setRoundRect(0, 0, view.width, view.height, radius)
        }
        capsule.clipToOutline = true
        addView(capsule, LayoutParams(LayoutParams.MATCH_PARENT, 0, Gravity.BOTTOM))
        capsule.addView(row, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        capsule.addView(dots, LayoutParams(LayoutParams.MATCH_PARENT, dp(5f).toInt(),
            Gravity.BOTTOM).apply { bottomMargin = dp(4f).toInt() })

        icon.outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                val w = view.width
                val h = view.height
                when (if (artShaped) look.art else NowBarLook.ART_CIRCLE) {
                    NowBarLook.ART_ROUNDED -> outline.setRoundRect(0, 0, w, h, w * 0.22f)
                    NowBarLook.ART_SQUARE -> outline.setRoundRect(0, 0, w, h, w * 0.08f)
                    else -> outline.setOval(0, 0, w, h)
                }
            }
        }
        icon.clipToOutline = true
        row.addView(icon, LayoutParams(0, 0, Gravity.START or Gravity.CENTER_VERTICAL))

        buildMusic()
        buildTexts()
        buildScore()
        buildGoal()
        layoutForLook()

        // ACTION_TIME_TICK is a non-wakeup alarm, so it stalls while the always-on display
        // sleeps; follow the tick that redraws the AOD clock instead.
        repeatWhenAttached {
            repeatOnLifecycle(Lifecycle.State.CREATED) {
                dozeTimeTick.collect { onDozeTimeTick() }
            }
        }
    }

    private fun buildMusic() {
        musicColumn.orientation = LinearLayout.VERTICAL
        songTitle.setTextColor(Color.WHITE)
        songTitle.typeface = medium
        songTitle.isSingleLine = true
        songTitle.ellipsize = TextUtils.TruncateAt.MARQUEE
        songTitle.marqueeRepeatLimit = 2
        songTitle.isHorizontalFadingEdgeEnabled = true
        songTitle.setFadingEdgeLength(dp(10f).toInt())
        songTitle.isSelected = true
        musicColumn.addView(songTitle, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))

        songArtist.setTextColor(0xB8FFFFFF.toInt())
        songArtist.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        songArtist.isSingleLine = true
        songArtist.ellipsize = TextUtils.TruncateAt.END
        musicColumn.addView(songArtist, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))

        controls.orientation = LinearLayout.HORIZONTAL
        controls.gravity = Gravity.CENTER_VERTICAL
        val ripple = TypedValue().also {
            context.theme.resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, it,
                true)
        }.resourceId
        for (view in controlViews) {
            styleControl(view, ripple)
            controls.addView(LinearLayout(context).apply {
                gravity = Gravity.CENTER
                addView(view, LinearLayout.LayoutParams(dp(34f).toInt(), dp(34f).toInt()))
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f))
        }
        musicColumn.addView(controls, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, dp(34f).toInt()))
        row.addView(musicColumn, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT,
            Gravity.CENTER_VERTICAL))

        styleControl(inlinePlay, ripple)
        row.addView(inlinePlay, LayoutParams(dp(34f).toInt(), dp(34f).toInt(),
            Gravity.END or Gravity.CENTER_VERTICAL).apply { marginEnd = dp(8f).toInt() })

        row.addView(wave, LayoutParams(LayoutParams.MATCH_PARENT, dp(11f).toInt(),
            Gravity.BOTTOM).apply {
                marginStart = dp(44f).toInt()
                marginEnd = dp(26f).toInt()
            })
    }

    private fun styleControl(view: ImageView, ripple: Int) {
        val pad = dp(8f).toInt()
        view.setPadding(pad, pad, pad, pad)
        view.imageTintList = ColorStateList.valueOf(Color.WHITE)
        if (ripple != 0) view.setBackgroundResource(ripple)
    }

    private fun buildTexts() {
        texts.orientation = LinearLayout.VERTICAL
        label.setTextColor(0xB8FFFFFF.toInt())
        label.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        label.typeface = medium
        label.isSingleLine = true
        label.ellipsize = TextUtils.TruncateAt.END
        title.setTextColor(Color.WHITE)
        title.typeface = semibold
        title.isSingleLine = true
        title.ellipsize = TextUtils.TruncateAt.END
        chronometer.setTextColor(Color.WHITE)
        chronometer.typeface = semibold
        chronometer.fontFeatureSettings = "tnum"
        subtitle.setTextColor(0xB8FFFFFF.toInt())
        subtitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        subtitle.isSingleLine = true
        subtitle.ellipsize = TextUtils.TruncateAt.END
        texts.addView(label)
        texts.addView(title)
        texts.addView(chronometer)
        texts.addView(subtitle)
        row.addView(texts, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT,
            Gravity.CENTER_VERTICAL))

        actionRow.orientation = LinearLayout.HORIZONTAL
        for (view in actionViews) {
            view.background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(0x1FFFFFFF)
            }
            val pad = dp(10f).toInt()
            view.setPadding(pad, pad, pad, pad)
            view.imageTintList = ColorStateList.valueOf(Color.WHITE)
            actionRow.addView(view, LinearLayout.LayoutParams(0, 0)
                .apply { marginStart = dp(6f).toInt() })
        }
        row.addView(actionRow, LayoutParams(LayoutParams.WRAP_CONTENT, 0,
            Gravity.END or Gravity.CENTER_VERTICAL).apply { marginEnd = dp(INSET).toInt() })

        row.addView(progress, LayoutParams(LayoutParams.MATCH_PARENT, dp(3f).toInt(),
            Gravity.BOTTOM).apply {
                marginEnd = dp(22f).toInt()
                bottomMargin = dp(7f).toInt()
            })
    }

    private fun buildScore() {
        scoreRow.orientation = LinearLayout.HORIZONTAL
        scoreRow.gravity = Gravity.CENTER_VERTICAL
        val side = dp(14f).toInt()
        scoreRow.setPaddingRelative(side, 0, side, 0)
        for (team in listOf(team1, team2)) {
            team.setTextColor(Color.WHITE)
            team.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            team.typeface = semibold
            team.isSingleLine = true
            team.ellipsize = TextUtils.TruncateAt.END
        }
        team1.gravity = Gravity.START
        team2.gravity = Gravity.END
        scoreText.setTextColor(Color.WHITE)
        scoreText.typeface = Typeface.create(Typeface.DEFAULT, 700, false)
        scoreText.fontFeatureSettings = "tnum"
        scoreText.gravity = Gravity.CENTER
        statusText.setTextColor(0xB8FFFFFF.toInt())
        statusText.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
        statusText.isSingleLine = true
        statusText.ellipsize = TextUtils.TruncateAt.END
        statusText.gravity = Gravity.CENTER
        val middle = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            addView(scoreText)
            addView(statusText)
        }
        val logo = dp(28f).toInt()
        scoreRow.addView(logo1, LinearLayout.LayoutParams(logo, logo))
        scoreRow.addView(team1, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT,
            1f).apply { marginStart = dp(8f).toInt() })
        scoreRow.addView(middle, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                marginStart = dp(6f).toInt()
                marginEnd = dp(6f).toInt()
            })
        scoreRow.addView(team2, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT,
            1f).apply { marginEnd = dp(8f).toInt() })
        scoreRow.addView(logo2, LinearLayout.LayoutParams(logo, logo))
        row.addView(scoreRow, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
    }

    private fun buildGoal() {
        flood.background = floodBg
        floodBg.orientation = GradientDrawable.Orientation.LEFT_RIGHT
        flood.visibility = GONE
        net.pivotX = 0f
        flood.addView(net, LayoutParams(dp(74f).toInt(), LayoutParams.MATCH_PARENT, Gravity.END))
        ball.background = LayerDrawable(arrayOf(
            GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.WHITE)
            },
            GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(0xFF1B1B1B.toInt())
            },
        )).apply {
            val inset = dp(4f).toInt()
            setLayerInset(1, inset, inset, inset, inset)
        }
        val ballSize = dp(12f).toInt()
        flood.addView(ball, LayoutParams(ballSize, ballSize, Gravity.START or Gravity.CENTER_VERTICAL))
        bannerLabel.setTextColor(Color.WHITE)
        bannerLabel.typeface = Typeface.create(Typeface.DEFAULT, 800, true)
        bannerLabel.letterSpacing = 0.06f
        bannerLabel.isAllCaps = true
        bannerLabel.includeFontPadding = false
        bannerLabel.isSingleLine = true
        bannerSub.setTextColor(0xDBFFFFFF.toInt())
        bannerSub.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
        bannerSub.typeface = semibold
        bannerSub.isSingleLine = true
        bannerSub.ellipsize = TextUtils.TruncateAt.END
        val column = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(bannerLabel)
            addView(bannerSub)
        }
        banner.orientation = LinearLayout.HORIZONTAL
        banner.gravity = Gravity.CENTER_VERTICAL
        val logo = dp(32f).toInt()
        banner.addView(bannerLogo, LinearLayout.LayoutParams(logo, logo).apply {
            marginEnd = dp(10f).toInt()
        })
        banner.addView(column)
        flood.addView(banner, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.MATCH_PARENT,
            Gravity.START).apply { marginStart = dp(14f).toInt() })
        capsule.addView(flood, capsule.indexOfChild(dots),
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        dozing = statusBarStateController.isDozing
        rootAlpha = (parent as? View)?.alpha ?: 1f
        statusBarStateController.addCallback(this)
        context.getSystemService(WindowManager::class.java)
            ?.addCrossWindowBlurEnabledListener(context.mainExecutor, blurListener)
        repository.addListener(this)
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        repository.removeListener(this)
        statusBarStateController.removeCallback(this)
        context.getSystemService(WindowManager::class.java)
            ?.removeCrossWindowBlurEnabledListener(blurListener)
        setGlass(false)
        blur = null
        chronometer.stop()
        dismissCard()
        popup?.dismiss()
        goalAnim?.cancel()
    }

    override fun onNowBarChanged(items: List<NowBarItem>) {
        this.items = items
        if (items.none { it.key == currentKey }) currentKey = items.firstOrNull()?.key
        bind()
        card?.update(items.firstOrNull { it.key == card?.key })
        celebrate()
    }

    override fun onDozingChanged(isDozing: Boolean) {
        dozing = isDozing
        if (dozing) {
            dismissCard()
            popup?.dismiss()
        }
        if (!dozing) {
            translationX = 0f
            translationY = 0f
        }
        if (dozing) goalAnim?.cancel()
        bind()
        celebrate()
    }

    override fun onStateChanged(newState: Int) {
        if (newState != StatusBarState.SHADE) return
        dismissCard()
        popup?.dismiss()
    }

    fun setRootAlpha(alpha: Float) {
        rootAlpha = alpha
        updateBlurAlpha()
    }

    override fun setAlpha(alpha: Float) {
        super.setAlpha(alpha)
        updateBlurAlpha()
    }

    private fun updateBlurAlpha() {
        blur?.alpha = (rootAlpha * alpha * 255f).roundToInt()
    }

    private val index get() = items.indexOfFirst { it.key == currentKey }.coerceAtLeast(0)

    private fun updateLook() {
        val new = repository.settings.look
        if (new == look) return
        val moved = new.height != look.height || new.width != look.width ||
            new.raise != look.raise || new.more != look.more ||
            new.aboveFingerprint != look.aboveFingerprint
        look = new
        layoutForLook()
        if (moved) onLayoutChanged()
    }

    private fun layoutForLook() {
        val height = px(look.height)
        val art = px((look.height * ART_RATIO).roundToInt())
        val inset = dp(if (compact) INSET_COMPACT else INSET).toInt()
        contentStart = inset + art + dp(12f).toInt()
        (capsule.layoutParams as LayoutParams).let {
            it.height = height
            it.topMargin = if (look.more == NowBarLook.MORE_PEEK) dp(PEEK).toInt() else 0
            capsule.layoutParams = it
        }
        peek.layoutParams = peek.layoutParams.apply { this.height = height }
        radius = max(dp(8f), height / 2f * look.corners / 100f)
        capsuleBg.cornerRadius = radius
        peekBg.cornerRadius = radius
        blur?.setCornerRadius(radius)
        capsule.invalidateOutline()
        (icon.layoutParams as LayoutParams).let {
            it.width = art
            it.height = art
            it.marginStart = inset
            icon.layoutParams = it
        }
        icon.invalidateOutline()
        (texts.layoutParams as LayoutParams).let {
            it.marginStart = contentStart - dp(1f).toInt()
            texts.layoutParams = it
        }
        actionRow.layoutParams = actionRow.layoutParams.apply { this.height = art }
        for (view in actionViews) {
            view.layoutParams = view.layoutParams.apply {
                width = art
                this.height = art
            }
        }
        (progress.layoutParams as LayoutParams).let {
            it.marginStart = contentStart - dp(1f).toInt()
            progress.layoutParams = it
        }
        songTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, look.textSize.toFloat())
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, max(look.textSize, 15).toFloat())
        chronometer.setTextSize(TypedValue.COMPLEX_UNIT_SP, if (compact) 15f else 21f)
        scoreText.setTextSize(TypedValue.COMPLEX_UNIT_SP, if (compact) 15f else 20f)
    }

    private fun bind() {
        updateLook()
        val settings = repository.settings
        // The always-on display holds the pinned Live Update, whatever was swiped to.
        val item = (if (dozing) items.firstOrNull {
            it.type == NowBarType.LIVE && it.packageName == settings.pinned
        } else null) ?: items.getOrNull(index)
        if (goalKey != null && item?.key != goalKey) goalAnim?.cancel()
        if (item == null || (dozing && !settings.aod)) {
            visibility = GONE
            chronometer.stop()
            setGlass(false)
            return
        }
        visibility = VISIBLE
        val music = item.type == NowBarType.MUSIC && !dozing
        val game = item.score != null
        val colors = if (music) item.artColors else null
        paintCapsule(colors)
        capsule.alpha = if (dozing) 0.85f else 1f
        val more = items.size > 1 && !dozing
        peek.visibility = if (more && look.more == NowBarLook.MORE_PEEK) VISIBLE else GONE
        dots.visibility = if (more && look.more == NowBarLook.MORE_DOTS) VISIBLE else GONE
        dots.set(items.size, index)

        bindIcon(item, game)
        musicColumn.visibility = if (music) VISIBLE else GONE
        inlinePlay.visibility = GONE
        texts.visibility = if (!music && !game) VISIBLE else GONE
        actionRow.visibility = if (!music && !game) VISIBLE else GONE
        progress.visibility = GONE
        scoreRow.visibility = if (game) VISIBLE else GONE
        when {
            music -> bindMusic(item, colors)
            game -> bindScore(item.score!!)
            else -> bindTexts(item)
        }
        val playing = music && item.playing && settings.wave && !compact
        wave.visibility = if (playing) VISIBLE else GONE
        wave.running = playing
        capsule.contentDescription = listOfNotNull(item.title, item.text).joinToString(", ")
    }

    private fun paintCapsule(colors: IntArray?) {
        val glass =
            look.glass && !dozing && blurEnabled &&
                GlassSettings.isOn(context.contentResolver, GlassSettings.PLACE_LOCKSCREEN)
        setGlass(glass)
        val fill = when {
            dozing -> Color.BLACK
            look.color == NowBarLook.COLOR_ACCENT -> accentFill
            look.color == NowBarLook.COLOR_ART && colors != null -> colors[0]
            else -> DARK_FILL
        }
        val opacity = when {
            dozing -> 100f
            glass -> look.opacity * 0.7f
            // Glass picked but no blur to draw it with: stay readable.
            look.glass -> max(look.opacity, 85).toFloat()
            else -> look.opacity.toFloat()
        }
        capsuleBg.setColor(ColorUtils.setAlphaComponent(fill, (opacity * 2.55f).roundToInt()))
        when {
            dozing -> capsuleBg.setStroke(dp(1f).toInt(), 0x29FFFFFF)
            look.outline == NowBarLook.OUTLINE_NONE -> capsuleBg.setStroke(0, Color.TRANSPARENT)
            look.outline == NowBarLook.OUTLINE_ACCENT ->
                capsuleBg.setStroke(dp(1.5f).toInt(), accent)
            else -> capsuleBg.setStroke(dp(1f).toInt(), 0x14FFFFFF)
        }
        capsule.elevation = when {
            dozing -> 0f
            look.shadow == NowBarLook.SHADOW_SOFT -> dp(6f)
            look.shadow == NowBarLook.SHADOW_GLOW -> dp(12f)
            else -> 0f
        }
        val shadow = if (look.shadow == NowBarLook.SHADOW_GLOW) accent else Color.BLACK
        capsule.outlineAmbientShadowColor = shadow
        capsule.outlineSpotShadowColor = shadow
        peekBg.setColor(ColorUtils.setAlphaComponent(
            ColorUtils.blendARGB(fill, Color.WHITE, 0.08f), 0xC0))
    }

    /**
     * The same glass the lock screen shortcuts sit on: Liquid Glass's blur and, where the material
     * has one, its curved rim. The capsule keeps its own fill over it.
     */
    private fun setGlass(on: Boolean) {
        val drawable = if (on) blurDrawable() else null
        drawable?.setBlurRadius(
            GlassSettings.scaleBlurRadius(
                resources.getDimensionPixelSize(R.dimen.keyguard_shortcuts_blur_radius),
                GlassSettings.blurScale(context.contentResolver, GlassSettings.PLACE_LOCKSCREEN),
            )
        )
        drawable?.setGlass(
            GlassSettings.look(context, GlassSettings.PLACE_LOCKSCREEN, Color.TRANSPARENT)
        )
        blur?.setVisible(drawable != null, false)
        if ((drawable != null) != (capsule.background is LayerDrawable)) {
            capsule.background =
                if (drawable != null) LayerDrawable(arrayOf(drawable, capsuleBg)) else capsuleBg
        }
    }

    private fun blurDrawable(): BackgroundBlurDrawable? {
        blur?.let { return it }
        val made = viewRootImpl?.createBackgroundBlurDrawable() ?: return null
        made.setCornerRadius(radius)
        blur = made
        updateBlurAlpha()
        return made
    }

    private fun bindIcon(item: NowBarItem, game: Boolean) {
        if (game || (item.type == NowBarType.MUSIC && !dozing && look.art == NowBarLook.ART_OFF)) {
            icon.visibility = GONE
            return
        }
        icon.visibility = VISIBLE
        icon.alpha = if (dozing) 0.6f else 1f
        val art = item.art
        artShaped = art != null && item.type == NowBarType.MUSIC
        icon.invalidateOutline()
        if (art != null) {
            icon.setImageBitmap(art)
            icon.scaleType = ImageView.ScaleType.CENTER_CROP
            icon.setPadding(0, 0, 0, 0)
            icon.imageTintList = null
            icon.background = null
            return
        }
        icon.setImageDrawable(item.icon?.mutate())
        icon.scaleType = ImageView.ScaleType.FIT_CENTER
        val appIcon = item.type == NowBarType.MUSIC
        val pad = if (appIcon) 0 else dp(9f).toInt()
        icon.setPadding(pad, pad, pad, pad)
        icon.imageTintList = if (appIcon) null else ColorStateList.valueOf(Color.WHITE)
        val tint = if (item.color != 0 && item.color != Color.BLACK) item.color else accent
        icon.background = if (appIcon) null else GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor((tint and 0x00FFFFFF) or (if (dozing) 0x14000000 else 0x66000000))
        }
    }

    private fun bindMusic(item: NowBarItem, colors: IntArray?) {
        chronometer.stop()
        val twoLines = look.text == NowBarLook.TEXT_TWO_LINES && look.height >= TWO_LINES_FROM &&
            !item.text.isNullOrEmpty()
        val line = if (twoLines || look.text == NowBarLook.TEXT_SONG) {
            item.title.toString()
        } else {
            listOfNotNull(item.title.takeIf { it.isNotEmpty() }, item.text).joinToString(" · ")
        }
        if (songTitle.text.toString() != line) {
            songTitle.text = line
            wave.poke()
        }
        songArtist.text = item.text
        songArtist.visibility = if (twoLines) VISIBLE else GONE

        val buttons = look.buttons != NowBarLook.BUTTONS_NONE
        controls.visibility = if (buttons && !compact) VISIBLE else GONE
        controlViews.forEachIndexed { i, view ->
            (view.parent as View).visibility =
                if (i == 1 || look.buttons == NowBarLook.BUTTONS_ALL) VISIBLE else GONE
            bindControl(view, item.actions.getOrNull(i))
        }
        if (buttons && compact) {
            inlinePlay.visibility = VISIBLE
            bindControl(inlinePlay, item.actions.getOrNull(1))
        }
        (musicColumn.layoutParams as LayoutParams).let {
            val start = if (look.art == NowBarLook.ART_OFF) dp(16f).toInt() else contentStart
            val end = dp(if (buttons && compact) 46f else 16f).toInt()
            if (it.marginStart != start || it.marginEnd != end) {
                it.marginStart = start
                it.marginEnd = end
                musicColumn.layoutParams = it
            }
        }
        wave.colors = colors?.copyOfRange(1, 4) ?: intArrayOf(
            ColorUtils.setAlphaComponent(accent, 0x8C), ColorUtils.setAlphaComponent(accent, 0x66),
            ColorUtils.setAlphaComponent(Color.WHITE, 0x40))
    }

    private fun bindControl(view: ImageView, action: NowBarAction?) {
        if (action == null) {
            view.visibility = INVISIBLE
            return
        }
        view.visibility = VISIBLE
        if (action.iconRes != 0) view.setImageResource(action.iconRes)
        else view.setImageDrawable(action.icon?.loadDrawable(context))
        view.contentDescription = action.title
        view.setOnClickListener { run(action) }
    }

    private fun bindTexts(item: NowBarItem) {
        label.visibility = GONE
        if (item.chronometerBase != 0L && !dozing) {
            chronometer.base = SystemClock.elapsedRealtime() +
                (item.chronometerBase - System.currentTimeMillis())
            chronometer.isCountDown = item.countDown
            chronometer.start()
            chronometer.visibility = VISIBLE
            title.visibility = GONE
            label.text = item.title
            label.visibility = if (item.title.isNotEmpty() && !compact) VISIBLE else GONE
            subtitle.text = item.text
        } else {
            chronometer.stop()
            chronometer.visibility = GONE
            title.visibility = VISIBLE
            when {
                item.chronometerBase != 0L -> {
                    // One line of minutes on the always-on display, refreshed by the doze tick:
                    // "Mom · 3 min". The first minute of a call shows just the name, not "0 min";
                    // a countdown rounds up so it never reads "0 min left" before it ends.
                    val ms = abs(item.chronometerBase - System.currentTimeMillis())
                    val minutes = (if (item.countDown) (ms + 59_999L) else ms) / 60_000L
                    val time = if (minutes == 0L && !item.countDown) null else context.getString(
                        if (item.countDown) R.string.now_bar_minutes_left
                        else R.string.now_bar_minutes,
                        minutes.toInt(),
                    )
                    val name = item.title.takeIf { it.isNotEmpty() }
                        ?: item.text?.takeIf { it.isNotEmpty() }
                    title.text = listOfNotNull(name, time).joinToString(" · ")
                        .ifEmpty { context.getString(R.string.now_bar_minutes, 0) }
                    subtitle.text = null
                }
                item.type == NowBarType.MUSIC -> {
                    title.text = listOfNotNull(item.title.takeIf { it.isNotEmpty() }, item.text)
                        .joinToString(" · ")
                    subtitle.text = null
                }
                else -> {
                    title.text = item.title
                    subtitle.text = listOfNotNull(item.live?.shortText,
                        item.text?.takeIf { it.isNotEmpty() }).joinToString(" · ")
                }
            }
        }
        subtitle.visibility =
            if (subtitle.text.isNullOrEmpty() || dozing || compact) GONE else VISIBLE

        val shown = if (dozing) emptyList()
            else item.actions.filter { it.icon != null || it.iconRes != 0 }.take(2)
        actionViews.forEachIndexed { i, view ->
            val action = shown.getOrNull(i)
            if (action == null) {
                view.visibility = GONE
                return@forEachIndexed
            }
            view.visibility = VISIBLE
            if (action.iconRes != 0) view.setImageResource(action.iconRes)
            else view.setImageDrawable(action.icon?.loadDrawable(context))
            view.contentDescription = action.title
            view.setOnClickListener { run(action) }
        }
        val art = (look.height * ART_RATIO).roundToInt()
        (texts.layoutParams as LayoutParams).marginEnd =
            dp(INSET + shown.size * (art + 6f) + 6f).toInt()
        texts.requestLayout()
        progress.visibility = if (item.progress >= 0f && !dozing && !compact) VISIBLE else GONE
        progress.set(item.progress, accent, item.live)
    }

    private fun bindScore(score: NowBarScore) {
        chronometer.stop()
        team1.text = score.team1
        team2.text = score.team2
        scoreText.text = if (score.score1.isEmpty()) "vs" else "${score.score1} – ${score.score2}"
        statusText.text = score.status
        statusText.visibility = if (score.status.isNullOrEmpty() || compact) GONE else VISIBLE
        for ((view, logo) in listOf(logo1 to score.logo1, logo2 to score.logo2)) {
            view.setImageDrawable(logo)
            view.visibility = if (logo != null) VISIBLE else GONE
            view.alpha = if (dozing) 0.6f else 1f
        }
    }

    /** Plays the last goal once, when it is fresh and the bar is in view to show it. */
    private fun celebrate() {
        val goal = repository.goal ?: return
        if (goal === celebrated || dozing || dragging || card != null || !isShown) return
        if (SystemClock.elapsedRealtime() - goal.at > GOAL_FRESH_MS) return
        val score = items.firstOrNull { it.key == goal.key }?.score ?: return
        celebrated = goal
        currentKey = goal.key
        bind()
        goalAnim?.cancel()
        val first = goal.team == 1
        val color = (if (first) score.color1 else score.color2) ?: accentFill
        floodBg.colors = intArrayOf(ColorUtils.blendARGB(color, Color.BLACK, 0.2f), color)
        bannerLogo.setImageDrawable(if (first) score.logo1 else score.logo2)
        bannerLogo.visibility = if (bannerLogo.drawable != null) VISIBLE else GONE
        bannerLabel.text = goal.label
        bannerLabel.setTextSize(TypedValue.COMPLEX_UNIT_SP, if (compact) 20f else 26f)
        bannerSub.text = "${if (first) score.team1 else score.team2} · " +
            "${score.score1} – ${score.score2}"
        bannerSub.visibility = if (compact) GONE else VISIBLE
        net.visibility = if (goal.net) VISIBLE else GONE
        ball.visibility = if (goal.net) VISIBLE else GONE
        flood.visibility = VISIBLE
        goalKey = goal.key
        goalAnim = ValueAnimator.ofFloat(0f, GOAL_MS.toFloat()).apply {
            duration = GOAL_MS
            interpolator = LinearInterpolator()
            addUpdateListener { stepGoal(it.animatedValue as Float) }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) = endGoal()
            })
            start()
        }
    }

    /** The goal's moment at [t] ms: out with the score, the flood and banner, back to the score. */
    private fun stepGoal(t: Float) {
        val w = flood.width
        val shift = dp(12f)
        val leave = decelerate.getInterpolation(phase(t, 0, 280))
        val come = decelerate.getInterpolation(phase(t, 2900, 3300))
        scoreRow.alpha = if (t < 2900) 1f - leave else come
        scoreRow.translationY = if (t < 2900) shift * leave else shift * (1f - come)
        val fill = decelerate.getInterpolation(phase(t, 0, 350))
        val drain = accelerate.getInterpolation(phase(t, 2600, 3020))
        floodClip.set((w * drain).roundToInt(), 0, (w * fill).roundToInt(), flood.height)
        flood.clipBounds = floodClip
        val enter = decelerate.getInterpolation(phase(t, 350, 650))
        val exit = phase(t, 2500, 2800)
        banner.alpha = enter * (1f - exit)
        banner.translationX = dp(26f) * (1f - enter) - dp(10f) * exit
        if (ball.visibility == VISIBLE) {
            val flight = phase(t, 380, 940)
            ball.translationX = dp(44f) + (w - dp(108f)) * accelerate.getInterpolation(flight)
            ball.rotation = 320f * flight
            ball.alpha = if (t < 380) 0f else 1f - phase(t, 940, 1010)
            val bulge = phase(t, 900, 1400)
            net.scaleX = when {
                bulge < 0.3f -> 1f + 0.22f * bulge / 0.3f
                bulge < 0.66f -> 1.22f - 0.28f * (bulge - 0.3f) / 0.36f
                else -> 0.94f + 0.06f * (bulge - 0.66f) / 0.34f
            }
        }
    }

    private fun endGoal() {
        goalAnim = null
        goalKey = null
        flood.visibility = GONE
        flood.clipBounds = null
        scoreRow.alpha = 1f
        scoreRow.translationY = 0f
        net.scaleX = 1f
    }

    private fun phase(t: Float, from: Int, to: Int) = ((t - from) / (to - from)).coerceIn(0f, 1f)

    private fun run(action: NowBarAction) {
        action.run?.invoke()
        action.intent?.let { send(it) }
    }

    private fun send(intent: PendingIntent) {
        if (intent.isActivity) {
            activityStarter.startPendingIntentDismissingKeyguard(intent)
        } else {
            try {
                intent.send()
            } catch (e: PendingIntent.CanceledException) {
                // Gone with its notification.
            }
        }
    }

    private fun openCard() {
        val item = items.getOrNull(index) ?: return
        dismissCard()
        card = NowBarCard(context, item, repository.settings.wave, activityStarter, ::send) {
            card = null
        }.also { it.show() }
    }

    private fun dismissCard() {
        card?.dismiss()
        card = null
    }

    private fun showMenu() {
        val item = items.getOrNull(index) ?: return
        popup?.dismiss()
        popup = PopupMenu(context, capsule).apply {
            menu.add(0, 1, 0, R.string.now_bar_hide)
            menu.add(0, 2, 1, R.string.now_bar_settings)
            val pinnable = item.type == NowBarType.LIVE && item.packageName != null
            val pinned = pinnable && repository.settings.pinned == item.packageName
            if (pinnable) menu.add(0, 3, 2, if (pinned) R.string.now_bar_unpin else R.string.now_bar_pin)
            setOnMenuItemClickListener {
                when (it.itemId) {
                    1 -> repository.hide(item.key)
                    3 -> repository.pin(if (pinned) null else item.packageName)
                    else -> activityStarter.postStartActivityDismissingKeyguard(
                        Intent(REMIXER_ACTION).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), 0
                    )
                }
                true
            }
            setOnDismissListener { popup = null }
            show()
        }
    }

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        if (dozing) return false
        when (ev.actionMasked) {
            // A child taking the touch is a button: the hold menu is for the bar itself.
            MotionEvent.ACTION_DOWN -> startGesture(ev, hold = false)
            MotionEvent.ACTION_MOVE -> if (horizontalDrag(ev)) return true
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> removeCallbacks(longPress)
        }
        return false
    }

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        if (dozing) return false
        velocity?.addMovement(ev)
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> startGesture(ev, hold = true)
            MotionEvent.ACTION_MOVE -> {
                horizontalDrag(ev)
                if (abs(ev.y - downY) > slop) removeCallbacks(longPress)
                if (dragging) {
                    val dx = ev.x - downX
                    val canMove = if (dx < 0) index < items.size - 1 else index > 0
                    val leaving = !canMove && canSwipeAway()
                    row.translationX = if (leaving) 0f else dx * (if (canMove) 1f else 0.3f)
                    capsule.translationX = if (leaving) dx else 0f
                    capsule.alpha = if (leaving) 1f - min(1f, abs(dx) / width) else 1f
                }
            }
            MotionEvent.ACTION_UP -> {
                removeCallbacks(longPress)
                if (dragging) settle() else if (!longPressed) openCard()
                recycleVelocity()
            }
            MotionEvent.ACTION_CANCEL -> {
                removeCallbacks(longPress)
                springBack()
                recycleVelocity()
            }
        }
        return true
    }

    private fun startGesture(ev: MotionEvent, hold: Boolean) {
        goalAnim?.cancel()
        wave.poke()
        downX = ev.x
        downY = ev.y
        dragging = false
        longPressed = false
        recycleVelocity()
        velocity = VelocityTracker.obtain().also { it.addMovement(ev) }
        removeCallbacks(longPress)
        if (hold) postDelayed(longPress, ViewConfiguration.getLongPressTimeout().toLong())
    }

    private fun horizontalDrag(ev: MotionEvent): Boolean {
        val dx = abs(ev.x - downX)
        if (!dragging && dx > slop && dx > abs(ev.y - downY) &&
            (items.size > 1 || canSwipeAway())
        ) {
            dragging = true
            removeCallbacks(longPress)
            parent?.requestDisallowInterceptTouchEvent(true)
        }
        return dragging
    }

    /** Nothing is playing, so the song can go; it comes back when it plays again. */
    private fun canSwipeAway(): Boolean {
        val item = items.getOrNull(index) ?: return false
        return item.type == NowBarType.MUSIC && !item.playing
    }

    private fun settle() {
        val vx = velocity?.let {
            it.computeCurrentVelocity(1000)
            it.xVelocity
        } ?: 0f
        val leaving = capsule.translationX
        if (leaving != 0f) {
            if (abs(leaving) > width * 0.25f || abs(vx) > minFling) swipeAway(leaving < 0)
            else springBack()
            return
        }
        val dx = row.translationX
        val target = when {
            (dx < -width * 0.25f || vx < -minFling) && index < items.size - 1 -> index + 1
            (dx > width * 0.25f || vx > minFling) && index > 0 -> index - 1
            else -> index
        }
        if (target == index) {
            springBack()
            return
        }
        val out = if (target > index) -width.toFloat() else width.toFloat()
        // Taken now: the list can change before the animation ends.
        val next = items[target].key
        row.animate().translationX(out).alpha(0f).setDuration(140L).withEndAction {
            currentKey = next
            bind()
            row.translationX = -out
            row.animate().translationX(0f).alpha(1f).setDuration(180L).start()
        }.start()
    }

    private fun swipeAway(toStart: Boolean) {
        dragging = false
        val key = items.getOrNull(index)?.key
        val out = if (toStart) -width.toFloat() else width.toFloat()
        capsule.animate().translationX(out).alpha(0f).setDuration(140L).withEndAction {
            key?.let(repository::hide)
            capsule.translationX = 0f
            capsule.alpha = 1f
        }.start()
    }

    private fun springBack() {
        dragging = false
        row.animate().translationX(0f).setDuration(180L).start()
        capsule.animate().translationX(0f).alpha(1f).setDuration(180L).start()
    }

    private fun recycleVelocity() {
        velocity?.recycle()
        velocity = null
    }

    private fun dp(value: Float) = value * density

    private fun px(dpValue: Int) = (dpValue * density).toInt()

    /** The net a goal's ball lands in: a diagonal mesh behind a white post. */
    private class GoalNet(context: Context) : View(context) {
        private val density = context.resources.displayMetrics.density
        private val mesh = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0x38FFFFFF
            strokeWidth = density
        }
        private val post = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0x8CFFFFFF.toInt()
            strokeWidth = 2 * density
        }

        override fun onDraw(canvas: Canvas) {
            val w = width.toFloat()
            val h = height.toFloat()
            var x = -h
            while (x < w) {
                canvas.drawLine(x, 0f, x + h, h, mesh)
                canvas.drawLine(x + h, 0f, x, h, mesh)
                x += 8 * density
            }
            canvas.drawLine(density, 0f, density, h, post)
        }
    }

    /** One dot per activity, when the bar is set to show them. */
    private class Dots(context: Context) : View(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private var count = 0
        private var current = 0

        fun set(count: Int, current: Int) {
            if (count == this.count && current == this.current) return
            this.count = count
            this.current = current
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            val r = height / 2f
            val step = r * 3.6f
            var x = (width - (count - 1) * step) / 2f
            for (i in 0 until count) {
                paint.color = if (i == current) Color.WHITE else 0x66FFFFFF
                canvas.drawCircle(x, r, r, paint)
                x += step
            }
        }
    }

    /** A thin progress bar: a dim track and a filled part. */
    private class ProgressLine(context: Context) : View(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private var fraction = 0f
        private var color = Color.WHITE
        private var live: NowBarLive? = null
        private val gap = context.resources.displayMetrics.density * 2f

        fun set(fraction: Float, color: Int, live: NowBarLive? = null) {
            this.fraction = fraction.coerceIn(0f, 1f)
            this.color = color
            this.live = live
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            val w = width.toFloat()
            val h = height.toFloat()
            val r = h / 2f
            val live = live
            if (live == null || live.segments.size <= 1) {
                paint.color = 0x29FFFFFF
                canvas.drawRoundRect(0f, 0f, w, h, r, r, paint)
                paint.color = live?.segments?.firstOrNull()?.second?.takeIf { it != 0 } ?: color
                canvas.drawRoundRect(0f, 0f, w * fraction, h, r, r, paint)
                drawPoints(canvas, live, w, h)
                return
            }
            // Android 16's segments: each its own pill, lit up to the progress.
            val gaps = gap * (live.segments.size - 1)
            val end = w * fraction
            var x = 0f
            for ((share, tint) in live.segments) {
                val sw = (w - gaps) * share
                val c = if (tint != 0) tint else color
                paint.color = ColorUtils.setAlphaComponent(c, if (live.styledByProgress) 0x29 else 0x66)
                canvas.drawRoundRect(x, 0f, x + sw, h, r, r, paint)
                if (end > x) {
                    paint.color = c
                    canvas.drawRoundRect(x, 0f, min(end, x + sw), h, r, r, paint)
                }
                x += sw + gap
            }
            drawPoints(canvas, live, w, h)
        }

        private fun drawPoints(canvas: Canvas, live: NowBarLive?, w: Float, h: Float) {
            for ((at, tint) in live?.points ?: return) {
                paint.color = if (tint != 0) tint else Color.WHITE
                canvas.drawCircle(w * at, h / 2f, h * 1.1f, paint)
            }
        }
    }

    private companion object {
        /** How far the next activity peeks out above the capsule, in dp, if set to. */
        const val PEEK = 7f
        const val ART_RATIO = 0.6f
        const val INSET = 12f
        const val INSET_COMPACT = 8f
        /** Below this height, in dp, everything fits on one line. */
        const val COMPACT_BELOW = 60
        const val TWO_LINES_FROM = 70
        const val DARK_FILL = 0xFF0D0809.toInt()
        const val REMIXER_ACTION = "com.resurrection.settings.NOW_BAR"
        /** A goal's moment, in ms. */
        const val GOAL_MS = 3400L
        /** How long a goal waits for the bar to be in view, in ms. */
        const val GOAL_FRESH_MS = 2 * 60_000L
        val BURN_IN_STEPS_X = floatArrayOf(0f, 3f, 5f, 3f, 0f, -3f, -5f, -3f)
        val BURN_IN_STEPS_Y = floatArrayOf(0f, -2f, -4f, -6f, -4f, -2f, 0f, -2f)
    }
}
