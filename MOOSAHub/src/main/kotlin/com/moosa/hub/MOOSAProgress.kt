package com.moosa.hub

import android.app.Activity
import android.app.Dialog
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
    private const val CONTAINER_TAG = "moosa_server_progress_container"
    private const val BAR_TAG = "moosa_server_progress_bar"
    private const val TEXT_TAG = "moosa_server_progress_text"
    private const val FALLBACK_TAG = "moosa_server_progress_fallback"

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
        update(4, "Checking servers • 0/$total • 0 verified")
    }

    fun providerProgress(done: Int, total: Int, verified: Int) {
        val safeTotal = total.coerceAtLeast(1)
        val checked = done.coerceIn(0, safeTotal)
        val percent = (
            5.0 + (checked.toDouble() / safeTotal) * 82.0
            ).toInt().coerceIn(5, 87)
        update(
            percent,
            "Checking servers • $checked/$safeTotal • $verified verified"
        )
    }

    fun serversReady(verified: Int) {
        update(91, "$verified verified servers ready")
    }

    fun subtitles() {
        update(96, "Preparing Arabic subtitles…")
    }

    fun finish(verified: Int) {
        update(100, "Ready • $verified verified servers")
        mainHandler.postDelayed({ hide() }, 650)
    }

    private fun update(percent: Int, text: String) {
        lastPercent = percent.coerceIn(0, 100)
        lastText = text
        mainHandler.post { render(retries = 10) }
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

    private fun render(retries: Int) {
        val act = currentActivity()
        if (act == null) {
            retry(retries)
            return
        }

        // Update every visible CloudStream loading surface. Previously the mere
        // existence of player_loading_overlay short-circuited rendering even
        // after that overlay became hidden, which could leave the visible
        // progress UI frozen at 87%.
        val playerRendered = renderPlayerOverlay(act)
        val dialogRendered = renderResultLoadingDialog(act)
        val primaryRendered = playerRendered || dialogRendered

        val rendered = if (primaryRendered) {
            hideFallback(act)
            true
        } else {
            renderActivityFallback(act)
        }

        if (!rendered) retry(retries)
    }

    private fun retry(retries: Int) {
        if (retries > 0) {
            mainHandler.postDelayed({ render(retries - 1) }, 120)
        }
    }

    private fun renderPlayerOverlay(act: Activity): Boolean {
        return try {
            val overlayId = act.resources.getIdentifier(
                "player_loading_overlay",
                "id",
                act.packageName
            )
            if (overlayId == 0) return false

            val overlay = act.findViewById<ViewGroup>(overlayId) ?: return false
            if (
                overlay.visibility != View.VISIBLE ||
                !overlay.isShown
            ) {
                return false
            }

            val container = findOrCreateContainer(overlay, act)
            applyProgress(container)
            true
        } catch (_: Throwable) {
            false
        }
    }

    private fun renderActivityFallback(act: Activity): Boolean {
        return try {
            val root = act.findViewById<ViewGroup>(android.R.id.content) ?: return false

            var container = root.findViewWithTag<LinearLayout>(FALLBACK_TAG)
            if (container == null) {
                container = LinearLayout(act).apply {
                    tag = FALLBACK_TAG
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.CENTER
                    setPadding(
                        dp(act, 24),
                        dp(act, 12),
                        dp(act, 24),
                        dp(act, 12)
                    )
                    setBackgroundColor(0xE61A1A1A.toInt())
                    elevation = dp(act, 8).toFloat()
                }

                val label = TextView(act).apply {
                    tag = TEXT_TAG
                    gravity = Gravity.CENTER
                    textSize = 15f
                    setTextColor(0xFFFFFFFF.toInt())
                    setPadding(0, 0, 0, dp(act, 8))
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
                        dp(act, 10)
                    )
                )

                if (root is FrameLayout) {
                    root.addView(
                        container,
                        FrameLayout.LayoutParams(
                            FrameLayout.LayoutParams.MATCH_PARENT,
                            FrameLayout.LayoutParams.WRAP_CONTENT,
                            Gravity.BOTTOM
                        ).apply {
                            leftMargin = dp(act, 36)
                            rightMargin = dp(act, 36)
                            bottomMargin = dp(act, 32)
                        }
                    )
                } else {
                    root.addView(
                        container,
                        ViewGroup.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT
                        )
                    )
                }
            }

            applyProgress(container)
            true
        } catch (_: Throwable) {
            false
        }
    }

    private fun findOrCreateContainer(parent: ViewGroup, act: Activity): LinearLayout {
        parent.findViewWithTag<LinearLayout>(CONTAINER_TAG)?.let { return it }

        val container = LinearLayout(act).apply {
            tag = CONTAINER_TAG
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(act, 28), dp(act, 8), dp(act, 28), dp(act, 8))
        }

        val label = TextView(act).apply {
            tag = TEXT_TAG
            gravity = Gravity.CENTER
            textSize = 15f
            setTextColor(0xFFFFFFFF.toInt())
            setPadding(0, 0, 0, dp(act, 8))
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
                dp(act, 10)
            )
        )

        val params = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.CENTER
        ).apply {
            leftMargin = dp(act, 70)
            rightMargin = dp(act, 70)
            topMargin = dp(act, 115)
        }

        parent.addView(container, params)
        return container
    }

    private fun renderResultLoadingDialog(act: Activity): Boolean {
        val dialog = findLoadingDialog(act) ?: return false

        return try {
            val progressId = act.resources.getIdentifier("progressBar", "id", act.packageName)
            if (progressId == 0) return false

            val progress = dialog.findViewById<ProgressBar>(progressId) ?: return false
            progress.visibility = View.VISIBLE
            progress.isIndeterminate = false
            progress.max = 100
            progress.progress = lastPercent

            val parent = progress.parent as? LinearLayout ?: return true
            var label = parent.findViewWithTag<TextView>(TEXT_TAG)
            if (label == null) {
                label = TextView(act).apply {
                    tag = TEXT_TAG
                    gravity = Gravity.CENTER
                    textSize = 14f
                    setPadding(dp(act, 12), dp(act, 10), dp(act, 12), dp(act, 6))
                }
                val index = parent.indexOfChild(progress).coerceAtLeast(0)
                parent.addView(
                    label,
                    index,
                    LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    )
                )
            }
            label.text = "$lastText • $lastPercent%"
            label.visibility = View.VISIBLE
            true
        } catch (_: Throwable) {
            false
        }
    }

    private fun applyProgress(container: LinearLayout) {
        container.visibility = View.VISIBLE
        container.findViewWithTag<TextView>(TEXT_TAG)?.text =
            "$lastText • $lastPercent%"
        container.findViewWithTag<ProgressBar>(BAR_TAG)?.apply {
            isIndeterminate = false
            max = 100
            progress = lastPercent
            visibility = View.VISIBLE
        }
    }

    private fun hide() {
        val act = currentActivity() ?: return
        try {
            val overlayId = act.resources.getIdentifier(
                "player_loading_overlay",
                "id",
                act.packageName
            )
            if (overlayId != 0) {
                act.findViewById<ViewGroup>(overlayId)
                    ?.findViewWithTag<View>(CONTAINER_TAG)
                    ?.visibility = View.GONE
            }
        } catch (_: Throwable) {
        }

        hideFallback(act)
    }

    private fun hideFallback(act: Activity) {
        try {
            act.findViewById<ViewGroup>(android.R.id.content)
                ?.findViewWithTag<View>(FALLBACK_TAG)
                ?.visibility = View.GONE
        } catch (_: Throwable) {
        }
    }

    private fun findLoadingDialog(host: Any): Dialog? {
        return try {
            val manager = host.javaClass.methods
                .firstOrNull {
                    it.name == "getSupportFragmentManager" &&
                        it.parameterCount == 0
                }
                ?.invoke(host)
            findDialogInManager(manager)
        } catch (_: Throwable) {
            null
        }
    }

    private fun findDialogInManager(manager: Any?): Dialog? {
        if (manager == null) return null

        val fragments = try {
            manager.javaClass.methods
                .firstOrNull {
                    it.name == "getFragments" &&
                        it.parameterCount == 0
                }
                ?.invoke(manager) as? List<*>
        } catch (_: Throwable) {
            null
        } ?: return null

        for (fragment in fragments.asReversed()) {
            if (fragment == null) continue

            try {
                val getter = fragment.javaClass.methods.firstOrNull {
                    it.name == "getLoadingDialog" && it.parameterCount == 0
                }
                val dialog = getter?.invoke(fragment) as? Dialog
                if (dialog?.isShowing == true) return dialog
            } catch (_: Throwable) {
            }

            try {
                val field = generateSequence(fragment.javaClass) { it.superclass }
                    .mapNotNull { cls ->
                        cls.declaredFields.firstOrNull { it.name == "loadingDialog" }
                    }
                    .firstOrNull()

                if (field != null) {
                    field.isAccessible = true
                    val dialog = field.get(fragment) as? Dialog
                    if (dialog?.isShowing == true) return dialog
                }
            } catch (_: Throwable) {
            }

            try {
                val childManager = fragment.javaClass.methods
                    .firstOrNull {
                        it.name == "getChildFragmentManager" &&
                            it.parameterCount == 0
                    }
                    ?.invoke(fragment)
                findDialogInManager(childManager)?.let { return it }
            } catch (_: Throwable) {
            }
        }

        return null
    }

    private fun dp(act: Activity, value: Int): Int =
        (value * act.resources.displayMetrics.density).toInt()
}
