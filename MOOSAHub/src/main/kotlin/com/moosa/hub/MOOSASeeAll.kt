package com.moosa.hub

import android.app.Activity
import android.content.res.Configuration
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
    private data class CategoryState(
        val api: MOOSAHubProvider,
        val request: MainPageRequest,
        var page: Int,
        var items: List<SearchResponse>,
        var hasNext: Boolean,
        val horizontal: Boolean
    )

    private val states = ConcurrentHashMap<String, CategoryState>()
    private val mainHandler = Handler(Looper.getMainLooper())

    fun register(
        api: MOOSAHubProvider,
        request: MainPageRequest,
        page: Int,
        response: HomePageResponse
    ) {
        if (page != 1) return

        val list = response.items.firstOrNull() ?: return
        states[list.name] = CategoryState(
            api = api,
            request = request,
            page = 1,
            items = list.list,
            hasNext = response.hasNext,
            horizontal = list.isHorizontalImages
        )

        mainHandler.postDelayed({ installHeaders(10) }, 250)
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

    private fun isTelevision(activity: Activity): Boolean {
        val mode =
            activity.resources.configuration.uiMode and
                Configuration.UI_MODE_TYPE_MASK
        return mode == Configuration.UI_MODE_TYPE_TELEVISION
    }

    private fun collectHeaders(view: View, output: MutableList<TextView>) {
        if (view is TextView && view.id != View.NO_ID) {
            val entry = try {
                view.resources.getResourceEntryName(view.id)
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
        if (activity == null || !isTelevision(activity)) {
            if (activity == null && retries > 0) {
                mainHandler.postDelayed({ installHeaders(retries - 1) }, 200)
            }
            return
        }

        val root = activity.findViewById<ViewGroup>(android.R.id.content)
        if (root == null) {
            if (retries > 0) {
                mainHandler.postDelayed({ installHeaders(retries - 1) }, 200)
            }
            return
        }

        val headers = mutableListOf<TextView>()
        collectHeaders(root, headers)

        var installed = 0
        headers.forEach { header ->
            val currentText = header.text?.toString().orEmpty()
            val categoryName = states.keys.firstOrNull { name ->
                currentText == name ||
                    currentText.startsWith("$name   •") ||
                    currentText.startsWith("$name •")
            } ?: return@forEach

            header.text = "$categoryName   •   Show More ›"
            header.contentDescription = "$categoryName. Show more."
            header.isFocusable = true
            header.isClickable = true
            header.setOnClickListener {
                openCategory(categoryName)
            }
            installed++
        }

        if (installed < states.size && retries > 0) {
            mainHandler.postDelayed({ installHeaders(retries - 1) }, 220)
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

    private suspend fun expandCategory(name: String): Any? {
        val state = states[name] ?: return null
        if (!state.hasNext) return createExpandable(state)

        val nextPage = state.page + 1
        val response = try {
            MOOSACatalog.home(
                api = state.api,
                page = nextPage,
                request = state.request
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

    private fun openCategory(name: String) {
        val activity = currentActivity() ?: return
        val state = states[name] ?: return
        val expandable = createExpandable(state) ?: return

        try {
            val homeFragment = Class.forName(
                "com.lagradost.cloudstream3.ui.home.HomeFragment"
            )
            val companion = homeFragment.getField("Companion").get(null)

            val method = companion.javaClass.methods.firstOrNull {
                it.name == "loadHomepageList" && it.parameterCount == 5
            } ?: return

            val expandCallback: suspend (String) -> Any? = { category ->
                expandCategory(category)
            }
            val dismissCallback: () -> Unit = {
                mainHandler.postDelayed({ installHeaders(3) }, 150)
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
            // The normal infinite row pagination remains available as fallback.
        }
    }
}
