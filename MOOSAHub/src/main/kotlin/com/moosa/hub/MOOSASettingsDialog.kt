package com.moosa.hub

import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.DialogFragment
import java.util.Locale

/**
 * MOOSA Control Center: a single native TV dialog with one left navigation
 * rail and an in-place details surface. No grid of cards, blue tint,
 * focus-driven scaling, nested windows for mere section selection, or web
 * artwork. Existing source/servers/quality/subtitle tools remain available.
 */
class MOOSASettingsDialog : DialogFragment() {
    private val bg = Color.rgb(5, 5, 5)
    private val railBg = Color.rgb(10, 10, 10)
    private val surface = Color.rgb(23, 23, 23)
    private val primary = Color.rgb(242, 242, 242)
    private val secondary = Color.rgb(158, 158, 158)
    private val quiet = Color.rgb(113, 113, 113)
    private val border = Color.rgb(43, 43, 43)

    private val sectionNames = arrayOf(
        "Sources", "Servers", "Playback", "Performance", "Subtitles", "Advanced"
    )
    private val navViews = mutableListOf<TextView>()
    private lateinit var detailColumn: LinearLayout
    private lateinit var closeControl: TextView
    private var actionControl: TextView? = null
    private var selectedSection = 0
    private var initialFocusApplied = false

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    private fun rounded(
        color: Int,
        radius: Int,
        stroke: Int? = null,
        strokeDp: Int = 1
    ): GradientDrawable = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(radius).toFloat()
        stroke?.let { setStroke(dp(strokeDp), it) }
    }

    private fun text(
        value: String,
        size: Float,
        color: Int = primary,
        bold: Boolean = false
    ): TextView = TextView(requireContext()).apply {
        text = value
        textSize = size
        setTextColor(color)
        includeFontPadding = true
        if (bold) setTypeface(typeface, Typeface.BOLD)
    }

    private fun line(): View = View(requireContext()).apply {
        setBackgroundColor(border)
    }

    private fun LinearLayout.addLine() {
        addView(line(), LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(1)
        ))
    }

    private fun marginBottom(amount: Int) =
        LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = dp(amount) }

    private fun navigationItem(index: Int): TextView =
        text(sectionNames[index], if (index == 3) 14.5f else 15f).apply {
            id = View.generateViewId()
            gravity = Gravity.CENTER_VERTICAL
            minHeight = dp(52)
            setPadding(dp(15), dp(8), dp(9), dp(8))
            isFocusable = true
            isClickable = true
            isFocusableInTouchMode = false
            setSingleLine(true)
            ellipsize = android.text.TextUtils.TruncateAt.END
            setOnFocusChangeListener { _, focused ->
                if (focused) showSection(index)
            }
            setOnClickListener {
                showSection(index)
                actionControl?.requestFocus()
            }
        }

    private fun paintNavigation() {
        navViews.forEachIndexed { index, view ->
            val active = selectedSection == index
            view.setTextColor(if (active) Color.rgb(8, 8, 8) else secondary)
            view.setTypeface(null, if (active) Typeface.BOLD else Typeface.NORMAL)
            view.background = if (active) rounded(primary, 5)
                else rounded(Color.TRANSPARENT, 5)
            view.contentDescription = sectionNames[index] +
                if (active) ", selected" else ""
        }
    }

    private fun addDetailHeading(
        code: String,
        title: String,
        description: String
    ) {
        detailColumn.addView(
            text(code, 10.5f, secondary).apply { letterSpacing = 0.15f },
            marginBottom(9)
        )
        detailColumn.addView(
            text(title, 42f, primary, true).apply {
                letterSpacing = -0.045f
                // Never let a long user-display title disappear off the TV.
                maxLines = 2
            },
            marginBottom(10)
        )
        detailColumn.addView(
            text(description, 13.5f, secondary).apply {
                maxLines = 4
            },
            marginBottom(21)
        )
    }

    private fun addMetric(value: String, caption: String) {
        val row = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.BOTTOM
        }
        row.addView(
            text(value, 48f, primary, true).apply {
                letterSpacing = -0.065f
                maxLines = 1
                // Avoid API-26-only autosize calls on Fire TV API 25.
            },
            LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f
            )
        )
        row.addView(text(caption, 12f, secondary).apply {
            gravity = Gravity.BOTTOM or Gravity.END
            maxLines = 2
            setPadding(dp(6), 0, 0, dp(7))
        }, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ))
        detailColumn.addView(row, marginBottom(19))
    }

    private fun addCoverage(enabled: Int, total: Int) {
        val amount = total.coerceIn(1, 8)
        val filled = if (total == 0) 0
            else ((enabled * amount + total / 2) / total).coerceIn(0, amount)
        val bar = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            weightSum = amount.toFloat()
        }
        repeat(amount) { i ->
            bar.addView(View(requireContext()).apply {
                background = rounded(
                    if (i < filled) Color.rgb(227, 227, 227)
                    else Color.rgb(57, 57, 57), 2
                )
            }, LinearLayout.LayoutParams(
                0, dp(5), 1f
            ).apply {
                if (i < amount - 1) marginEnd = dp(7)
            })
        }
        detailColumn.addView(bar, marginBottom(25))
    }

    private fun addDetailRow(title: String, value: String) {
        val row = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(13), 0, dp(13))
        }
        row.addView(text(title, 13f, secondary).apply {
            maxLines = 2
        }, LinearLayout.LayoutParams(
            0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f
        ))
        row.addView(text(value, 13f, primary, true).apply {
            gravity = Gravity.END
            maxLines = 2
        }, LinearLayout.LayoutParams(
            0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f
        ))
        detailColumn.addLine()
        detailColumn.addView(row)
    }

    private fun detailAction(label: String, onClick: () -> Unit) {
        // Flexible spacer keeps the action near the bottom at 1080p, and
        // ScrollView lets all content remain reachable at 720p.
        detailColumn.addView(View(requireContext()), LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
        ))
        val button = text(label + "   ↗", 14f, Color.rgb(9, 9, 9), true).apply {
            id = View.generateViewId()
            minHeight = dp(54)
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(19), dp(12), dp(19), dp(12))
            background = rounded(Color.rgb(235, 235, 235), 5)
            isClickable = true
            isFocusable = true
            isFocusableInTouchMode = false
            setOnClickListener { onClick() }
            setOnFocusChangeListener { view, focused ->
                view.background = rounded(
                    if (focused) Color.WHITE else Color.rgb(235, 235, 235),
                    5,
                    if (focused) Color.WHITE else null
                )
            }
        }
        detailColumn.addView(button, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ))
        actionControl = button
        val selectedNav = navViews.getOrNull(selectedSection) ?: return
        button.nextFocusLeftId = selectedNav.id
        button.nextFocusDownId = button.id
        button.nextFocusUpId = closeControl.id
        navViews.forEach { it.nextFocusRightId = button.id }
        closeControl.nextFocusDownId = selectedNav.id
    }

    private fun showSection(index: Int) {
        if (!::detailColumn.isInitialized || index !in sectionNames.indices) return
        selectedSection = index
        paintNavigation()
        detailColumn.removeAllViews()
        actionControl = null
        when (index) {
            0 -> {
                val all = MOOSASourceTester.sources()
                val enabled = all.count { MOOSASourceTester.isEnabled(it) }
                addDetailHeading("01 / CONTENT ENGINE", "Sources",
                    "Manage which installed providers are used for content discovery.")
                addMetric(String.format(Locale.US, "%02d/%02d", enabled, all.size),
                    "Sources enabled")
                addCoverage(enabled, all.size)
                addDetailRow("Discovery",
                    if (enabled == all.size && all.isNotEmpty()) "All enabled sources"
                    else enabled.toString() + " enabled")
                addDetailRow("Server scan",
                    if (MOOSAServerRegistry.snapshot().isEmpty()) "Not started"
                    else "Results available")
                detailAction("Manage sources") {
                    MOOSASourceTesterDialog.show(requireContext())
                }
            }
            1 -> {
                val servers = MOOSAServerRegistry.snapshot()
                val online = servers.count { it.lastResult == true }
                addDetailHeading("02 / VALIDATION", "Servers",
                    "Discover, test and manage streaming servers.")
                addMetric(String.format(Locale.US, "%02d", servers.size),
                    "Servers discovered")
                addDetailRow("Verified online", online.toString())
                addDetailRow("Other / untested", (servers.size - online).toString())
                detailAction("Open server manager") {
                    MOOSAServerManagerDialog.show(requireContext())
                }
            }
            2 -> {
                val verify = MOOSASettingsStore.verifyLinks()
                val fast = MOOSASettingsStore.arabicFastMode()
                addDetailHeading("03 / STREAM OUTPUT", "Playback",
                    "Choose how streams and Arabic subtitles are handled.")
                addMetric(if (verify) "ON" else "OFF", "Link verification")
                addDetailRow("Stream verification", if (verify) "Enabled" else "Disabled")
                addDetailRow("Arabic subtitle mode", if (fast) "Fast" else "Normal")
                detailAction("Playback options") { openPlaybackOptions() }
            }
            3 -> {
                val concurrent = MOOSASettingsStore.providerConcurrency()
                addDetailHeading("04 / SEARCH ENGINE", "Performance",
                    "Control simultaneous provider searching.")
                addMetric(String.format(Locale.US, "%02d", concurrent),
                    "Simultaneous providers")
                addDetailRow("Parallel search", concurrent.toString() + " providers")
                addDetailRow("Available presets", "4 – 16")
                detailAction("Performance options") { openPerformance() }
            }
            4 -> {
                addDetailHeading("05 / LANGUAGES", "Subtitles",
                    "Manage Arabic and English subtitle providers and diagnostics.")
                addMetric("AR/EN", "Preferred languages")
                addDetailRow("OpenSubtitles", "Enabled")
                addDetailRow("Wyzie",
                    if (MOOSASettingsStore.wyzieKey() != null) "Key configured"
                    else "No key")
                addDetailRow("SubDL",
                    if (MOOSASettingsStore.subdlKey() != null) "Key configured"
                    else "No key")
                detailAction("Subtitle settings") { openSubtitleSources() }
            }
            5 -> {
                addDetailHeading("06 / ADVANCED", "Web Bridge",
                    "Optional device pairing for MOOSA Web. Leave disconnected if unused.")
                addMetric("WEB", "Optional feature")
                addDetailRow("Connection", MOOSACloudStreamBridge.status)
                addDetailRow("Configuration", "On demand")
                detailAction("Configure Web Bridge") { openWebBridge() }
            }
        }
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val context = requireContext()
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(bg, 14, Color.rgb(56, 56, 56))
            clipChildren = false
            clipToPadding = false
        }
        val header = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(28), 0, dp(23), 0)
            minimumHeight = dp(68)
        }
        header.addView(text("MOOSA", 18f, primary, true))
        header.addView(text(" / HUB", 17f, quiet), LinearLayout.LayoutParams(
            0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f
        ))
        header.addView(text("SETTINGS", 10.5f, secondary).apply {
            letterSpacing = 0.14f
        })
        closeControl = text("×", 25f, primary).apply {
            id = View.generateViewId()
            gravity = Gravity.CENTER
            minWidth = dp(46)
            minHeight = dp(45)
            background = rounded(surface, 22)
            isFocusable = true
            isClickable = true
            setOnClickListener { dismiss() }
            setOnFocusChangeListener { view, focused ->
                (view as TextView).setTextColor(
                    if (focused) Color.BLACK else primary
                )
                view.background = rounded(
                    if (focused) primary else surface, 22
                )
            }
        }
        header.addView(closeControl, LinearLayout.LayoutParams(
            dp(46), dp(46)
        ).apply { marginStart = dp(15) })
        root.addView(header, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(68)
        ))
        root.addLine()

        val columns = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            weightSum = 100f
        }
        val left = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(railBg)
            setPadding(dp(16), dp(28), dp(14), dp(17))
        }
        left.addView(text("WORKSPACE", 10f, quiet).apply {
            letterSpacing = 0.18f
            setPadding(dp(13), 0, 0, 0)
        }, marginBottom(20))

        sectionNames.forEachIndexed { i, _ ->
            val control = navigationItem(i)
            navViews.add(control)
            if (i == 5) {
                left.addView(View(context), LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
                ))
                left.addLine()
            }
            left.addView(control, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(55)
            ).apply {
                if (i < 5) bottomMargin = dp(4)
                if (i == 5) topMargin = dp(11)
            })
        }
        navViews.forEachIndexed { i, view ->
            view.nextFocusUpId = if (i == 0) closeControl.id else navViews[i - 1].id
            view.nextFocusDownId = if (i == navViews.lastIndex)
                view.id else navViews[i + 1].id
        }
        columns.addView(left, LinearLayout.LayoutParams(
            0, ViewGroup.LayoutParams.MATCH_PARENT, 30f
        ))
        columns.addView(line(), LinearLayout.LayoutParams(
            dp(1), ViewGroup.LayoutParams.MATCH_PARENT
        ))
        val rightScroll = ScrollView(context).apply {
            isFillViewport = true
            isVerticalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
        }
        detailColumn = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(31), dp(30), dp(31), dp(27))
            setBackgroundColor(bg)
        }
        rightScroll.addView(detailColumn, android.widget.FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ))
        columns.addView(rightScroll, LinearLayout.LayoutParams(
            0, ViewGroup.LayoutParams.MATCH_PARENT, 70f
        ))
        root.addView(columns, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
        ))
        showSection(0)

        return Dialog(context).apply {
            requestWindowFeature(Window.FEATURE_NO_TITLE)
            setContentView(root)
            setCanceledOnTouchOutside(true)
        }
    }

    override fun onStart() {
        super.onStart()
        val metrics = resources.displayMetrics
        dialog?.window?.apply {
            setLayout(
                (metrics.widthPixels * 0.88f).toInt(),
                (metrics.heightPixels * 0.80f).toInt()
            )
            setBackgroundDrawableResource(android.R.color.transparent)
            setDimAmount(0.70f)
            addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        }
        if (!initialFocusApplied && navViews.isNotEmpty()) {
            initialFocusApplied = true
            navViews[0].post {
                if (isAdded && dialog?.isShowing == true) navViews[0].requestFocus()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (::detailColumn.isInitialized) {
            detailColumn.post {
                if (!isAdded || dialog?.isShowing != true) return@post
                val detailWasFocused = actionControl?.hasFocus() == true
                showSection(selectedSection)
                if (detailWasFocused) actionControl?.requestFocus()
            }
        }
    }

    private fun openWebBridge() {
        val input = EditText(requireContext()).apply {
            hint = "Paste 14-character pairing code"
            setSingleLine(true)
            inputType = android.text.InputType.TYPE_CLASS_TEXT or
                android.text.InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS
            setPadding(dp(16), dp(12), dp(16), dp(12))
        }
        val container = LinearLayout(requireContext()).apply {
            setPadding(dp(15), dp(8), dp(15), dp(8))
            addView(input)
        }
        AlertDialog.Builder(requireContext())
            .setTitle("MOOSA Web Bridge")
            .setMessage("Open moosa-web.onrender.com and select Connect CloudStream. " +
                "Enter the pairing code shown there. " +
                "Only enabled installed providers are used, and only when you press Play. " +
                "Current status: " + MOOSACloudStreamBridge.status)
            .setView(container)
            .setPositiveButton("Connect") { _, _ ->
                MOOSACloudStreamBridge.connect(requireContext(), input.text.toString())
            }
            .setNeutralButton("Disconnect") { _, _ ->
                MOOSACloudStreamBridge.disconnect(requireContext())
                Toast.makeText(requireContext(), "Bridge disconnected", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun openPerformance() {
        val values =
            intArrayOf(4, 6, 8, 10, 12, 16)

        val labels = values
            .map {
                "$it simultaneous providers"
            }
            .toTypedArray()

        val current =
            MOOSASettingsStore.providerConcurrency()

        val selected =
            values.indexOf(current)
                .takeIf { it >= 0 }
                ?: 3

        AlertDialog.Builder(requireContext())
            .setTitle("Performance")
            .setSingleChoiceItems(
                labels,
                selected
            ) { dialog, which ->
                MOOSASettingsStore
                    .setProviderConcurrency(
                        values[which]
                    )

                Toast.makeText(
                    requireContext(),
                    "Saved: ${values[which]} providers",
                    Toast.LENGTH_SHORT
                ).show()

                dialog.dismiss()
            }
            .setNegativeButton(
                "Cancel",
                null
            )
            .show()
    }

    private fun openPlaybackOptions() {
        val labels = arrayOf(
            "Verify stream links before showing them",
            "Fast Arabic subtitle mode"
        )

        val checked = booleanArrayOf(
            MOOSASettingsStore.verifyLinks(),
            MOOSASettingsStore.arabicFastMode()
        )

        AlertDialog.Builder(requireContext())
            .setTitle("Playback")
            .setMultiChoiceItems(
                labels,
                checked
            ) { _, which, enabled ->
                checked[which] = enabled
            }
            .setPositiveButton(
                "Save"
            ) { _, _ ->
                MOOSASettingsStore
                    .setVerifyLinks(
                        checked[0]
                    )

                MOOSASettingsStore
                    .setArabicFastMode(
                        checked[1]
                    )

                Toast.makeText(
                    requireContext(),
                    "Playback settings saved",
                    Toast.LENGTH_SHORT
                ).show()
            }
            .setNegativeButton(
                "Cancel",
                null
            )
            .show()
    }

    private fun openSubtitleSources() {
        val entries = arrayOf(
            "Wyzie Key",
            "SubDL Key",
            "Find more Arabic subtitles (on demand)",
            "Subtitle synchronization tips",
            "Arabic subtitle priority",
            "Subtitle diagnostics (last playback)"
        )

        AlertDialog.Builder(requireContext())
            .setTitle("Subtitles")
            .setItems(entries) { _, which ->
                when (which) {
                    0 -> editKey(
                        title = "Wyzie Key",
                        current =
                            MOOSASettingsStore.wyzieKey(),
                        onSave =
                            MOOSASettingsStore::setWyzieKey
                    )

                    1 -> editKey(
                        title = "SubDL Key",
                        current =
                            MOOSASettingsStore.subdlKey(),
                        onSave =
                            MOOSASettingsStore::setSubdlKey
                    )

                    2 -> {
                        AlertDialog.Builder(requireContext())
                            .setTitle("More subtitles without slower servers")
                            .setMessage(
                                "In the CloudStream video player, open Subtitles, " +
                                "choose Load from Internet, and select SubSource " +
                                "(or SubDL). Search in Arabic and select a release " +
                                "matching the video, such as WEB-DL or BluRay. " +
                                "SubSource delivers ZIP archives that CloudStream " +
                                "unpacks on demand. MOOSA Hub deliberately does NOT " +
                                "download these archives during server discovery."
                            )
                            .setPositiveButton("OK", null)
                            .show()
                    }

                    3 -> {
                        AlertDialog.Builder(requireContext())
                            .setTitle("Keep subtitles in sync")
                            .setMessage(
                                "Match the subtitle release (WEB-DL, WEBRip, BluRay) " +
                                "to the selected video. FPS hints can help, but do " +
                                "not guarantee sync. For a fixed offset, adjust " +
                                "Subtitle delay in the CloudStream player. If the " +
                                "gap grows throughout the movie, select a different " +
                                "release instead of adding more delay."
                            )
                            .setPositiveButton("OK", null)
                            .show()
                    }

                    4 -> {
                        AlertDialog.Builder(requireContext())
                            .setTitle("Arabic subtitle priority")
                            .setMessage(
                                "Arabic and English subtitles are verified and sent " +
                                "individually as they become available. Arabic is " +
                                "prioritized in selection, but network arrival order " +
                                "can vary. Only two languages are emitted by MOOSA Hub."
                            )
                            .setPositiveButton("OK", null)
                            .show()
                    }

                    5 -> {
                        AlertDialog.Builder(requireContext())
                            .setTitle("Last subtitle search (no additional requests)")
                            .setMessage(MOOSASubtitleHealth.report())
                            .setPositiveButton("OK", null)
                            .show()
                    }
                }
            }
            .setNegativeButton(
                "Close",
                null
            )
            .show()
    }

    private fun editKey(
        title: String,
        current: String?,
        onSave: (String?) -> Unit
    ) {
        val input = EditText(
            requireContext()
        ).apply {
            hint = title
            setSingleLine(true)
            setText(current.orEmpty())
        }

        AlertDialog.Builder(requireContext())
            .setTitle(title)
            .setView(input)
            .setPositiveButton(
                "Save"
            ) { _, _ ->
                onSave(
                    input.text?.toString()
                )

                Toast.makeText(
                    requireContext(),
                    "$title saved",
                    Toast.LENGTH_SHORT
                ).show()
            }
            .setNeutralButton(
                "Clear"
            ) { _, _ ->
                onSave(null)

                Toast.makeText(
                    requireContext(),
                    "$title cleared",
                    Toast.LENGTH_SHORT
                ).show()
            }
            .setNegativeButton(
                "Cancel",
                null
            )
            .show()
    }

    companion object {
        fun show(context: Context) {
            val activity =
                context as? AppCompatActivity
                    ?: return

            if (
                activity.isFinishing ||
                activity.isDestroyed
            ) {
                return
            }

            MOOSASettingsDialog().show(
                activity.supportFragmentManager,
                "moosa_hub_settings"
            )
        }
    }
}
