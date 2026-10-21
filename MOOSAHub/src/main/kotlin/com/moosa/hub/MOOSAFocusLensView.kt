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
import com.lagradost.cloudstream3.SearchResponse
import kotlin.math.abs

/** TV-native filter controls with real, asynchronously supplied TMDB previews. */
internal class MOOSAFocusLensView(
    context: Context,
    private val original: MainPageRequest,
    private val onDraftChanged: (MOOSABrowseFilters.Selection?) -> Unit,
    private val onApply: (MOOSABrowseFilters.Selection?) -> Unit,
    private val onClose: () -> Unit
) : LinearLayout(context) {
    private val border = Color.rgb(59, 59, 59)
    private var draft: MOOSABrowseFilters.Selection? = null
    private var section = 0
    private val navigation = mutableListOf<Button>()
    private val choices = mutableListOf<Button>()
    private val options = LinearLayout(context)
    private val heading = text("Where to watch.", 29f, true)
    private val description = text("", 12f)
    private val count = text("—", 43f, true)
    private val preview = LinearLayout(context)
    private val previewNote = text("Loading real titles…", 11f)
    private val typeButton = button("Series")
    private val resetButton = button("Reset")
    private val applyButton = button("Show Results  →", true)
    private val closeButton = button("×")
    private val sections = listOf("Platforms", "Genre", "Origin", "Sort")

    private fun dp(n: Int) = (n * resources.displayMetrics.density).toInt()
    private fun shape(fill: Int, line: Int = border) = GradientDrawable().apply {
        setColor(fill)
        cornerRadius = dp(7).toFloat()
        setStroke(dp(1), line)
    }
    private fun text(value: String, size: Float, bold: Boolean = false) =
        TextView(context).apply {
            text = value
            textSize = size
            setTextColor(if (bold) Color.WHITE else Color.rgb(165, 165, 165))
            if (bold) setTypeface(typeface, Typeface.BOLD)
            maxLines = 3
        }
    private fun button(value: String, white: Boolean = false): Button =
        Button(context).apply {
            id = View.generateViewId()
            text = value
            isAllCaps = false
            textSize = 12f
            minHeight = 0
            minimumHeight = 0
            minWidth = 0
            minimumWidth = 0
            stateListAnimator = null
            setSingleLine(true)
            setPadding(dp(7), 0, dp(7), 0)
            isFocusable = true
            setTextColor(if (white) Color.BLACK else Color.WHITE)
            background = shape(if (white) Color.rgb(238, 238, 238) else Color.rgb(27, 27, 27))
            setOnFocusChangeListener { v, focused ->
                v.background = GradientDrawable().apply {
                    setColor(when {
                        focused -> Color.rgb(62, 62, 62)
                        white -> Color.rgb(238, 238, 238)
                        else -> Color.rgb(27, 27, 27)
                    })
                    cornerRadius = dp(7).toFloat()
                    setStroke(dp(if (focused) 2 else 1),
                        if (focused) Color.WHITE else border)
                }
                (v as Button).setTextColor(
                    if (white && !focused) Color.BLACK else Color.WHITE
                )
            }
        }

    init {
        orientation = VERTICAL
        isFocusable = false
        setPadding(dp(15), dp(13), dp(15), dp(11))
        background = shape(Color.rgb(9, 9, 9), Color.rgb(86, 86, 86))

        val header = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val brand = LinearLayout(context).apply { orientation = VERTICAL }
        brand.addView(text("MOOSA / FOCUS LENS", 17f, true))
        brand.addView(text(original.name, 11f))
        header.addView(brand, LayoutParams(0, dp(59), 1f))
        closeButton.textSize = 23f
        closeButton.setOnClickListener { onClose() }
        header.addView(closeButton, LayoutParams(dp(40), dp(40)))
        addView(header)
        addView(View(context).apply { setBackgroundColor(border) }, LayoutParams(-1, dp(1)))

        val body = LinearLayout(context).apply {
            orientation = HORIZONTAL
            weightSum = 100f
        }
        val rail = LinearLayout(context).apply {
            orientation = VERTICAL
            setPadding(0, dp(17), dp(10), dp(5))
        }
        rail.addView(text("REFINE YOUR SEARCH", 10f), LayoutParams(-1, dp(30)))
        sections.forEachIndexed { index, title ->
            val b = button("%02d   %s".format(index + 1, title))
            b.gravity = Gravity.CENTER_VERTICAL or Gravity.START
            b.setOnClickListener {
                section = index
                redraw()
                b.requestFocus()
            }
            navigation += b
            rail.addView(b, LayoutParams(-1, dp(55)).apply { bottomMargin = dp(8) })
        }
        rail.addView(View(context), LayoutParams(-1, 0, 1f))
        rail.addView(text("CHANGE ONLY WHAT YOU NEED", 9f))
        body.addView(rail, LayoutParams(0, -1, 24f))
        body.addView(View(context).apply { setBackgroundColor(border) }, LayoutParams(dp(1), -1))

        val middle = LinearLayout(context).apply {
            orientation = VERTICAL
            setPadding(dp(16), dp(18), dp(16), dp(5))
        }
        middle.addView(text("ONE SECTION AT A TIME", 10f), LayoutParams(-1, dp(27)))
        middle.addView(heading, LayoutParams(-1, dp(50)))
        middle.addView(description, LayoutParams(-1, dp(48)))
        typeButton.setOnClickListener {
            draft = MOOSABrowseFilters.withType(current(), !current().isTv)
            onDraftChanged(draft)
            redrawOptions()
            typeButton.requestFocus()
        }
        middle.addView(typeButton, LayoutParams(-1, dp(38)).apply { bottomMargin = dp(10) })
        val scroll = ScrollView(context).apply {
            isVerticalScrollBarEnabled = false
            isFocusable = false
            overScrollMode = View.OVER_SCROLL_NEVER
        }
        options.orientation = VERTICAL
        scroll.addView(options, ViewGroup.LayoutParams(-1, -2))
        middle.addView(scroll, LayoutParams(-1, 0, 1f))
        body.addView(middle, LayoutParams(0, -1, 51f))
        body.addView(View(context).apply { setBackgroundColor(border) }, LayoutParams(dp(1), -1))

        val side = LinearLayout(context).apply {
            orientation = VERTICAL
            setPadding(dp(15), dp(19), dp(3), dp(9))
        }
        side.addView(text("LIVE PREVIEW", 11f, true), LayoutParams(-1, dp(35)))
        side.addView(count, LayoutParams(-1, dp(60)))
        side.addView(text("FIRST PAGE · TITLES LOADED", 10f), LayoutParams(-1, dp(33)))
        side.addView(View(context).apply { setBackgroundColor(border) }, LayoutParams(-1, dp(1)))
        side.addView(previewNote, LayoutParams(-1, dp(43)))
        side.addView(preview, LayoutParams(-1, 0, 1f))
        side.addView(text("TMDB sample · availability may change", 10f))
        body.addView(side, LayoutParams(0, -1, 25f))
        addView(body, LayoutParams(-1, 0, 1f))

        val footer = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(7), 0, 0)
        }
        resetButton.setOnClickListener {
            draft = null
            onDraftChanged(null)
            redraw()
            resetButton.requestFocus()
        }
        applyButton.setOnClickListener { onApply(draft) }
        footer.addView(text("DPAD / TV REMOTE", 10f), LayoutParams(0, dp(45), 1f))
        footer.addView(resetButton, LayoutParams(dp(98), dp(44)).apply {
            marginEnd = dp(10)
        })
        footer.addView(applyButton, LayoutParams(dp(166), dp(44)))
        addView(footer, LayoutParams(-1, dp(58)))
        redraw()
    }

    private fun current() = draft ?: MOOSABrowseFilters.initial(original)

    fun bindSelection(selection: MOOSABrowseFilters.Selection?) {
        draft = selection
        section = 0
        redraw()
    }

    private fun redraw() {
        navigation.forEachIndexed { index, b ->
            val selected = index == section
            b.background = shape(
                if (selected) Color.rgb(235, 235, 235) else Color.rgb(27, 27, 27),
                if (selected) Color.WHITE else border
            )
            b.setTextColor(if (selected) Color.BLACK else Color.WHITE)
        }
        heading.text = listOf("Where to watch.", "What feels right.", "From where.", "Your order.")[section]
        description.text = listOf(
            "Choose streaming services based on real region-specific availability.",
            "Choose genres from TMDB's actual catalog.",
            "Country of production, not your watch region.",
            "Choose how the discovery results are ranked."
        )[section]
        redrawOptions()
    }

    private fun redrawOptions(returnKey: String? = null) {
        options.removeAllViews()
        choices.clear()
        val s = current()
        typeButton.text = "Content type · " + if (s.isTv) "Series" else "Movies"
        if (section == 0) {
            options.addView(text("AVAILABILITY REGION", 10f), LayoutParams(-1, dp(27)))
            val regions = LinearLayout(context).apply { orientation = HORIZONTAL }
            MOOSABrowseFilters.watchRegions.forEach { region ->
                val b = button(region.name, region.code == s.watchRegion)
                b.tag = "region:" + region.code
                b.setOnClickListener {
                    draft = current().copy(watchRegion = region.code)
                    onDraftChanged(draft)
                    redrawOptions("region:" + region.code)
                }
                choices += b
                regions.addView(b, LayoutParams(0, dp(35), 1f).apply {
                    marginEnd = dp(4)
                })
            }
            options.addView(regions, LayoutParams(-1, dp(42)))
            options.addView(text("Streaming subscription services", 10f),
                LayoutParams(-1, dp(26)))
        }
        val pairs: List<Pair<String, String>> = when(section) {
            0 -> listOf("All Platforms" to "") +
                MOOSABrowseFilters.platforms.map { it.name to it.name }
            1 -> listOf("All Genres" to "") +
                MOOSABrowseFilters.availableGenres(s.isTv).map { it.label to it.key }
            2 -> MOOSABrowseFilters.countries.map { it.name to it.code }
            else -> MOOSABrowseFilters.sorts.map { it.name to it.apiSort }
        }
        pairs.chunked(2).forEach { chunk ->
            val line = LinearLayout(context).apply { orientation = HORIZONTAL }
            chunk.forEach { (name, value) ->
                val selected = when(section) {
                    0 -> if (value == "") s.platformNames.isEmpty()
                         else value in s.platformNames
                    1 -> s.genreKey == value.ifEmpty { null }
                    2 -> s.originCountry == value
                    else -> s.sort == value
                }
                val b = button(name + if (selected) " ✓" else "", selected)
                val focusKey = section.toString() + ":" + value
                b.tag = focusKey
                b.setOnClickListener {
                    val x = current()
                    draft = when(section) {
                        0 -> x.copy(platformNames = when {
                            value.isEmpty() -> emptyList()
                            value in x.platformNames -> x.platformNames - value
                            else -> x.platformNames + value
                        })
                        1 -> x.copy(genreKey = value.ifEmpty { null },
                            originCountry = if (value == "anime") "JP" else x.originCountry)
                        2 -> x.copy(originCountry = value)
                        else -> x.copy(sort = value)
                    }
                    onDraftChanged(draft)
                    redrawOptions(focusKey)
                }
                choices += b
                line.addView(b, LayoutParams(0, dp(43), 1f).apply {
                    marginEnd = dp(6)
                })
            }
            options.addView(line, LayoutParams(-1, dp(50)))
        }
        if (returnKey != null) {
            choices.firstOrNull { it.tag == returnKey }?.let { target ->
                target.post { if (isAttachedToWindow) target.requestFocus() }
            }
        }
    }

    fun showPreview(rows: List<SearchResponse>, loading: Boolean = false,
                    error: Boolean = false) {
        preview.removeAllViews()
        if (loading) {
            count.text = "···"
            previewNote.text = "Loading actual TMDB titles…"
            return
        }
        count.text = if (error) "—" else rows.size.toString()
        previewNote.text = when {
            error -> "Preview unavailable; try Show Results."
            rows.isEmpty() -> "No titles on this first page."
            else -> "First-page sample"
        }
        rows.take(3).forEachIndexed { i, item ->
            val unit = LinearLayout(context).apply {
                orientation = VERTICAL
                setPadding(0, dp(12), 0, dp(11))
            }
            unit.addView(text("%02d".format(i + 1) + "  " + item.name, 13f, true))
            unit.addView(text("Catalog title", 10f))
            preview.addView(unit, LayoutParams(-1, -2))
            preview.addView(View(context).apply { setBackgroundColor(border) },
                LayoutParams(-1, dp(1)))
        }
    }

    fun focusFirst() {
        navigation.getOrNull(section)?.post {
            if (isAttachedToWindow) navigation[section].requestFocus()
        }
    }

    fun trapDpad(direction: Int): Boolean {
        val all = (navigation + choices + listOf(
            typeButton, resetButton, applyButton, closeButton
        )).filter { it.isShown && it.isFocusable }
        val focused = findFocus()
        if (focused == null || focused !in all) {
            focusFirst()
            return true
        }
        val loc = IntArray(2)
        focused.getLocationOnScreen(loc)
        val horizontal = direction == KeyEvent.KEYCODE_DPAD_LEFT ||
            direction == KeyEvent.KEYCODE_DPAD_RIGHT
        val sign = if (direction == KeyEvent.KEYCODE_DPAD_RIGHT ||
            direction == KeyEvent.KEYCODE_DPAD_DOWN) 1 else -1
        val main = (if (horizontal) loc[0] + focused.width / 2
                    else loc[1] + focused.height / 2)
        val cross = (if (horizontal) loc[1] + focused.height / 2
                     else loc[0] + focused.width / 2)
        val nearest = all.filter { it !== focused }.mapNotNull { candidate ->
            val pt = IntArray(2)
            candidate.getLocationOnScreen(pt)
            val delta = (if (horizontal) pt[0] + candidate.width/2
                         else pt[1] + candidate.height/2) - main
            val extra = (if (horizontal) pt[1] + candidate.height/2
                         else pt[0] + candidate.width/2) - cross
            if (delta * sign <= 3) null
            else candidate to (abs(delta) + 3 * abs(extra))
        }.minByOrNull { it.second }?.first
        nearest?.requestFocus()
        return true
    }
}
