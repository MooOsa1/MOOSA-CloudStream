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
import com.lagradost.cloudstream3.MainAPI
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

class MOOSASourceTesterDialog : DialogFragment() {
    private data class RowRefs(
        val api: MainAPI,
        val row: View,
        val check: CheckBox,
        val status: TextView,
        val test: Button
    )

    private lateinit var listContainer: LinearLayout
    private lateinit var countText: TextView
    private lateinit var progressBar: ProgressBar
    private lateinit var progressText: TextView
    private lateinit var testAllButton: Button
    private lateinit var searchButton: Button
    private lateinit var selectAll: Button
    private lateinit var selectNone: Button
    private lateinit var disableOffline: Button

    private val rows = mutableListOf<RowRefs>()
    private val rowsByName = ConcurrentHashMap<String, RowRefs>()
    private var query = ""
    private val runningTests = ConcurrentHashMap.newKeySet<String>()
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
            text.startsWith("Discover") || text.startsWith("Test All")
        )

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val context = requireContext()

        val shell = MOOSATVSettingsStyle.shell(
            context, "SETTINGS / SOURCES",
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
            context, page, "DISCOVERY / PROVIDERS",
            "Sources",
            "Manage installed providers and test them."
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
                "ONLINE = verified playable link • UNVERIFIED = source responded but playback could not be confirmed",
                12f,
                MOOSATVSettingsStyle.gray
            ).apply {
                setPadding(0, dp(4), 0, dp(10))
            }
        )

        val controls1 = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        selectAll = actionButton("Select All", Color.rgb(53, 53, 53))
        selectNone = actionButton("Select None", Color.rgb(53, 53, 53))
        controls1.addView(
            selectAll,
            LinearLayout.LayoutParams(0, dp(52), 1f).apply { marginEnd = dp(6) }
        )
        controls1.addView(
            selectNone,
            LinearLayout.LayoutParams(0, dp(58), 1f).apply { marginStart = dp(6) }
        )
        page.addView(controls1)

        val controls2 = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(7), 0, 0)
        }
        testAllButton = actionButton("Test All Sources", Color.rgb(53, 53, 53))
        disableOffline = actionButton("Disable Offline", Color.rgb(53, 53, 53))
        controls2.addView(
            testAllButton,
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

        searchButton = actionButton("Search Sources…", Color.rgb(53, 53, 53))
        page.addView(
            searchButton,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(48)
            ).apply { topMargin = dp(8) }
        )

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
            MOOSASourceTester.setAll(true)
            bulkUpdating = true
            rows.forEach { refs ->
                refs.check.isChecked = true
            }
            bulkUpdating = false
            updateCountOnly()
        }

        selectNone.setOnClickListener {
            MOOSASourceTester.setAll(false)
            bulkUpdating = true
            rows.forEach { refs ->
                refs.check.isChecked = false
            }
            bulkUpdating = false
            updateCountOnly()
        }

        disableOffline.setOnClickListener {
            MOOSASourceTester.disableOffline()
            bulkUpdating = true
            rows.forEach { refs ->
                refs.check.isChecked =
                    MOOSASourceTester.isEnabled(refs.api)
            }
            bulkUpdating = false
            updateCountOnly()
        }

        testAllButton.setOnClickListener { runAllTests() }
        searchButton.setOnClickListener { openSearchDialog() }

        wireTopFocus()
        rebuildList()

        android.os.Handler(android.os.Looper.getMainLooper()).post {
            testAllButton.requestFocus()
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
        selectAll.nextFocusDownId = testAllButton.id

        selectNone.nextFocusLeftId = selectAll.id
        selectNone.nextFocusDownId = disableOffline.id

        testAllButton.nextFocusUpId = selectAll.id
        testAllButton.nextFocusRightId = disableOffline.id
        testAllButton.nextFocusDownId = searchButton.id

        disableOffline.nextFocusUpId = selectNone.id
        disableOffline.nextFocusLeftId = testAllButton.id
        disableOffline.nextFocusDownId = searchButton.id

        searchButton.nextFocusUpId = testAllButton.id
    }

    private fun rebuildList() {
        listContainer.removeAllViews()
        rows.clear()
        rowsByName.clear()

        val all = MOOSASourceTester.sources()
        countText.text =
            "${all.count { MOOSASourceTester.isEnabled(it) }} / ${all.size} enabled" +
                " • ${MOOSAHubProvider().animeSourceCount()} anime"

        val visible = all.filter {
            query.isBlank() ||
                it.name.contains(query, ignoreCase = true) ||
                it.mainUrl.contains(query, ignoreCase = true)
        }

        visible.forEach { api ->
            val refs = buildSourceRow(api)
            rows += refs
            rowsByName["${api.name}|${api.mainUrl}"] = refs
            listContainer.addView(
                refs.row,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    dp(60)
                ).apply { bottomMargin = dp(5) }
            )
        }

        disableOffline.visibility = if (all.any {
            MOOSASourceTester.snapshot(it).status == MOOSASourceTester.Status.OFFLINE
        }) View.VISIBLE else View.GONE
        wireRows()
    }

    private fun buildSourceRow(api: MainAPI): RowRefs {
        val context = requireContext()

        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(5), dp(8), dp(5))
            isClickable = true
            styleFocusable(this, MOOSATVSettingsStyle.panel, 9)
        }

        val check = CheckBox(context).apply {
            isChecked = MOOSASourceTester.isEnabled(api)
            isFocusable = false
            isClickable = false
            buttonTintList = ColorStateList.valueOf(Color.rgb(225, 225, 225))
        }
        row.addView(
            check,
            LinearLayout.LayoutParams(dp(46), ViewGroup.LayoutParams.MATCH_PARENT)
        )

        row.addView(
            label(api.name, 14f, Color.WHITE, true).apply { maxLines = 2 },
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

        val refs = RowRefs(api, row, check, status, test)
        refreshRow(refs)

        row.setOnClickListener {
            check.isChecked = !check.isChecked
        }

        check.setOnCheckedChangeListener { _, enabled ->
            if (!bulkUpdating) {
                MOOSASourceTester.setEnabled(api, enabled)
            }
            updateCountOnly()
        }

        test.setOnClickListener {
            runSingleTest(refs)
        }

        return refs
    }

    private fun refreshRow(refs: RowRefs) {
        val state = MOOSASourceTester.snapshot(refs.api)
        val (text, color) = when (state.status) {
            MOOSASourceTester.Status.ONLINE ->
                "• ONLINE" to Color.rgb(53, 53, 53)
            MOOSASourceTester.Status.UNVERIFIED ->
                "• UNVERIFIED" to Color.rgb(53, 53, 53)
            MOOSASourceTester.Status.OFFLINE ->
                "• OFFLINE" to Color.rgb(53, 53, 53)
            MOOSASourceTester.Status.TESTING ->
                "• TESTING" to Color.rgb(53, 53, 53)
            MOOSASourceTester.Status.UNKNOWN ->
                "• UNKNOWN" to Color.rgb(53, 53, 53)
        }

        refs.status.text =
            if (state.serverCount > 0) "$text  ${state.serverCount}"
            else text
        refs.status.background = rounded(MOOSATVSettingsStyle.panel, 5, MOOSATVSettingsStyle.separator)
    }

    private fun wireRows() {
        if (rows.isEmpty()) {
            searchButton.nextFocusDownId = searchButton.id
            return
        }

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

    private fun updateOfflineAction() {
        val all = MOOSASourceTester.sources()
        disableOffline.visibility = if (all.any {
            MOOSASourceTester.snapshot(it).status == MOOSASourceTester.Status.OFFLINE
        }) View.VISIBLE else View.GONE
    }

    private fun updateCountOnly() {
        val all = MOOSASourceTester.sources()
        countText.text =
            "${all.count { MOOSASourceTester.isEnabled(it) }} / ${all.size} enabled" +
                " • ${MOOSAHubProvider().animeSourceCount()} anime"
    }

    private fun runSingleTest(refs: RowRefs) {
        if (!runningTests.add(refs.api.name)) return

        refs.test.text = "…"

        lifecycleScope.launch {
            refs.status.text = "• TESTING"
            refs.status.background = rounded(MOOSATVSettingsStyle.panel, 5, MOOSATVSettingsStyle.separator)

            withContext(Dispatchers.IO) {
                MOOSASourceTester.test(
                    api = refs.api,
                    discoveryDepth = 2
                ) {
                    refs.status.post {
                        refreshRow(refs)
                    }
                }
            }

            runningTests.remove(refs.api.name)

            if (isAdded) {
                refs.test.text = "TEST"
                refreshRow(refs)
                updateOfflineAction()
            }
        }
    }

    private fun runAllTests() {
        if (scanRunning) return
        scanRunning = true

        val total = MOOSASourceTester.sources().size.coerceAtLeast(1)
        val done = AtomicInteger(0)
        val online = AtomicInteger(0)
        val unverified = AtomicInteger(0)
        val offline = AtomicInteger(0)

        progressBar.max = total
        progressBar.progress = 0
        progressBar.visibility = View.VISIBLE
        progressText.visibility = View.VISIBLE
        progressText.text = "0/$total"
        testAllButton.text = "TESTING…"

        lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                MOOSASourceTester.testAll(discoveryDepth = 2) { api, state ->
                    rowsByName["${api.name}|${api.mainUrl}"]?.let { refs ->
                        refs.status.post {
                            refreshRow(refs)
                        }
                    }

                    if (state.status == MOOSASourceTester.Status.TESTING) {
                        return@testAll
                    }

                    when (state.status) {
                        MOOSASourceTester.Status.ONLINE ->
                            online.incrementAndGet()
                        MOOSASourceTester.Status.UNVERIFIED ->
                            unverified.incrementAndGet()
                        MOOSASourceTester.Status.OFFLINE ->
                            offline.incrementAndGet()
                        else -> Unit
                    }

                    val completed = done.incrementAndGet()
                    progressBar.post {
                        progressBar.progress = completed
                        progressText.text =
                            "$completed/$total  • Online ${online.get()}  • Unverified ${unverified.get()}  • Offline ${offline.get()}"
                    }
                }
            }

            if (isAdded) {
                scanRunning = false
                testAllButton.text = "TEST ALL SOURCES"
                updateOfflineAction()
                progressBar.progress = total
                progressText.text =
                    "Finished • Online ${online.get()} • Unverified ${unverified.get()} • Offline ${offline.get()}"
                Toast.makeText(
                    requireContext(),
                    "Source test finished.",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    private fun openSearchDialog() {
        val input = EditText(requireContext()).apply {
            hint = "Source name"
            setSingleLine(true)
            setText(query)
        }

        AlertDialog.Builder(requireContext())
            .setTitle("Search Sources")
            .setView(input)
            .setPositiveButton("Search") { _, _ ->
                query = input.text?.toString()?.trim().orEmpty()
                searchButton.text = if (query.isBlank()) {
                    "SEARCH SOURCES…"
                } else {
                    "SEARCH: $query"
                }
                rebuildList()
                searchButton.requestFocus()
            }
            .setNeutralButton("Clear") { _, _ ->
                query = ""
                searchButton.text = "SEARCH SOURCES…"
                rebuildList()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    companion object {
        fun show(context: Context) {
            val activity = context as? AppCompatActivity ?: return
            MOOSASourceTesterDialog().show(
                activity.supportFragmentManager,
                "moosa_source_tester"
            )
        }
    }
}
