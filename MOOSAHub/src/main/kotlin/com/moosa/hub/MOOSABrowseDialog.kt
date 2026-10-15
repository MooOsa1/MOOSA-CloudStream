package com.moosa.hub

import android.app.AlertDialog
import android.app.Dialog
import android.content.Context
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
    private lateinit var closeButton: Button
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
            setTextColor(Color.WHITE)
            textSize = 12.5f
            isFocusable = true
            isFocusableInTouchMode = false
            background = rounded(Color.rgb(33, 33, 36), 6, Color.rgb(92, 92, 96))
            setOnFocusChangeListener { view, focused ->
                view.background = rounded(
                    if (focused) Color.rgb(245, 245, 246) else Color.rgb(33, 33, 36),
                    6,
                    if (focused) Color.WHITE else Color.rgb(92, 92, 96)
                )
                (view as Button).setTextColor(
                    if (focused) Color.rgb(14, 14, 16) else Color.WHITE
                )
            }
        }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val context = requireContext()

        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(12))
            background = rounded(
                Color.rgb(12, 12, 14),
                12,
                Color.rgb(49, 49, 53),
                1
            )
        }

        val header = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val title = TextView(context).apply {
            text = request.name
            textSize = 23f
            setTextColor(Color.WHITE)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        }
        header.addView(
            title,
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

        closeButton = focusableButton("CLOSE")
        header.addView(
            closeButton,
            LinearLayout.LayoutParams(dp(90), dp(42))
        )

        root.addView(header)

        // Focusable on Android TV with DPAD/remote. Each selection changes the
        // server-side TMDB query, never just the visible first-page cards.
        fun filterRow(vararg buttons: Button): LinearLayout =
            LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                for (button in buttons) {
                    addView(button, LinearLayout.LayoutParams(
                        0, dp(42), 1f
                    ).apply { marginEnd = dp(6) })
                }
            }

        typeButton = focusableButton("")
        genreButton = focusableButton("")
        sortButton = focusableButton("")
        countryButton = focusableButton("")
        resetButton = focusableButton("RESET")
        root.addView(filterRow(typeButton, genreButton),
            LinearLayout.LayoutParams(-1, dp(48)).apply { topMargin = dp(6) })
        root.addView(filterRow(sortButton, countryButton, resetButton),
            LinearLayout.LayoutParams(-1, dp(48)))

        typeButton.setOnClickListener {
            val current = selectedFilters ?: MOOSABrowseFilters.initial(request)
            val labels = arrayOf("Movies", "TV Series")
            AlertDialog.Builder(requireContext())
                .setTitle("Type • Real TMDB results")
                .setSingleChoiceItems(labels, if (current.isTv) 1 else 0) { dialog, which ->
                    applyFilters(MOOSABrowseFilters.withType(current, which == 1))
                    dialog.dismiss()
                }
                .setNegativeButton("CANCEL", null).show()
        }

        genreButton.setOnClickListener {
            val current = selectedFilters ?: MOOSABrowseFilters.initial(request)
            val genres = MOOSABrowseFilters.availableGenres(current.isTv)
            val labels = (listOf("All genres") + genres.map { it.label }).toTypedArray()
            val index = genres.indexOfFirst { it.key == current.genreKey } + 1
            AlertDialog.Builder(requireContext())
                .setTitle("Genre • Real TMDB categories")
                .setSingleChoiceItems(labels, index) { dialog, which ->
                    applyFilters(current.copy(
                        genreKey = if (which == 0) null else genres[which - 1].key
                    ))
                    dialog.dismiss()
                }
                .setNegativeButton("CANCEL", null).show()
        }

        sortButton.setOnClickListener {
            val current = selectedFilters ?: MOOSABrowseFilters.initial(request)
            val options = MOOSABrowseFilters.sorts
            val index = options.indexOfFirst { it.apiSort == current.sort }.coerceAtLeast(0)
            AlertDialog.Builder(requireContext())
                .setTitle("Sort • Server-side ranking")
                .setSingleChoiceItems(options.map { it.name }.toTypedArray(), index) { dialog, which ->
                    applyFilters(current.copy(sort = options[which].apiSort))
                    dialog.dismiss()
                }
                .setNegativeButton("CANCEL", null).show()
        }

        countryButton.setOnClickListener {
            val current = selectedFilters ?: MOOSABrowseFilters.initial(request)
            val options = MOOSABrowseFilters.countries
            val index = options.indexOfFirst { it.code == current.originCountry }.coerceAtLeast(0)
            AlertDialog.Builder(requireContext())
                .setTitle("Country of origin • TMDB")
                .setSingleChoiceItems(options.map { it.name }.toTypedArray(), index) { dialog, which ->
                    applyFilters(current.copy(originCountry = options[which].code))
                    dialog.dismiss()
                }
                .setNegativeButton("CANCEL", null).show()
        }

        resetButton.setOnClickListener { applyFilters(null) }
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
            visibility = View.GONE
        }
        root.addView(
            progress,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(6)
            ).apply {
                topMargin = dp(6)
                bottomMargin = dp(6)
            }
        )

        recycler = AutofitRecyclerView(context).apply {
            id = View.generateViewId()
            overScrollMode = View.OVER_SCROLL_NEVER
            itemAnimator = null
            setHasFixedSize(true)
            setItemViewCacheSize(6)
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

        closeButton.nextFocusDownId = typeButton.id
        closeButton.setOnClickListener {
            dismissAllowingStateLoss()
        }

        installNavigationReturnWatcher()

        browseJob = lifecycleScope.launch {
            loadInitial()
        }

        return Dialog(context).apply {
            requestWindowFeature(Window.FEATURE_NO_TITLE)
            setContentView(root)
            setCanceledOnTouchOutside(true)
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
        browseJob?.cancel()
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
    }

    private fun updateFilterLabels() {
        val filters = selectedFilters
        val active = filters ?: MOOSABrowseFilters.initial(request)
        val genre = MOOSABrowseFilters.genres.firstOrNull { it.key == active.genreKey }
        val sort = MOOSABrowseFilters.sorts.firstOrNull { it.apiSort == active.sort }
        val country = MOOSABrowseFilters.countries.firstOrNull {
            it.code == active.originCountry
        }
        typeButton.text = "TYPE: " + if (active.isTv) "SERIES" else "MOVIES"
        genreButton.text = "GENRE: " + (genre?.label ?: "ALL")
        sortButton.text = "SORT: " + if (filters == null) "SHELF" else (sort?.name ?: "POPULAR")
        countryButton.text = "COUNTRY: " + (country?.name ?: "ALL")
        resetButton.isEnabled = filters != null
        resetButton.alpha = if (filters == null) 0.55f else 1f
    }

    private fun applyFilters(choice: MOOSABrowseFilters.Selection?) {
        if (choice == selectedFilters) return
        selectedFilters = choice
        browseGeneration += 1
        browseJob?.cancel()
        loading = false
        lastPage = 0
        hasNext = true
        items.clear()
        adapter.submitList(emptyList())
        recycler.scrollToPosition(0)
        updateFilterLabels()
        browseJob = lifecycleScope.launch { loadInitial() }
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

            status.text = if (items.isEmpty()) "No titles available" else ""
            status.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE

            recycler.post {
                recycler.layoutManager
                    ?.findViewByPosition(0)
                    ?.requestFocus()
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
            progress.visibility = View.GONE
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
            progress.visibility = View.GONE
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
