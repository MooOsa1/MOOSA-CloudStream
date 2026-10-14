package com.moosa.arabic

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
import com.lagradost.cloudstream3.ui.search.SEARCH_ACTION_PLAY_FILE
import com.lagradost.cloudstream3.ui.search.SearchAdapter
import com.lagradost.cloudstream3.ui.search.SearchHelper.handleSearchClickCallback
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.lang.reflect.Proxy

class MOOSABrowseDialog : DialogFragment() {
    private lateinit var api: MOOSAArabicProvider
    private lateinit var request: MainPageRequest

    private lateinit var recycler: AutofitRecyclerView
    private lateinit var adapter: SearchAdapter
    private lateinit var progress: ProgressBar
    private lateinit var status: TextView
    private lateinit var closeButton: Button

    private val items = mutableListOf<SearchResponse>()
    private var lastPage = 0
    private var hasNext = true
    private var loading = false
    private var waitingForResult = false
    private var resultDestinationSeen = false
    private var returnPosition = 0
    private var navController: Any? = null
    private var destinationListener: Any? = null

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
            this.text = text
            setTextColor(Color.WHITE)
            textSize = 12.5f
            isFocusable = true
            isFocusableInTouchMode = false
            background = rounded(
                Color.rgb(30, 42, 56),
                10,
                Color.argb(100, 255, 255, 255)
            )
            setOnFocusChangeListener { view, focused ->
                view.background = rounded(
                    if (focused) Color.rgb(38, 91, 135)
                    else Color.rgb(34, 82, 128),
                    10,
                    if (focused) Color.WHITE
                    else Color.argb(100, 255, 255, 255),
                    if (focused) 2 else 1
                )
            }
        }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val context = requireContext()

        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(12))
            background = rounded(
                Color.rgb(10, 20, 31),
                18,
                Color.rgb(56, 102, 137),
                2
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
            setTextColor(Color.rgb(174, 191, 207))
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

        progress = ProgressBar(
            context,
            null,
            android.R.attr.progressBarStyleHorizontal
        ).apply {
            max = 100
            progress = 0
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
            if (
                callback.action == SEARCH_ACTION_LOAD ||
                callback.action == SEARCH_ACTION_PLAY_FILE
            ) {
                returnPosition = callback.position.coerceAtLeast(0)
                waitingForResult = true
                resultDestinationSeen = false
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

        closeButton.nextFocusDownId = recycler.id
        closeButton.setOnClickListener {
            dismissAllowingStateLoss()
        }

        installNavigationReturnWatcher()

        lifecycleScope.launch {
            loadInitial()
        }

        return Dialog(context).apply {
            requestWindowFeature(Window.FEATURE_NO_TITLE)
            setContentView(root)
            setCanceledOnTouchOutside(true)
        }
    }

    override fun onDestroy() {
        val controller = navController
        val listener = destinationListener

        if (controller != null && listener != null) {
            try {
                controller.javaClass.methods
                    .firstOrNull {
                        it.name == "removeOnDestinationChangedListener" &&
                            it.parameterCount == 1
                    }
                    ?.invoke(controller, listener)
            } catch (_: Throwable) {
            }
        }

        destinationListener = null
        navController = null
        super.onDestroy()
    }

    private fun installNavigationReturnWatcher() {
        val hostActivity = activity as? AppCompatActivity ?: return
        val navHostId = hostActivity.resources.getIdentifier(
            "nav_host_fragment",
            "id",
            hostActivity.packageName
        )
        if (navHostId == 0) return

        val host = hostActivity.supportFragmentManager
            .findFragmentById(navHostId)
            ?: return

        val controller = try {
            host.javaClass.methods
                .firstOrNull {
                    it.name == "getNavController" &&
                        it.parameterCount == 0
                }
                ?.invoke(host)
        } catch (_: Throwable) {
            null
        } ?: return

        val listenerClass = try {
            Class.forName(
                "androidx.navigation.NavController\$OnDestinationChangedListener"
            )
        } catch (_: Throwable) {
            return
        }

        val listener = Proxy.newProxyInstance(
            listenerClass.classLoader,
            arrayOf(listenerClass)
        ) { _, method, args ->
            if (
                method.name == "onDestinationChanged" &&
                waitingForResult
            ) {
                val destination = args?.getOrNull(1)
                val destinationId = try {
                    destination
                        ?.javaClass
                        ?.methods
                        ?.firstOrNull {
                            it.name == "getId" &&
                                it.parameterCount == 0
                        }
                        ?.invoke(destination) as? Int
                } catch (_: Throwable) {
                    null
                } ?: 0

                val entryName = try {
                    if (destinationId != 0) {
                        hostActivity.resources
                            .getResourceEntryName(destinationId)
                    } else {
                        ""
                    }
                } catch (_: Throwable) {
                    ""
                }.lowercase()

                val isContentDestination =
                    "result" in entryName ||
                        "navigation_results" in entryName ||
                        "navigation_player" in entryName

                val isHomeDestination =
                    entryName == "navigation_home" ||
                        entryName.endsWith("_home")

                if (isContentDestination) {
                    resultDestinationSeen = true
                } else if (resultDestinationSeen && isHomeDestination) {
                    // Let navigation and TV focus settle before restoring the
                    // hidden list. Transient home destinations during resume
                    // must not put the dialog over an active player.
                    android.os.Handler(android.os.Looper.getMainLooper())
                        .postDelayed({
                            val currentDestination = try {
                                controller.javaClass.methods
                                    .firstOrNull {
                                        it.name == "getCurrentDestination" &&
                                            it.parameterCount == 0
                                    }
                                    ?.invoke(controller)
                            } catch (_: Throwable) {
                                null
                            }
                            val currentId = try {
                                currentDestination
                                    ?.javaClass
                                    ?.methods
                                    ?.firstOrNull {
                                        it.name == "getId" &&
                                            it.parameterCount == 0
                                    }
                                    ?.invoke(currentDestination) as? Int
                            } catch (_: Throwable) {
                                null
                            } ?: 0
                            val currentName = try {
                                if (currentId != 0) {
                                    hostActivity.resources.getResourceEntryName(currentId)
                                } else {
                                    ""
                                }
                            } catch (_: Throwable) {
                                ""
                            }.lowercase()
                            val stillHome =
                                currentName == "navigation_home" ||
                                    currentName.endsWith("_home")

                            if (
                                waitingForResult &&
                                resultDestinationSeen &&
                                stillHome &&
                                hostActivity.hasWindowFocus() &&
                                !hostActivity.isFinishing &&
                                !hostActivity.isDestroyed
                            ) {
                                waitingForResult = false
                                resultDestinationSeen = false
                                dialog?.show()
                                recycler.post {
                                    recycler.scrollToPosition(returnPosition)
                                    recycler.layoutManager
                                        ?.findViewByPosition(returnPosition)
                                        ?.requestFocus()
                                }
                            }
                        }, 700L)
                }
            }
            null
        }

        try {
            controller.javaClass.methods
                .firstOrNull {
                    it.name == "addOnDestinationChangedListener" &&
                        it.parameterCount == 1
                }
                ?.invoke(controller, listener)

            navController = controller
            destinationListener = listener
        } catch (_: Throwable) {
            navController = null
            destinationListener = null
        }
    }

    override fun onDismiss(dialog: android.content.DialogInterface) {
        super.onDismiss(dialog)
        waitingForResult = false
        MOOSASeeAll.restoreFocus(request.name)
    }

    override fun onStart() {
        super.onStart()
        val metrics = resources.displayMetrics
        dialog?.window?.apply {
            setLayout(
                (metrics.widthPixels * 0.95f).toInt(),
                (metrics.heightPixels * 0.94f).toInt()
            )
            setBackgroundDrawableResource(android.R.color.transparent)
        }
    }

    private suspend fun loadInitial() {
        if (loading) return
        loading = true

        progress.visibility = View.VISIBLE
        progress.isIndeterminate = true
        status.text = "Loading…"

        try {
            val batch = withContext(Dispatchers.IO) {
                MOOSACatalog.browseBatch(
                    api = api,
                    request = request,
                    startPage = 1,
                    pageCount = 10
                )
            }

            items.clear()
            items += batch.items
            lastPage = batch.lastPage
            hasNext = batch.hasNext

            adapter.submitList(items.toList())

            status.text = when {
                items.isEmpty() ->
                    "No titles available"
                hasNext ->
                    "${items.size} titles • more available"
                else ->
                    "${items.size} titles"
            }

            recycler.post {
                recycler.layoutManager
                    ?.findViewByPosition(0)
                    ?.requestFocus()
            }
        } catch (_: Throwable) {
            status.text = "Could not load this category"
        } finally {
            progress.visibility = View.GONE
            progress.isIndeterminate = false
            loading = false
        }
    }

    private fun maybeLoadMore() {
        if (loading || !hasNext || items.isEmpty()) return

        val manager = recycler.layoutManager as? GridLayoutManager ?: return
        val lastVisible = manager.findLastVisibleItemPosition()
        if (lastVisible < adapter.itemCount - 14) return

        lifecycleScope.launch {
            loadMore()
        }
    }

    private suspend fun loadMore() {
        if (loading || !hasNext) return
        loading = true

        progress.visibility = View.VISIBLE
        progress.isIndeterminate = true
        status.text =
            "${items.size} titles • loading more…"

        try {
            val batch = withContext(Dispatchers.IO) {
                MOOSACatalog.browseBatch(
                    api = api,
                    request = request,
                    startPage = lastPage + 1,
                    pageCount = 5
                )
            }

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

            status.text =
                "${items.size} titles${if (hasNext) " • more available" else ""}"
        } catch (_: Throwable) {
            status.text =
                "${items.size} titles • retry when you scroll again"
        } finally {
            progress.visibility = View.GONE
            progress.isIndeterminate = false
            loading = false
        }
    }

    companion object {
        fun show(
            context: Context,
            api: MOOSAArabicProvider,
            request: MainPageRequest
        ) {
            val activity = context as? AppCompatActivity ?: return
            if (activity.isFinishing || activity.isDestroyed) return

            MOOSABrowseDialog().apply {
                this.api = api
                this.request = request
            }.show(
                activity.supportFragmentManager,
                "moosa_browse_${request.name}"
            )
        }
    }
}
