/*
 * SPDX-FileCopyrightText: The Resurrected Remix Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.systemui.rr.nowbar

import android.app.appsearch.AppSearchManager
import android.app.appsearch.GenericDocument
import android.app.appsearch.GlobalSearchSession
import android.app.appsearch.SearchSpec
import android.content.Context
import java.util.concurrent.Executor

/**
 * The Google app's own record of each game it follows, kept in AppSearch: what One UI's Now Bar
 * reads, with the line At a Glance's card leaves out ("GOAL!!! ... gives MEX the lead!").
 * [executor] runs the callbacks and must run one task at a time.
 */
class NowBarAppSearch(private val context: Context, private val executor: Executor) {
    private var session: GlobalSearchSession? = null
    private var opening = false
    private val waiting = ArrayList<(GlobalSearchSession?) -> Unit>()

    /** Google's sports documents, or null and why not. */
    fun games(onDone: (List<GenericDocument>?, String?) -> Unit) = withSession { open ->
        if (open == null) {
            onDone(null, "no global search session")
            return@withSession
        }
        val results = try {
            open.search("", SearchSpec.Builder()
                .addFilterPackageNames(GOOGLE_APP)
                .addFilterSchemas(SPORTS_SCHEMA)
                .setResultCountPerPage(PAGE)
                .build())
        } catch (e: RuntimeException) {
            onDone(null, e.toString())
            return@withSession
        }
        results.getNextPage(executor) { page ->
            results.close()
            if (page.isSuccess) {
                onDone(page.resultValue.orEmpty().map { it.genericDocument }, null)
            } else {
                onDone(null, page.errorMessage)
            }
        }
    }

    fun close() = executor.execute {
        session?.close()
        session = null
    }

    private fun withSession(block: (GlobalSearchSession?) -> Unit) = executor.execute {
        session?.let {
            block(it)
            return@execute
        }
        waiting += block
        if (opening) return@execute
        opening = true
        val manager = context.getSystemService(AppSearchManager::class.java)
        if (manager == null) {
            opened(null)
            return@execute
        }
        try {
            manager.createGlobalSearchSession(executor) { result ->
                opened(if (result.isSuccess) result.resultValue else null)
            }
        } catch (e: RuntimeException) {
            opened(null)
        }
    }

    private fun opened(open: GlobalSearchSession?) {
        session = open
        opening = false
        val blocks = waiting.toList()
        waiting.clear()
        blocks.forEach { it(open) }
    }

    companion object {
        const val GOOGLE_APP = "com.google.android.googlequicksearchbox"
        const val SPORTS_SCHEMA = "AmbientDataSchema:SportsScore"
        private const val PAGE = 20

        /** The first non-blank text property called [name], at any depth of [doc]. */
        fun text(doc: GenericDocument, name: String): String? {
            for (property in doc.propertyNames) {
                val value = doc.getProperty(property) as? Array<*> ?: continue
                if (property == name) {
                    (value.firstOrNull() as? String)?.takeIf { it.isNotBlank() }?.let { return it }
                }
                for (child in value) {
                    if (child is GenericDocument) text(child, name)?.let { return it }
                }
            }
            return null
        }
    }
}
