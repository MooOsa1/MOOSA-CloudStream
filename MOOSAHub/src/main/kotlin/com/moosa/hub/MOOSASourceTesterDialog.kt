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
import android.widget.FrameLayout
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
import java.util.concurrent.atomic.AtomicInteger

class MOOSASourceTesterDialog : DialogFragment() {
    private lateinit var listContainer: LinearLayout
    private lateinit var countText: TextView
    private lateinit var progressBar: ProgressBar
    private lateinit var progressText: TextView
    private lateinit var testAllButton: Button
    private lateinit var searchButton: Button

    private lateinit var selectAll: Button
    private lateinit var selectNone: Button
    private lateinit var disableOffline: Button
    private lateinit var saveProfile: Button
    private lateinit var loadProfile: Button
    private lateinit var deleteProfile: Button

    private val rowFocusPairs = mutableListOf<Pair<View, Button>>()
    private var query: String = ""

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

    private fun decorateFocusable(view: View, baseColor: Int) {
        view.id = View.generateViewId()
        view.isFocusable = true
        view.isFocusableInTouchMode = false
        view.setOnFocusChangeListener { focused, hasFocus ->
            focused.scaleX = if (hasFocus) 1.025f else 1f
            focused.scaleY = if (hasFocus) 1.025f else 1f
            focused.alpha = if (hasFocus) 1f else 0.92f
            focused.background = rounded(
                if (hasFocus) Color.rgb(42, 117, 190) else baseColor,
                11,
                if (hasFocus) Color.WHITE else Color.argb(70, 255, 255, 255),
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
            decorateFocusable(this, color)
        }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val context = requireContext()

        val root = FrameLayout(context).apply {
            setPadding(dp(20), dp(16), dp(20), dp(18))
            background = rounded(
                Color.rgb(16, 25, 35),
                20,
                Color.rgb(120, 135, 150),
                2
            )
        }

        val scroll = ScrollView(context).apply {
            isFillViewport = true
            isFocusable = false
            descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
        }

        val content = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
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

        val header = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        header.addView(
            label("Select / Test Sources", 27f, Color.WHITE, true),
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        )
        countText = label("", 15f, Color.LTGRAY, true)
        header.addView(countText)
        content.addView(header)

        content.addView(
            label(
                "ONLINE = a playable server was verified.  UNVERIFIED = the source responded, but no playable link was confirmed.",
                14f,
                Color.LTGRAY
            ).apply {
                setPadding(0, dp(6), 0, dp(12))
            }
        )

        val row1 = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        selectAll = actionButton("SELECT ALL", Color.rgb(35, 180, 75))
        selectNone = actionButton("SELECT NONE", Color.rgb(210, 45, 55))
        row1.addView(
            selectAll,
            LinearLayout.LayoutParams(0, dp(64), 1f).apply { marginEnd = dp(8) }
        )
        row1.addView(
            selectNone,
            LinearLayout.LayoutParams(0, dp(64), 1f).apply { marginStart = dp(8) }
        )
        content.addView(row1)

        val row2 = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(8), 0, dp(8))
        }
        testAllButton = actionButton(
            "DISCOVER & TEST ALL",
            Color.rgb(30, 105, 180)
        )
        disableOffline = actionButton(
            "DISABLE OFFLINE",
            Color.rgb(80, 80, 90)
        )
        row2.addView(
            testAllButton,
            LinearLayout.LayoutParams(0, dp(60), 1f).apply { marginEnd = dp(8) }
        )
        row2.addView(
            disableOffline,
            LinearLayout.LayoutParams(0, dp(60), 1f).apply { marginStart = dp(8) }
        )
        content.addView(row2)

        progressBar = ProgressBar(
            context,
            null,
            android.R.attr.progressBarStyleHorizontal
        ).apply {
            max = 100
            progress = 0
            visibility = View.GONE
        }
        content.addView(
            progressBar,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(10)
            ).apply {
                topMargin = dp(4)
            }
        )

        progressText = label("", 13f, Color.LTGRAY, true).apply {
            visibility = View.GONE
            setPadding(0, dp(4), 0, dp(8))
        }
        content.addView(progressText)

        val row3 = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        saveProfile = actionButton("SAVE PROFILE", Color.rgb(35, 120, 210))
        loadProfile = actionButton("LOAD PROFILE", Color.rgb(40, 155, 75))
        deleteProfile = actionButton("DELETE PROFILE", Color.rgb(205, 50, 55))
        row3.addView(
            saveProfile,
            LinearLayout.LayoutParams(0, dp(58), 1f).apply { marginEnd = dp(5) }
        )
        row3.addView(
            loadProfile,
            LinearLayout.LayoutParams(0, dp(58), 1f).apply {
                marginStart = dp(5)
                marginEnd = dp(5)
            }
        )
        row3.addView(
            deleteProfile,
            LinearLayout.LayoutParams(0, dp(58), 1f).apply { marginStart = dp(5) }
        )
        content.addView(row3)

        searchButton = actionButton(
            "SEARCH SOURCES…",
            Color.rgb(35, 45, 55)
        )
        content.addView(
            searchButton,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(58)
            ).apply {
                topMargin = dp(10)
            }
        )

        listContainer = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(10), 0, dp(12))
        }
        content.addView(listContainer)

        selectAll.setOnClickListener {
            MOOSASourceTester.setAll(true)
            rebuildList()
        }
        selectNone.setOnClickListener {
            MOOSASourceTester.setAll(false)
            rebuildList()
        }
        disableOffline.setOnClickListener {
            MOOSASourceTester.disableOffline()
            rebuildList()
        }
        testAllButton.setOnClickListener { runAllTests() }
        saveProfile.setOnClickListener { promptSaveProfile() }
        loadProfile.setOnClickListener { chooseProfile(load = true) }
        deleteProfile.setOnClickListener { chooseProfile(load = false) }
        searchButton.setOnClickListener { openSearchDialog() }

        wireTopFocus()
        rebuildList()

        android.os.Handler(android.os.Looper.getMainLooper()).post {
            selectAll.requestFocus()
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
                (metrics.widthPixels * 0.94f).toInt(),
                (metrics.heightPixels * 0.94f).toInt()
            )
            setBackgroundDrawableResource(android.R.color.transparent)
        }
    }

    private fun wireTopFocus() {
        selectAll.nextFocusRightId = selectNone.id
        selectAll.nextFocusDownId = testAllButton.id

        selectNone.nextFocusLeftId = selectAll.id
        selectNone.nextFocusDownId = disableOffline.id

        testAllButton.nextFocusUpId = selectAll.id
        testAllButton.nextFocusRightId = disableOffline.id
        testAllButton.nextFocusDownId = saveProfile.id

        disableOffline.nextFocusUpId = selectNone.id
        disableOffline.nextFocusLeftId = testAllButton.id
        disableOffline.nextFocusDownId = deleteProfile.id

        saveProfile.nextFocusUpId = testAllButton.id
        saveProfile.nextFocusRightId = loadProfile.id
        saveProfile.nextFocusDownId = searchButton.id

        loadProfile.nextFocusUpId = testAllButton.id
        loadProfile.nextFocusLeftId = saveProfile.id
        loadProfile.nextFocusRightId = deleteProfile.id
        loadProfile.nextFocusDownId = searchButton.id

        deleteProfile.nextFocusUpId = disableOffline.id
        deleteProfile.nextFocusLeftId = loadProfile.id
        deleteProfile.nextFocusDownId = searchButton.id

        searchButton.nextFocusUpId = loadProfile.id
    }

    private fun rebuildList() {
        if (!::listContainer.isInitialized) return

        listContainer.removeAllViews()
        rowFocusPairs.clear()

        val all = MOOSASourceTester.sources()
        countText.text =
            "${all.count { MOOSASourceTester.isEnabled(it) }} / ${all.size} enabled"

        val visible = all.filter {
            query.isBlank() ||
                it.name.contains(query, ignoreCase = true) ||
                it.mainUrl.contains(query, ignoreCase = true)
        }

        visible.forEach { api ->
            val pair = buildSourceRow(api)
            rowFocusPairs += pair
            listContainer.addView(
                pair.first,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    dp(70)
                ).apply {
                    bottomMargin = dp(5)
                }
            )
        }

        wireListFocus()
    }

    private fun buildSourceRow(api: MainAPI): Pair<View, Button> {
        val context = requireContext()

        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(5), dp(8), dp(5))
            isClickable = true
            decorateFocusable(this, Color.rgb(24, 31, 40))
        }

        val check = CheckBox(context).apply {
            isChecked = MOOSASourceTester.isEnabled(api)
            isFocusable = false
            isClickable = false
            buttonTintList = ColorStateList.valueOf(Color.rgb(45, 145, 255))
        }
        row.addView(
            check,
            LinearLayout.LayoutParams(dp(48), ViewGroup.LayoutParams.MATCH_PARENT)
        )

        row.addView(
            label(api.name, 17f, Color.WHITE, true),
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        )

        val state = MOOSASourceTester.snapshot(api)
        val status = TextView(context).apply {
            val pair = when (state.status) {
                MOOSASourceTester.Status.ONLINE ->
                    "• ONLINE" to Color.rgb(30, 180, 85)
                MOOSASourceTester.Status.UNVERIFIED ->
                    "• UNVERIFIED" to Color.rgb(215, 145, 35)
                MOOSASourceTester.Status.OFFLINE ->
                    "• OFFLINE" to Color.rgb(220, 55, 55)
                MOOSASourceTester.Status.TESTING ->
                    "• TESTING" to Color.rgb(70, 125, 210)
                MOOSASourceTester.Status.UNKNOWN ->
                    "• UNKNOWN" to Color.rgb(95, 105, 115)
            }
            text = if (state.serverCount > 0) {
                "${pair.first}  ${state.serverCount}"
            } else {
                pair.first
            }
            textSize = 12f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setPadding(dp(9), dp(6), dp(9), dp(6))
            background = rounded(pair.second, 8)
            isFocusable = false
        }
        row.addView(
            status,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { marginEnd = dp(10) }
        )

        val test = actionButton("TEST", Color.rgb(45, 55, 65))
        row.addView(
            test,
            LinearLayout.LayoutParams(dp(88), dp(46))
        )

        row.setOnClickListener {
            check.isChecked = !check.isChecked
        }
        check.setOnCheckedChangeListener { _, enabled ->
            MOOSASourceTester.setEnabled(api, enabled)
            updateCountOnly()
        }
        test.setOnClickListener { runSingleTest(api, test) }

        return row to test
    }

    private fun wireListFocus() {
        if (rowFocusPairs.isEmpty()) {
            searchButton.nextFocusDownId = searchButton.id
            return
        }

        val firstRow = rowFocusPairs.first().first
        val firstTest = rowFocusPairs.first().second
        searchButton.nextFocusDownId = firstRow.id

        rowFocusPairs.forEachIndexed { index, pair ->
            val row = pair.first
            val test = pair.second
            val previous = rowFocusPairs.getOrNull(index - 1)
            val next = rowFocusPairs.getOrNull(index + 1)

            row.nextFocusRightId = test.id
            test.nextFocusLeftId = row.id

            row.nextFocusUpId = previous?.first?.id ?: searchButton.id
            test.nextFocusUpId = previous?.second?.id ?: searchButton.id

            row.nextFocusDownId = next?.first?.id ?: row.id
            test.nextFocusDownId = next?.second?.id ?: test.id
        }

        deleteProfile.nextFocusDownId = firstTest.id
    }

    private fun updateCountOnly() {
        val all = MOOSASourceTester.sources()
        countText.text =
            "${all.count { MOOSASourceTester.isEnabled(it) }} / ${all.size} enabled"
    }

    private fun runSingleTest(api: MainAPI, button: Button) {
        button.isEnabled = false
        button.text = "…"

        lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                MOOSASourceTester.test(api, discoveryDepth = 2)
            }

            if (isAdded) {
                button.isEnabled = true
                button.text = "TEST"
                rebuildList()
                button.requestFocus()
            }
        }
    }

    private fun runAllTests() {
        testAllButton.isEnabled = false

        val total = MOOSASourceTester.sources().size.coerceAtLeast(1)
        val done = AtomicInteger(0)
        val online = AtomicInteger(0)
        val unverified = AtomicInteger(0)
        val offline = AtomicInteger(0)

        progressBar.max = total
        progressBar.progress = 0
        progressBar.visibility = View.VISIBLE
        progressText.visibility = View.VISIBLE
        progressText.text = "Scanning 0/$total"
        testAllButton.text = "TESTING…"

        lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                MOOSASourceTester.testAll(discoveryDepth = 2) { _, state ->
                    if (state.status == MOOSASourceTester.Status.TESTING) {
                        return@testAll
                    }

                    when (state.status) {
                        MOOSASourceTester.Status.ONLINE -> online.incrementAndGet()
                        MOOSASourceTester.Status.UNVERIFIED -> unverified.incrementAndGet()
                        MOOSASourceTester.Status.OFFLINE -> offline.incrementAndGet()
                        else -> Unit
                    }

                    val completed = done.incrementAndGet()
                    progressBar.post {
                        progressBar.progress = completed
                        progressText.text =
                            "Scanning $completed/$total  •  Online ${online.get()}  •  Unverified ${unverified.get()}  •  Offline ${offline.get()}"
                    }
                }
            }

            if (isAdded) {
                testAllButton.isEnabled = true
                testAllButton.text = "DISCOVER & TEST ALL"
                progressBar.progress = total
                progressText.text =
                    "Finished • Online ${online.get()} • Unverified ${unverified.get()} • Offline ${offline.get()}"
                rebuildList()
                testAllButton.requestFocus()

                Toast.makeText(
                    requireContext(),
                    "Source scan finished.",
                    Toast.LENGTH_LONG
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

    private fun promptSaveProfile() {
        val input = EditText(requireContext()).apply {
            hint = "Profile name"
            setSingleLine(true)
        }

        AlertDialog.Builder(requireContext())
            .setTitle("Save Profile")
            .setView(input)
            .setPositiveButton("Save") { _, _ ->
                input.text
                    ?.toString()
                    ?.trim()
                    ?.takeIf { it.isNotBlank() }
                    ?.let(MOOSASettingsStore::saveSourceProfile)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun chooseProfile(load: Boolean) {
        val profiles = MOOSASettingsStore.sourceProfiles().keys.toTypedArray()

        if (profiles.isEmpty()) {
            Toast.makeText(
                requireContext(),
                "No profiles saved.",
                Toast.LENGTH_SHORT
            ).show()
            return
        }

        AlertDialog.Builder(requireContext())
            .setTitle(if (load) "Load Profile" else "Delete Profile")
            .setItems(profiles) { _, which ->
                val name = profiles[which]
                if (load) {
                    MOOSASettingsStore.loadSourceProfile(name)
                    rebuildList()
                } else {
                    MOOSASettingsStore.deleteSourceProfile(name)
                }
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
