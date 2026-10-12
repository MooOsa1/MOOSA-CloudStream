package com.moosa.hub

import android.app.Activity
import android.os.Handler
import android.os.Looper
import com.lagradost.cloudstream3.HomePageList
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.newMovieSearchResponse
import java.util.concurrent.ConcurrentHashMap

object MOOSASeeAll {
    private const val MARKER = "moosa_showmore="
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

    fun makeButton(
        api: MOOSAHubProvider,
        request: MainPageRequest,
        items: List<SearchResponse>,
        hasNext: Boolean
    ): SearchResponse {
        val token = (
            request.name + "|" + request.data
            ).hashCode().toUInt().toString(16)

        states[token] = CategoryState(
            api = api,
            request = request,
            page = 1,
            items = items,
            hasNext = hasNext,
            horizontal = false
        )

        val fallbackUrl = items.firstOrNull()?.url
            ?: "https://www.themoviedb.org/movie/2"

        val separator = if ("?" in fallbackUrl) "&" else "?"
        return api.newMovieSearchResponse(
            name = "Show More ›",
            url = "${fallbackUrl}${separator}${MARKER}${token}",
            type = TvType.Movie,
            fix = false
        ) {
            this.year = null
            this.posterUrl = null
        }
    }

    fun isShowMoreUrl(url: String): Boolean =
        MARKER in url

    fun cleanUrl(url: String): String =
        url.replace(Regex("""[?&]moosa_showmore=[^&]+"""), "")
            .replace("?&", "?")
            .trimEnd('?', '&')

    fun open(url: String): Boolean {
        val token = Regex("""(?:\\?|&)moosa_showmore=([^&]+)""")
            .find(url)
            ?.groupValues
            ?.getOrNull(1)
            ?: return false
        val state = states[token] ?: return false

        mainHandler.postDelayed({
            openCategory(state)
        }, 180L)
        return true
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
            .filterNot { isShowMoreUrl(it.url) }
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
            val dismissCallback: () -> Unit = {}

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
