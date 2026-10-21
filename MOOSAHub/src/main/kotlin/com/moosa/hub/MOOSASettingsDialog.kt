package com.moosa.hub

import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.DialogFragment

/** The approved TV Native 01 settings layout. */
class MOOSASettingsDialog : DialogFragment() {
    private val ui = MOOSATVSettingsStyle
    private lateinit var shell: MOOSATVSettingsStyle.Shell
    private var page = "home"
    private var verify = true
    private var arabicFast = true
    private var concurrency = 10
    private val presets = intArrayOf(4, 6, 8, 10, 12, 16)

    private fun dp(n: Int) = ui.dp(requireContext(), n)
    private fun txt(t: String, n: Float = 13f, color: Int = ui.white,
                    bold: Boolean = false) = ui.text(requireContext(), t, n, color, bold)
    private fun notifyUser(t: String) = Toast.makeText(requireContext(), t, Toast.LENGTH_SHORT).show()

    private fun newPage(): LinearLayout {
        shell.content.removeAllViews()
        val scroll = ScrollView(requireContext()).apply {
            isVerticalScrollBarEnabled = false
            isFocusable = false
            descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
            overScrollMode = View.OVER_SCROLL_NEVER
        }
        val body = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
        }
        scroll.addView(body, ViewGroup.LayoutParams(-1, -2))
        shell.content.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        return body
    }

    private fun actionRow(name: String, subtitle: String = "",
                          action: () -> Unit): View {
        val row = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(63)
            setPadding(dp(14), dp(11), dp(14), dp(11))
        }
        val column = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
        }
        column.addView(txt(name, 15f, ui.white, true))
        if (subtitle.isNotBlank()) {
            column.addView(txt(subtitle, 11f, ui.gray).apply { maxLines = 2 })
        }
        row.addView(column, LinearLayout.LayoutParams(0, -2, 1f))
        row.addView(txt("›", 23f, ui.white).apply {
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
        }, LinearLayout.LayoutParams(dp(25), -2))
        ui.focus(row)
        row.setOnClickListener { action() }
        return row
    }

    private fun addRow(body: LinearLayout, view: View) {
        body.addView(view, LinearLayout.LayoutParams(-1, -2).apply {
            bottomMargin = dp(8)
        })
    }

    private fun action(body: LinearLayout, name: String,
                       primary: Boolean = true, onPress: () -> Unit) {
        val button = ui.button(requireContext(), name, primary)
        button.setOnClickListener { onPress() }
        body.addView(button, LinearLayout.LayoutParams(-1, dp(50)).apply {
            topMargin = dp(15)
        })
    }

    private fun heading(body: LinearLayout, tag: String,
                        title: String, description: String) =
        ui.heading(requireContext(), body, tag, title, description)

    private fun stat(body: LinearLayout, figure: String, description: String) {
        val row = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.BOTTOM
            setPadding(0, dp(13), 0, dp(13))
        }
        row.addView(txt(figure, 37f, ui.white, true),
                    LinearLayout.LayoutParams(0, -2, 1f))
        row.addView(txt(description, 12f, ui.gray).apply {
            gravity = Gravity.END or Gravity.BOTTOM
            maxLines = 2
        }, LinearLayout.LayoutParams(0, -2, 1f))
        body.addView(ui.divider(requireContext()), LinearLayout.LayoutParams(-1, dp(1)))
        body.addView(row)
        body.addView(ui.divider(requireContext()), LinearLayout.LayoutParams(-1, dp(1)))
    }

    private fun setting(body: LinearLayout, label: String, value: String) {
        val row = LinearLayout(requireContext()).apply {
            gravity = Gravity.CENTER_VERTICAL
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(14), 0, dp(14))
        }
        row.addView(txt(label, 13f, ui.gray), LinearLayout.LayoutParams(0, -2, 1f))
        row.addView(txt(value, 13f, ui.white, true).apply {
            gravity = Gravity.END
            maxLines = 2
        }, LinearLayout.LayoutParams(0, -2, 1f))
        body.addView(row)
        body.addView(ui.divider(requireContext()), LinearLayout.LayoutParams(-1, dp(1)))
    }

    private fun note(body: LinearLayout, message: String) {
        body.addView(txt(message, 12f, ui.gray).apply {
            setPadding(dp(16), dp(15), dp(16), dp(15))
            background = ui.shape(requireContext(), ui.panel, 6, ui.separator)
        }, LinearLayout.LayoutParams(-1, -2).apply {
            topMargin = dp(17)
        })
    }

    private fun overview(body: LinearLayout) {
        heading(body, "SETTINGS / OVERVIEW", "Everything, organized.",
                "Choose a section. Every tool uses the same interface.")
        val sections = listOf(
            Triple("Sources", "5 installed providers · manage and test", "sources"),
            Triple("Servers", "Discover and test streaming links", "servers"),
            Triple("Playback", "Verification and Arabic fast mode", "playback"),
            Triple("Performance", "Parallel searches", "performance"),
            Triple("Subtitles", "Arabic and English settings", "subtitles"),
            Triple("Advanced", "Optional Web Bridge", "advanced")
        )
        for (index in sections.indices step 2) {
            val pair = LinearLayout(requireContext()).apply {
                orientation = LinearLayout.HORIZONTAL
            }
            for (i in index..minOf(index + 1, sections.lastIndex)) {
                val entry = sections[i]
                pair.addView(actionRow(entry.first, entry.second) {
                    showPage(entry.third)
                }, LinearLayout.LayoutParams(0, dp(76), 1f).apply {
                    if (i == index) marginEnd = dp(9)
                })
            }
            body.addView(pair, LinearLayout.LayoutParams(-1, -2).apply {
                bottomMargin = dp(10)
            })
        }
    }

    private fun sources(body: LinearLayout) {
        val all = MOOSASourceTester.sources()
        val enabled = all.count { MOOSASourceTester.isEnabled(it) }
        heading(body, "DISCOVERY / PROVIDERS", "Sources",
            "Manage installed CloudStream providers and test them.")
        stat(body, "$enabled / " + all.size, "Enabled sources")
        setting(body, "Enabled sources", "$enabled active")
        setting(body, "Source diagnostics", "Available")
        action(body, "Open source manager  ↗") {
            MOOSASourceTesterDialog.show(requireContext())
        }
    }

    private fun servers(body: LinearLayout) {
        val all = MOOSAServerRegistry.snapshot()
        heading(body, "STREAMING / SERVER MANAGER", "Server Manager",
            "Discover and validate links without leaving the settings area.")
        stat(body, "%02d / %02d".format(all.size, all.size), "Servers available")
        if (all.isNotEmpty()) {
            setting(body, "Verified online", all.count { it.lastResult == true }.toString())
            setting(body, "Offline / untested", all.count { it.lastResult != true }.toString())
        }
        action(body, if (all.isEmpty()) "Discover & Test Servers  ↗"
                     else "Open server manager  ↗") {
            MOOSAServerManagerDialog.show(requireContext())
        }
        if (all.isEmpty()) note(body,
            "No servers discovered. Selection, search and offline tools appear after results are available.")
    }

    private fun playback(body: LinearLayout) {
        heading(body, "PLAYER / PREFERENCES", "Playback",
            "Only the controls you need for playback.")
        addRow(body, actionRow("Verify stream links",
            "Check links before presenting them • " + if (verify) "ON" else "OFF") {
            verify = !verify
            rerenderWithoutLosingFocus()
        })
        addRow(body, actionRow("Fast Arabic mode",
            "Prioritize Arabic subtitle searching • " +
                if (arabicFast) "ON" else "OFF") {
            arabicFast = !arabicFast
            rerenderWithoutLosingFocus()
        })
        action(body, "Save settings  ✓") {
            MOOSASettingsStore.setVerifyLinks(verify)
            MOOSASettingsStore.setArabicFastMode(arabicFast)
            notifyUser("Playback settings saved")
            rerenderWithoutLosingFocus()
        }
    }

    private fun performance(body: LinearLayout) {
        heading(body, "ENGINE / PERFORMANCE", "Performance",
            "Set how many providers search simultaneously.")
        stat(body, concurrency.toString(), "Simultaneous providers")
        body.addView(txt("CONCURRENCY PRESET", 11f, ui.gray), LinearLayout.LayoutParams(-1, -2).apply {
            topMargin = dp(19)
            bottomMargin = dp(13)
        })
        val grid = LinearLayout(requireContext()).apply { orientation = LinearLayout.HORIZONTAL }
        presets.forEachIndexed { i, value ->
            val b = ui.button(requireContext(), value.toString(), value == concurrency)
            b.setOnClickListener { concurrency = value; render() }
            grid.addView(b, LinearLayout.LayoutParams(0, dp(48), 1f).apply {
                if (i > 0) marginStart = dp(6)
            })
        }
        body.addView(grid)
        action(body, "Apply performance setting  ✓") {
            MOOSASettingsStore.setProviderConcurrency(concurrency)
            notifyUser("Saved: $concurrency providers")
            rerenderWithoutLosingFocus()
        }
    }

    private fun subtitles(body: LinearLayout) {
        heading(body, "LANGUAGE / SOURCES", "Subtitles",
            "Arabic and English subtitle providers and synchronization tools.")
        addRow(body, actionRow("OpenSubtitles", "Built-in subtitle discovery") {
            showPage("subtitle-info")
        })
        addRow(body, actionRow("Wyzie", if (MOOSASettingsStore.wyzieKey() != null)
            "Key configured" else "Not configured") { showPage("subtitle-wyzie") })
        addRow(body, actionRow("SubDL", if (MOOSASettingsStore.subdlKey() != null)
            "Key configured" else "Not configured") { showPage("subtitle-subdl") })
        addRow(body, actionRow("Subtitle diagnostics", "Last playback report") {
            showPage("subtitle-diagnostics")
        })
        addRow(body, actionRow("Sync guidance", "Match source release and timing") {
            showPage("subtitle-sync")
        })
        addRow(body, actionRow("Arabic subtitle priority", "Subtitle selection policy") {
            showPage("subtitle-priority")
        })
        addRow(body, actionRow("Find more Arabic subtitles", "On-demand search") {
            showPage("subtitle-more")
        })
    }

    private fun editKey(body: LinearLayout, subdl: Boolean) {
        val name = if (subdl) "SubDL" else "Wyzie"
        heading(body, "SUBTITLES / PROVIDERS", name + " Key",
            "Configure the optional subtitle source.")
        val input = EditText(requireContext()).apply {
            hint = "Enter " + name + " key"
            setSingleLine(true)
            setText(if (subdl) MOOSASettingsStore.subdlKey()
                    else MOOSASettingsStore.wyzieKey())
            inputType = android.text.InputType.TYPE_CLASS_TEXT or
                android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
            textSize = 15f
            setTextColor(ui.white)
            setHintTextColor(ui.gray)
            background = ui.shape(requireContext(), ui.panel, 5, ui.separator)
            setPadding(dp(16), dp(11), dp(16), dp(11))
        }
        body.addView(input, LinearLayout.LayoutParams(-1, dp(51)))
        action(body, "Save key  ✓") {
            val key = input.text.toString().trim().takeIf { it.isNotEmpty() }
            if (subdl) MOOSASettingsStore.setSubdlKey(key)
            else MOOSASettingsStore.setWyzieKey(key)
            notifyUser(name + " saved")
            showPage("subtitles")
        }
        action(body, "Clear key", false) {
            if (subdl) MOOSASettingsStore.setSubdlKey(null)
            else MOOSASettingsStore.setWyzieKey(null)
            showPage("subtitles")
        }
    }

    private fun help(body: LinearLayout) {
        val title: String
        val details: String
        when(page) {
            "subtitle-sync" -> {
                title = "Subtitle synchronization"
                details = "Match WEB-DL, WEBRip or BluRay subtitle releases to the video. For fixed offsets, use the player's Subtitle delay. If delay grows throughout the film, choose a matching release."
            }
            "subtitle-priority" -> {
                title = "Arabic subtitle priority"
                details = "Only Arabic and English subtitles are returned. Arabic has selection priority; arrival order can vary with network response."
            }
            "subtitle-more" -> {
                title = "More Arabic subtitles"
                details = "In the CloudStream player, open Subtitles → Load from Internet, select SubSource or SubDL, then choose a release matching the video."
            }
            "subtitle-diagnostics" -> {
                title = "Subtitle diagnostics"
                details = MOOSASubtitleHealth.report()
            }
            else -> {
                title = "OpenSubtitles"
                details = "OpenSubtitles discovery is built in. Arabic and English tracks are selected individually."
            }
        }
        heading(body, "SUBTITLES / HELP", title, "Information and diagnostics")
        note(body, details)
        action(body, "Back to subtitles  ←", false) { showPage("subtitles") }
    }

    private fun advanced(body: LinearLayout) {
        heading(body, "OPTIONAL / INTEGRATIONS", "Advanced",
            "Additional features that do not need space in your main settings.")
        setting(body, "MOOSA Web Bridge", MOOSACloudStreamBridge.status)
        note(body, "Only connect if you use MOOSA Web. The pairing code is shown on the website.")
        val input = EditText(requireContext()).apply {
            hint = "Enter 14-character pairing code"
            setSingleLine(true)
            inputType = android.text.InputType.TYPE_CLASS_TEXT or
                android.text.InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS
            setTextColor(ui.white)
            setHintTextColor(ui.gray)
            textSize = 14f
            background = ui.shape(requireContext(), ui.panel, 5, ui.separator)
            setPadding(dp(16), dp(11), dp(16), dp(11))
        }
        body.addView(input, LinearLayout.LayoutParams(-1, dp(51)).apply {
            topMargin = dp(14)
        })
        action(body, "Connect Web Bridge  ↗") {
            val code = input.text.toString().trim()
            if (code.isEmpty()) notifyUser("Enter the pairing code first")
            else {
                MOOSACloudStreamBridge.connect(requireContext(), code)
                render()
            }
        }
        action(body, "Disconnect Web Bridge", false) {
            MOOSACloudStreamBridge.disconnect(requireContext())
            render()
        }
    }

    private fun rerenderWithoutLosingFocus() {
        // Rebuilding a selected setting previously moved remote focus to the
        // top bar. Remember its ordinal and restore it on the next layout.
        val before = shell.content.getFocusables(View.FOCUS_FORWARD)
        val index = before.indexOf(shell.content.findFocus())
        render()
        shell.content.post {
            if (!isAdded) return@post
            val after = shell.content.getFocusables(View.FOCUS_FORWARD)
            if (after.isNotEmpty()) {
                after[index.coerceIn(0, after.lastIndex)].requestFocus()
            }
        }
    }

    private fun render() {
        if (!::shell.isInitialized) return
        val target = if (page == "home") "OVERVIEW" else
            page.removePrefix("subtitle-").uppercase(java.util.Locale.US)
        shell.path("SETTINGS / " + target)
        val body = newPage()
        when(page) {
            "home" -> overview(body)
            "sources" -> sources(body)
            "servers" -> servers(body)
            "playback" -> playback(body)
            "performance" -> performance(body)
            "subtitles" -> subtitles(body)
            "advanced" -> advanced(body)
            "subtitle-wyzie" -> editKey(body, false)
            "subtitle-subdl" -> editKey(body, true)
            else -> help(body)
        }
    }

    private fun firstAction(view: View): View? {
        if (view.isFocusable && view.isShown) return view
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                firstAction(view.getChildAt(i))?.let { return it }
            }
        }
        return null
    }

    private fun showPage(section: String) {
        page = section
        if (page == "playback") {
            verify = MOOSASettingsStore.verifyLinks()
            arabicFast = MOOSASettingsStore.arabicFastMode()
        }
        if (page == "performance") concurrency = MOOSASettingsStore.providerConcurrency()
        render()
        shell.content.post {
            if (isAdded) (firstAction(shell.content) ?: shell.back).requestFocus()
        }
    }

    private fun back() {
        when {
            page.startsWith("subtitle-") -> showPage("subtitles")
            page != "home" -> showPage("home")
            else -> dismiss()
        }
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val context = requireContext()
        shell = ui.shell(context, "SETTINGS / OVERVIEW",
            backAction = { back() }, closeAction = { dismiss() })
        verify = MOOSASettingsStore.verifyLinks()
        arabicFast = MOOSASettingsStore.arabicFastMode()
        concurrency = MOOSASettingsStore.providerConcurrency()
        render()
        return Dialog(context).apply {
            requestWindowFeature(Window.FEATURE_NO_TITLE)
            setContentView(shell.root)
            setCanceledOnTouchOutside(true)
            setOnKeyListener { _, keyCode, event ->
                if ((keyCode == KeyEvent.KEYCODE_BACK ||
                     keyCode == KeyEvent.KEYCODE_ESCAPE) && page != "home") {
                    if (event.action == KeyEvent.ACTION_UP) back()
                    true
                } else false
            }
        }
    }

    override fun onStart() {
        super.onStart()
        ui.window(dialog, resources.displayMetrics)
        shell.content.post {
            if (isAdded && dialog?.isShowing == true) {
                firstAction(shell.content)?.requestFocus()
            }
        }
    }

    companion object {
        fun show(context: Context) {
            val host = context as? AppCompatActivity ?: return
            if (host.isFinishing || host.isDestroyed ||
                host.supportFragmentManager.isStateSaved) return
            if (host.supportFragmentManager.findFragmentByTag("moosa_hub_settings") != null) return
            MOOSASettingsDialog().show(host.supportFragmentManager, "moosa_hub_settings")
        }
    }
}
