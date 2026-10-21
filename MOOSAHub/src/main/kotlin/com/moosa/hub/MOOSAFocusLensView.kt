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
    // Semantic option rows, including rows that are below the ScrollView viewport.
    // DPAD must navigate one row at a time, not chase nearest global coordinates.
    private val optionRows = mutableListOf<List<Button>>()
    private val typeMovie = button("Movies")
    private val typeSeries = button("Series")
    private val tabUnderlines = mutableListOf<View>()
    private val reset = button("Reset")
    private val apply = button("Show Results  →", primary = true)
    private val scroll = ScrollView(context)
    private val content = LinearLayout(context)
    private var draft: MOOSABrowseFilters.Selection? = null
    private var tab = 0
    private val tabNames = listOf("Platform", "Genre", "Year", "Rating", "Sort")
    private val yearModes = listOf(
        "any" to "Any year", "current" to MOOSABrowseFilters.currentYear().toString(),
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

    // Selection is a discreet dark chip with a thin outline; bright white
    // is reserved for Show Results only. This matches the approved TV mock.
    private fun paint(button: Button) {
        val focused = button.isFocused
        val primary = button in whites
        val selected = button.isSelected
        val fill = when {
            focused -> Color.rgb(65, 65, 65)
            primary -> Color.rgb(236, 236, 236)
            selected -> Color.rgb(53, 53, 53)
            else -> Color.rgb(32, 32, 32)
        }
        button.background = background(
            fill,
            when {
                focused -> Color.WHITE
                selected -> Color.rgb(180, 180, 180)
                primary -> Color.WHITE
                else -> edge
            }
        ).apply {
            setStroke(dp(if (focused) 2 else 1),
                when {
                    focused -> Color.WHITE
                    selected -> Color.rgb(175, 175, 175)
                    primary -> Color.WHITE
                    else -> edge
                })
        }
        button.setTextColor(if (primary && !focused) Color.BLACK else Color.WHITE)
    }

    private fun paintTab(button: Button, index: Int) {
        val focused = button.isFocused
        val selected = tab == index
        button.background = background(
            if (focused) Color.rgb(57, 57, 57) else Color.TRANSPARENT,
            if (focused) Color.WHITE else Color.TRANSPARENT,
            3
        )
        button.setTextColor(
            if (selected || focused) Color.WHITE else Color.rgb(151, 151, 151)
        )
        tabUnderlines.getOrNull(index)?.setBackgroundColor(
            if (selected) Color.WHITE else Color.TRANSPARENT
        )
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
            textSize = 11.5f
            includeFontPadding = false
            setSingleLine(true)
            setPadding(dp(7), 0, dp(7), 0)
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
        setPadding(dp(14), dp(7), dp(14), dp(7))
        background = background(black, Color.rgb(91, 91, 91), 7)
        isFocusable = false

        // One real × already exists in Show More's header. Do not repeat
        // it inside the Refine panel (the remote Back / Refine button closes).
        val header = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        typeMovie.setOnClickListener { chooseType(false) }
        typeSeries.setOnClickListener { chooseType(true) }
        header.addView(typeMovie, LayoutParams(dp(95), dp(33)).apply {
            marginEnd = dp(7)
        })
        header.addView(typeSeries, LayoutParams(dp(95), dp(33)))
        addView(header, LayoutParams(-1, dp(37)))

        // Selected tab = white underline, not a big white rectangle.
        val tabsRow = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        tabNames.forEachIndexed { index, label ->
            val b = button(label)
            val underline = View(context)
            tabs.add(b)
            tabUnderlines.add(underline)
            b.setOnFocusChangeListener { view, _ ->
                paintTab(view as Button, index)
            }
            b.setOnClickListener {
                tab = index
                renderTabs()
                renderOptions()
                b.requestFocus()
            }
            val cell = LinearLayout(context).apply {
                orientation = VERTICAL
                gravity = Gravity.CENTER
                addView(b, LayoutParams(-1, dp(30)))
                addView(underline, LayoutParams(-1, dp(2)))
            }
            tabsRow.addView(cell, LayoutParams(0, dp(36), 1f).apply {
                marginEnd = if (index == 4) 0 else dp(4)
            })
        }
        addView(tabsRow, LayoutParams(-1, dp(38)))
        addView(View(context).apply { setBackgroundColor(edge) },
            LayoutParams(-1, dp(1)))

        // Always show a thin scrollbar so more genre choices are discoverable.
        scroll.isVerticalScrollBarEnabled = true
        scroll.isScrollbarFadingEnabled = false
        scroll.isFocusable = false
        scroll.isFillViewport = false
        scroll.overScrollMode = View.OVER_SCROLL_NEVER
        content.orientation = VERTICAL
        content.setPadding(0, dp(9), dp(4), dp(5))
        scroll.addView(content, ViewGroup.LayoutParams(-1, -2))
        scroll.clipToPadding = true
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
        footer.addView(reset, LayoutParams(dp(90), dp(37)).apply {
            marginEnd = dp(9)
        })
        footer.addView(apply, LayoutParams(dp(158), dp(37)))
        addView(footer, LayoutParams(-1, dp(43)))
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
        tabs.forEachIndexed { index, btn -> paintTab(btn, index) }
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
                line.addView(b, LayoutParams(0, dp(36), 1f).apply {
                    marginEnd = if (i < group.lastIndex) dp(7) else 0
                })
            }
            repeat((columns - group.size).coerceAtLeast(0)) {
                line.addView(View(context), LayoutParams(0, dp(36), 1f))
            }
            content.addView(line, LayoutParams(-1, dp(41)))
            optionRows.add(group)
        }
    }

    private fun renderOptions(focusKey: String? = null) {
        content.removeAllViews()
        dynamic.clear()
        optionRows.clear()
        if (focusKey == null) scroll.scrollTo(0, 0)
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
                content.addView(muted("Streaming availability powered by JustWatch"),
                    LayoutParams(-1, dp(23)))
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
                // Focus the replacement immediately; a delayed-only focus
                // allows a rapid next remote press to move to an old view.
                button.requestFocus()
                button.post {
                    if (isAttachedToWindow && isShown) focusInsideList(button)
                }
            }
        }
    }

    private fun renderYear() {
        val s = current()
        row(yearModes.map { (key, label) ->
            cell(label, s.yearMode == key, "year:" + key) {
                val now = MOOSABrowseFilters.currentYear()
                val from = when (key) {
                    "current" -> now
                    "last3" -> now - 2
                    "last5" -> now - 4
                    "last10" -> now - 9
                    else -> now - 8
                }
                update(current().copy(
                    yearMode = key, yearFrom = from, yearTo = now
                ))
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
        optionRows.add(listOf(fromMinus, fromPlus, toMinus, toPlus))
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
        // Focus the currently selected tab, as in the approved prototype.
        val first = tabs.getOrNull(tab) ?: typeMovie
        if (isAttachedToWindow && isShown) {
            first.requestFocus()
        } else {
            first.post {
                if (isAttachedToWindow && isShown) first.requestFocus()
            }
        }
    }

    /**
     * The footer is *outside* the ScrollView. Scroll its actual content row
     * into view before requesting focus, otherwise Fire OS 6 may land focus on
     * an off-screen button, apparently trapping or skipping the remote.
     */
    private fun focusInsideList(target: Button) {
        if (target !in dynamic) return
        val parent = target.parent as? View ?: return
        if (scroll.height <= 0 || parent.height <= 0) {
            target.requestRectangleOnScreen(
                android.graphics.Rect(0, 0, target.width, target.height), true
            )
            return
        }
        val margin = dp(7)
        val top = parent.top
        val bottom = parent.bottom
        val first = scroll.scrollY
        val last = first + scroll.height
        val next = when {
            top < first + margin -> (top - margin).coerceAtLeast(0)
            bottom > last - margin -> (bottom - scroll.height + margin)
                .coerceAtLeast(0)
            else -> first
        }
        if (next != first) scroll.scrollTo(0, next)
        target.requestFocus()
    }

    private fun horizontalCenter(view: View): Int {
        val coords = IntArray(2)
        view.getLocationOnScreen(coords)
        return coords[0] + view.width / 2
    }

    private fun closestInRow(reference: View, row: List<Button>): Button {
        // Horizontal alignment is used only between ADJACENT semantic rows,
        // never to jump between arbitrary options several rows away.
        val x = horizontalCenter(reference)
        return row.minByOrNull { kotlin.math.abs(horizontalCenter(it) - x) }
            ?: reference as Button
    }

    /**
     * Controlled navigation for old Fire TV/Android 7 remotes:
     * Type ←→ Type, Tabs ←→ Tabs, options ←→ within same row,
     * Up/Down moves ONE adjacent row, and bottom options → footer.
     * All off-screen option rows remain reachable, with explicit scrolling.
     */
    fun trapDpad(direction: Int): Boolean {
        val rows = mutableListOf<List<Button>>()
        rows.add(listOf(typeMovie, typeSeries))
        rows.add(tabs.toList())
        rows.addAll(optionRows)
        rows.add(listOf(reset, apply))

        val focused = findFocus() as? Button
        val rowIndex = rows.indexOfFirst { focused != null && focused in it }
        if (rowIndex < 0) {
            focusFirst()
            return true
        }
        val row = rows[rowIndex]
        val index = row.indexOf(focused)
        val destination: Button? = when (direction) {
            KeyEvent.KEYCODE_DPAD_LEFT ->
                row.getOrNull(index - 1)
            KeyEvent.KEYCODE_DPAD_RIGHT ->
                row.getOrNull(index + 1)
            KeyEvent.KEYCODE_DPAD_UP -> {
                when {
                    rowIndex == 0 -> null
                    rowIndex == 2 -> tabs[tab] // Options -> selected tab.
                    else -> closestInRow(focused!!, rows[rowIndex - 1])
                }
            }
            KeyEvent.KEYCODE_DPAD_DOWN -> {
                when {
                    rowIndex == rows.lastIndex -> null
                    rowIndex == 0 -> tabs[tab]
                    else -> closestInRow(focused!!, rows[rowIndex + 1])
                }
            }
            else -> null
        }
        if (destination != null && destination !== focused) {
            if (destination in dynamic) focusInsideList(destination)
            else destination.requestFocus()
        }
        // Also consume at every edge; no focus leaks onto covered posters.
        return true
    }

}
