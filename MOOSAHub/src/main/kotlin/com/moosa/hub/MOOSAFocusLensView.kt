package com.moosa.hub

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.lagradost.cloudstream3.MainPageRequest
import kotlin.math.abs

/**
 * v129 top-down Refine overlay. Five useful tabs, genuine TMDB filters,
 * original shelf title stays above this panel. No fake previews or nested dialogs.
 * One immutable draft is applied only with "Show Results".
 */
internal class MOOSAFocusLensView(
    context: Context,
    private val original: MainPageRequest,
    private val onDraftChanged: (MOOSABrowseFilters.Selection?) -> Unit,
    private val onApply: (MOOSABrowseFilters.Selection?) -> Unit,
    private val onClose: () -> Unit
) : LinearLayout(context) {
    private val edge = Color.rgb(65, 65, 65)
    private val black = Color.rgb(20, 20, 20)
    private val neutral = Color.rgb(36, 36, 36)
    private val chosen = Color.rgb(239, 239, 239)
    private val whites = mutableSetOf<Button>()
    private val tabs = mutableListOf<Button>()
    private val dynamic = mutableListOf<Button>()
    private val typeMovie = button("Movies")
    private val typeSeries = button("Series")
    private val dismiss = button("×")
    private val reset = button("Reset")
    private val apply = button("Show Results  →", primary = true)
    private val scroll = ScrollView(context)
    private val content = LinearLayout(context)
    private var draft: MOOSABrowseFilters.Selection? = null
    private var tab = 0
    private val tabNames = listOf("Platform", "Genre", "Year", "Rating", "Sort")
    private val yearModes = listOf(
        "any" to "Any year", "current" to "This year",
        "last3" to "Last 3 years", "last5" to "Last 5 years",
        "last10" to "Last 10 years"
    )

    private fun dp(n: Int) = (n * resources.displayMetrics.density).toInt()

    private fun background(fill: Int, line: Int, radius: Int = 5): GradientDrawable =
        GradientDrawable().apply {
            setColor(fill)
            cornerRadius = dp(radius).toFloat()
            setStroke(dp(1), line)
        }

    private fun paint(button: Button) {
        val focused = button.isFocused
        val light = button.isSelected || button in whites
        val color = when {
            focused -> Color.rgb(69, 69, 69)
            light -> chosen
            else -> neutral
        }
        button.background = GradientDrawable().apply {
            setColor(color)
            cornerRadius = dp(5).toFloat()
            setStroke(dp(if (focused) 2 else 1),
                if (focused) Color.WHITE else if (light) chosen else edge)
        }
        button.setTextColor(if (light && !focused) Color.BLACK else Color.WHITE)
    }

    private fun button(label: String, primary: Boolean = false): Button =
        Button(context).apply {
            id = View.generateViewId()
            text = label
            isAllCaps = false
            isFocusable = true
            isFocusableInTouchMode = false
            minHeight = 0
            minimumHeight = 0
            minWidth = 0
            minimumWidth = 0
            textSize = 12f
            setSingleLine(true)
            setPadding(dp(8), 0, dp(8), 0)
            stateListAnimator = null
            if (primary) whites.add(this)
            setOnFocusChangeListener { view, _ -> paint(view as Button) }
            paint(this)
        }

    private fun muted(label: String): TextView =
        TextView(context).apply {
            text = label
            textSize = 10f
            setTextColor(Color.rgb(168, 168, 168))
            gravity = Gravity.CENTER_VERTICAL
            letterSpacing = 0.07f
        }

    private fun setChosen(button: Button, selected: Boolean) {
        button.isSelected = selected
        paint(button)
    }

    private fun current(): MOOSABrowseFilters.Selection =
        draft ?: MOOSABrowseFilters.initial(original)

    private fun update(value: MOOSABrowseFilters.Selection?) {
        draft = value
        onDraftChanged(value)
    }

    init {
        orientation = VERTICAL
        setPadding(dp(17), dp(9), dp(17), dp(10))
        background = background(black, Color.rgb(91, 91, 91), 7)
        isFocusable = false

        val header = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val type = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        type.addView(typeMovie, LayoutParams(dp(114), dp(37)).apply {
            marginEnd = dp(6)
        })
        type.addView(typeSeries, LayoutParams(dp(114), dp(37)))
        typeMovie.setOnClickListener { chooseType(false) }
        typeSeries.setOnClickListener { chooseType(true) }
        header.addView(type, LayoutParams(0, dp(43), 1f))
        dismiss.textSize = 25f
        dismiss.setOnClickListener { onClose() }
        header.addView(dismiss, LayoutParams(dp(38), dp(36)))
        addView(header, LayoutParams(-1, dp(46)))

        val tabsRow = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        tabNames.forEachIndexed { index, label ->
            val b = button(label)
            b.setOnClickListener {
                tab = index
                renderTabs()
                renderOptions()
                b.requestFocus()
            }
            tabs.add(b)
            tabsRow.addView(b, LayoutParams(0, dp(39), 1f).apply {
                marginEnd = if (index == 4) 0 else dp(6)
            })
        }
        addView(tabsRow, LayoutParams(-1, dp(45)))
        addView(View(context).apply { setBackgroundColor(edge) },
            LayoutParams(-1, dp(1)))

        scroll.isVerticalScrollBarEnabled = false
        scroll.isFocusable = false
        scroll.isFillViewport = false
        scroll.overScrollMode = View.OVER_SCROLL_NEVER
        content.orientation = VERTICAL
        content.setPadding(0, dp(11), 0, dp(2))
        scroll.addView(content, ViewGroup.LayoutParams(-1, -2))
        addView(scroll, LayoutParams(-1, 0, 1f))

        addView(View(context).apply { setBackgroundColor(edge) },
            LayoutParams(-1, dp(1)).apply { topMargin = dp(5) })
        val footer = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL or Gravity.RIGHT
        }
        reset.setOnClickListener {
            update(null)
            renderAll()
            reset.requestFocus()
        }
        apply.setOnClickListener { onApply(draft) }
        footer.addView(View(context), LayoutParams(0, dp(46), 1f))
        footer.addView(reset, LayoutParams(dp(105), dp(41)).apply {
            marginEnd = dp(10)
        })
        footer.addView(apply, LayoutParams(dp(168), dp(41)))
        addView(footer, LayoutParams(-1, dp(48)))
        renderAll()
    }

    private fun chooseType(series: Boolean) {
        val selection = current()
        update(MOOSABrowseFilters.withType(selection, series))
        renderAll()
        (if (series) typeSeries else typeMovie).requestFocus()
    }

    fun bindSelection(selection: MOOSABrowseFilters.Selection?) {
        draft = selection
        tab = 0
        renderAll()
    }

    private fun renderAll() {
        setChosen(typeMovie, !current().isTv)
        setChosen(typeSeries, current().isTv)
        renderTabs()
        renderOptions()
    }

    private fun renderTabs() {
        tabs.forEachIndexed { index, btn ->
            setChosen(btn, tab == index)
        }
    }

    private fun cell(label: String, selected: Boolean, key: String,
                     onClick: () -> Unit): Button {
        val b = button(label + if (selected) "  ✓" else "")
        b.tag = key
        b.setOnClickListener { onClick() }
        setChosen(b, selected)
        dynamic.add(b)
        return b
    }

    private fun row(values: List<Button>, columns: Int) {
        values.chunked(columns).forEach { group ->
            val line = LinearLayout(context).apply {
                orientation = HORIZONTAL
                gravity = Gravity.TOP
            }
            group.forEachIndexed { i, b ->
                line.addView(b, LayoutParams(0, dp(42), 1f).apply {
                    marginEnd = if (i < group.lastIndex) dp(7) else 0
                })
            }
            repeat((columns - group.size).coerceAtLeast(0)) {
                line.addView(View(context), LayoutParams(0, dp(42), 1f))
            }
            content.addView(line, LayoutParams(-1, dp(48)))
        }
    }

    private fun renderOptions(focusKey: String? = null) {
        content.removeAllViews()
        dynamic.clear()
        val s = current()
        when (tab) {
            0 -> {
                val choices = listOf("All platforms") +
                    MOOSABrowseFilters.platforms.map { it.name }
                row(choices.map { label ->
                    val key = if (label == "All platforms") "" else label
                    val selected = if (key.isEmpty()) s.platformNames.isEmpty()
                        else key in s.platformNames
                    cell(label, selected, "platform:" + key) {
                        val x = current()
                        val next = when {
                            key.isEmpty() -> emptyList()
                            key in x.platformNames -> x.platformNames - key
                            else -> x.platformNames + key
                        }
                        update(x.copy(platformNames = next))
                        renderOptions("platform:" + key)
                    }
                }, 4)
            }
            1 -> {
                val names = MOOSABrowseFilters.availableGenres(s.isTv)
                val choices = listOf("" to "All genres") +
                    names.map { it.key to it.label }
                val selectedGenres = MOOSABrowseFilters.selectedGenres(s)
                row(choices.map { (key, label) ->
                    val selected = if (key.isEmpty()) selectedGenres.isEmpty()
                        else key in selectedGenres
                    cell(label, selected, "genre:" + key) {
                        update(MOOSABrowseFilters.withGenreToggle(
                            current(), key.ifEmpty { null }
                        ))
                        renderOptions("genre:" + key)
                    }
                }, 4)
            }
            2 -> renderYear()
            3 -> {
                row(listOf(0, 7, 8, 9).map { score ->
                    val label = if (score == 0) "Any rating" else score.toString() + "+"
                    cell(label, s.minimumRating == score, "rating:" + score) {
                        update(current().copy(minimumRating = score))
                        renderOptions("rating:" + score)
                    }
                }, 4)
            }
            4 -> {
                row(MOOSABrowseFilters.sorts.map { choice ->
                    cell(choice.name, s.sort == choice.apiSort,
                        "sort:" + choice.apiSort) {
                        update(current().copy(sort = choice.apiSort))
                        renderOptions("sort:" + choice.apiSort)
                    }
                }, 5)
            }
        }
        if (focusKey != null) {
            dynamic.firstOrNull { it.tag == focusKey }?.let { button ->
                button.post {
                    if (isAttachedToWindow && isShown) button.requestFocus()
                }
            }
        }
    }

    private fun renderYear() {
        val s = current()
        row(yearModes.map { (key, label) ->
            cell(label, s.yearMode == key, "year:" + key) {
                update(current().copy(yearMode = key))
                renderOptions("year:" + key)
            }
        }, 5)

        val range = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(2), dp(5), dp(2), 0)
        }
        range.addView(muted("FROM"), LayoutParams(dp(48), dp(37)))
        val fromMinus = yearStep("from", -1, "−")
        val fromPlus = yearStep("from", 1, "+")
        range.addView(fromMinus, LayoutParams(dp(37), dp(38)))
        range.addView(yearText(s.yearFrom), LayoutParams(0, dp(38), 1f))
        range.addView(fromPlus, LayoutParams(dp(37), dp(38)))
        range.addView(muted("  —  TO"), LayoutParams(dp(71), dp(38)))
        val toMinus = yearStep("to", -1, "−")
        val toPlus = yearStep("to", 1, "+")
        range.addView(toMinus, LayoutParams(dp(37), dp(38)))
        range.addView(yearText(s.yearTo), LayoutParams(0, dp(38), 1f))
        range.addView(toPlus, LayoutParams(dp(37), dp(38)))
        content.addView(range, LayoutParams(-1, dp(54)))
    }

    private fun yearText(value: Int) = TextView(context).apply {
        text = value.toString()
        textSize = 17f
        gravity = Gravity.CENTER
        setTextColor(Color.WHITE)
        setTypeface(typeface, Typeface.BOLD)
    }

    private fun yearStep(which: String, delta: Int, sign: String): Button {
        val key = "step:" + which + ":" + delta
        return cell(sign, false, key) {
            val s = current()
            val now = MOOSABrowseFilters.currentYear()
            var first = s.yearFrom.coerceIn(1900, now)
            var last = s.yearTo.coerceIn(first, now)
            if (which == "from") {
                first = (first + delta).coerceIn(1900, now)
                if (first > last) last = first
            } else {
                last = (last + delta).coerceIn(1900, now)
                if (last < first) first = last
            }
            update(s.copy(yearMode = "range", yearFrom = first, yearTo = last))
            renderOptions(key)
        }
    }

    fun focusFirst() {
        tabs.getOrNull(tab)?.post {
            if (isAttachedToWindow && isShown) tabs[tab].requestFocus()
        }
    }

    /** Deterministic DPAD trap; posters underneath cannot receive focus. */
    fun trapDpad(direction: Int): Boolean {
        val focusables = (listOf(typeMovie, typeSeries, dismiss) + tabs +
            dynamic + listOf(reset, apply)).filter { it.isShown && it.isFocusable }
        val current = findFocus()
        if (current == null || current !in focusables) {
            focusFirst()
            return true
        }
        val pos = IntArray(2)
        current.getLocationOnScreen(pos)
        val horizontal = direction == KeyEvent.KEYCODE_DPAD_LEFT ||
            direction == KeyEvent.KEYCODE_DPAD_RIGHT
        val sign = if (direction == KeyEvent.KEYCODE_DPAD_RIGHT ||
            direction == KeyEvent.KEYCODE_DPAD_DOWN) 1 else -1
        val a = if (horizontal) pos[0] + current.width / 2
            else pos[1] + current.height / 2
        val b = if (horizontal) pos[1] + current.height / 2
            else pos[0] + current.width / 2
        val next = focusables.filter { it !== current }.mapNotNull { other ->
            val loc = IntArray(2)
            other.getLocationOnScreen(loc)
            val along = (if (horizontal) loc[0] + other.width / 2
                         else loc[1] + other.height / 2) - a
            val across = (if (horizontal) loc[1] + other.height / 2
                          else loc[0] + other.width / 2) - b
            if (along * sign <= 3) null
            else other to (abs(along) + abs(across) * 3)
        }.minByOrNull { it.second }?.first
        next?.requestFocus()
        return true
    }
}
