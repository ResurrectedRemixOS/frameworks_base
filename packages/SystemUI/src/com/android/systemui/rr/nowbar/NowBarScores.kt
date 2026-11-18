/*
 * SPDX-FileCopyrightText: The Resurrected Remix Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.systemui.rr.nowbar

import android.app.PendingIntent
import android.app.smartspace.SmartspaceAction
import android.app.smartspace.SmartspaceConfig
import android.app.smartspace.SmartspaceManager
import android.app.smartspace.SmartspaceSession
import android.app.smartspace.SmartspaceTarget
import android.app.smartspace.SmartspaceTargetEvent
import android.app.smartspace.uitemplatedata.HeadToHeadTemplateData
import android.content.Context
import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.os.Handler
import android.os.Parcel
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.RemoteViews
import android.widget.TextView
import com.android.systemui.res.R
import java.util.concurrent.Executor

/** Live game scores for the Now Bar from Google's At a Glance, through a Smartspace session. */
class NowBarScores(
    private val context: Context,
    private val handler: Handler,
    private val bgExecutor: Executor,
    private val onChanged: () -> Unit,
) {
    var items: List<NowBarItem> = emptyList()
        private set

    private var source = SOURCE_OFF
    private var session: SmartspaceSession? = null
    private var shown = false
    private val cards = HashMap<String, Pair<Int, NowBarScore?>>()

    private val targetsListener = SmartspaceSession.OnTargetsAvailableListener { targets ->
        if (source != SOURCE_GLANCE || !shown) return@OnTargetsAvailableListener
        val from = session ?: return@OnTargetsAvailableListener
        Log.i(TAG, "At a Glance sent " + targets.joinToString { "${it.featureType}:${it.smartspaceTargetId}" })
        bgExecutor.execute {
            val list = read(targets)
            handler.post { if (session === from) set(list) }
        }
    }

    fun setSource(value: Int) {
        if (value == source) return
        stop()
        source = value
        if (value == SOURCE_GLANCE) startGlance()
    }

    fun setShown(value: Boolean) {
        if (value == shown) return
        shown = value
        session?.let(::notifyShown)
    }

    private fun notifyShown(session: SmartspaceSession) {
        try {
            session.notifySmartspaceEvent(SmartspaceTargetEvent.Builder(
                if (shown) SmartspaceTargetEvent.EVENT_UI_SURFACE_SHOWN
                else SmartspaceTargetEvent.EVENT_UI_SURFACE_HIDDEN).build())
            if (shown) session.requestSmartspaceUpdate()
        } catch (e: Exception) {
            Log.w(TAG, "Smartspace session gone", e)
        }
    }

    private fun startGlance() {
        val manager = context.getSystemService(SmartspaceManager::class.java) ?: return
        try {
            session = manager.createSmartspaceSession(
                SmartspaceConfig.Builder(context, UI_SURFACE).setSmartspaceTargetCount(10).build()
            ).also {
                it.addOnTargetsAvailableListener(
                    Executor { r -> handler.post(r) }, targetsListener)
                // Intelligence only fills a surface it has been told is showing.
                if (shown) notifyShown(it)
            }
        } catch (e: Exception) {
            Log.w(TAG, "No Smartspace session", e)
        }
    }

    private fun stop() {
        session?.let {
            try {
                it.notifySmartspaceEvent(
                    SmartspaceTargetEvent.Builder(SmartspaceTargetEvent.EVENT_UI_SURFACE_HIDDEN).build())
                it.removeOnTargetsAvailableListener(targetsListener)
                it.close()
            } catch (e: Exception) {
                // Gone with the service.
            }
        }
        session = null
        set(emptyList())
    }

    private fun set(list: List<NowBarItem>) {
        if (list.isEmpty() && items.isEmpty()) return
        items = list
        onChanged()
    }

    // At a Glance

    private fun read(targets: List<SmartspaceTarget>): List<NowBarItem> {
        val list = targets.mapNotNull(::glanceItem)
        cards.keys.retainAll(targets.map { it.smartspaceTargetId }.toSet())
        return list
    }

    private fun glanceItem(target: SmartspaceTarget): NowBarItem? {
        if (target.featureType != SmartspaceTarget.FEATURE_SPORTS) return null
        // Every card comes twice; the copy for the always-on display has no logos.
        if (target.smartspaceTargetId.endsWith(AOD_SUFFIX)) return null
        val h2h = target.templateData as? HeadToHeadTemplateData
        val score = (if (h2h != null) headToHead(target, h2h) else extrasScore(target) ?: cardScore(target))
            ?: return null
        val action = target.headerAction ?: target.baseAction
        return item("glance:${target.smartspaceTargetId}", score, action?.let(::intentOf))
    }

    private fun headToHead(target: SmartspaceTarget, h2h: HeadToHeadTemplateData): NowBarScore? {
        val first = h2h.headToHeadFirstCompetitorText?.text?.toString()?.trim() ?: return null
        val second = h2h.headToHeadSecondCompetitorText?.text?.toString()?.trim() ?: return null
        val m1 = NAME_SCORE.matchEntire(first)
        val m2 = NAME_SCORE.matchEntire(second)
        val status = h2h.headToHeadTitle?.text ?: target.headerAction?.subtitle
        val logo1 = h2h.headToHeadFirstCompetitorIcon?.icon?.loadDrawable(context)
        val logo2 = h2h.headToHeadSecondCompetitorIcon?.icon?.loadDrawable(context)
        return if (m1 != null && m2 != null) {
            score(m1.groupValues[1], m2.groupValues[1], m1.groupValues[2], m2.groupValues[2],
                logo1, logo2, status)
        } else {
            // Before the game: names only.
            score(first, second, "", "", logo1, logo2, status)
        }
    }

    private fun extrasScore(target: SmartspaceTarget): NowBarScore? {
        for (action in listOfNotNull(target.headerAction, target.baseAction)) {
            val extras = action.extras ?: continue
            val team1 = extras.getString("firstCompetitorName") ?: continue
            val team2 = extras.getString("secondCompetitorName") ?: continue
            return score(team1, team2, extras.getString("firstCompetitorScore") ?: "",
                extras.getString("secondCompetitorScore") ?: "",
                logo(extras.getParcelable("firstCompetitorLogo", Bitmap::class.java)),
                logo(extras.getParcelable("secondCompetitorLogo", Bitmap::class.java)),
                extras.getString("matchTimeSummary") ?: action.subtitle)
        }
        return null
    }

    private fun cardScore(target: SmartspaceTarget): NowBarScore? {
        val views = target.remoteViews ?: return null
        val id = target.smartspaceTargetId
        val hash = hashOf(views)
        cards[id]?.let { (last, score) -> if (hash != null && hash == last) return score }
        return readCard(views).also { if (hash != null) cards[id] = hash to it }
    }

    private fun hashOf(views: RemoteViews): Int? {
        val parcel = Parcel.obtain()
        return try {
            parcel.pushAllowFds(false)
            views.writeToParcel(parcel, 0)
            parcel.marshall().contentHashCode()
        } catch (e: RuntimeException) {
            null
        } finally {
            parcel.recycle()
        }
    }

    /**
     * Intelligence sends the Pixel card as RemoteViews with empty actions and no template.
     * Read it the way the eye does: league, first team (logo with its abbreviation as the
     * description), its score, the clock, the other score, second team. No scores before
     * kick-off.
     */
    private fun readCard(views: RemoteViews): NowBarScore? {
        val root = try {
            views.apply(context, FrameLayout(context))
        } catch (e: Exception) {
            Log.w(TAG, "Card would not inflate", e)
            return null
        }
        val texts = ArrayList<String>()
        val logos = LinkedHashMap<String, Drawable>()
        fun walk(view: View) {
            if (view.visibility != View.VISIBLE) return
            when (view) {
                is TextView -> view.text?.toString()?.replace(SPACES, " ")?.trim()
                    ?.takeIf { it.isNotEmpty() }?.let(texts::add)
                is ImageView -> {
                    val team = view.contentDescription?.toString()?.trim()
                    val logo = view.drawable
                    if (!team.isNullOrEmpty() && logo != null) logos.putIfAbsent(team, logo)
                }
                is ViewGroup -> for (i in 0 until view.childCount) walk(view.getChildAt(i))
            }
        }
        walk(root)
        if (DEBUG) Log.d(TAG, "Card texts=$texts logos=${logos.keys}")
        // Buttons carry descriptions too; the teams' abbreviations are also written out.
        val teams = logos.keys.filter { it in texts }.takeIf { it.size == 2 } ?: logos.keys.toList()
        val scores = texts.filter(SCORE::matches)
        if (teams.size != 2 || (scores.size != 0 && scores.size != 2)) return null
        val league = texts.firstOrNull()?.takeIf { it !in teams && !SCORE.matches(it) }
        val status = texts.filterNot { it in teams || SCORE.matches(it) || it == league }
            .joinToString(" · ").ifEmpty { league ?: "" }
        return score(teams[0], teams[1], scores.getOrElse(0) { "" }, scores.getOrElse(1) { "" },
            logos[teams[0]], logos[teams[1]], status)
    }

    private fun intentOf(action: SmartspaceAction): PendingIntent? =
        action.pendingIntent ?: action.intent?.let {
            PendingIntent.getActivity(context, 0, it, PendingIntent.FLAG_IMMUTABLE)
        }

    // Both

    private fun score(team1: String, team2: String, score1: String, score2: String,
            logo1: Drawable?, logo2: Drawable?, status: CharSequence?): NowBarScore? {
        val t1 = team1.trim()
        val t2 = team2.trim()
        if (!isTeam(t1) || !isTeam(t2)) return null
        val s1 = score1.trim()
        val s2 = score2.trim()
        // Both scores, or none before the game; never one.
        if ((s1.isEmpty() || s2.isEmpty()) && s1 != s2) return null
        if (s1.isNotEmpty() && (!SCORE.matches(s1) || !SCORE.matches(s2))) return null
        return NowBarScore(t1, t2, s1, s2, logo1, logo2, status?.toString()?.trim())
    }

    private fun item(key: String, score: NowBarScore, intent: PendingIntent?) = NowBarItem(
        key = key,
        type = NowBarType.SPORTS,
        packageName = null,
        icon = context.getDrawable(R.drawable.rr_nowbar_sports),
        art = null,
        title = if (score.score1.isEmpty()) {
            context.getString(R.string.now_bar_versus, score.team1, score.team2)
        } else {
            "${score.team1} ${score.score1} – ${score.score2} ${score.team2}"
        },
        text = score.status,
        contentIntent = intent,
        score = score,
    )

    private fun isTeam(name: String) = name.length in 2..40 && name.any { it.isLetter() }

    private fun logo(bitmap: Bitmap?): Drawable? =
        bitmap?.let { BitmapDrawable(context.resources, it) }

    companion object {
        const val SOURCE_OFF = 0
        const val SOURCE_GLANCE = 1

        private const val TAG = "NowBarScores"
        private val DEBUG = Log.isLoggable(TAG, Log.DEBUG)
        private const val UI_SURFACE = "lockscreen"
        private const val AOD_SUFFIX = "_aod"
        private val NAME_SCORE = Regex("""^(\D{2,40}?)\s+(\d{1,3})$""")
        private val SCORE = Regex("""^\d{1,3}$""")
        private val SPACES = Regex("""\s+""")
    }
}
