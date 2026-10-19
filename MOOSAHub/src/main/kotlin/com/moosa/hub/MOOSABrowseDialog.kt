package com.moosa.hub

import android.app.AlertDialog
import android.app.Dialog
import android.view.KeyEvent
import android.view.animation.DecelerateInterpolator
import android.content.Context
import android.text.TextUtils
import android.graphics.Color
import android.content.res.ColorStateList
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.lifecycle.Lifecycle
import java.lang.reflect.Proxy
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.Button
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.DialogFragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.ui.AutofitRecyclerView
import com.lagradost.cloudstream3.ui.setRecycledViewPool
import com.lagradost.cloudstream3.ui.search.SEARCH_ACTION_LOAD
import com.lagradost.cloudstream3.ui.search.SEARCH_ACTION_SHOW_METADATA
import com.lagradost.cloudstream3.ui.search.SEARCH_ACTION_PLAY_FILE
import com.lagradost.cloudstream3.ui.search.SearchAdapter
import com.lagradost.cloudstream3.ui.search.SearchHelper.handleSearchClickCallback
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MOOSABrowseDialog : DialogFragment() {
    private lateinit var api: MOOSAHubProvider
    private lateinit var request: MainPageRequest

    private lateinit var recycler: AutofitRecyclerView
    private lateinit var adapter: SearchAdapter
    private lateinit var progress: ProgressBar
    private lateinit var status: TextView
    private lateinit var categoryTitle: TextView
    private lateinit var closeButton: Button
    private lateinit var filterToggleButton: Button
    private lateinit var filterPanel: LinearLayout
    private lateinit var filtersScrim: View
    private lateinit var chipsRow: LinearLayout
    private var lastRenderedChips: List<String> = emptyList()
    private lateinit var showResultsButton: Button
    private var draftFilters: MOOSABrowseFilters.Selection? = null
    private var filterPanelVisible = false
    private lateinit var browseRoot: LinearLayout
    private var entryPlayed = false
    private var closingAnimated = false
    private var focusFilterId = View.NO_ID
    private var stayOnFilterAfterReload = false

    private data class BrowseSnapshot(
        val items: List<SearchResponse>,
        val lastPage: Int,
        val hasNext: Boolean,
        val savedAtMs: Long
    )
    // In-dialog, source-keyed LRU: no extra HTTP request when the viewer
    // switches back to a recently selected filter combination.
    private val browseSnapshots = LinkedHashMap<String, BrowseSnapshot>()
    private lateinit var typeButton: Button
    private lateinit var genreButton: Button
    private lateinit var sortButton: Button
    private lateinit var countryButton: Button
    private lateinit var resetButton: Button
    private var selectedFilters: MOOSABrowseFilters.Selection? = null
    private var browseJob: Job? = null
    private var browseGeneration = 0

    private fun selectedRequest(): MainPageRequest =
        selectedFilters?.let { MOOSABrowseFilters.toRequest(request, it) } ?: request

    private val items = mutableListOf<SearchResponse>()
    private var lastPage = 0
    private var hasNext = true
    private var loading = false
    private var waitingForResult = false
    private var metadataPreviewRequested = false
    private var resultDestinationSeen = false
    private var genuineHomeReturnObserved = false
    private var returnPosition = 0
    private var navController: Any? = null
    private var destinationListener: Any? = null
    private val returnHandler = Handler(Looper.getMainLooper())
    private var pendingReturn: Runnable? = null

    private fun cancelPendingReturn() {
        pendingReturn?.let(returnHandler::removeCallbacks)
        pendingReturn = null
    }

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

    private fun focusableButton(text: String): Button =
        Button(requireContext()).apply {
            id = View.generateViewId()
            this.text = text
            isAllCaps = false
            setSingleLine(true)
            ellipsize = TextUtils.TruncateAt.END
            includeFontPadding = false
            setTextColor(Color.rgb(234, 234, 237))
            textSize = 12f
            isFocusable = true
            isFocusableInTouchMode = false
            minHeight = 0
            minimumHeight = 0
            stateListAnimator = null
            background = rounded(Color.rgb(38, 38, 42), 8, Color.rgb(87, 87, 93))
            setPadding(dp(8), 0, dp(8), 0)
            setOnFocusChangeListener { view, focused ->
                view.animate().cancel()
                view.background = rounded(
                    if (focused) Color.rgb(241, 242, 246) else Color.rgb(38, 38, 42),
                    8,
                    if (focused) Color.WHITE else Color.rgb(87, 87, 93)
                )
                (view as Button).setTextColor(
                    if (focused) Color.rgb(23, 23, 26) else Color.rgb(234, 234, 237)
                )
                view.animate().scaleX(if (focused) 1.015f else 1f)
                    .scaleY(if (focused) 1.015f else 1f)
                    .setDuration(145L)
                    .setInterpolator(DecelerateInterpolator()).start()
            }
        }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val context = requireContext()

        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(15), dp(11), dp(15), dp(11))
            background = rounded(
                Color.rgb(19, 19, 22),
                13,
                Color.rgb(67, 67, 73),
                1
            )
            alpha = 0f
            scaleX = 0.98f
            scaleY = 0.98f
        }
        browseRoot = root
        val container = FrameLayout(context).apply {
            clipChildren = false
            clipToPadding = false
        }
        container.addView(root, FrameLayout.LayoutParams(-1, -1))

        val header = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        categoryTitle = TextView(context).apply {
            text = request.name
            textSize = 21f
            setSingleLine(true)
            ellipsize = TextUtils.TruncateAt.END
            setTextColor(Color.WHITE)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        }
        header.addView(
            categoryTitle,
            LinearLayout.LayoutParams(
                0,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                1f
            )
        )

        status = TextView(context).apply {
            textSize = 14f
            setTextColor(Color.rgb(184, 184, 189))
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, dp(12), 0)
        }
        header.addView(status)

        filterToggleButton = focusableButton("☷  Filters  ▾")
        header.addView(filterToggleButton, LinearLayout.LayoutParams(
            dp(118), dp(37)
        ).apply { marginEnd = dp(9) })
        closeButton = focusableButton("×").apply {
            contentDescription = "Close"
            textSize = 24f
        }
        header.addView(closeButton, LinearLayout.LayoutParams(dp(40), dp(37)))
        root.addView(header)

        // A single unobtrusive row shows APPLIED filters. The editor stays
        // invisible until requested and never pushes the posters downward.
        chipsRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            isFocusable = false
        }
        // Small screens and long country/genre labels can scroll; no
        // cropped chips and no extra focusable elements in the DPAD path.
        val chipScroller = HorizontalScrollView(context).apply {
            isHorizontalScrollBarEnabled = false
            isFocusable = false
            overScrollMode = View.OVER_SCROLL_NEVER
            clipToPadding = false
            addView(chipsRow, ViewGroup.LayoutParams(-2, -1))
        }
        root.addView(chipScroller, LinearLayout.LayoutParams(-1, dp(31)).apply {
            topMargin = dp(3)
        })

        fun filterRow(vararg buttons: Button): LinearLayout =
            LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                for ((index, button) in buttons.withIndex()) {
                    addView(button, LinearLayout.LayoutParams(
                        0, dp(42), 1f
                    ).apply {
                        if (index < buttons.lastIndex) marginEnd = dp(9)
                    })
                }
            }

        typeButton = focusableButton("")
        genreButton = focusableButton("")
        sortButton = focusableButton("")
        countryButton = focusableButton("")
        resetButton = focusableButton("Reset")
        showResultsButton = focusableButton("Show Results")

        // DRAFT selections avoid three or four immediate catalog requests
        // as the user moves between the type, genre, order and country.
        filterToggleButton.setOnClickListener { setFilterPanelVisible(!filterPanelVisible) }

        typeButton.setOnClickListener {
            focusFilterId = typeButton.id
            val current = draftFilters ?: MOOSABrowseFilters.initial(request)
            val labels = arrayOf("Movies", "TV Series")
            AlertDialog.Builder(requireContext())
                .setTitle("Type • Real TMDB results")
                .setSingleChoiceItems(labels, if (current.isTv) 1 else 0) { dialog, which ->
                    draftFilters = MOOSABrowseFilters.withType(current, which == 1)
                    updateFilterLabels()
                    dialog.dismiss()
                }
                .setNegativeButton("CANCEL", null).show()
        }

        genreButton.setOnClickListener {
            focusFilterId = genreButton.id
            val current = draftFilters ?: MOOSABrowseFilters.initial(request)
            val genres = MOOSABrowseFilters.availableGenres(current.isTv)
            val labels = (listOf("All genres") + genres.map { it.label }).toTypedArray()
            val index = genres.indexOfFirst { it.key == current.genreKey } + 1
            AlertDialog.Builder(requireContext())
                .setTitle("Genre • Real TMDB categories")
                .setSingleChoiceItems(labels, index) { dialog, which ->
                    val nextGenre = if (which == 0) null else genres[which - 1].key
                    draftFilters = current.copy(
                        genreKey = nextGenre,
                        // Indian/US shelves should not accidentally return zero
                        // matches when Anime is selected. The country chip can
                        // still be changed independently after this.
                        originCountry = if (nextGenre == "anime") "JP"
                        else current.originCountry
                    )
                    updateFilterLabels()
                    dialog.dismiss()
                }
                .setNegativeButton("CANCEL", null).show()
        }

        sortButton.setOnClickListener {
            focusFilterId = sortButton.id
            val current = draftFilters ?: MOOSABrowseFilters.initial(request)
            val options = MOOSABrowseFilters.sorts
            val index = options.indexOfFirst { it.apiSort == current.sort }.coerceAtLeast(0)
            AlertDialog.Builder(requireContext())
                .setTitle("Sort • Server-side ranking")
                .setSingleChoiceItems(options.map { it.name }.toTypedArray(), index) { dialog, which ->
                    draftFilters = current.copy(sort = options[which].apiSort)
                    updateFilterLabels()
                    dialog.dismiss()
                }
                .setNegativeButton("CANCEL", null).show()
        }

        countryButton.setOnClickListener {
            focusFilterId = countryButton.id
            val current = draftFilters ?: MOOSABrowseFilters.initial(request)
            val options = MOOSABrowseFilters.countries
            val index = options.indexOfFirst { it.code == current.originCountry }.coerceAtLeast(0)
            AlertDialog.Builder(requireContext())
                .setTitle("Country of origin • TMDB")
                .setSingleChoiceItems(options.map { it.name }.toTypedArray(), index) { dialog, which ->
                    draftFilters = current.copy(originCountry = options[which].code)
                    updateFilterLabels()
                    dialog.dismiss()
                }
                .setNegativeButton("CANCEL", null).show()
        }

        resetButton.setOnClickListener {
            focusFilterId = resetButton.id
            draftFilters = null
            updateFilterLabels()
        }
        showResultsButton.setOnClickListener {
            val change = draftFilters != selectedFilters
            if (change) applyFilters(draftFilters)
            setFilterPanelVisible(false, focusResults = true)
        }

        // Directed navigation: top row -> second row -> actions, never a
        // diagonal jump into the grid while the dropdown is open.
        filterToggleButton.nextFocusRightId = closeButton.id
        closeButton.nextFocusLeftId = filterToggleButton.id
        typeButton.nextFocusRightId = genreButton.id
        genreButton.nextFocusLeftId = typeButton.id
        typeButton.nextFocusDownId = sortButton.id
        genreButton.nextFocusDownId = countryButton.id
        typeButton.nextFocusUpId = filterToggleButton.id
        genreButton.nextFocusUpId = filterToggleButton.id
        sortButton.nextFocusUpId = typeButton.id
        countryButton.nextFocusUpId = genreButton.id
        sortButton.nextFocusDownId = resetButton.id
        countryButton.nextFocusDownId = showResultsButton.id
        resetButton.nextFocusUpId = sortButton.id
        showResultsButton.nextFocusUpId = countryButton.id
        sortButton.nextFocusRightId = countryButton.id
        countryButton.nextFocusLeftId = sortButton.id
        resetButton.nextFocusRightId = showResultsButton.id
        showResultsButton.nextFocusLeftId = resetButton.id
        updateFilterLabels()

        progress = ProgressBar(
            context,
            null,
            android.R.attr.progressBarStyleHorizontal
        ).apply {
            max = 100
            progress = 0
            progressTintList = ColorStateList.valueOf(Color.WHITE)
            progressBackgroundTintList = ColorStateList.valueOf(Color.rgb(55, 55, 60))
            visibility = View.INVISIBLE // Reserve space to avoid grid jumping
        }
        root.addView(
            progress,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(4)
            ).apply {
                topMargin = dp(4)
                bottomMargin = dp(4)
            }
        )

        recycler = AutofitRecyclerView(context).apply {
            id = View.generateViewId()
            overScrollMode = View.OVER_SCROLL_NEVER
            itemAnimator = null
            setHasFixedSize(true)
            setItemViewCacheSize(8)
            descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
            isFocusable = false
            clipToPadding = false
            setPadding(0, dp(4), 0, dp(12))
        }

        val screenWidth = resources.displayMetrics.widthPixels
        val desired = if (request.horizontalImages) dp(260) else dp(175)
        recycler.spanCount = (screenWidth / desired).coerceIn(
            if (request.horizontalImages) 3 else 5,
            if (request.horizontalImages) 5 else 8
        )

        adapter = SearchAdapter(
            recycler,
            request.horizontalImages
        ) { callback ->
            if (callback.action == SEARCH_ACTION_SHOW_METADATA) {
                // CloudStream's long press opens a separate preview.
                // Its More Info button navigates later without invoking
                // SEARCH_ACTION_LOAD. Track that navigation as well.
                returnPosition = callback.position.coerceAtLeast(0)
                metadataPreviewRequested = true
            }
            if (
                callback.action == SEARCH_ACTION_LOAD ||
                callback.action == SEARCH_ACTION_PLAY_FILE
            ) {
                metadataPreviewRequested = false
                // Preserve the list and selected item. Only show it again
                // after a verified result/player -> home navigation.
                returnPosition = callback.position.coerceAtLeast(0)
                waitingForResult = true
                resultDestinationSeen = false
                genuineHomeReturnObserved = false
                cancelPendingReturn()
                dialog?.hide()
            }

            handleSearchClickCallback(callback)
        }
        recycler.adapter = adapter
        recycler.setRecycledViewPool(SearchAdapter.sharedPool)

        recycler.addOnScrollListener(
            object : RecyclerView.OnScrollListener() {
                override fun onScrolled(
                    recyclerView: RecyclerView,
                    dx: Int,
                    dy: Int
                ) {
                    super.onScrolled(recyclerView, dx, dy)
                    maybeLoadMore()
                }

                override fun onScrollStateChanged(
                    recyclerView: RecyclerView,
                    newState: Int
                ) {
                    super.onScrollStateChanged(recyclerView, newState)
                    maybeLoadMore()
                }
            }
        )

        root.addView(
            recycler,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        )

        // The scrim dims only the catalog. The header and filter trigger
        // remain accessible above the dropdown.
        filtersScrim = View(context).apply {
            setBackgroundColor(Color.argb(145, 0, 0, 0))
            visibility = View.GONE
            isClickable = true
            setOnClickListener { setFilterPanelVisible(false) }
        }
        container.addView(filtersScrim, FrameLayout.LayoutParams(-1, -1).apply {
            topMargin = dp(54)
            leftMargin = dp(10)
            rightMargin = dp(10)
        })
        filterPanel = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(15), dp(13), dp(15), dp(13))
            background = rounded(Color.rgb(34, 34, 39), 11,
                Color.rgb(87, 87, 94))
            visibility = View.GONE
            isFocusable = false
            elevation = dp(14).toFloat()
        }
        filterPanel.addView(TextView(context).apply {
            text = "Customize results"
            textSize = 16f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setTextColor(Color.WHITE)
        }, LinearLayout.LayoutParams(-1, dp(28)))
        filterPanel.addView(filterRow(typeButton, genreButton),
            LinearLayout.LayoutParams(-1, dp(49)))
        filterPanel.addView(filterRow(sortButton, countryButton),
            LinearLayout.LayoutParams(-1, dp(49)).apply { topMargin = dp(4) })
        filterPanel.addView(filterRow(resetButton, showResultsButton),
            LinearLayout.LayoutParams(-1, dp(49)).apply { topMargin = dp(8) })
        container.addView(filterPanel, FrameLayout.LayoutParams(-1, -2).apply {
            topMargin = dp(57)
            leftMargin = dp(15)
            rightMargin = dp(15)
        })
        filterToggleButton.setOnKeyListener { _, keyCode, event ->
            if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN &&
                event.action == KeyEvent.ACTION_DOWN
            ) {
                if (filterPanelVisible) typeButton.requestFocus()
                else focusFirstVisiblePoster()
            } else false
        }
        closeButton.nextFocusDownId = filterToggleButton.id
        closeButton.setOnClickListener { closeWithMotion() }

        installNavigationReturnWatcher()

        browseJob = lifecycleScope.launch {
            loadInitial()
        }

        return Dialog(context).apply {
            requestWindowFeature(Window.FEATURE_NO_TITLE)
            setContentView(container)
            setCanceledOnTouchOutside(true)
            setOnKeyListener { _, keyCode, event ->
                when {
                    filterPanelVisible && (
                        keyCode == KeyEvent.KEYCODE_DPAD_DOWN ||
                        keyCode == KeyEvent.KEYCODE_DPAD_UP ||
                        keyCode == KeyEvent.KEYCODE_DPAD_LEFT ||
                        keyCode == KeyEvent.KEYCODE_DPAD_RIGHT
                    ) -> {
                        // Modal focus trap. The underlying RecyclerView must
                        // never receive a DPAD direction while filters are open,
                        // including at the last filter row.
                        if (event.action == KeyEvent.ACTION_DOWN) {
                            moveFilterPanelFocus(keyCode)
                        }
                        true
                    }
                    keyCode == KeyEvent.KEYCODE_BACK ||
                        keyCode == KeyEvent.KEYCODE_ESCAPE -> {
                        if (event.action == KeyEvent.ACTION_UP) {
                            if (filterPanelVisible) setFilterPanelVisible(false)
                            else closeWithMotion()
                        }
                        true
                    }
                    else -> false
                }
            }
        }
    }

    private fun destinationName(activity: AppCompatActivity, dest: Any?): String {
        val id = try {
            dest?.javaClass?.methods?.firstOrNull {
                it.name == "getId" && it.parameterCount == 0
            }?.invoke(dest) as? Int ?: 0
        } catch (_: Throwable) { 0 }
        return try {
            if (id != 0) activity.resources.getResourceEntryName(id).lowercase() else ""
        } catch (_: Throwable) { "" }
    }

    private fun isHome(name: String): Boolean =
        name == "navigation_home" || name.endsWith("_home")

    private fun isContent(name: String): Boolean =
        "result" in name || "navigation_player" in name ||
            name.endsWith("_player")

    private fun homeActuallyVisible(host: AppCompatActivity): Boolean {
        if (host.isFinishing || host.isDestroyed ||
            !host.hasWindowFocus() ||
            !lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
        ) return false
        val id = host.resources.getIdentifier(
            "home_master_recycler", "id", host.packageName
        )
        if (id == 0) return false
        val home = host.findViewById<View>(id) ?: return false
        return home.isAttachedToWindow && home.isShown
    }

    private fun scheduleVerifiedHomeReturn(host: AppCompatActivity, controller: Any) {
        cancelPendingReturn()
        val action = object : Runnable {
            override fun run() {
                pendingReturn = null
                val current = try {
                    controller.javaClass.methods.firstOrNull {
                        it.name == "getCurrentDestination" && it.parameterCount == 0
                    }?.invoke(controller)
                } catch (_: Throwable) { null }
                // Never restore just because the Activity resumed from a
                // screen saver. A real content -> home transition must have
                // been observed AND the home UI must now be foreground.
                if (!waitingForResult || !resultDestinationSeen ||
                    !isHome(destinationName(host, current)) ||
                    !homeActuallyVisible(host) ||
                    host.supportFragmentManager.isStateSaved
                ) return

                waitingForResult = false
                resultDestinationSeen = false
                metadataPreviewRequested = false
                genuineHomeReturnObserved = false
                dialog?.show()
                recycler.post {
                    val position = returnPosition.coerceIn(
                        0, (adapter.itemCount - 1).coerceAtLeast(0)
                    )
                    (recycler.layoutManager as? GridLayoutManager)
                        ?.scrollToPositionWithOffset(position, 0)
                    recycler.post {
                        recycler.layoutManager?.findViewByPosition(position)
                            ?.requestFocus()
                    }
                }
            }
        }
        pendingReturn = action
        returnHandler.postDelayed(action, 1100L)
    }

    private fun installNavigationReturnWatcher() {
        val host = activity as? AppCompatActivity ?: return
        val navId = host.resources.getIdentifier(
            "nav_host_fragment", "id", host.packageName
        )
        if (navId == 0) return
        val navHost = host.supportFragmentManager.findFragmentById(navId)
            ?: return
        val controller = try {
            navHost.javaClass.methods.firstOrNull {
                it.name == "getNavController" && it.parameterCount == 0
            }?.invoke(navHost)
        } catch (_: Throwable) { null } ?: return
        val klass = try {
            Class.forName("androidx.navigation.NavController\$OnDestinationChangedListener")
        } catch (_: Throwable) { return }

        val listener = Proxy.newProxyInstance(
            klass.classLoader, arrayOf(klass)
        ) { _, method, args ->
            if (method.name == "onDestinationChanged") {
                val name = destinationName(host, args?.getOrNull(1))
                if (isContent(name) &&
                    (waitingForResult || metadataPreviewRequested)
                ) {
                    // More Info in CloudStream's preview bypasses the normal
                    // SearchAdapter LOAD action. Hide the preserved overlay
                    // when the results page actually becomes the destination.
                    waitingForResult = true
                    metadataPreviewRequested = false
                    resultDestinationSeen = true
                    genuineHomeReturnObserved = false
                    cancelPendingReturn()
                    dialog?.hide()
                } else if (waitingForResult) {
                    when {
                        isContent(name) -> {
                            resultDestinationSeen = true
                        genuineHomeReturnObserved = false
                        cancelPendingReturn()
                            dialog?.hide()
                        }
                        resultDestinationSeen && isHome(name) -> {
                            genuineHomeReturnObserved = true
                            scheduleVerifiedHomeReturn(host, controller)
                        }
                        else -> {
                            genuineHomeReturnObserved = false
                            cancelPendingReturn()
                        }
                    }
                }
            }
            null
        }
        try {
            controller.javaClass.methods.firstOrNull {
                it.name == "addOnDestinationChangedListener" &&
                    it.parameterCount == 1
            }?.invoke(controller, listener)
            navController = controller
            destinationListener = listener
        } catch (_: Throwable) {
            navController = null
            destinationListener = null
        }
    }

    override fun onDestroy() {
        if (::browseRoot.isInitialized) browseRoot.animate().cancel()
        // CloudStream may ship an older kotlinx.coroutines runtime without
        // Job.cancel$default. Explicit cause invokes the Job interface method.
        browseJob?.cancel(CancellationException("Show More dialog destroyed"))
        cancelPendingReturn()
        val controller = navController
        val listener = destinationListener
        if (controller != null && listener != null) {
            try {
                controller.javaClass.methods.firstOrNull {
                    it.name == "removeOnDestinationChangedListener" &&
                        it.parameterCount == 1
                }?.invoke(controller, listener)
            } catch (_: Throwable) { }
        }
        navController = null
        destinationListener = null
        super.onDestroy()
    }

    override fun onDismiss(dialog: android.content.DialogInterface) {
        cancelPendingReturn()
        super.onDismiss(dialog)
        if (!waitingForResult) {
            MOOSASeeAll.restoreFocus(request.name)
        }
    }

    override fun onStop() {
        cancelPendingReturn()
        super.onStop()
    }

    override fun onResume() {
        super.onResume()
        // A return-to-home event can arrive while Android is still paused.
        // Only retry that already-observed navigation, never restore merely
        // because the TV screen saver was dismissed.
        if (waitingForResult && resultDestinationSeen &&
            genuineHomeReturnObserved
        ) {
            val host = activity as? AppCompatActivity
            val controller = navController
            if (host != null && controller != null) {
                scheduleVerifiedHomeReturn(host, controller)
            }
        }
    }

    override fun onStart() {
        super.onStart()
        // DialogFragment.show() runs on every lifecycle start even when the
        // dialog was explicitly hidden. Keep it hidden over an active player.
        if (waitingForResult) dialog?.hide()
        val metrics = resources.displayMetrics
        dialog?.window?.apply {
            setLayout(
                (metrics.widthPixels * 0.95f).toInt(),
                (metrics.heightPixels * 0.94f).toInt()
            )
            setBackgroundDrawableResource(android.R.color.transparent)
        }
        if (!entryPlayed && ::browseRoot.isInitialized && !waitingForResult) {
            entryPlayed = true
            browseRoot.animate()
                .alpha(1f).scaleX(1f).scaleY(1f)
                .setDuration(180L)
                .setInterpolator(DecelerateInterpolator())
                .start()
        }
    }

    private fun moveFilterPanelFocus(direction: Int) {
        // Keep the directional grid entirely inside the modal panel.
        // The default Android focus search can otherwise descend through the
        // overlay into a poster when the remote presses DOWN on the last row.
        val focused = dialog?.currentFocus
        val current: View = when (focused) {
            typeButton -> typeButton
            genreButton -> genreButton
            sortButton -> sortButton
            countryButton -> countryButton
            resetButton -> resetButton
            showResultsButton -> showResultsButton
            else -> typeButton
        }
        val target: View = when (direction) {
            KeyEvent.KEYCODE_DPAD_DOWN -> when (current) {
                typeButton -> sortButton
                genreButton -> countryButton
                sortButton -> resetButton
                countryButton -> showResultsButton
                else -> current
            }
            KeyEvent.KEYCODE_DPAD_UP -> when (current) {
                sortButton -> typeButton
                countryButton -> genreButton
                resetButton -> sortButton
                showResultsButton -> countryButton
                else -> current
            }
            KeyEvent.KEYCODE_DPAD_LEFT -> when (current) {
                genreButton -> typeButton
                countryButton -> sortButton
                showResultsButton -> resetButton
                else -> current
            }
            KeyEvent.KEYCODE_DPAD_RIGHT -> when (current) {
                typeButton -> genreButton
                sortButton -> countryButton
                resetButton -> showResultsButton
                else -> current
            }
            else -> current
        }
        if (target !== focused) target.requestFocus()
    }

    private fun setFilterPanelVisible(open: Boolean, focusResults: Boolean = false) {
        if (!::filterPanel.isInitialized || open == filterPanelVisible) return
        filterPanelVisible = open
        // Temporarily remove the entire movie grid from Android's focus
        // search. Restoring it on close makes the modal truly self-contained.
        recycler.descendantFocusability = if (open)
            ViewGroup.FOCUS_BLOCK_DESCENDANTS
        else ViewGroup.FOCUS_AFTER_DESCENDANTS
        filterPanel.animate().cancel()
        filtersScrim.animate().cancel()
        filterToggleButton.text = if (open) "☷  Filters  ▴" else "☷  Filters  ▾"
        if (open) {
            draftFilters = selectedFilters
            updateFilterLabels()
            filtersScrim.visibility = View.VISIBLE
            filtersScrim.alpha = 0f
            filtersScrim.animate().alpha(1f).setDuration(190L).start()
            filterPanel.visibility = View.VISIBLE
            filterPanel.alpha = 0f
            filterPanel.translationY = -dp(27).toFloat()
            filterPanel.animate().alpha(1f).translationY(0f)
                .setDuration(260L).setInterpolator(DecelerateInterpolator())
                .start()
            // Focus immediately, rather than leaving a ~260ms window
            // where the next DPAD press can land on the poster grid.
            restoreFilterFocus()
        } else {
            filterPanel.animate().alpha(0f)
                .translationY(-dp(22).toFloat())
                .setDuration(190L).setInterpolator(DecelerateInterpolator())
                .withEndAction {
                    if (!filterPanelVisible) {
                        filterPanel.visibility = View.GONE
                        if (focusResults) {
                            // New network results may not have arrived yet;
                            // loadInitial() handles the first-card focus then.
                            if (adapter.itemCount > 0) focusFirstVisiblePoster()
                            else filterToggleButton.requestFocus()
                        } else filterToggleButton.requestFocus()
                    }
                }.start()
            filtersScrim.animate().alpha(0f).setDuration(190L)
                .withEndAction {
                    if (!filterPanelVisible) filtersScrim.visibility = View.GONE
                }.start()
        }
    }

    private fun closeWithMotion() {
        if (closingAnimated || !::browseRoot.isInitialized) return
        closingAnimated = true
        if (::filterPanel.isInitialized) {
            filterPanel.animate().cancel()
            filterPanel.visibility = View.GONE
            filtersScrim.animate().cancel()
            filtersScrim.visibility = View.GONE
        }
        browseRoot.animate().cancel()
        browseRoot.animate()
            .alpha(0f).scaleX(0.985f).scaleY(0.985f)
            .setDuration(145L)
            .setInterpolator(DecelerateInterpolator())
            .withEndAction {
                if (isAdded) dismissAllowingStateLoss()
            }
            .start()
    }

    private fun focusFirstVisiblePoster(): Boolean {
        if (filterPanelVisible) return false
        if (!::recycler.isInitialized || !::adapter.isInitialized ||
            adapter.itemCount == 0
        ) return false
        val manager = recycler.layoutManager as? GridLayoutManager ?: return false
        val first = manager.findFirstVisibleItemPosition().coerceAtLeast(0)
        val card = manager.findViewByPosition(first)
            ?: manager.findViewByPosition(0)
        if (card != null) return card.requestFocus()
        recycler.post {
            (recycler.layoutManager as? GridLayoutManager)
                ?.findViewByPosition(first)?.requestFocus()
        }
        return true
    }

    private fun restoreFilterFocus() {
        if (!::typeButton.isInitialized) return
        val id = if (focusFilterId == View.NO_ID) typeButton.id else focusFilterId
        val target = listOf(
            typeButton, genreButton, sortButton, countryButton,
            resetButton, showResultsButton
        ).firstOrNull { it.id == id && it.isEnabled } ?: typeButton
        target.post {
            if (isAdded && filterPanelVisible &&
                !waitingForResult && !closingAnimated
            ) {
                target.requestFocus()
            }
        }
    }

    private fun saveSnapshot(key: String) {
        if (items.isEmpty()) return
        browseSnapshots[key] = BrowseSnapshot(
            items.toList(), lastPage, hasNext, android.os.SystemClock.elapsedRealtime()
        )
        if (browseSnapshots.size > 6) {
            browseSnapshots.remove(browseSnapshots.keys.first())
        }
    }

    private fun updateFilterLabels() {
        // Text on the control panel represents draft filters.
        val draft = draftFilters
        val current = draft ?: MOOSABrowseFilters.initial(request)
        val genre = MOOSABrowseFilters.genres.firstOrNull { it.key == current.genreKey }
        val sort = MOOSABrowseFilters.sorts.firstOrNull { it.apiSort == current.sort }
        val country = MOOSABrowseFilters.countries.firstOrNull {
            it.code == current.originCountry
        }
        typeButton.text = "Type: " + if (current.isTv) "Series" else "Movies"
        genreButton.text = "Genre: " + (genre?.label ?: "All")
        sortButton.text = "Sort: " + if (draft == null) "Shelf" else (sort?.name ?: "Popular")
        countryButton.text = "Country: " + (country?.name ?: "All")
        resetButton.alpha = 1f

        // Chips represent APPLIED filters, never uncommitted panel edits.
        val applied = selectedFilters
        val active = applied ?: MOOSABrowseFilters.initial(request)
        val labels = listOfNotNull(
            if (active.isTv) "Series" else "Movies",
            MOOSABrowseFilters.genres.firstOrNull { it.key == active.genreKey }
                ?.label ?: "All genres",
            if (applied == null) "Shelf" else (
                MOOSABrowseFilters.sorts.firstOrNull { it.apiSort == active.sort }
                    ?.name ?: "Popular"),
            MOOSABrowseFilters.countries.firstOrNull {
                it.code == active.originCountry && it.code.isNotEmpty()
            }?.name
        )
        if (labels == lastRenderedChips) return
        lastRenderedChips = labels
        chipsRow.removeAllViews()
        for (label in labels) {
            val chip = TextView(requireContext()).apply {
                text = label
                textSize = 11f
                setTextColor(Color.rgb(195, 195, 204))
                gravity = Gravity.CENTER
                setPadding(dp(10), 0, dp(10), 0)
                setSingleLine(true)
                ellipsize = TextUtils.TruncateAt.END
                background = rounded(Color.rgb(38, 38, 43), 6,
                    Color.rgb(68, 68, 75))
                isFocusable = false
            }
            chipsRow.addView(chip, LinearLayout.LayoutParams(-2, dp(23)).apply {
                marginEnd = dp(7)
            })
        }
    }

    private fun updateCategoryTitle() {
        if (!::categoryTitle.isInitialized) return
        val applied = selectedFilters
        if (applied == null) {
            categoryTitle.text = request.name
            return
        }
        val medium = if (applied.isTv) "Series" else "Movies"
        val genre = MOOSABrowseFilters.genres.firstOrNull {
            it.key == applied.genreKey
        }?.label
        val country = MOOSABrowseFilters.countries.firstOrNull {
            it.code == applied.originCountry && it.code.isNotEmpty()
        }?.name
        val order = MOOSABrowseFilters.sorts.firstOrNull {
            it.apiSort == applied.sort
        }?.name ?: "Most popular"
        val kind = if (genre == null) medium else "$genre $medium"
        categoryTitle.text = listOfNotNull(country, kind, order)
            .joinToString(" • ")
    }

    private fun applyFilters(choice: MOOSABrowseFilters.Selection?) {
        if (choice == selectedFilters) return
        selectedFilters = choice
        updateCategoryTitle()
        browseGeneration += 1
        // IMPORTANT: never use Job.cancel() with an omitted default argument.
        // CloudStream's bundled coroutines runtime lacks Job.cancel$default,
        // which previously crashed the whole app when a filter was changed.
        browseJob?.cancel(CancellationException("Show More filters changed"))
        loading = false
        lastPage = 0
        hasNext = true
        stayOnFilterAfterReload = false
        items.clear()
        adapter.submitList(emptyList())
        recycler.scrollToPosition(0)
        updateFilterLabels()
        recycler.animate().cancel()
        recycler.alpha = 0.6f
        val cached = browseSnapshots[selectedRequest().data]
            ?.takeIf {
                android.os.SystemClock.elapsedRealtime() - it.savedAtMs < 120_000L
            }
        if (cached != null) {
            items.addAll(cached.items)
            lastPage = cached.lastPage
            hasNext = cached.hasNext
            adapter.submitList(items.toList())
            progress.visibility = View.INVISIBLE
            recycler.animate().alpha(1f).setDuration(170L).start()
            recycler.post { maybeLoadMore() }
        } else {
            browseJob = lifecycleScope.launch { loadInitial() }
        }
        // Show Results returns focus to the catalog. When a fresh request
        // finishes loadInitial() focuses the first result only once.
    }

    private suspend fun loadInitial() {
        if (loading) return
        val generation = browseGeneration
        val effectiveRequest = selectedRequest()
        loading = true

        progress.visibility = View.VISIBLE
        progress.isIndeterminate = true
        status.visibility = View.GONE
        status.text = ""

        try {
            val batch = withContext(Dispatchers.IO) {
                MOOSACatalog.browseBatch(
                    api = api,
                    request = effectiveRequest,
                    startPage = 1,
                    pageCount = 2
                )
            }

            if (generation != browseGeneration) return
            items.clear()
            items += batch.items
            lastPage = batch.lastPage
            hasNext = batch.hasNext

            adapter.submitList(items.toList())
            saveSnapshot(effectiveRequest.data)
            recycler.animate().cancel()
            recycler.animate().alpha(1f).setDuration(175L)
                .setInterpolator(DecelerateInterpolator()).start()

            status.text = if (items.isEmpty()) "No titles available" else ""
            status.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE

            if (filterPanelVisible) {
                restoreFilterFocus()
            } else if (!stayOnFilterAfterReload) {
                recycler.post { focusFirstVisiblePoster() }
            } else {
                restoreFilterFocus()
            }
        } catch (error: CancellationException) {
            throw error
        } catch (_: Throwable) {
            if (generation == browseGeneration) {
                status.text = "Could not load this category"
                status.visibility = View.VISIBLE
            }
        } finally {
            if (generation != browseGeneration) return
            progress.visibility = View.INVISIBLE
            progress.isIndeterminate = false
            loading = false
            // Continue requesting pages if the viewport is not scrollable
            // or if there are too few visible cards to fire a scroll event.
            recycler.post { maybeLoadMore() }
        }
    }

    private fun maybeLoadMore() {
        if (loading || !hasNext || items.isEmpty()) return

        val manager = recycler.layoutManager as? GridLayoutManager ?: return
        val lastVisible = manager.findLastVisibleItemPosition()
        if (lastVisible < adapter.itemCount - 14) return

        browseJob = lifecycleScope.launch {
            loadMore()
        }
    }

    private suspend fun loadMore() {
        if (loading || !hasNext) return
        val generation = browseGeneration
        val effectiveRequest = selectedRequest()
        loading = true

        progress.visibility = View.VISIBLE
        progress.isIndeterminate = true
        status.text = ""
        status.visibility = View.GONE

        try {
            val batch = withContext(Dispatchers.IO) {
                MOOSACatalog.browseBatch(
                    api = api,
                    request = effectiveRequest,
                    startPage = lastPage + 1,
                    pageCount = 2
                )
            }

            if (generation != browseGeneration) return
            if (batch.items.isNotEmpty()) {
                items += batch.items

                val unique =
                    items.distinctBy { it.url }

                items.clear()
                items += unique

                lastPage = batch.lastPage
                hasNext = batch.hasNext
                adapter.submitList(items.toList())
                saveSnapshot(effectiveRequest.data)
            } else {
                hasNext = false
            }

            status.text = ""
            status.visibility = View.GONE
        } catch (error: CancellationException) {
            throw error
        } catch (_: Throwable) {
            if (generation == browseGeneration) {
                status.text = "Could not load more titles"
                status.visibility = View.VISIBLE
            }
        } finally {
            if (generation != browseGeneration) return
            progress.visibility = View.INVISIBLE
            progress.isIndeterminate = false
            loading = false
            recycler.post { maybeLoadMore() }
        }
    }

    companion object {
        fun show(
            context: Context,
            api: MOOSAHubProvider,
            request: MainPageRequest
        ) {
            val activity = context as? AppCompatActivity ?: return
            if (activity.isFinishing || activity.isDestroyed ||
                activity.supportFragmentManager.isStateSaved
            ) return

            val tag = "moosa_browse_${request.name}"
            if (activity.supportFragmentManager.findFragmentByTag(tag) != null) return

            MOOSABrowseDialog().apply {
                this.api = api
                this.request = request
            }.show(
                activity.supportFragmentManager,
                tag
            )
        }
    }
}
