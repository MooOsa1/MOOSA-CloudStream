package com.moosa.hub

import android.app.Activity
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView

object MOOSAProgress {
    private const val CONTAINER_TAG = "moosa_progress_container"
    private const val TEXT_TAG = "moosa_progress_text"
    private const val BAR_TAG = "moosa_progress_bar"
    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile
    private var lastPercent: Int = 0

    @Volatile
    private var lastText: String = ""

    fun setArabicSubtitleDefault() {
        try {
            val appClass = Class.forName("com.lagradost.cloudstream3.CloudStreamApp")
            val companion = appClass.getField("Companion").get(null)
            val method = companion.javaClass.methods.firstOrNull {
                it.name == "setKey" && it.parameterCount == 2
            } ?: return
            method.invoke(companion, "subs_auto_select", "ar")
        } catch (_: Throwable) {
        }
    }

    fun start(totalProviders: Int) {
        val total = totalProviders.coerceAtLeast(1)
        update(0, "جاري تجهيز السيرفرات • باقي $total")
    }

    fun providerProgress(done: Int, total: Int) {
        val safeTotal = total.coerceAtLeast(1)
        val percent = ((done.toDouble() / safeTotal) * 92.0).toInt().coerceIn(0, 92)
        val remaining = (safeTotal - done).coerceAtLeast(0)
        update(percent, "فحص السيرفرات • باقي $remaining من $safeTotal")
    }

    fun subtitles() {
        update(96, "تجهيز الترجمة العربية…")
    }

    fun finish() {
        update(100, "جاهز")
    }

    private fun update(percent: Int, text: String) {
        lastPercent = percent.coerceIn(0, 100)
        lastText = text
        mainHandler.post { render(8) }
    }

    private fun currentActivity(): Activity? {
        return try {
            val cls = Class.forName("com.lagradost.cloudstream3.CommonActivity")
            val instance = cls.getField("INSTANCE").get(null)
            cls.methods
                .firstOrNull { it.name == "getActivity" && it.parameterCount == 0 }
                ?.invoke(instance) as? Activity
        } catch (_: Throwable) {
            null
        }
    }

    private fun Activity.dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    private fun render(retries: Int) {
        val act = currentActivity()
        if (act == null) {
            if (retries > 0) mainHandler.postDelayed({ render(retries - 1) }, 100)
            return
        }

        try {
            val overlayId = act.resources.getIdentifier(
                "player_loading_overlay",
                "id",
                act.packageName
            )
            val overlay = if (overlayId != 0) {
                act.window.decorView.findViewById<ViewGroup>(overlayId)
            } else null

            if (overlay != null && overlay.visibility == View.VISIBLE) {
                renderPlayerOverlay(act, overlay)
                return
            }

            if (retries > 0) {
                mainHandler.postDelayed({ render(retries - 1) }, 120)
            }
        } catch (_: Throwable) {
            if (retries > 0) mainHandler.postDelayed({ render(retries - 1) }, 120)
        }
    }

    private fun renderPlayerOverlay(act: Activity, overlay: ViewGroup) {
        val mainLoadId = act.resources.getIdentifier("main_load", "id", act.packageName)
        if (mainLoadId != 0) {
            overlay.findViewById<View>(mainLoadId)?.visibility = View.GONE
        }

        var container = overlay.findViewWithTag<LinearLayout>(CONTAINER_TAG)
        if (container == null) {
            container = LinearLayout(act).apply {
                tag = CONTAINER_TAG
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setPadding(act.dp(32), act.dp(10), act.dp(32), act.dp(10))
            }

            val label = TextView(act).apply {
                tag = TEXT_TAG
                gravity = Gravity.CENTER
                textSize = 15f
                setPadding(0, 0, 0, act.dp(10))
            }

            val bar = ProgressBar(
                act,
                null,
                android.R.attr.progressBarStyleHorizontal
            ).apply {
                tag = BAR_TAG
                isIndeterminate = false
                max = 100
            }

            container.addView(
                label,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            )
            container.addView(
                bar,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    act.dp(14)
                )
            )

            val params = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER
            ).apply {
                leftMargin = act.dp(90)
                rightMargin = act.dp(90)
            }
            overlay.addView(container, params)
        }

        container.findViewWithTag<TextView>(TEXT_TAG)?.text =
            "$lastText • $lastPercent%"
        container.findViewWithTag<ProgressBar>(BAR_TAG)?.progress = lastPercent
        container.visibility = View.VISIBLE
    }
}
