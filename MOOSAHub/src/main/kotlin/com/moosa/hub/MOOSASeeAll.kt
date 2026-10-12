package com.moosa.hub

import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.TextView
import com.lagradost.cloudstream3.CommonActivity.activity
import com.lagradost.cloudstream3.HomePageList
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.ui.home.HomeFragment
import com.lagradost.cloudstream3.ui.home.HomeViewModel
import java.util.WeakHashMap
import java.util.concurrent.ConcurrentHashMap

object MOOSASeeAll {
    private val mainHandler = Handler(Looper.getMainLooper())

    private data class CategoryState(
        val api: MOOSAHubProvider,
        val request: MainPageRequest,
        var page: Int,
        var items: List<SearchResponse>,
        var hasNext: Boolean,
        val horizontal: Boolean
    )

    private val states = ConcurrentHashMap<String, CategoryState>()
    private val observedRoots = WeakHashMap<ViewGroup, ViewTreeObserver.OnGlobalLayoutListener>()

    fun register(
        api: MOOSAHubProvider,
        request: MainPageRequest,
        items: List<SearchResponse>,
        hasNext: Boolean,
        horizontal: Boolean = false
    ) {
        if (!hasNext || items.isEmpty()) return

        states[request.name] = CategoryState(
            api = api,
            request = request,
            page = 1,
            items = items,
            hasNext = hasNext,
            horizontal = horizontal
        )

        mainHandler.post {
            installObserver()
            applyHeaders()
        }
    }

    private fun installObserver() {
        val current = activity ?: return
        val root = current.findViewById<ViewGroup>(android.R.id.content) ?: return

        synchronized(observedRoots) {
            if (observedRoots.containsKey(root)) return

            val listener = ViewTreeObserver.OnGlobalLayoutListener {
                applyHeaders()
            }
            observedRoots[root] = listener
            root.viewTreeObserver.addOnGlobalLayoutListener(listener)
        }
    }

    private fun collectHeaders(view: View, output: MutableList<TextView>) {
        if (view is TextView) {
            val entry = try {
                if (view.id != View.NO_ID) {
                    view.resources.getResourceEntryName(view.id)
                } else {
                    null
                }
            } catch (_: Throwable) {
                null
            }

            if (entry == "home_child_more_info") {
                output += view
            }
        }

        if (view is ViewGroup) {
            for (index in 0 until view.childCount) {
                collectHeaders(view.getChildAt(index), output)
            }
        }
    }

    private fun originalHeaderText(value: String): String =
        value.substringBefore("  SHOW MORE").trim()

    private fun applyHeaders() {
        val currentActivity = activity ?: return
        val root = currentActivity.findViewById<ViewGroup>(android.R.id.content) ?: return
        val headers = mutableListOf<TextView>()
        collectHeaders(root, headers)

        headers.forEach { header ->
            val original = originalHeaderText(header.text?.toString().orEmpty())
            val state = states[original] ?: return@forEach

            header.text = "${state.request.name}  SHOW MORE  ›"
            header.contentDescription = "${state.request.name}. Show more."
            header.isClickable = true
            header.isFocusable = true
            header.isFocusableInTouchMode = false
            header.alpha = 0.9f

            header.setOnFocusChangeListener { view, focused ->
                view.alpha = if (focused) 1f else 0.9f
                view.setBackgroundColor(
                    if (focused) Color.argb(120, 24, 101, 180)
                    else Color.TRANSPARENT
                )
            }

            header.setOnClickListener {
                openCategory(state)
            }

            header.setOnKeyListener { _, keyCode, event ->
                if (
                    event.action == android.view.KeyEvent.ACTION_UP &&
                    (
                        keyCode == android.view.KeyEvent.KEYCODE_DPAD_CENTER ||
                            keyCode == android.view.KeyEvent.KEYCODE_ENTER
                        )
                ) {
                    openCategory(state)
                    true
                } else {
                    false
                }
            }
        }
    }

    private fun createExpandable(
        state: CategoryState
    ): HomeViewModel.ExpandableHomepageList =
        HomeViewModel.ExpandableHomepageList(
            HomePageList(
                state.request.name,
                state.items,
                state.horizontal
            ),
            state.page,
            state.hasNext
        )

    private suspend fun expandCategory(
        state: CategoryState
    ): HomeViewModel.ExpandableHomepageList? {
        if (!state.hasNext) return createExpandable(state)

        val nextPage = state.page + 1
        val response: HomePageResponse = try {
            MOOSACatalog.home(
                api = state.api,
                page = nextPage,
                request = state.request,
                showMoreButton = false
            )
        } catch (_: Throwable) {
            state.hasNext = false
            return createExpandable(state)
        }

        val nextList = response.items.firstOrNull()
        if (nextList == null || nextList.list.isEmpty()) {
            state.hasNext = false
            return createExpandable(state)
        }

        state.items = (state.items + nextList.list).distinctBy { it.url }
        state.page = nextPage
        state.hasNext = response.hasNext

        return createExpandable(state)
    }

    private fun openCategory(state: CategoryState) {
        val currentActivity = activity ?: return
        val expandable = createExpandable(state)

        mainHandler.post {
            try {
                with(HomeFragment.Companion) {
                    currentActivity.loadHomepageList(
                        expand = expandable,
                        deleteCallback = null,
                        expandCallback = {
                            expandCategory(state)
                        },
                        dismissCallback = {
                            mainHandler.postDelayed({
                                applyHeaders()
                            }, 120L)
                        }
                    )
                }
            } catch (_: Throwable) {
            }
        }
    }
}
