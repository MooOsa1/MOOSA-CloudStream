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
    // Saved by stable option key, never by position in a rebuilt ViewGroup.
    private val focusByPage = mutableMapOf<String, String>()
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

    private fun actionRow(
        name: String, subtitle: String = "",
        focusKey: String = name, action: () -> Unit
    ): View {
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
        val owner = page
        row.tag = focusKey
        ui.focus(row, onFocused = { focusByPage[owner] = focusKey })
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
        val owner = page
        val key = "action:" + name
        val button = ui.button(requireContext(), name, primary,
            onFocused = { focusByPage[owner] = key })
        button.tag = key
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
        heading(body, "SETTINGS / OVERVIEW", "Settings",
                "Manage providers, server testing, playback, and subtitles.")
        val sourceList = MOOSASourceTester.sources()
        val activeSources = sourceList.count { MOOSASourceTester.isEnabled(it) }
        val sections = listOf(
            Triple("Providers", activeSources.toString() + " / " +
                sourceList.size + " enabled · manage and test", "sources"),
            Triple("Server Manager", "Scan and verify streaming servers", "servers"),
            Triple("Playback", "Link verification and Arabic fast mode", "playback"),
            Triple("Search Performance", "Simultaneous provider searches", "performance"),
            Triple("Subtitle Settings", "Arabic and English subtitles", "subtitles")
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
        heading(body, "DISCOVERY / PROVIDERS", "Providers",
            "Enable, disable, and test installed content providers.")
        stat(body, "$enabled / " + all.size, "Enabled sources")
        setting(body, "Enabled sources", "$enabled active")
        setting(body, "Source diagnostics", "Available")
        action(body, "Manage Providers  ↗") {
            MOOSASourceTesterDialog.show(requireContext())
        }
    }

    private fun servers(body: LinearLayout) {
        val all = MOOSAServerRegistry.snapshot()
        heading(body, "STREAMING / SERVER MANAGER", "Server Manager",
            "Discover and validate links without leaving the settings area.")
        stat(body, "%02d / %02d".format(all.count { it.lastResult == true }, all.size), "Online / discovered")
        if (all.isNotEmpty()) {
            setting(body, "Verified online", all.count { it.lastResult == true }.toString())
            setting(body, "Offline / untested", all.count { it.lastResult != true }.toString())
        }
        action(body, if (all.isEmpty()) "Scan & Test Servers  ↗"
                     else "Manage Servers  ↗") {
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
        heading(body, "ENGINE / PERFORMANCE", "Search Performance",
            "Choose how many providers are searched at the same time.")
        stat(body, concurrency.toString(), "Simultaneous providers")
        body.addView(txt("CONCURRENCY PRESET", 11f, ui.gray), LinearLayout.LayoutParams(-1, -2).apply {
            topMargin = dp(19)
            bottomMargin = dp(13)
        })
        val grid = LinearLayout(requireContext()).apply { orientation = LinearLayout.HORIZONTAL }
        presets.forEachIndexed { i, value ->
            val b = ui.button(requireContext(), value.toString(), value == concurrency)
            val owner = page
            val key = "preset:$value"
            b.tag = key
            ui.focus(b, value == concurrency, onFocused = {
                focusByPage[owner] = key
            })
            b.setOnClickListener {
                concurrency = value
                rerenderWithoutLosingFocus()
            }
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
        heading(body, "LANGUAGE / SUBTITLES", "Subtitle Settings",
            "Configure providers and review subtitles from your last playback.")
        // A built-in status is not a fake selectable settings action.
        setting(body, "OpenSubtitles Status", "Built-in")
        addRow(body, actionRow("Wyzie API Key", if (MOOSASettingsStore.wyzieKey() != null)
            "Key configured" else "Not configured", "wyzie") {
            showPage("subtitle-wyzie")
        })
        addRow(body, actionRow("SubDL API Key", if (MOOSASettingsStore.subdlKey() != null)
            "Key configured" else "Not configured", "subdl") {
            showPage("subtitle-subdl")
        })
        addRow(body, actionRow("Subtitle Diagnostics",
            "Last playback search results", "diagnostics") {
            showPage("subtitle-diagnostics")
        })
        addRow(body, actionRow("Subtitle Help",
            "Synchronization, priority and finding more subtitles", "help") {
            showPage("subtitle-help")
        })
    }

    private fun editKey(body: LinearLayout, subdl: Boolean) {
        val name = if (subdl) "SubDL" else "Wyzie"
        heading(body, "SUBTITLES / PROVIDERS", name + " API Key",
            "Configure the optional subtitle provider.")
        val input = EditText(requireContext()).apply {
            hint = "Enter " + name + " API Key"
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
        action(body, "Save API Key  ✓") {
            val key = input.text.toString().trim().takeIf { it.isNotEmpty() }
            if (subdl) MOOSASettingsStore.setSubdlKey(key)
            else MOOSASettingsStore.setWyzieKey(key)
            notifyUser(name + " saved")
            showPage("subtitles")
        }
        // Don't show a destructive action when there is no saved key.
        val hasSavedKey = if (subdl) MOOSASettingsStore.subdlKey() != null
            else MOOSASettingsStore.wyzieKey() != null
        if (hasSavedKey) {
            action(body, "Remove Saved Key", false) {
                if (subdl) MOOSASettingsStore.setSubdlKey(null)
                else MOOSASettingsStore.setWyzieKey(null)
                showPage("subtitles")
            }
        }
    }

    private fun help(body: LinearLayout) {
        val diagnostics = page == "subtitle-diagnostics"
        heading(body, "SUBTITLES / " + if (diagnostics) "DIAGNOSTICS" else "HELP",
            if (diagnostics) "Subtitle Diagnostics" else "Subtitle Help",
            if (diagnostics) "Last playback subtitle search results."
            else "Synchronization, language priority, and finding more subtitles.")
        if (diagnostics) {
            note(body, MOOSASubtitleHealth.report())
        } else {
            note(body, "Sync: match WEB-DL, WEBRip or BluRay subtitle releases. " +
                "For a fixed offset, adjust Subtitle delay inside the player. " +
                "If the gap grows, select a matching release.")
            note(body, "Arabic priority: Arabic tracks are preferred to English, " +
                "but network results may arrive in a different order.")
            note(body, "More subtitles: in the CloudStream player open " +
                "Subtitles > Load from Internet and try SubSource or SubDL.")
        }
        action(body, "Back to Subtitle Settings  ←", false) { showPage("subtitles") }
    }

    private fun findTaggedAction(view: View, key: String): View? {
        if (view.tag == key && view.isFocusable && view.visibility == View.VISIBLE)
            return view
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                findTaggedAction(view.getChildAt(i), key)?.let { return it }
            }
        }
        return null
    }

    private fun restorePageFocus(expectedPage: String) {
        shell.content.post {
            if (!isAdded || page != expectedPage || dialog?.isShowing != true)
                return@post
            val key = focusByPage[expectedPage]
            val target = key?.let { findTaggedAction(shell.content, it) }
                ?: firstAction(shell.content)
                ?: shell.back
            target.requestFocus()
        }
    }

    private fun rerenderWithoutLosingFocus() {
        val current = shell.content.findFocus()?.tag as? String
        if (current != null) focusByPage[page] = current
        render()
        restorePageFocus(page)
    }

    private fun render() {
        if (!::shell.isInitialized) return
        val target = when (page) {
            "home" -> "OVERVIEW"
            "sources" -> "PROVIDERS"
            "servers" -> "SERVER MANAGER"
            "performance" -> "SEARCH PERFORMANCE"
            "subtitles" -> "SUBTITLE SETTINGS"
            "subtitle-wyzie" -> "SUBTITLES / WYZIE API KEY"
            "subtitle-subdl" -> "SUBTITLES / SUBDL API KEY"
            "subtitle-help" -> "SUBTITLES / HELP"
            "subtitle-diagnostics" -> "SUBTITLES / DIAGNOSTICS"
            else -> page.uppercase(java.util.Locale.US)
        }
        shell.path("SETTINGS / " + target)
        val body = newPage()
        when(page) {
            "home" -> overview(body)
            "sources" -> sources(body)
            "servers" -> servers(body)
            "playback" -> playback(body)
            "performance" -> performance(body)
            "subtitles" -> subtitles(body)
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
        restorePageFocus(page)
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
                val key = focusByPage[page]
                (key?.let { findTaggedAction(shell.content, it) }
                    ?: firstAction(shell.content))?.requestFocus()
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
