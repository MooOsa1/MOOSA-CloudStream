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
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.widget.addTextChangedListener
import androidx.fragment.app.DialogFragment
import androidx.lifecycle.lifecycleScope
import com.lagradost.cloudstream3.MainAPI
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MOOSASourceTesterDialog : DialogFragment() {
    private lateinit var listContainer: LinearLayout
    private lateinit var countText: TextView
    private lateinit var search: EditText
    private lateinit var testAllButton: Button
    private var query: String = ""

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    private fun rounded(color: Int, radius: Int, stroke: Int? = null): GradientDrawable =
        GradientDrawable().apply {
            setColor(color)
            cornerRadius = dp(radius).toFloat()
            stroke?.let { setStroke(dp(1), it) }
        }

    private fun text(
        value: String,
        size: Float,
        color: Int = Color.WHITE,
        bold: Boolean = false
    ) = TextView(requireContext()).apply {
        this.text = value
        textSize = size
        setTextColor(color)
        if (bold) setTypeface(typeface, android.graphics.Typeface.BOLD)
    }

    private fun actionButton(label: String, color: Int): Button =
        Button(requireContext()).apply {
            text = label
            setTextColor(Color.WHITE)
            textSize = 13f
            isAllCaps = true
            isFocusable = true
            background = rounded(color, 12, Color.argb(80, 255, 255, 255))
        }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val context = requireContext()
        val root = FrameLayout(context).apply {
            setPadding(dp(20), dp(16), dp(20), dp(18))
            background = rounded(
                Color.rgb(16, 25, 35),
                20,
                Color.rgb(120, 135, 150)
            )
        }

        val scroll = ScrollView(context).apply {
            isFillViewport = true
        }
        val content = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
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
            text("Select Sources", 27f, Color.WHITE, true),
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        )
        countText = text("", 15f, Color.LTGRAY, true)
        header.addView(countText)
        content.addView(header)

        content.addView(
            text("Enable or Disable Sources", 17f, Color.LTGRAY).apply {
                setPadding(0, dp(8), 0, dp(12))
            }
        )

        val row1 = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        val selectAll = actionButton("SELECT ALL", Color.rgb(35, 180, 75))
        val selectNone = actionButton("SELECT NONE", Color.rgb(210, 45, 55))
        row1.addView(
            selectAll,
            LinearLayout.LayoutParams(0, dp(72), 1f).apply { marginEnd = dp(8) }
        )
        row1.addView(
            selectNone,
            LinearLayout.LayoutParams(0, dp(72), 1f).apply { marginStart = dp(8) }
        )
        content.addView(row1)

        val row2 = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(0, dp(10), 0, dp(10))
        }
        testAllButton = actionButton("DISCOVER & TEST ALL", Color.rgb(30, 105, 180))
        val disableOffline = actionButton("DISABLE OFFLINE", Color.rgb(80, 80, 90))
        row2.addView(
            testAllButton,
            LinearLayout.LayoutParams(0, dp(62), 1f).apply { marginEnd = dp(8) }
        )
        row2.addView(
            disableOffline,
            LinearLayout.LayoutParams(0, dp(62), 1f).apply { marginStart = dp(8) }
        )
        content.addView(row2)

        val row3 = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        val saveProfile = actionButton("SAVE PROFILE", Color.rgb(35, 120, 210))
        val loadProfile = actionButton("LOAD PROFILE", Color.rgb(40, 155, 75))
        val deleteProfile = actionButton("DELETE PROFILE", Color.rgb(205, 50, 55))
        row3.addView(
            saveProfile,
            LinearLayout.LayoutParams(0, dp(62), 1f).apply { marginEnd = dp(5) }
        )
        row3.addView(
            loadProfile,
            LinearLayout.LayoutParams(0, dp(62), 1f).apply {
                marginStart = dp(5); marginEnd = dp(5)
            }
        )
        row3.addView(
            deleteProfile,
            LinearLayout.LayoutParams(0, dp(62), 1f).apply { marginStart = dp(5) }
        )
        content.addView(row3)

        content.addView(
            View(context).apply { setBackgroundColor(Color.DKGRAY) },
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(1)
            ).apply {
                topMargin = dp(14)
                bottomMargin = dp(14)
            }
        )

        search = EditText(context).apply {
            hint = "Search sources..."
            setHintTextColor(Color.GRAY)
            setTextColor(Color.WHITE)
            textSize = 18f
            setSingleLine(true)
            setPadding(dp(16), dp(10), dp(16), dp(10))
            background = rounded(
                Color.rgb(30, 38, 48),
                12,
                Color.rgb(85, 100, 115)
            )
        }
        content.addView(
            search,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(58)
            )
        )

        listContainer = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(12), 0, dp(12))
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
        testAllButton.setOnClickListener {
            runAllTests()
        }
        saveProfile.setOnClickListener {
            promptSaveProfile()
        }
        loadProfile.setOnClickListener {
            chooseProfile(load = true)
        }
        deleteProfile.setOnClickListener {
            chooseProfile(load = false)
        }

        search.addTextChangedListener {
            query = it?.toString()?.trim().orEmpty()
            rebuildList()
        }

        rebuildList()

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

    private fun rebuildList() {
        if (!::listContainer.isInitialized) return
        listContainer.removeAllViews()

        val all = MOOSASourceTester.sources()
        val enabled = all.count { MOOSASourceTester.isEnabled(it) }
        countText.text = "$enabled / ${all.size} enabled"

        val visible = all.filter {
            query.isBlank() ||
                it.name.contains(query, ignoreCase = true) ||
                it.mainUrl.contains(query, ignoreCase = true)
        }

        visible.forEach { api ->
            listContainer.addView(
                buildSourceRow(api),
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    dp(72)
                ).apply {
                    bottomMargin = dp(6)
                }
            )
        }
    }

    private fun buildSourceRow(api: MainAPI): View {
        val context = requireContext()
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(6), dp(8), dp(6))
            background = rounded(Color.rgb(24, 31, 40), 10)
            isFocusable = true
        }

        val check = CheckBox(context).apply {
            isChecked = MOOSASourceTester.isEnabled(api)
            buttonTintList = android.content.res.ColorStateList.valueOf(
                Color.rgb(45, 145, 255)
            )
        }
        row.addView(
            check,
            LinearLayout.LayoutParams(dp(48), ViewGroup.LayoutParams.MATCH_PARENT)
        )

        val name = text(api.name, 18f, Color.WHITE, true)
        row.addView(
            name,
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        )

        val state = MOOSASourceTester.snapshot(api)
        val status = TextView(context).apply {
            val (label, color) = when (state.status) {
                MOOSASourceTester.Status.ONLINE ->
                    "• ONLINE" to Color.rgb(30, 180, 85)
                MOOSASourceTester.Status.OFFLINE ->
                    "• OFFLINE" to Color.rgb(220, 55, 55)
                MOOSASourceTester.Status.TESTING ->
                    "• TESTING" to Color.rgb(225, 170, 45)
                MOOSASourceTester.Status.UNKNOWN ->
                    "• UNKNOWN" to Color.rgb(95, 105, 115)
            }
            text = if (state.serverCount > 0) {
                "$label  ${state.serverCount}"
            } else {
                label
            }
            textSize = 13f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setPadding(dp(12), dp(7), dp(12), dp(7))
            background = rounded(color, 8)
        }
        row.addView(
            status,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { marginEnd = dp(12) }
        )

        val test = Button(context).apply {
            text = "TEST"
            textSize = 13f
            setTextColor(Color.WHITE)
            isFocusable = true
            background = rounded(Color.rgb(45, 55, 65), 9)
            minWidth = 0
            minimumWidth = 0
        }
        row.addView(
            test,
            LinearLayout.LayoutParams(dp(90), dp(48))
        )

        check.setOnCheckedChangeListener { _, checked ->
            MOOSASourceTester.setEnabled(api, checked)
            updateCountOnly()
        }
        row.setOnClickListener { check.toggle() }
        test.setOnClickListener {
            runSingleTest(api, test)
        }

        return row
    }

    private fun updateCountOnly() {
        val all = MOOSASourceTester.sources()
        countText.text =
            "${all.count { MOOSASourceTester.isEnabled(it) }} / ${all.size} enabled"
    }

    private fun runSingleTest(api: MainAPI, button: Button) {
        button.isEnabled = false
        button.text = "..."

        lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                MOOSASourceTester.test(api)
            }
            if (isAdded) {
                button.isEnabled = true
                button.text = "TEST"
                rebuildList()
            }
        }
    }

    private fun runAllTests() {
        testAllButton.isEnabled = false
        testAllButton.text = "TESTING..."

        lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                MOOSASourceTester.testAll()
            }
            if (isAdded) {
                testAllButton.isEnabled = true
                testAllButton.text = "DISCOVER & TEST ALL"
                rebuildList()
                Toast.makeText(
                    requireContext(),
                    "Source test finished. Server Manager has been updated.",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
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
                val name = input.text?.toString()?.trim().orEmpty()
                if (name.isNotBlank()) {
                    MOOSASettingsStore.saveSourceProfile(name)
                }
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
