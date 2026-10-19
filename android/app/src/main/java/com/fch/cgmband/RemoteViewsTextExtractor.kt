package com.fch.cgmband

import android.app.Notification
import android.content.Context
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView

/**
 * The iCan notification uses a custom RemoteViews layout, so its glucose value is not
 * in the standard extras. Inflate the layout (with the CGM app's own resources) and
 * collect every TextView's text keyed by its resource entry name, e.g. "tv_cgm_value".
 */
object RemoteViewsTextExtractor {

    private const val TAG = "CgmExtractor"

    @Suppress("DEPRECATION") // contentView / bigContentView are still populated for custom layouts.
    fun extract(context: Context, notification: Notification): Map<String, String> {
        val big = notification.bigContentView?.let { inflateTexts(context, it) }.orEmpty()
        val small = notification.contentView?.let { inflateTexts(context, it) }.orEmpty()
        return extrasTexts(notification) + big + small
    }

    private fun inflateTexts(context: Context, views: android.widget.RemoteViews): Map<String, String> =
        try {
            collect(views.apply(context, FrameLayout(context)))
        } catch (e: RuntimeException) {
            Log.w(TAG, "Failed to inflate CGM notification layout", e)
            emptyMap()
        }

    /** Hidden views (and everything inside hidden containers) may hold stale text, so skip them. */
    private fun collect(view: View): Map<String, String> {
        if (view.visibility != View.VISIBLE) return emptyMap()
        val own = textOf(view)
        if (view !is ViewGroup) return own
        return (0 until view.childCount).fold(own) { acc, i -> acc + collect(view.getChildAt(i)) }
    }

    private fun textOf(view: View): Map<String, String> {
        if (view !is TextView || view.id == View.NO_ID) return emptyMap()
        val name = runCatching { view.resources.getResourceEntryName(view.id) }.getOrNull() ?: return emptyMap()
        return mapOf(name to view.text.toString())
    }

    private fun extrasTexts(notification: Notification): Map<String, String> {
        val extras = notification.extras ?: return emptyMap()
        return listOf(Notification.EXTRA_TITLE, Notification.EXTRA_TEXT)
            .mapNotNull { key -> extras.getCharSequence(key)?.toString()?.let { key to it } }
            .toMap()
    }
}
