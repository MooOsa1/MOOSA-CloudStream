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
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.widget.addTextChangedListener
import androidx.fragment.app.DialogFragment
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MOOSAServerManagerDialog : DialogFragment() {
    private lateinit var listContainer: LinearLayout
    private lateinit var countText: TextView
    private lateinit var search: EditText
    private lateinit var discoverButton: Button
    private lateinit var selectAll: Button
    private lateinit var selectNone: Button
    private lateinit var disableOffline: Button

    private val rowFocusPairs = mutableListOf<Pair<CheckBox, Button>>()
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

    private fun decorateFocusable(view: View, baseColor: Int) {
        view.id = View.generateViewId()
        view.isFocusable = true
        view.isFocusableInTouchMode = false
        view.setOnFocusChangeListener { focusedView, hasFocus ->
            focusedView.scaleX = if (hasFocus) 1.025f else 1f
            focusedView.scaleY = if (hasFocus) 1.025f else 1f
            focusedView.alpha = if (hasFocus) 1f else 0.92f
            focusedView.background = rounded(
                if (hasFocus) Color.rgb(42, 117, 190) else baseColor,
                11,
                if (hasFocus) Color.WHITE else Color.argb(70, 255, 255, 255),
                if (hasFocus) 2 else 1
            )
        }
    }

    private fun button(text: String, color: Int): Button =
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
            label("Server Manager", 27f, Color.WHITE, true),
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        )
        countText = label("", 15f, Color.LTGRAY, true)
        header.addView(countText)
        content.addView(header)

        content.addView(
            label(
                "Discover, test, enable or disable individual servers",
                16f,
                Color.LTGRAY
            ).apply {
                setPadding(0, dp(8), 0, dp(12))
            }
        )

        val row1 = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        selectAll = button("SELECT ALL", Color.rgb(35, 180, 75))
        selectNone = button("SELECT NONE", Color.rgb(210, 45, 55))
        row1.addView(
            selectAll,
            LinearLayout.LayoutParams(0, dp(66), 1f).apply {
                marginEnd = dp(8)
            }
        )
        row1.addView(
            selectNone,
            LinearLayout.LayoutParams(0, dp(66), 1f).apply {
                marginStart = dp(8)
            }
        )
        content.addView(row1)

        val row2 = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(10), 0, dp(12))
        }
        discoverButton = button(
            "DISCOVER & TEST SERVERS",
            Color.rgb(30, 105, 180)
        )
        disableOffline = button(
            "DISABLE OFFLINE",
            Color.rgb(80, 80, 90)
        )
        row2.addView(
            discoverButton,
            LinearLayout.LayoutParams(0, dp(66), 1f).apply {
                marginEnd = dp(8)
            }
        )
        row2.addView(
            disableOffline,
            LinearLayout.LayoutParams(0, dp(66), 1f).apply {
                marginStart = dp(8)
            }
        )
        content.addView(row2)

        search = EditText(context).apply {
            id = View.generateViewId()
            hint = "Search servers..."
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
            isFocusable = true
            isFocusableInTouchMode = false
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
            MOOSAServerRegistry.snapshot().forEach {
                MOOSAServerRegistry.setDisabled(it.key, false)
            }
            rebuildList()
        }

        selectNone.setOnClickListener {
            MOOSAServerRegistry.snapshot().forEach {
                MOOSAServerRegistry.setDisabled(it.key, true)
            }
            rebuildList()
        }

        disableOffline.setOnClickListener {
            MOOSAServerRegistry.snapshot()
                .filter { it.lastResult == false }
                .forEach {
                    MOOSAServerRegistry.setDisabled(it.key, true)
                }
            rebuildList()
        }

        discoverButton.setOnClickListener {
            discoverAndTest()
        }

        search.addTextChangedListener {
            query = it?.toString()?.trim().orEmpty()
            rebuildList()
        }

        search.setOnEditorActionListener { _, _, _ ->
            rowFocusPairs.firstOrNull()?.first?.requestFocus()
            true
        }

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

    private fun wireTopFocus() {
        selectAll.nextFocusRightId = selectNone.id
        selectAll.nextFocusDownId = discoverButton.id

        selectNone.nextFocusLeftId = selectAll.id
        selectNone.nextFocusDownId = disableOffline.id

        discoverButton.nextFocusUpId = selectAll.id
        discoverButton.nextFocusRightId = disableOffline.id
        discoverButton.nextFocusDownId = search.id

        disableOffline.nextFocusUpId = selectNone.id
        disableOffline.nextFocusLeftId = discoverButton.id
        disableOffline.nextFocusDownId = search.id

        search.nextFocusUpId = discoverButton.id
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
        rowFocusPairs.clear()

        val all = MOOSAServerRegistry.snapshot()
        val enabledCount = all.count {
            !MOOSAServerRegistry.isDisabled(it.key)
        }
        countText.text = "$enabledCount / ${all.size} enabled"

        val visible = all.filter {
            query.isBlank() ||
                it.label.contains(query, ignoreCase = true)
        }

        if (visible.isEmpty()) {
            listContainer.addView(
                label(
                    "No servers yet. Use DISCOVER & TEST SERVERS.",
                    17f,
                    Color.LTGRAY
                ).apply {
                    setPadding(dp(8), dp(18), dp(8), dp(18))
                }
            )
            search.nextFocusDownId = View.NO_ID
            return
        }

        visible.forEach { entry ->
            val pair = buildServerRow(entry)
            rowFocusPairs += pair
            listContainer.addView(
                pair.first.parent as View,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    dp(72)
                ).apply {
                    bottomMargin = dp(6)
                }
            )
        }

        wireRows()
    }

    private fun buildServerRow(
        entry: MOOSAServerRegistry.Entry
    ): Pair<CheckBox, Button> {
        val context = requireContext()

        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(6), dp(8), dp(6))
            background = rounded(Color.rgb(24, 31, 40), 10)
            isFocusable = false
        }

        val check = CheckBox(context).apply {
            id = View.generateViewId()
            isChecked = !MOOSAServerRegistry.isDisabled(entry.key)
            isFocusable = true
            isFocusableInTouchMode = false
            buttonTintList = ColorStateList.valueOf(Color.rgb(45, 145, 255))
        }
        row.addView(
            check,
            LinearLayout.LayoutParams(dp(52), ViewGroup.LayoutParams.MATCH_PARENT)
        )

        row.addView(
            label(entry.label, 17f, Color.WHITE, true),
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        )

        val state = TextView(context).apply {
            val (textValue, color) = when (entry.lastResult) {
                true -> "• ONLINE" to Color.rgb(30, 180, 85)
                false -> "• OFFLINE" to Color.rgb(220, 55, 55)
                null -> "• UNKNOWN" to Color.rgb(95, 105, 115)
            }
            text = textValue
            textSize = 13f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setPadding(dp(12), dp(7), dp(12), dp(7))
            background = rounded(color, 8)
            isFocusable = false
        }
        row.addView(
            state,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                marginEnd = dp(12)
            }
        )

        val test = button("TEST", Color.rgb(45, 55, 65))
        row.addView(
            test,
            LinearLayout.LayoutParams(dp(94), dp(48))
        )

        check.setOnCheckedChangeListener { _, enabled ->
            MOOSAServerRegistry.setDisabled(entry.key, !enabled)
            updateCountOnly()
        }

        test.setOnClickListener {
            testServer(entry, test)
        }

        return check to test
    }

    private fun wireRows() {
        search.nextFocusDownId = rowFocusPairs.first().first.id

        rowFocusPairs.forEachIndexed { index, pair ->
            val previous = rowFocusPairs.getOrNull(index - 1)
            val next = rowFocusPairs.getOrNull(index + 1)
            val check = pair.first
            val test = pair.second

            check.nextFocusRightId = test.id
            test.nextFocusLeftId = check.id

            check.nextFocusUpId = previous?.first?.id ?: search.id
            test.nextFocusUpId = previous?.second?.id ?: search.id
            check.nextFocusDownId = next?.first?.id ?: check.id
            test.nextFocusDownId = next?.second?.id ?: test.id
        }
    }

    private fun updateCountOnly() {
        val all = MOOSAServerRegistry.snapshot()
        countText.text =
            "${all.count { !MOOSAServerRegistry.isDisabled(it.key) }} / ${all.size} enabled"
    }

    private fun testServer(
        entry: MOOSAServerRegistry.Entry,
        button: Button
    ) {
        button.isEnabled = false
        button.text = "..."

        lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                MOOSAServerRegistry.test(entry.key)
            }
            if (isAdded) {
                button.isEnabled = true
                button.text = "TEST"
                rebuildList()
            }
        }
    }

    private fun discoverAndTest() {
        discoverButton.isEnabled = false
        discoverButton.text = "DISCOVERING..."

        lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                MOOSASourceTester.testAll()
            }

            if (isAdded) {
                discoverButton.isEnabled = true
                discoverButton.text = "DISCOVER & TEST SERVERS"
                rebuildList()
                Toast.makeText(
                    requireContext(),
                    "Server discovery finished.",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
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
