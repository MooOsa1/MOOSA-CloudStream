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

    private lateinit var listContainer: LinearLayout
    private lateinit var countText: TextView
    private lateinit var progressBar: ProgressBar
    private lateinit var progressText: TextView
    private lateinit var discoverButton: Button
    private lateinit var searchButton: Button
    private lateinit var selectAll: Button
    private lateinit var selectNone: Button
    private lateinit var disableOffline: Button

    private val rows = mutableListOf<RowRefs>()
    private var query = ""

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

    private fun styleFocusable(
        view: View,
        baseColor: Int,
        radius: Int = 10
    ) {
        view.id = View.generateViewId()
        view.isFocusable = true
        view.isFocusableInTouchMode = false
        view.background = rounded(
            baseColor,
            radius,
            Color.argb(85, 255, 255, 255)
        )

        view.setOnFocusChangeListener { focused, hasFocus ->
            focused.scaleX = if (hasFocus) 1.025f else 1f
            focused.scaleY = if (hasFocus) 1.025f else 1f
            focused.background = rounded(
                if (hasFocus) Color.rgb(35, 110, 190) else baseColor,
                radius,
                if (hasFocus) Color.WHITE
                else Color.argb(85, 255, 255, 255),
                if (hasFocus) 2 else 1
            )
        }
    }

    private fun actionButton(text: String, color: Int): Button =
        Button(requireContext()).apply {
            this.text = text
            setTextColor(Color.WHITE)
            textSize = 13f
            minWidth = 0
            minimumWidth = 0
            styleFocusable(this, color)
        }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val context = requireContext()

        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(14), dp(18), dp(16))
            background = rounded(
                Color.rgb(13, 29, 47),
                18,
                Color.rgb(75, 143, 205),
                2
            )
        }

        val header = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        header.addView(
            label("Server Manager", 27f, Color.WHITE, true),
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        )
        countText = label("", 15f, Color.LTGRAY, true)
        header.addView(countText)
        root.addView(header)

        root.addView(
            label(
                "DISCOVER scans sources in the background. ONLINE means the final stream link passed two probes.",
                13f,
                Color.LTGRAY
            ).apply {
                setPadding(0, dp(4), 0, dp(10))
            }
        )

        val controls1 = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        selectAll = actionButton("SELECT ALL", Color.rgb(28, 150, 78))
        selectNone = actionButton("SELECT NONE", Color.rgb(185, 52, 60))
        controls1.addView(
            selectAll,
            LinearLayout.LayoutParams(0, dp(58), 1f).apply { marginEnd = dp(6) }
        )
        controls1.addView(
            selectNone,
            LinearLayout.LayoutParams(0, dp(58), 1f).apply { marginStart = dp(6) }
        )
        root.addView(controls1)

        val controls2 = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(7), 0, 0)
        }
        discoverButton = actionButton(
            "DISCOVER & TEST SERVERS",
            Color.rgb(24, 95, 180)
        )
        disableOffline = actionButton(
            "DISABLE OFFLINE",
            Color.rgb(90, 70, 120)
        )
        controls2.addView(
            discoverButton,
            LinearLayout.LayoutParams(0, dp(58), 1f).apply { marginEnd = dp(6) }
        )
        controls2.addView(
            disableOffline,
            LinearLayout.LayoutParams(0, dp(58), 1f).apply { marginStart = dp(6) }
        )
        root.addView(controls2)

        progressBar = ProgressBar(
            context,
            null,
            android.R.attr.progressBarStyleHorizontal
        ).apply {
            max = 100
            progress = 0
            visibility = View.GONE
        }
        root.addView(
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
        root.addView(progressText)

        searchButton = actionButton("SEARCH SERVERS…", Color.rgb(50, 60, 72))
        root.addView(
            searchButton,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(54)
            ).apply { topMargin = dp(8) }
        )

        val scroll = ScrollView(context).apply {
            isFillViewport = true
            isFocusable = false
            descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
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
        root.addView(
            scroll,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        )

        selectAll.setOnClickListener {
            MOOSAServerRegistry.snapshot().forEach {
                MOOSAServerRegistry.setDisabled(it.key, false)
            }
            rows.forEach { it.check.isChecked = true }
            updateCountOnly()
        }

        selectNone.setOnClickListener {
            MOOSAServerRegistry.snapshot().forEach {
                MOOSAServerRegistry.setDisabled(it.key, true)
            }
            rows.forEach { it.check.isChecked = false }
            updateCountOnly()
        }

        disableOffline.setOnClickListener {
            MOOSAServerRegistry.snapshot()
                .filter { it.lastResult == false }
                .forEach {
                    MOOSAServerRegistry.setDisabled(it.key, true)
                }

            rows.forEach { refs ->
                refs.check.isChecked =
                    !MOOSAServerRegistry.isDisabled(refs.key)
            }
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
        val metrics = resources.displayMetrics
        dialog?.window?.apply {
            setLayout(
                (metrics.widthPixels * 0.95f).toInt(),
                (metrics.heightPixels * 0.95f).toInt()
            )
            setBackgroundDrawableResource(android.R.color.transparent)
        }
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
        countText.text =
            "${all.count { !MOOSAServerRegistry.isDisabled(it.key) }} / ${all.size} enabled"

        val visible = all.filter {
            query.isBlank() ||
                it.label.contains(query, ignoreCase = true)
        }

        if (visible.isEmpty()) {
            listContainer.addView(
                label(
                    "No servers yet. Press DISCOVER & TEST SERVERS.",
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
                    dp(68)
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
            styleFocusable(this, Color.rgb(24, 39, 55), 9)
        }

        val check = CheckBox(context).apply {
            isChecked = !MOOSAServerRegistry.isDisabled(entry.key)
            isFocusable = false
            isClickable = false
            buttonTintList = ColorStateList.valueOf(Color.rgb(45, 145, 255))
        }
        row.addView(
            check,
            LinearLayout.LayoutParams(dp(46), ViewGroup.LayoutParams.MATCH_PARENT)
        )

        val name = label(entry.label, 16f, Color.WHITE, true).apply {
            maxLines = 2
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        row.addView(
            name,
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        )

        val status = TextView(context).apply {
            textSize = 12f
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

        val test = actionButton("TEST", Color.rgb(55, 65, 78))
        row.addView(
            test,
            LinearLayout.LayoutParams(dp(88), dp(44))
        )

        val refs = RowRefs(entry.key, row, check, status, test)
        refreshRow(refs)

        row.setOnClickListener {
            check.isChecked = !check.isChecked
        }

        check.setOnCheckedChangeListener { _, enabled ->
            MOOSAServerRegistry.setDisabled(entry.key, !enabled)
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
            true -> "• ONLINE" to Color.rgb(25, 165, 82)
            false -> "• OFFLINE" to Color.rgb(205, 58, 62)
            null -> "• UNKNOWN" to Color.rgb(90, 100, 112)
        }
        refs.status.text = text
        refs.status.background = rounded(color, 8)
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
        refs.test.isEnabled = false
        refs.test.text = "…"
        refs.status.text = "• TESTING"
        refs.status.background = rounded(Color.rgb(45, 105, 190), 8)

        lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                MOOSAServerRegistry.test(refs.key)
            }

            if (isAdded) {
                refs.test.isEnabled = true
                refs.test.text = "TEST"
                refreshRow(refs)
                refs.test.requestFocus()
            }
        }
    }

    private fun discoverAndTest() {
        discoverButton.isEnabled = false

        val total = MOOSASourceTester.sources().size.coerceAtLeast(1)
        val done = AtomicInteger(0)

        progressBar.max = total
        progressBar.progress = 0
        progressBar.visibility = View.VISIBLE
        progressText.visibility = View.VISIBLE
        progressText.text = "Sources 0/$total • Servers ${MOOSAServerRegistry.snapshot().size}"
        discoverButton.text = "DISCOVERING…"

        lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                MOOSASourceTester.testAll(discoveryDepth = 2) { _, state ->
                    if (state.status == MOOSASourceTester.Status.TESTING) {
                        return@testAll
                    }

                    val completed = done.incrementAndGet()
                    progressBar.post {
                        progressBar.progress = completed
                        progressText.text =
                            "Sources $completed/$total • Servers ${MOOSAServerRegistry.snapshot().size}"
                    }
                }
            }

            if (isAdded) {
                discoverButton.isEnabled = true
                discoverButton.text = "DISCOVER & TEST SERVERS"
                progressBar.progress = total
                progressText.text =
                    "Finished • ${MOOSAServerRegistry.snapshot().size} server groups"

                rebuildList()
                discoverButton.requestFocus()

                Toast.makeText(
                    requireContext(),
                    "Server discovery finished.",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    private fun openSearchDialog() {
        val input = EditText(requireContext()).apply {
            hint = "Server name"
            setSingleLine(true)
            setText(query)
        }

        AlertDialog.Builder(requireContext())
            .setTitle("Search Servers")
            .setView(input)
            .setPositiveButton("Search") { _, _ ->
                query = input.text?.toString()?.trim().orEmpty()
                searchButton.text = if (query.isBlank()) {
                    "SEARCH SERVERS…"
                } else {
                    "SEARCH: $query"
                }
                rebuildList()
                searchButton.requestFocus()
            }
            .setNeutralButton("Clear") { _, _ ->
                query = ""
                searchButton.text = "SEARCH SERVERS…"
                rebuildList()
            }
            .setNegativeButton("Cancel", null)
            .show()
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
