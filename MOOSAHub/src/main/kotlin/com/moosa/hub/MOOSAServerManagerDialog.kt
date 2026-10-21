package com.moosa.hub

import android.app.Dialog
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.DialogFragment
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicInteger

class MOOSAServerManagerDialog : DialogFragment() {
    private data class RowRefs(
        val key: String,
        val row: View,
        val check: CheckBox,
        val status: TextView,
        val test: Button
    )

    private lateinit var selectionControls: LinearLayout
    private lateinit var listContainer: LinearLayout
    private lateinit var countText: TextView
    private lateinit var progressBar: ProgressBar
    private lateinit var progressText: TextView
    private lateinit var discoverButton: Button
    private lateinit var searchButton: Button
    private lateinit var searchPanel: LinearLayout
    private lateinit var searchInput: EditText
    private lateinit var selectAll: Button
    private lateinit var selectNone: Button
    private lateinit var disableOffline: Button

    private val rows = mutableListOf<RowRefs>()
    private var query = ""
    private val runningTests = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    private var scanRunning = false
    private var bulkUpdating = false

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    private fun rounded(
        color: Int,
        radius: Int,
        stroke: Int? = null,
        strokeDp: Int = 1
    ): GradientDrawable =
        GradientDrawable().apply {
            setColor(color)
            cornerRadius = dp(radius).toFloat()
            stroke?.let { setStroke(dp(strokeDp), it) }
        }

    private fun label(
        value: String,
        size: Float,
        color: Int = Color.WHITE,
        bold: Boolean = false
    ) = TextView(requireContext()).apply {
        text = value
        textSize = size
        setTextColor(color)
        isFocusable = false
        if (bold) setTypeface(typeface, android.graphics.Typeface.BOLD)
    }

    private fun styleFocusable(view: View, baseColor: Int, radius: Int = 5) {
        // Shared native black/white TV focus; never scale children offscreen.
        MOOSATVSettingsStyle.focus(view)
    }

    private fun actionButton(text: String, color: Int): Button =
        MOOSATVSettingsStyle.button(
            requireContext(), text,
            text.startsWith("Scan & Test") || text.startsWith("Test All")
        )

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val context = requireContext()

        val shell = MOOSATVSettingsStyle.shell(
            context, "SETTINGS / SERVERS",
            backAction = { dismiss() },
            closeAction = { dismiss() }
        )
        val root = shell.root
        val content = shell.content
        val page = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        val pageScroll = ScrollView(context).apply {
            isFocusable = false
            isVerticalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
        }
        pageScroll.addView(page, ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ))
        content.addView(pageScroll, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
        ))
        MOOSATVSettingsStyle.heading(
            context, page, "STREAMING / SERVER MANAGER",
            "Server Manager",
            "Discover, validate and manage streaming links."
        )

        val header = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        header.addView(
            label("Enabled", 15f, MOOSATVSettingsStyle.gray, true),
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        )
        countText = label("", 27f, Color.WHITE, true)
        header.addView(countText)
        page.addView(header)

        page.addView(
            label(
                "DISCOVER scans sources in the background. ONLINE means the final stream link passed two probes.",
                12f,
                MOOSATVSettingsStyle.gray
            ).apply {
                setPadding(0, dp(4), 0, dp(10))
            }
        )

        val controls1 = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        selectAll = actionButton("Enable All", Color.rgb(53, 53, 53))
        selectNone = actionButton("Disable All", Color.rgb(53, 53, 53))
        controls1.addView(
            selectAll,
            LinearLayout.LayoutParams(0, dp(52), 1f).apply { marginEnd = dp(6) }
        )
        controls1.addView(
            selectNone,
            LinearLayout.LayoutParams(0, dp(58), 1f).apply { marginStart = dp(6) }
        )
        selectionControls = controls1
        page.addView(controls1)
        controls1.visibility = View.GONE

        val controls2 = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(7), 0, 0)
        }
        discoverButton = actionButton(
            "Scan & Test Servers",
            Color.rgb(53, 53, 53)
        )
        disableOffline = actionButton(
            "Disable Offline Servers",
            Color.rgb(53, 53, 53)
        )
        controls2.addView(
            discoverButton,
            LinearLayout.LayoutParams(0, dp(58), 1f).apply { marginEnd = dp(6) }
        )
        controls2.addView(
            disableOffline,
            LinearLayout.LayoutParams(0, dp(58), 1f).apply { marginStart = dp(6) }
        )
        page.addView(controls2)
        disableOffline.visibility = View.GONE

        progressBar = ProgressBar(
            context,
            null,
            android.R.attr.progressBarStyleHorizontal
        ).apply {
            max = 100
            progress = 0
            visibility = View.GONE
        }
        page.addView(
            progressBar,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(9)
            ).apply { topMargin = dp(8) }
        )

        progressText = label("", 13f, Color.LTGRAY, true).apply {
            visibility = View.GONE
            setPadding(0, dp(3), 0, dp(7))
        }
        page.addView(progressText)

        searchButton = actionButton("Search Servers…", Color.rgb(53, 53, 53))
        searchButton.visibility = View.GONE
        page.addView(
            searchButton,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(48)
            ).apply { topMargin = dp(8) }
        )
        searchPanel = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            visibility = View.GONE
        }
        searchInput = EditText(context).apply {
            hint = "Search Servers by name"
            setSingleLine(true)
            textSize = 14f
            setTextColor(Color.WHITE)
            setHintTextColor(MOOSATVSettingsStyle.gray)
            background = MOOSATVSettingsStyle.shape(
                context, MOOSATVSettingsStyle.panel, 5, MOOSATVSettingsStyle.separator
            )
            setPadding(dp(12), dp(8), dp(12), dp(8))
        }
        searchPanel.addView(searchInput, LinearLayout.LayoutParams(
            0, dp(45), 1f
        ).apply { marginEnd = dp(6) })
        val applySearch = MOOSATVSettingsStyle.button(context, "Apply", true)
        val clearSearch = MOOSATVSettingsStyle.button(context, "Clear")
        searchPanel.addView(applySearch, LinearLayout.LayoutParams(
            dp(86), dp(45)
        ).apply { marginEnd = dp(6) })
        searchPanel.addView(clearSearch, LinearLayout.LayoutParams(
            dp(85), dp(45)
        ))
        page.addView(searchPanel, LinearLayout.LayoutParams(-1, -2).apply {
            topMargin = dp(8)
        })
        applySearch.setOnClickListener {
            query = searchInput.text?.toString()?.trim().orEmpty()
            searchButton.text = if (query.isBlank()) "Search Servers…"
                else "Search: " + query
            rebuildList()
            searchPanel.visibility = View.GONE
            searchButton.requestFocus()
        }
        clearSearch.setOnClickListener {
            query = ""
            searchInput.setText("")
            searchButton.text = "Search Servers…"
            rebuildList()
            searchPanel.visibility = View.GONE
            searchButton.requestFocus()
        }


        val scroll = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
        }
        listContainer = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(8), 0, dp(10))
        }
        scroll.addView(
            listContainer,
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )
        page.addView(
            scroll,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )

        selectAll.setOnClickListener {
            MOOSAServerRegistry.setAllEnabled(true)
            bulkUpdating = true
            rows.forEach { it.check.isChecked = true }
            bulkUpdating = false
            updateCountOnly()
        }

        selectNone.setOnClickListener {
            MOOSAServerRegistry.setAllEnabled(false)
            bulkUpdating = true
            rows.forEach { it.check.isChecked = false }
            bulkUpdating = false
            updateCountOnly()
        }

        disableOffline.setOnClickListener {
            MOOSAServerRegistry.disableOffline()

            bulkUpdating = true
            rows.forEach { refs ->
                refs.check.isChecked =
                    !MOOSAServerRegistry.isDisabled(refs.key)
            }
            bulkUpdating = false
            updateCountOnly()
        }

        discoverButton.setOnClickListener { discoverAndTest() }
        searchButton.setOnClickListener { openSearchDialog() }

        wireTopFocus()
        rebuildList()

        android.os.Handler(android.os.Looper.getMainLooper()).post {
            discoverButton.requestFocus()
        }

        return Dialog(context).apply {
            requestWindowFeature(Window.FEATURE_NO_TITLE)
            setContentView(root)
            setCanceledOnTouchOutside(true)
        }
    }

    override fun onStart() {
        super.onStart()
        MOOSATVSettingsStyle.window(dialog, resources.displayMetrics)
    }

    private fun wireTopFocus() {
        selectAll.nextFocusRightId = selectNone.id
        selectAll.nextFocusDownId = discoverButton.id

        selectNone.nextFocusLeftId = selectAll.id
        selectNone.nextFocusDownId = disableOffline.id

        discoverButton.nextFocusUpId = selectAll.id
        discoverButton.nextFocusRightId = disableOffline.id
        discoverButton.nextFocusDownId = searchButton.id

        disableOffline.nextFocusUpId = selectNone.id
        disableOffline.nextFocusLeftId = discoverButton.id
        disableOffline.nextFocusDownId = searchButton.id

        searchButton.nextFocusUpId = discoverButton.id
    }

    private fun rebuildList() {
        listContainer.removeAllViews()
        rows.clear()

        val all = MOOSAServerRegistry.snapshot()
        selectionControls.visibility = if (all.isEmpty()) View.GONE else View.VISIBLE
        searchButton.visibility = if (all.isEmpty()) View.GONE else View.VISIBLE
        disableOffline.visibility = if (all.any { it.lastResult == false }) View.VISIBLE else View.GONE
        countText.text =
            "${all.count { !MOOSAServerRegistry.isDisabled(it.key) }} / ${all.size} enabled"

        val visible = all.filter {
            query.isBlank() ||
                it.label.contains(query, ignoreCase = true)
        }

        if (visible.isEmpty()) {
            listContainer.addView(
                label(
                    "No servers found yet. Choose Scan & Test Servers.",
                    16f,
                    Color.LTGRAY
                ).apply {
                    setPadding(dp(8), dp(16), dp(8), dp(16))
                }
            )
            searchButton.nextFocusDownId = searchButton.id
            return
        }

        visible.forEach { entry ->
            val refs = buildServerRow(entry)
            rows += refs
            listContainer.addView(
                refs.row,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    dp(60)
                ).apply { bottomMargin = dp(5) }
            )
        }

        wireRows()
    }

    private fun currentEntry(key: String): MOOSAServerRegistry.Entry? =
        MOOSAServerRegistry.snapshot().firstOrNull { it.key == key }

    private fun buildServerRow(
        entry: MOOSAServerRegistry.Entry
    ): RowRefs {
        val context = requireContext()

        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(5), dp(8), dp(5))
            isClickable = true
            styleFocusable(this, MOOSATVSettingsStyle.panel, 9)
        }

        val check = CheckBox(context).apply {
            isChecked = !MOOSAServerRegistry.isDisabled(entry.key)
            isFocusable = false
            isClickable = false
            buttonTintList = ColorStateList.valueOf(Color.rgb(225, 225, 225))
        }
        row.addView(
            check,
            LinearLayout.LayoutParams(dp(46), ViewGroup.LayoutParams.MATCH_PARENT)
        )

        val name = label(entry.label, 15.5f, Color.WHITE, true).apply {
            maxLines = 2
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        row.addView(
            name,
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        )

        val status = TextView(context).apply {
            textSize = 11.5f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setPadding(dp(9), dp(6), dp(9), dp(6))
            isFocusable = false
        }
        row.addView(
            status,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { marginEnd = dp(10) }
        )

        val test = actionButton("TEST", Color.rgb(53, 53, 53))
        row.addView(
            test,
            LinearLayout.LayoutParams(dp(82), dp(40))
        )

        val refs = RowRefs(entry.key, row, check, status, test)
        refreshRow(refs)

        row.setOnClickListener {
            check.isChecked = !check.isChecked
        }

        check.setOnCheckedChangeListener { _, enabled ->
            if (!bulkUpdating) {
                MOOSAServerRegistry.setDisabled(
                    entry.key,
                    !enabled
                )
            }
            updateCountOnly()
        }

        test.setOnClickListener {
            testServer(refs)
        }

        return refs
    }

    private fun refreshRow(refs: RowRefs) {
        val entry = currentEntry(refs.key) ?: return
        val (text, color) = when (entry.lastResult) {
            true -> "ONLINE" to Color.rgb(53, 53, 53)
            false -> "OFFLINE" to Color.rgb(53, 53, 53)
            null -> "UNKNOWN" to Color.rgb(53, 53, 53)
        }

        refs.status.text =
            if (entry.variants > 1) {
                "$text • ${entry.variants}"
            } else {
                text
            }
        refs.status.background = rounded(MOOSATVSettingsStyle.panel, 5, MOOSATVSettingsStyle.separator)
    }

    private fun wireRows() {
        if (rows.isEmpty()) return

        searchButton.nextFocusDownId = rows.first().row.id

        rows.forEachIndexed { index, refs ->
            val previous = rows.getOrNull(index - 1)
            val next = rows.getOrNull(index + 1)

            refs.row.nextFocusRightId = refs.test.id
            refs.test.nextFocusLeftId = refs.row.id

            refs.row.nextFocusUpId =
                previous?.row?.id ?: searchButton.id
            refs.test.nextFocusUpId =
                previous?.test?.id ?: searchButton.id

            refs.row.nextFocusDownId =
                next?.row?.id ?: refs.row.id
            refs.test.nextFocusDownId =
                next?.test?.id ?: refs.test.id
        }
    }

    private fun updateCountOnly() {
        val all = MOOSAServerRegistry.snapshot()
        countText.text =
            "${all.count { !MOOSAServerRegistry.isDisabled(it.key) }} / ${all.size} enabled"
    }

    private fun testServer(refs: RowRefs) {
        if (!runningTests.add(refs.key)) return

        refs.test.text = "…"
        refs.status.text = "• TESTING…"
        refs.status.background = rounded(MOOSATVSettingsStyle.panel, 5, MOOSATVSettingsStyle.separator)

        lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                MOOSAServerRegistry.test(refs.key)
            }

            runningTests.remove(refs.key)

            if (isAdded) {
                refs.test.text = "TEST"
                refreshRow(refs)
            }
        }
    }

    private fun discoverAndTest() {
        if (scanRunning) return
        scanRunning = true

        val sourceTotal =
            MOOSASourceTester.sources().size.coerceAtLeast(1)
        val sourceDone = AtomicInteger(0)

        progressBar.max = sourceTotal
        progressBar.progress = 0
        progressBar.visibility = View.VISIBLE
        progressText.visibility = View.VISIBLE
        progressText.text = "Discovering sources 0/$sourceTotal"
        discoverButton.text = "SCANNING…"

        lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                MOOSASourceTester.testAll(
                    discoveryDepth = 2
                ) { _, state ->
                    if (
                        state.status ==
                        MOOSASourceTester.Status.TESTING
                    ) {
                        return@testAll
                    }

                    val completed =
                        sourceDone.incrementAndGet()

                    progressBar.post {
                        progressBar.max = sourceTotal
                        progressBar.progress = completed
                        progressText.text =
                            "Discovering $completed/$sourceTotal • " +
                                "${MOOSAServerRegistry.snapshot().size} servers"
                    }
                }

                val serverTotal =
                    MOOSAServerRegistry.snapshot().size

                progressBar.post {
                    progressBar.max =
                        serverTotal.coerceAtLeast(1)
                    progressBar.progress = 0
                    progressText.text =
                        "Testing servers 0/$serverTotal"
                    discoverButton.text = "TESTING SERVERS…"
                }

                MOOSAServerRegistry.testAll(
                    concurrency = 8
                ) { done, total ->
                    progressBar.post {
                        progressBar.max =
                            total.coerceAtLeast(1)
                        progressBar.progress = done
                        progressText.text =
                            "Testing servers $done/$total"
                    }
                }
            }

            if (isAdded) {
                scanRunning = false
                discoverButton.text =
                    "Scan & Test Servers"

                val all = MOOSAServerRegistry.snapshot()
                val online =
                    all.count { it.lastResult == true }
                val offline =
                    all.count { it.lastResult == false }

                progressBar.max =
                    all.size.coerceAtLeast(1)
                progressBar.progress = all.size
                progressText.text =
                    "Finished • ${all.size} servers • " +
                        "$online online • $offline offline"

                rebuildList()

                Toast.makeText(
                    requireContext(),
                    "Server scan finished.",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    private fun openSearchDialog() {
        // Search in the same TV Native page; no mismatched AlertDialog.
        searchInput.setText(query)
        searchPanel.visibility = View.VISIBLE
        searchInput.requestFocus()
    }

    companion object {
        fun show(context: Context) {
            val activity = context as? AppCompatActivity ?: return
            MOOSAServerManagerDialog().show(
                activity.supportFragmentManager,
                "moosa_server_manager"
            )
        }
    }
}
