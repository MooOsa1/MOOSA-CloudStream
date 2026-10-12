package com.moosa.hub

import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.DialogFragment
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.lagradost.cloudstream3.APIHolder.apis
import com.lagradost.cloudstream3.ProviderType

class MOOSASettingsDialog : DialogFragment() {

    private fun dp(context: Context, value: Int): Int =
        (value * context.resources.displayMetrics.density).toInt()

    private fun rounded(
        color: Int,
        radiusDp: Int,
        strokeColor: Int? = null,
        strokeDp: Int = 0
    ): GradientDrawable {
        return GradientDrawable().apply {
            setColor(color)
            cornerRadius = dp(requireContext(), radiusDp).toFloat()
            if (strokeColor != null && strokeDp > 0) {
                setStroke(dp(requireContext(), strokeDp), strokeColor)
            }
        }
    }

    private fun title(text: String, size: Float, color: Int, bold: Boolean = false): TextView {
        return TextView(requireContext()).apply {
            this.text = text
            textSize = size
            setTextColor(color)
            if (bold) setTypeface(typeface, android.graphics.Typeface.BOLD)
        }
    }

    private fun section(text: String): TextView {
        return title(text, 13f, Color.rgb(120, 205, 255), true).apply {
            isAllCaps = true
            letterSpacing = 0.08f
            setPadding(0, dp(context, 18), 0, dp(context, 8))
        }
    }

    private fun card(
        icon: String,
        heading: String,
        description: String,
        onClick: () -> Unit
    ): View {
        val context = requireContext()
        val normal = Color.rgb(27, 67, 108)
        val focused = Color.rgb(39, 94, 145)

        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(context, 16), dp(context, 14), dp(context, 14), dp(context, 14))
            isFocusable = true
            isClickable = true
            background = rounded(normal, 14, Color.argb(45, 255, 255, 255), 1)
            setOnClickListener { onClick() }
            setOnFocusChangeListener { view, hasFocus ->
                view.background = if (hasFocus) {
                    rounded(focused, 14, Color.WHITE, 2)
                } else {
                    rounded(normal, 14, Color.argb(45, 255, 255, 255), 1)
                }
            }
        }

        val iconBox = TextView(context).apply {
            text = icon
            textSize = 24f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            background = rounded(Color.rgb(29, 128, 189), 999)
        }
        row.addView(
            iconBox,
            LinearLayout.LayoutParams(dp(context, 58), dp(context, 58)).apply {
                marginEnd = dp(context, 16)
            }
        )

        val textHolder = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
        }
        textHolder.addView(
            title(heading, 18f, Color.WHITE, true),
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )
        textHolder.addView(
            title(description, 13f, Color.rgb(199, 220, 238), false).apply {
                setPadding(0, dp(context, 3), 0, 0)
            },
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )

        row.addView(
            textHolder,
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        )

        row.addView(
            title("›", 36f, Color.WHITE, false).apply {
                gravity = Gravity.CENTER
            },
            LinearLayout.LayoutParams(dp(context, 34), ViewGroup.LayoutParams.MATCH_PARENT)
        )

        return row
    }

    private fun addCard(container: LinearLayout, view: View) {
        container.addView(
            view,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = dp(requireContext(), 10)
            }
        )
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val context = requireContext()

        val root = FrameLayout(context).apply {
            setPadding(dp(context, 18), dp(context, 18), dp(context, 18), dp(context, 18))
            background = rounded(
                Color.rgb(12, 42, 76),
                20,
                Color.rgb(116, 185, 241),
                2
            )
        }

        val scroll = ScrollView(context).apply {
            isFillViewport = true
            clipToPadding = false
        }
        val content = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(context, 10), dp(context, 6), dp(context, 10), dp(context, 24))
        }
        scroll.addView(
            content,
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )
        root.addView(
            scroll,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )

        content.addView(title("MOOSA HUB", 13f, Color.rgb(146, 218, 255), true))
        content.addView(
            title("Settings", 31f, Color.WHITE, true).apply {
                setPadding(0, dp(context, 2), 0, dp(context, 12))
            }
        )
        content.addView(
            View(context).apply {
                setBackgroundColor(Color.argb(90, 179, 223, 255))
            },
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(context, 1)
            ).apply {
                bottomMargin = dp(context, 8)
            }
        )

        content.addView(section("PERFORMANCE"))
        addCard(
            content,
            card(
                "◴",
                "Performance Settings",
                "Adjust simultaneous provider task count"
            ) {
                openPerformance()
            }
        )

        content.addView(section("SOURCES"))
        addCard(
            content,
            card(
                "☰",
                "Select / Test Sources",
                "Enable, disable, discover and test all sources"
            ) {
                openSources()
            }
        )
        addCard(
            content,
            card(
                "◉",
                "Server Manager",
                "Disable or test servers found during playback"
            ) {
                openServerManager()
            }
        )
        addCard(
            content,
            card(
                "●",
                "Toggle Extensions",
                "Control link verification and Arabic fast mode"
            ) {
                openExtensions()
            }
        )

        content.addView(section("SUBTITLES"))
        addCard(
            content,
            card(
                "ع",
                "Arabic Subtitles",
                "Arabic loads first; English follows afterwards"
            ) {
                openArabicSettings()
            }
        )

        content.addView(section("INTEGRATIONS"))
        addCard(
            content,
            card(
                "⚿",
                "Wyzie Key",
                "Add your Wyzie subtitle provider access key"
            ) {
                openWyzieKey()
            }
        )
        addCard(
            content,
            card(
                "S",
                "SubDL Key",
                "Add SubDL as an Arabic and English subtitle source"
            ) {
                openSubdlKey()
            }
        )

        content.addView(section("MAINTENANCE"))
        addCard(
            content,
            card(
                "↻",
                "Reload Settings",
                "Close this page and apply the latest preferences"
            ) {
                dismiss()
            }
        )

        return Dialog(context).apply {
            requestWindowFeature(Window.FEATURE_NO_TITLE)
            setContentView(root)
            setCanceledOnTouchOutside(true)
        }
    }

    override fun onStart() {
        super.onStart()
        val metrics = resources.displayMetrics
        val width = (metrics.widthPixels * 0.92f).toInt()
        val height = (metrics.heightPixels * 0.92f).toInt()
        dialog?.window?.apply {
            setLayout(width, height)
            setBackgroundDrawableResource(android.R.color.transparent)
        }
    }

    private fun openPerformance() {
        val values = intArrayOf(4, 6, 8, 10, 12, 16)
        val labels = values.map { "$it simultaneous providers" }.toTypedArray()
        val current = MOOSASettingsStore.providerConcurrency()
        val selected = values.indexOf(current).takeIf { it >= 0 } ?: 2

        AlertDialog.Builder(requireContext())
            .setTitle("Performance Settings")
            .setSingleChoiceItems(labels, selected) { dialog, which ->
                MOOSASettingsStore.setProviderConcurrency(values[which])
                dialog.dismiss()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun openSources() {
        MOOSASourceTesterDialog.show(requireContext())
    }

    private fun openServerManager() {
        val entries = MOOSAServerRegistry.snapshot()

        if (entries.isEmpty()) {
            AlertDialog.Builder(requireContext())
                .setTitle("Server Manager")
                .setMessage(
                    "No servers have been scanned yet. Play a movie or episode once, " +
                        "then come back here to test or disable individual servers."
                )
                .setPositiveButton("OK", null)
                .show()
            return
        }

        val labels = entries.map { entry ->
            val status = when (entry.lastResult) {
                true -> "✓"
                false -> "✕"
                null -> "?"
            }
            val disabled = if (MOOSAServerRegistry.isDisabled(entry.key)) {
                " • Disabled"
            } else {
                ""
            }
            "$status  ${entry.label}$disabled"
        }.toTypedArray()

        AlertDialog.Builder(requireContext())
            .setTitle("Server Manager")
            .setItems(labels) { _, which ->
                openServerActions(entries[which])
            }
            .setNegativeButton("Close", null)
            .show()
    }

    private fun openServerActions(entry: MOOSAServerRegistry.Entry) {
        val disabled = MOOSAServerRegistry.isDisabled(entry.key)
        val state = when (entry.lastResult) {
            true -> "Working"
            false -> "Failed"
            null -> "Not tested"
        }

        val dialog = AlertDialog.Builder(requireContext())
            .setTitle(entry.label)
            .setMessage(
                "Status: $state\n" +
                    "Playback: ${if (disabled) "Disabled" else "Enabled"}"
            )
            .setPositiveButton("Test", null)
            .setNeutralButton(if (disabled) "Enable" else "Disable") { _, _ ->
                MOOSAServerRegistry.setDisabled(entry.key, !disabled)
                openServerManager()
            }
            .setNegativeButton("Back") { _, _ ->
                openServerManager()
            }
            .create()

        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val button = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
                button.isEnabled = false
                button.text = "Testing…"

                lifecycleScope.launch {
                    val result = withContext(Dispatchers.IO) {
                        MOOSAServerRegistry.test(entry.key)
                    }

                    button.isEnabled = true
                    button.text = "Test"

                    AlertDialog.Builder(requireContext())
                        .setTitle(entry.label)
                        .setMessage(
                            when (result) {
                                true -> "Server is working ✓"
                                false -> "Server test failed ✕"
                                null -> "Server is no longer available for testing."
                            }
                        )
                        .setPositiveButton("OK", null)
                        .show()
                }
            }
        }

        dialog.show()
    }

    private fun openExtensions() {
        val labels = arrayOf(
            "Verify server links before showing them",
            "Fast Arabic subtitle mode"
        )
        val checked = booleanArrayOf(
            MOOSASettingsStore.verifyLinks(),
            MOOSASettingsStore.arabicFastMode()
        )

        AlertDialog.Builder(requireContext())
            .setTitle("Toggle Extensions")
            .setMultiChoiceItems(labels, checked) { _, which, enabled ->
                checked[which] = enabled
            }
            .setPositiveButton("Save") { _, _ ->
                MOOSASettingsStore.setVerifyLinks(checked[0])
                MOOSASettingsStore.setArabicFastMode(checked[1])
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun openArabicSettings() {
        AlertDialog.Builder(requireContext())
            .setTitle("Arabic Subtitles")
            .setMessage(
                "Arabic subtitles are requested immediately when you press Play. " +
                    "They are emitted before English subtitles whenever a source responds."
            )
            .setPositiveButton("OK", null)
            .show()
    }

    private fun openWyzieKey() {
        val input = EditText(requireContext()).apply {
            hint = "Wyzie key"
            setSingleLine(true)
            setText(MOOSASettingsStore.wyzieKey().orEmpty())
            setPadding(
                dp(context, 18),
                dp(context, 14),
                dp(context, 18),
                dp(context, 14)
            )
        }

        AlertDialog.Builder(requireContext())
            .setTitle("Wyzie Key")
            .setMessage("Optional. Used as an extra subtitle source when configured.")
            .setView(input)
            .setPositiveButton("Save") { _, _ ->
                MOOSASettingsStore.setWyzieKey(input.text?.toString())
            }
            .setNeutralButton("Clear") { _, _ ->
                MOOSASettingsStore.setWyzieKey(null)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun openSubdlKey() {
        val input = EditText(requireContext()).apply {
            hint = "SubDL API key"
            setSingleLine(true)
            setText(MOOSASettingsStore.subdlKey().orEmpty())
            setPadding(
                dp(context, 18),
                dp(context, 14),
                dp(context, 18),
                dp(context, 14)
            )
        }

        AlertDialog.Builder(requireContext())
            .setTitle("SubDL Key")
            .setMessage(
                "Optional. Add a SubDL API key to search Arabic and English subtitles."
            )
            .setView(input)
            .setPositiveButton("Save") { _, _ ->
                MOOSASettingsStore.setSubdlKey(input.text?.toString())
            }
            .setNeutralButton("Clear") { _, _ ->
                MOOSASettingsStore.setSubdlKey(null)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    companion object {
        fun show(context: Context) {
            val activity = context as? AppCompatActivity ?: return
            if (activity.isFinishing || activity.isDestroyed) return

            MOOSASettingsDialog().show(
                activity.supportFragmentManager,
                "moosa_hub_settings"
            )
        }
    }
}
