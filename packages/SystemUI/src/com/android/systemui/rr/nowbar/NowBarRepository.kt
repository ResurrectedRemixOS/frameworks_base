/*
 * SPDX-FileCopyrightText: The Resurrected Remix Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.systemui.rr.nowbar

import android.Manifest.permission.READ_GLOBAL_APP_SEARCH_DATA
import android.app.Notification
import android.app.PendingIntent
import android.app.Person
import android.app.WallpaperColors
import android.app.appsearch.GenericDocument
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.graphics.Bitmap
import android.graphics.drawable.Drawable
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.net.Uri
import android.os.BatteryManager
import android.os.Handler
import android.os.Looper
import android.os.UserHandle
import android.provider.Settings
import android.service.notification.NotificationListenerService.RankingMap
import android.service.notification.StatusBarNotification
import android.service.notification.ZenModeConfig
import android.text.format.DateFormat
import android.util.Log
import androidx.core.graphics.ColorUtils
import androidx.core.graphics.drawable.toBitmap
import com.android.settingslib.utils.StringUtil
import com.android.systemui.CoreStartable
import com.android.systemui.clocks.FlatlineChipDrawable
import com.android.systemui.clocks.FlatlineClockView
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Background
import com.android.systemui.keyguard.WakefulnessLifecycle
import com.android.systemui.plugins.ActivityStarter
import com.android.systemui.res.R
import com.android.systemui.rr.media.MediaArt
import com.android.systemui.statusbar.NotificationListener
import android.bluetooth.BluetoothDevice
import com.android.settingslib.bluetooth.BluetoothUtils
import com.android.systemui.statusbar.commandline.Command
import com.android.systemui.statusbar.commandline.CommandRegistry
import com.android.systemui.statusbar.policy.BatteryController
import com.android.systemui.statusbar.policy.BluetoothController
import com.android.systemui.statusbar.policy.FlashlightController
import com.android.systemui.statusbar.policy.KeyguardStateController
import com.android.systemui.statusbar.policy.ZenModeController
import java.io.PrintWriter
import java.util.Calendar
import java.util.Date
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import kotlin.math.max

/**
 * Everything happening right now, for the Now Bar: music from media sessions; calls, navigation,
 * timers, recording and Live Updates from ongoing notifications; charging and the flashlight.
 */
@SysUISingleton
class NowBarRepository
@Inject
constructor(
    private val context: Context,
    private val notificationListener: NotificationListener,
    private val batteryController: BatteryController,
    private val flashlightController: FlashlightController,
    private val bluetoothController: BluetoothController,
    private val zenModeController: ZenModeController,
    private val keyguardStateController: KeyguardStateController,
    private val wakefulnessLifecycle: WakefulnessLifecycle,
    @Background private val bgExecutor: Executor,
    val activityStarter: ActivityStarter,
    private val commandRegistry: CommandRegistry,
) : CoreStartable, NotificationListener.NotificationHandler {

    fun interface Listener {
        fun onNowBarChanged(items: List<NowBarItem>)
    }

    val settings = NowBarSettings(context)

    /** What the Now Bar shows, most important first. */
    var items: List<NowBarItem> = emptyList()
        private set

    private val handler = Handler(Looper.getMainLooper())
    private val listeners = ArrayList<Listener>()
    private val notificationItems = LinkedHashMap<String, NowBarItem>()
    private val hidden = HashSet<String>()
    private var lockscreenCallKeys = emptySet<String>()
    private val controllers = ArrayList<MediaController>()
    private var mediaItem: NowBarItem? = null
    // The song's art scaled, with its colors: one instance across state changes, so views can
    // compare art by identity.
    private var artKey: String? = null
    private var pendingArtKey: String? = null
    private var art: Bitmap? = null
    private var artColors: IntArray? = null
    private var chargingItem: NowBarItem? = null
    private var budsItems: List<NowBarItem> = emptyList()
    private var flashlightItem: NowBarItem? = null
    private var modeItem: NowBarItem? = null
    private lateinit var sessionManager: MediaSessionManager
    private val scores = NowBarScores(context, handler, bgExecutor) { publish() }

    /** The last goal seen as it happened, in a game the bar shows. */
    val goal: NowBarGoal? get() = scores.goal

    private val scoreNotifier = NowBarScoreNotifier(context)

    /** The last goal seen as it happened in [key]'s game. */
    fun goalFor(key: String) = scores.goalFor(key)

    /** The score card's last line: Google's own when System UI may read it, else a goal seen here. */
    fun lineFor(item: NowBarItem): CharSequence? {
        val score = item.score ?: return null
        return scores.momentFor(item.key)
            ?: scores.goalFor(item.key)?.let { NowBarScoreViews.goalLine(context, it, score) }
    }

    private val sessionsListener =
        MediaSessionManager.OnActiveSessionsChangedListener { sessions ->
            updateSessions(sessions ?: emptyList())
        }

    private val mediaCallback = object : MediaController.Callback() {
        override fun onMetadataChanged(metadata: MediaMetadata?) = updateMedia()

        override fun onPlaybackStateChanged(state: PlaybackState?) = updateMedia()

        override fun onSessionDestroyed() {
            handler.post { updateSessions(activeSessions()) }
        }
    }

    private val batteryCallback = object : BatteryController.BatteryStateChangeCallback {
        override fun onBatteryLevelChanged(level: Int, pluggedIn: Boolean, charging: Boolean) {
            chargingItem = if (pluggedIn) chargingItem(level, charging) else null
            publish()
        }
    }

    private val bluetoothCallback = object : BluetoothController.Callback {
        override fun onBluetoothStateChange(enabled: Boolean) = updateBuds()
        override fun onBluetoothDevicesChanged() = updateBuds()
    }

    private val flashlightListener = object : FlashlightController.FlashlightListener {
        override fun onFlashlightChanged(enabled: Boolean) {
            handler.post {
                flashlightItem = if (enabled) flashlightItem() else null
                publish()
            }
        }

        override fun onFlashlightError() {
            handler.post {
                flashlightItem = null
                publish()
            }
        }

        override fun onFlashlightAvailabilityChanged(available: Boolean) {}
    }

    private val zenCallback = object : ZenModeController.Callback {
        override fun onZenChanged(zen: Int) = updateMode()
        override fun onConfigChanged(config: ZenModeConfig) = updateMode()
    }

    override fun start() {
        instance = this
        commandRegistry.registerCommand("now-bar-goal") { GoalCommand() }
        commandRegistry.registerCommand("now-bar-appsearch", { AppSearchCommand() }, bgExecutor)
        val observer = object : ContentObserver(handler) {
            override fun onChange(selfChange: Boolean) {
                val liveApps = settings.liveApps
                settings.read()
                if (settings.liveApps != liveApps) readNotifications()
                scores.setSource(if (settings.enabled) settings.scores else NowBarScores.SOURCE_OFF)
                publish()
            }
        }
        for (key in NowBarSettings.KEYS) {
            context.contentResolver.registerContentObserver(
                Settings.System.getUriFor(key), false, observer, UserHandle.USER_ALL
            )
        }
        notificationListener.addNotificationHandler(this)
        sessionManager = context.getSystemService(MediaSessionManager::class.java)
        sessionManager.addOnActiveSessionsChangedListener(sessionsListener, null, handler)
        updateSessions(activeSessions())
        batteryController.addCallback(batteryCallback)
        flashlightController.addCallback(flashlightListener)
        bluetoothController.addCallback(bluetoothCallback)
        updateBuds()
        zenModeController.addCallback(zenCallback)
        updateMode()
        keyguardStateController.addCallback(object : KeyguardStateController.Callback {
            override fun onKeyguardShowingChanged() = updateScoresShown()
        })
        wakefulnessLifecycle.addObserver(object : WakefulnessLifecycle.Observer {
            override fun onStartedWakingUp() = updateScoresShown()
            override fun onStartedGoingToSleep() = updateScoresShown()
        })
        updateScoresShown()
        scores.setSource(if (settings.enabled) settings.scores else NowBarScores.SOURCE_OFF)
    }

    private fun updateScoresShown() {
        val awake = wakefulnessLifecycle.wakefulness == WakefulnessLifecycle.WAKEFULNESS_WAKING ||
            wakefulnessLifecycle.wakefulness == WakefulnessLifecycle.WAKEFULNESS_AWAKE
        // Unlocked too, for the status bar chip and the shade's score cards.
        scores.setShown(
            awake && !(keyguardStateController.isShowing && keyguardStateController.isOccluded))
    }

    override fun dump(pw: PrintWriter, args: Array<out String>) {
        pw.println("NowBar: enabled=${settings.enabled} sessions=${controllers.size} " +
            "media=${mediaItem != null} notifications=${notificationItems.keys} hidden=$hidden " +
            "scores=${settings.scores}:${scores.items.size} goal=${scores.goal?.let {
                "${it.key}:${it.team}:${it.label}"
            }}")
        pw.println("  items=${items.map { "${it.type}:${it.key}" }}")
        for (listener in listeners) {
            val view = listener as? android.view.View
            if (view == null) {
                pw.println("  listener=$listener")
                continue
            }
            val at = IntArray(2).also { view.getLocationOnScreen(it) }
            pw.println("  view=${view.javaClass.simpleName} attached=${view.isAttachedToWindow} " +
                "visibility=${view.visibility} alpha=${view.alpha} size=${view.width}x${view.height} " +
                "at=${at[0]},${at[1]} shown=${view.isShown}")
        }
    }

    private inner class GoalCommand : Command {
        override fun execute(pw: PrintWriter, args: List<String>) {
            if (args.firstOrNull() == "final") {
                pw.println(if (scores.testFinal()) "Full time" else "No game in the Now Bar")
                return
            }
            val team = if (args.firstOrNull() == "2") 2 else 1
            pw.println(if (scores.testGoal(team)) "Goal for team $team" else "No game in the Now Bar")
        }

        override fun help(pw: PrintWriter) {
            pw.println("Usage: adb shell cmd statusbar now-bar-goal [1|2|final]")
            pw.println("Plays the Now Bar's goal moment for a team of the first game, or its end.")
        }
    }

    private inner class AppSearchCommand : Command {
        override fun execute(pw: PrintWriter, args: List<String>) {
            val granted = context.checkSelfPermission(READ_GLOBAL_APP_SEARCH_DATA) ==
                PackageManager.PERMISSION_GRANTED
            pw.println("READ_GLOBAL_APP_SEARCH_DATA ${if (granted) "granted" else "not granted"}")
            val done = CountDownLatch(1)
            var found: List<GenericDocument>? = null
            var error: String? = null
            val appSearch = NowBarAppSearch(context) { it.run() }
            appSearch.games { docs, why ->
                found = docs
                error = why
                done.countDown()
            }
            if (!done.await(5, TimeUnit.SECONDS)) {
                pw.println("No answer from AppSearch")
                return
            }
            appSearch.close()
            val docs = found
            if (docs == null) {
                pw.println("Failed: $error")
                return
            }
            pw.println("${docs.size} games from Google")
            for (doc in docs) {
                pw.println("${doc.namespace} ${doc.id} ${doc.schemaType}")
                pw.println("  shortDescription=${NowBarAppSearch.text(doc, "shortDescription")}")
            }
            if (args.firstOrNull() == "full") docs.firstOrNull()?.let { pw.println(it) }
        }

        override fun help(pw: PrintWriter) {
            pw.println("Usage: adb shell cmd statusbar now-bar-appsearch [full]")
            pw.println("Reads Google's game records the way One UI does; full prints the first.")
        }
    }

    fun addListener(listener: Listener) {
        listeners += listener
        listener.onNowBarChanged(items)
    }

    fun removeListener(listener: Listener) {
        listeners -= listener
    }

    /** Pins [packageName]'s Live Update first and on the always-on display; null unpins. */
    fun pin(packageName: String?) {
        Settings.System.putStringForUser(context.contentResolver, NowBarSettings.KEY_PIN,
            packageName ?: "", UserHandle.USER_CURRENT)
    }

    /** Hides [key] until it ends. */
    fun hide(key: String) {
        hidden += key
        publish()
    }

    private fun publish() {
        val all = ArrayList<NowBarItem>(notificationItems.values)
        mediaItem?.let(all::add)
        chargingItem?.let(all::add)
        flashlightItem?.let(all::add)
        modeItem?.let(all::add)
        all += budsItems
        all += scores.items
        hidden.retainAll(all.map { it.key }.toSet())
        val pinned = settings.pinned
        // A pinned Live Update leads, so it is what the always-on display shows.
        items = all.filter { settings.shows(it.type) && it.key !in hidden }
            .sortedWith(compareBy({ !(it.type == NowBarType.LIVE && it.packageName == pinned) },
                { it.type.ordinal }))
        val callKeys = if (settings.hidesLockscreenCalls()) {
            items.filter { it.type == NowBarType.CALL }.map { it.key }.toSet()
        } else {
            emptySet()
        }
        val callsChanged = callKeys != lockscreenCallKeys
        lockscreenCallKeys = callKeys
        scoreNotifier.update(scores.items.filter { settings.shows(it.type) }, ::lineFor)
        listeners.toList().forEach { it.onNowBarChanged(items) }
        if (callsChanged) lockscreenListener?.invoke()
    }

    /** True while this notification is the call the Now Bar is showing. */
    fun showsCallOnLockscreen(key: String) =
        settings.hidesLockscreenCalls() && items.any { it.type == NowBarType.CALL && it.key == key }

    // Media

    private fun activeSessions(): List<MediaController> =
        try {
            sessionManager.getActiveSessions(null)
        } catch (e: SecurityException) {
            Log.w(TAG, "No media sessions", e)
            emptyList()
        }

    private fun updateSessions(sessions: List<MediaController>) {
        controllers.forEach { it.unregisterCallback(mediaCallback) }
        controllers.clear()
        controllers += sessions
        controllers.forEach { it.registerCallback(mediaCallback, handler) }
        updateMedia()
    }

    private fun updateMedia() {
        // Metadata once per session: a second fetch can come back empty when the session ends.
        val sessions = controllers.mapNotNull { c -> c.metadata?.let { c to it } }
        val session = sessions.firstOrNull { (c, _) -> c.isPlaying() }
            ?: sessions.firstOrNull { (c, _) -> c.playbackState?.state == PlaybackState.STATE_PAUSED }
        val item = session?.let { (c, metadata) -> mediaItem(c, metadata) }
        // A song swiped away while paused comes back when it plays again.
        if (item?.playing == true && mediaItem?.playing != true) hidden -= item.key
        mediaItem = item
        publish()
    }

    private fun MediaController.isPlaying() = playbackState?.state == PlaybackState.STATE_PLAYING

    private fun mediaItem(controller: MediaController, metadata: MediaMetadata): NowBarItem {
        val playing = controller.isPlaying()
        val pkg = controller.packageName
        val title = metadata.getString(MediaMetadata.METADATA_KEY_TITLE)
            ?: metadata.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE)
            ?: appLabel(pkg)
        val artist = metadata.getString(MediaMetadata.METADATA_KEY_ARTIST)
            ?: metadata.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST)
            ?: metadata.getString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE)
        val raw = metadata.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
            ?: metadata.getBitmap(MediaMetadata.METADATA_KEY_ART)
            ?: metadata.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON)
        val art = raw?.let { art(pkg, metadata, it) }
        val controls = controller.transportControls
        val actions = listOf(
            NowBarAction(context.getString(R.string.now_bar_previous),
                iconRes = R.drawable.rr_nowbar_prev, run = { controls.skipToPrevious() }),
            if (playing) {
                NowBarAction(context.getString(R.string.now_bar_pause),
                    iconRes = R.drawable.rr_nowbar_pause, run = { controls.pause() })
            } else {
                NowBarAction(context.getString(R.string.now_bar_play),
                    iconRes = R.drawable.rr_nowbar_play, run = { controls.play() })
            },
            NowBarAction(context.getString(R.string.now_bar_next),
                iconRes = R.drawable.rr_nowbar_next, run = { controls.skipToNext() }),
        )
        return NowBarItem(
            key = "media:$pkg",
            type = NowBarType.MUSIC,
            packageName = pkg,
            icon = appIcon(pkg),
            art = art,
            artColors = if (art != null) artColors else null,
            title = title ?: "",
            text = artist,
            playing = playing,
            actions = actions,
            contentIntent = controller.sessionActivity,
            controller = controller,
        )
    }

    // Notifications

    override fun onNotificationPosted(sbn: StatusBarNotification, rankingMap: RankingMap) {
        val n = sbn.notification
        if (sbn.packageName != context.packageName && !n.isGroupSummary &&
            n.flags and (Notification.FLAG_ONGOING_EVENT or Notification.FLAG_ONLY_ALERT_ONCE) == 0
        ) {
            FlatlineClockView.pulse()
            FlatlineChipDrawable.pulse()
        }
        val item = notificationItem(sbn)
        if (item != null) {
            notificationItems[sbn.key] = item
        } else if (notificationItems.remove(sbn.key) == null) {
            return
        }
        publish()
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification, rankingMap: RankingMap) {
        if (notificationItems.remove(sbn.key) != null) publish()
    }

    override fun onNotificationRemoved(
        sbn: StatusBarNotification,
        rankingMap: RankingMap,
        reason: Int,
    ) = onNotificationRemoved(sbn, rankingMap)

    override fun onNotificationsInitialized() {
        readNotifications()
        publish()
    }

    /** Classifies what is posted now: at start, and when the Live Updates app list changes. */
    private fun readNotifications() {
        val active = try {
            notificationListener.activeNotifications
        } catch (e: RuntimeException) {
            null
        } ?: return
        notificationItems.clear()
        active.forEach { sbn -> notificationItem(sbn)?.let { notificationItems[sbn.key] = it } }
    }

    override fun onNotificationRankingUpdate(rankingMap: RankingMap) {}

    private fun classify(sbn: StatusBarNotification, oneUi: NowBarOneUi?): NowBarType? {
        val n = sbn.notification
        val ongoing =
            n.flags and (Notification.FLAG_ONGOING_EVENT or Notification.FLAG_FOREGROUND_SERVICE) != 0
        if (!ongoing || n.isMediaNotification) return null
        val chronometer = n.extras.getBoolean(Notification.EXTRA_SHOW_CHRONOMETER)
        return when {
            n.isStyle(Notification.CallStyle::class.java) ||
                n.category == Notification.CATEGORY_CALL -> NowBarType.CALL
            sbn.packageName == context.packageName ->
                if (n.channelId == SCREEN_RECORD_CHANNEL) NowBarType.RECORDING else null
            n.category == Notification.CATEGORY_NAVIGATION -> NowBarType.NAVIGATION
            n.category == Notification.CATEGORY_STOPWATCH -> NowBarType.TIMER
            chronometer && (n.category == Notification.CATEGORY_ALARM ||
                sbn.packageName in CLOCK_PACKAGES) -> NowBarType.TIMER
            sbn.packageName in RECORDER_PACKAGES -> NowBarType.RECORDING
            (n.flags and Notification.FLAG_PROMOTED_ONGOING != 0 ||
                n.isStyle(Notification.ProgressStyle::class.java)) &&
                settings.showsLiveFrom(sbn.packageName) -> NowBarType.LIVE
            oneUi != null && settings.showsLiveFrom(sbn.packageName) -> NowBarType.LIVE
            else -> null
        }
    }

    private fun notificationItem(sbn: StatusBarNotification): NowBarItem? {
        val n = sbn.notification
        val extras = n.extras
        val oneUi = NowBarOneUi.of(extras)
        val type = classify(sbn, oneUi) ?: return null
        val packageContext = try {
            sbn.getPackageContext(context)
        } catch (e: RuntimeException) {
            context
        }
        var title = oneUi?.title
            ?: extras.getCharSequence(Notification.EXTRA_TITLE)
            ?: extras.getCharSequence(Notification.EXTRA_TITLE_BIG)
        if (type == NowBarType.CALL) {
            extras.getParcelable(Notification.EXTRA_CALL_PERSON, Person::class.java)?.name
                ?.let { title = it }
        }
        val label = title ?: appLabel(sbn.packageName) ?: return null
        val live = oneUi?.live ?: if (type == NowBarType.LIVE) liveOf(n, packageContext) else null
        val max = extras.getInt(Notification.EXTRA_PROGRESS_MAX)
        val progress = when {
            live != null -> live.progress
            max > 0 && !extras.getBoolean(Notification.EXTRA_PROGRESS_INDETERMINATE) ->
                extras.getInt(Notification.EXTRA_PROGRESS).toFloat() / max
            else -> -1f
        }
        val actions = n.actions.orEmpty()
            .filter { it.remoteInputs.isNullOrEmpty() && it.title != null }
            .take(MAX_ACTIONS)
            .map { NowBarAction(it.title, icon = it.getIcon(), intent = it.actionIntent) }
        return NowBarItem(
            key = sbn.key,
            type = type,
            packageName = sbn.packageName,
            icon = oneUi?.icon?.loadDrawable(packageContext)
                ?: n.smallIcon?.loadDrawable(packageContext),
            art = n.getLargeIcon()?.loadDrawable(packageContext)?.let {
                it.toBitmap(LARGE_ICON_SIZE, LARGE_ICON_SIZE)
            },
            title = label,
            text = oneUi?.text ?: extras.getCharSequence(Notification.EXTRA_TEXT),
            chronometerBase =
                if (extras.getBoolean(Notification.EXTRA_SHOW_CHRONOMETER)) n.`when` else 0L,
            countDown = extras.getBoolean(Notification.EXTRA_CHRONOMETER_COUNT_DOWN),
            progress = progress,
            color = n.color,
            actions = actions,
            contentIntent = n.contentIntent,
            live = live,
            automation = oneUi?.automation == true,
        )
    }

    /** Android 16's progress bar from a Live Update, or null when it has none. */
    private fun liveOf(n: Notification, packageContext: Context): NowBarLive? {
        val short = n.shortCriticalText
        if (!n.isStyle(Notification.ProgressStyle::class.java)) {
            return short?.let { NowBarLive(emptyList(), emptyList(), -1f, false, it) }
        }
        val style = try {
            Notification.Builder.recoverBuilder(packageContext, n).style
                as? Notification.ProgressStyle
        } catch (e: RuntimeException) {
            null
        } ?: return null
        val max = style.progressMax.coerceAtLeast(1).toFloat()
        val segments = style.progressSegments
        return NowBarLive(
            segments = if (segments.isEmpty()) listOf(1f to 0)
                else segments.map { it.length / max to it.color },
            points = style.progressPoints.map { (it.position / max).coerceIn(0f, 1f) to it.color },
            progress = if (style.isProgressIndeterminate) -1f
                else (style.progress / max).coerceIn(0f, 1f),
            styledByProgress = style.isStyledByProgress,
            shortText = short,
        )
    }

    // Charging and flashlight

    private fun chargingItem(level: Int, charging: Boolean): NowBarItem {
        val remaining = context.getSystemService(BatteryManager::class.java)
            ?.computeChargeTimeRemaining() ?: -1L
        val text = when {
            level >= 100 || !charging -> context.getString(R.string.now_bar_charged)
            remaining > 0 -> context.getString(
                R.string.now_bar_full_in,
                StringUtil.formatElapsedTime(context, remaining.toDouble(), false, true),
            )
            else -> null
        }
        return NowBarItem(
            key = KEY_CHARGING,
            type = NowBarType.CHARGING,
            packageName = null,
            icon = context.getDrawable(R.drawable.rr_nowbar_bolt),
            art = null,
            title = context.getString(R.string.now_bar_charging, level),
            text = text,
            progress = level / 100f,
        )
    }

    // Earbuds and watches

    private fun updateBuds() {
        handler.post {
            val items = budsItems()
            if (items.map { it.key to it.text } == budsItems.map { it.key to it.text }) return@post
            budsItems = items
            publish()
        }
    }

    /** One item per connected device that reports a battery: left, right and case, or one level. */
    private fun budsItems(): List<NowBarItem> {
        val items = ArrayList<NowBarItem>()
        for (device in bluetoothController.connectedDevices) {
            if (!device.isConnected) continue
            val bt = device.device ?: continue
            val left = BluetoothUtils.getIntMetaData(bt, BluetoothDevice.METADATA_UNTETHERED_LEFT_BATTERY)
            val right = BluetoothUtils.getIntMetaData(bt, BluetoothDevice.METADATA_UNTETHERED_RIGHT_BATTERY)
            val case = BluetoothUtils.getIntMetaData(bt, BluetoothDevice.METADATA_UNTETHERED_CASE_BATTERY)
            val main = device.batteryLevel
            val levels = listOf(left, right, case, main).filter { it in 0..100 }
            if (levels.isEmpty()) continue
            val text = if (left in 0..100 || right in 0..100) {
                listOfNotNull(
                    left.takeIf { it in 0..100 }?.let { context.getString(R.string.now_bar_buds_left, it) },
                    right.takeIf { it in 0..100 }?.let { context.getString(R.string.now_bar_buds_right, it) },
                    case.takeIf { it in 0..100 }?.let { context.getString(R.string.now_bar_buds_case, it) },
                ).joinToString(" · ")
            } else {
                context.getString(R.string.now_bar_buds_level, main)
            }
            items += NowBarItem(
                key = "buds:${bt.address}",
                type = NowBarType.BUDS,
                packageName = null,
                icon = context.getDrawable(R.drawable.ic_headset),
                art = null,
                title = device.name ?: context.getString(R.string.now_bar_buds),
                text = text,
                progress = levels.min() / 100f,
            )
        }
        return items
    }

    private fun flashlightItem() = NowBarItem(
        key = KEY_FLASHLIGHT,
        type = NowBarType.FLASHLIGHT,
        packageName = null,
        icon = context.getDrawable(R.drawable.rr_ql_flashlight),
        art = null,
        title = context.getString(R.string.now_bar_flashlight_on),
        text = null,
        actions = listOf(
            NowBarAction(context.getString(R.string.now_bar_turn_off),
                iconRes = R.drawable.rr_nowbar_close, run = { flashlightController.setFlashlight(false) })
        ),
    )

    // Do Not Disturb and modes

    private fun updateMode() {
        handler.post {
            modeItem = modeItem()
            publish()
        }
    }

    private fun modeItem(): NowBarItem? {
        if (zenModeController.zen == Settings.Global.ZEN_MODE_OFF) return null
        val config = zenModeController.config
        val manual = config?.manualRule?.takeIf { config.isManualActive }
        val rule = manual ?: config?.automaticRules?.values?.firstOrNull { it.isActive }
        val dnd = context.getString(R.string.quick_settings_dnd_label)
        val end = rule?.conditionId?.let(::modeEnd) ?: 0L
        return NowBarItem(
            key = KEY_MODE,
            type = NowBarType.MODE,
            packageName = null,
            icon = context.getDrawable(R.drawable.rr_nowbar_mode),
            art = null,
            title = if (rule == null || rule === manual) dnd else rule.name ?: dnd,
            text = if (end > System.currentTimeMillis()) {
                context.getString(R.string.now_bar_until,
                    DateFormat.getTimeFormat(context).format(Date(end)))
            } else {
                null
            },
            actions = listOf(
                NowBarAction(context.getString(R.string.now_bar_turn_off),
                    iconRes = R.drawable.rr_nowbar_close,
                    run = { zenModeController.setZen(Settings.Global.ZEN_MODE_OFF, null, TAG) })
            ),
            contentIntent = modesIntent,
        )
    }

    private fun modeEnd(conditionId: Uri): Long {
        val countdown = ZenModeConfig.tryParseCountdownConditionId(conditionId)
        if (countdown > 0L) return countdown
        val schedule = ZenModeConfig.tryParseScheduleConditionId(conditionId) ?: return 0L
        val now = System.currentTimeMillis()
        val end = Calendar.getInstance().apply {
            timeInMillis = now
            set(Calendar.HOUR_OF_DAY, schedule.endHour)
            set(Calendar.MINUTE, schedule.endMinute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
            if (timeInMillis <= now) add(Calendar.DATE, 1)
        }.timeInMillis
        val alarm = schedule.nextAlarm
        return if (schedule.exitAtAlarm && alarm > now && alarm < end) alarm else end
    }

    private val modesIntent by lazy {
        PendingIntent.getActivity(context, 0,
            Intent(Settings.ACTION_ZEN_MODE_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE)
    }

    private fun appLabel(pkg: String?): CharSequence? =
        pkg?.let {
            try {
                context.packageManager.getApplicationLabel(
                    context.packageManager.getApplicationInfo(it, 0)
                )
            } catch (e: PackageManager.NameNotFoundException) {
                null
            }
        }

    private fun appIcon(pkg: String): Drawable? =
        try {
            context.packageManager.getApplicationIcon(pkg)
        } catch (e: PackageManager.NameNotFoundException) {
            null
        }

    private fun scaled(bitmap: Bitmap, size: Int): Bitmap {
        val largest = max(bitmap.width, bitmap.height)
        if (largest <= size) return bitmap
        val f = size.toFloat() / largest
        return Bitmap.createScaledBitmap(
            bitmap, (bitmap.width * f).toInt().coerceAtLeast(1),
            (bitmap.height * f).toInt().coerceAtLeast(1), true,
        )
    }

    /**
     * [raw] scaled, with its colors, made on the background executor: a cover is a few frames
     * of work. Every getMetadata() unparcels a new Bitmap, so [MediaArt.key] tells the song;
     * the last song's art stays until the new one is ready.
     */
    private fun art(pkg: String, metadata: MediaMetadata, raw: Bitmap): Bitmap? {
        val key = MediaArt.key(pkg, metadata, raw)
        if (key != artKey && key != pendingArtKey) {
            pendingArtKey = key
            bgExecutor.execute {
                val scaled = try {
                    scaled(raw, ART_SIZE)
                } catch (e: RuntimeException) {
                    raw
                }
                val colors = paletteOf(scaled)
                handler.post {
                    if (pendingArtKey != key) return@post
                    pendingArtKey = null
                    artKey = key
                    art = scaled
                    artColors = colors
                    updateMedia()
                }
            }
        }
        return art
    }

    /** The capsule's tint and three wave colors from the album art. */
    private fun paletteOf(art: Bitmap): IntArray? =
        try {
            val wallpaperColors = WallpaperColors.fromBitmap(art)
            val primary = wallpaperColors.primaryColor.toArgb()
            val secondary = wallpaperColors.secondaryColor?.toArgb() ?: primary
            val tertiary = wallpaperColors.tertiaryColor?.toArgb() ?: secondary
            intArrayOf(
                tone(primary, 0.17f, 0.5f, 0xF0),
                tone(primary, 0.66f, 0.85f, 0x8C),
                tone(secondary, 0.72f, 0.85f, 0x99),
                tone(tertiary, 0.6f, 0.85f, 0x8C),
            )
        } catch (e: RuntimeException) {
            // Hardware or odd bitmaps: the default look.
            null
        }

    private fun tone(color: Int, lightness: Float, maxSaturation: Float, alpha: Int): Int {
        val hsl = FloatArray(3)
        ColorUtils.colorToHSL(color, hsl)
        hsl[1] = hsl[1].coerceAtMost(maxSaturation)
        hsl[2] = lightness
        return ColorUtils.setAlphaComponent(ColorUtils.HSLToColor(hsl), alpha)
    }

    companion object {
        /** For views inflated outside Dagger, like the status bar chip. */
        @Volatile
        var instance: NowBarRepository? = null
            private set

        /** Lock screen list refreshes when a call enters or leaves the bar. */
        @Volatile
        var lockscreenListener: (() -> Unit)? = null

        private const val TAG = "NowBar"
        private const val KEY_CHARGING = "charging"
        private const val KEY_FLASHLIGHT = "flashlight"
        private const val KEY_MODE = "mode"
        private const val SCREEN_RECORD_CHANNEL = "screen_record"
        private val CLOCK_PACKAGES = setOf("com.google.android.deskclock", "com.android.deskclock")
        private val RECORDER_PACKAGES = setOf("org.lineageos.recorder")
        private const val MAX_ACTIONS = 3
        private const val ART_SIZE = 512
        private const val LARGE_ICON_SIZE = 192
    }
}
