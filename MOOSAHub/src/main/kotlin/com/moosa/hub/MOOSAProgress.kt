package com.moosa.hub

import android.app.Activity
import android.app.Dialog
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView

object MOOSAProgress {
    private const val TEXT_TAG = "moosa_server_progress_text"
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
        update(0, "جاري البحث عن السيرفرات • باقي $total")
    }

    fun searchProgress(done: Int, total: Int) {
        val safeTotal = total.coerceAtLeast(1)
        val percent = ((done.toDouble() / safeTotal) * 65.0).toInt().coerceIn(0, 65)
        val remaining = (safeTotal - done).coerceAtLeast(0)
        update(percent, "البحث عن السيرفرات • باقي $remaining من $safeTotal")
    }

    fun linksProgress(done: Int, total: Int) {
        val safeTotal = total.coerceAtLeast(1)
        val percent = (65 + ((done.toDouble() / safeTotal) * 30.0).toInt()).coerceIn(65, 95)
        val remaining = (safeTotal - done).coerceAtLeast(0)
        update(percent, "تجهيز روابط التشغيل • باقي $remaining من $safeTotal")
    }

    fun subtitles() {
        update(97, "اختيار أفضل ترجمة عربية…")
    }

    fun finish() {
        update(100, "جاهز")
    }

    private fun update(percent: Int, text: String) {
        lastPercent = percent.coerceIn(0, 100)
        lastText = text
        mainHandler.post { render(retries = 6) }
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
            if (retries > 0) mainHandler.postDelayed({ render(retries - 1) }, 120)
            return
        }

        val dialog = findLoadingDialog(act)
        if (dialog == null) {
            if (retries > 0) mainHandler.postDelayed({ render(retries - 1) }, 120)
            return
        }

        try {
            val progressId = act.resources.getIdentifier("progressBar", "id", act.packageName)
            if (progressId == 0) return

            val progress = dialog.findViewById<ProgressBar>(progressId) ?: return
            progress.visibility = View.VISIBLE
            progress.isIndeterminate = false
            progress.max = 100
            progress.progress = lastPercent

            val parent = progress.parent as? LinearLayout ?: return
            var label = parent.findViewWithTag<TextView>(TEXT_TAG)
            if (label == null) {
                label = TextView(act).apply {
                    tag = TEXT_TAG
                    gravity = Gravity.CENTER
                    textSize = 14f
                    setPadding(12, 10, 12, 6)
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
        } catch (_: Throwable) {
        }
    }

    private fun findLoadingDialog(host: Any): Dialog? {
        return try {
            val manager = host.javaClass.methods
                .firstOrNull { it.name == "getSupportFragmentManager" && it.parameterCount == 0 }
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
                .firstOrNull { it.name == "getFragments" && it.parameterCount == 0 }
                ?.invoke(manager) as? List<*>
        } catch (_: Throwable) {
            null
        } ?: return null

        for (fragment in fragments.asReversed()) {
            if (fragment == null) continue

            try {
                val dialog = fragment.javaClass.methods
                    .firstOrNull { it.name == "getLoadingDialog" && it.parameterCount == 0 }
                    ?.invoke(fragment) as? Dialog
                if (dialog?.isShowing == true) return dialog
            } catch (_: Throwable) {
            }

            try {
                val childManager = fragment.javaClass.methods
                    .firstOrNull { it.name == "getChildFragmentManager" && it.parameterCount == 0 }
                    ?.invoke(fragment)
                findDialogInManager(childManager)?.let { return it }
            } catch (_: Throwable) {
            }
        }

        return null
    }
}
