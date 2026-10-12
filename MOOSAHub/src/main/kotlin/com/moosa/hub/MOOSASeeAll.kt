package com.moosa.hub

import android.app.Activity
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import com.lagradost.cloudstream3.HomePageList
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.SearchResponse
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

        mainHandler.postDelayed({ installHeaders(28) }, 180L)
    }

    private fun currentActivity(): Activity? {
        return try {
            val cls = Class.forName("com.lagradost.cloudstream3.CommonActivity")
            val instance = cls.getField("INSTANCE").get(null)
            cls.methods
                .firstOrNull {
                    it.name == "getActivity" && it.parameterCount == 0
                }
                ?.invoke(instance) as? Activity
        } catch (_: Throwable) {
            null
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

    private fun installHeaders(retries: Int) {
        val activity = currentActivity()
        val root = activity?.findViewById<ViewGroup>(android.R.id.content)

        if (activity == null || root == null) {
            if (retries > 0) {
                mainHandler.postDelayed({ installHeaders(retries - 1) }, 220L)
            }
            return
        }

        val headers = mutableListOf<TextView>()
        collectHeaders(root, headers)

        var installed = 0

        headers.forEach { header ->
            val current = header.text?.toString().orEmpty()
                .substringBefore("  SHOW MORE")
                .trim()

            val state = states[current]
                ?: states.entries.firstOrNull {
                    current.startsWith(it.key)
                }?.value
                ?: return@forEach

            val title = state.request.name
            header.text = "$title  SHOW MORE  ›"
            header.contentDescription = "$title. Show more."
            header.isClickable = true
            header.isFocusable = true
            header.isFocusableInTouchMode = true
            header.alpha = 0.88f
            header.setPadding(
                header.paddingLeft,
                header.paddingTop,
                header.paddingRight,
                header.paddingBottom
            )

            header.setOnFocusChangeListener { view, focused ->
                view.alpha = if (focused) 1f else 0.88f
                view.setBackgroundColor(
                    if (focused) Color.argb(90, 30, 120, 205)
                    else Color.TRANSPARENT
                )
            }

            header.setOnClickListener {
                openCategory(state)
            }

            val parent = header.parent as? ViewGroup
            if (parent != null) {
                val recyclerId = activity.resources.getIdentifier(
                    "home_child_recyclerview",
                    "id",
                    activity.packageName
                )
                if (recyclerId != 0) {
                    header.nextFocusDownId = recyclerId
                }
            }

            installed++
        }

        if (installed < states.size && retries > 0) {
            mainHandler.postDelayed({ installHeaders(retries - 1) }, 220L)
        }
    }

    private fun createExpandable(state: CategoryState): Any? {
        return try {
            val expandableClass = Class.forName(
                "com.lagradost.cloudstream3.ui.home.HomeViewModel\$ExpandableHomepageList"
            )
            val constructor = expandableClass.constructors.firstOrNull {
                it.parameterCount == 3
            } ?: return null

            constructor.newInstance(
                HomePageList(
                    state.request.name,
                    state.items,
                    state.horizontal
                ),
                state.page,
                state.hasNext
            )
        } catch (_: Throwable) {
            null
        }
    }

    private suspend fun expandCategory(state: CategoryState): Any? {
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

        state.items = (state.items + nextList.list)
            .distinctBy { it.url }
        state.page = nextPage
        state.hasNext = response.hasNext

        return createExpandable(state)
    }

    private fun openCategory(state: CategoryState) {
        val activity = currentActivity() ?: return
        val expandable = createExpandable(state) ?: return

        try {
            val homeFragment = Class.forName(
                "com.lagradost.cloudstream3.ui.home.HomeFragment"
            )
            val companion = homeFragment.getField("Companion").get(null)

            val method = companion.javaClass.methods.firstOrNull {
                it.name == "loadHomepageList" &&
                    it.parameterCount == 5
            } ?: return

            val expandCallback: suspend (String) -> Any? = {
                expandCategory(state)
            }
            val dismissCallback: () -> Unit = {
                mainHandler.postDelayed({ installHeaders(8) }, 150L)
            }

            method.invoke(
                companion,
                activity,
                expandable,
                null,
                expandCallback,
                dismissCallback
            )
        } catch (_: Throwable) {
        }
    }
}
