package com.moosa.hub

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.SystemClock
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.lagradost.cloudstream3.CommonActivity.activity
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.SearchResponse
import java.lang.ref.WeakReference
import java.util.WeakHashMap
import java.util.concurrent.ConcurrentHashMap

object MOOSASeeAll {
    private val mainHandler = Handler(Looper.getMainLooper())

    private data class CategoryState(
        val api: MOOSAHubProvider,
        val request: MainPageRequest,
        var parentAdapterPosition: Int = RecyclerView.NO_POSITION,
        var parentTopOffset: Int = 0
    )

    private val states = ConcurrentHashMap<String, CategoryState>()
    private val observedRoots =
        WeakHashMap<ViewGroup, ViewTreeObserver.OnGlobalLayoutListener>()
    private val headerRefs =
        ConcurrentHashMap<String, WeakReference<TextView>>()
    private val boundHeaders = WeakHashMap<TextView, String>()
    private var headerRefreshScheduled = false
    private var lastBrowseOpenAt = 0L

    private fun scheduleHeaderRefresh() {
        // CloudStream dispatches many global-layout events while rows scroll.
        // Coalesce them; never modify TextView text on every layout callback.
        if (headerRefreshScheduled) return
        headerRefreshScheduled = true
        mainHandler.postDelayed({
            headerRefreshScheduled = false
            applyHeaders()
        }, 90L)
    }

    private fun styleHeader(header: TextView, focused: Boolean) {
        val density = header.resources.displayMetrics.density
        header.alpha = if (focused) 1f else 0.94f
        header.setTextColor(if (focused) Color.rgb(16, 16, 18) else Color.WHITE)
        header.background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 6f * density
            setColor(if (focused) Color.rgb(245, 245, 246) else Color.TRANSPARENT)
        }
    }

    fun register(
        api: MOOSAHubProvider,
        request: MainPageRequest,
        items: List<SearchResponse>,
        hasNext: Boolean,
        horizontal: Boolean = false
    ) {
        if (items.isEmpty()) return

        val existing = states[request.name]
        if (existing == null || existing.request.data != request.data) {
            states[request.name] = CategoryState(api = api, request = request)
        }

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
                scheduleHeaderRefresh()
            }
            observedRoots[root] = listener
            root.viewTreeObserver.addOnGlobalLayoutListener(listener)
        }
    }

    private fun collectHeaders(
        view: View,
        output: MutableList<TextView>
    ) {
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
        val root =
            currentActivity.findViewById<ViewGroup>(android.R.id.content)
                ?: return

        val headers = mutableListOf<TextView>()
        collectHeaders(root, headers)

        headers.forEach { header ->
            val original = originalHeaderText(header.text?.toString().orEmpty())
            val state = states[original] ?: return@forEach
            val expectedText = "${state.request.name}  SHOW MORE  ›"

            // Rows are rebound/recycled by CloudStream. Bind only on a real
            // category change, not on every frame/layout event.
            if (boundHeaders[header] == state.request.name &&
                header.text?.toString() == expectedText
            ) {
                return@forEach
            }
            boundHeaders[header] = state.request.name
            if (header.text?.toString() != expectedText) {
                header.text = expectedText
            }
            header.contentDescription = "${state.request.name}. Show more."
            header.isClickable = true
            header.isFocusable = true
            header.isFocusableInTouchMode = false

            header.setOnFocusChangeListener { view, focused ->
                styleHeader(view as TextView, focused)
            }
            styleHeader(header, header.hasFocus())
            headerRefs[state.request.name] = WeakReference(header)

            // Android already calls performClick() for remote DPAD_CENTER
            // and Enter. Do NOT install an ACTION_UP onKey callback too:
            // it opened two dialogs from one physical key press.
            header.setOnKeyListener(null)
            header.setOnClickListener {
                val now = SystemClock.uptimeMillis()
                if (now - lastBrowseOpenAt < 650L ||
                    !header.isShown || !header.isAttachedToWindow ||
                    !currentActivity.hasWindowFocus() ||
                    homeMasterRecycler(currentActivity)?.isShown != true
                ) {
                    return@setOnClickListener
                }
                lastBrowseOpenAt = now
                headerRefs[state.request.name] = WeakReference(header)
                captureParentPosition(currentActivity, header, state)
                MOOSABrowseDialog.show(currentActivity, state.api, state.request)
            }
        }
    }

    private fun homeMasterRecycler(
        activity: android.app.Activity
    ): RecyclerView? {
        val id = activity.resources.getIdentifier(
            "home_master_recycler",
            "id",
            activity.packageName
        )
        if (id == 0) return null
        return activity.findViewById(id)
    }

    private fun directChildOf(
        ancestor: ViewGroup,
        view: View
    ): View? {
        var current: View = view
        var parent = current.parent

        while (parent is View) {
            if (parent === ancestor) {
                return current
            }
            current = parent
            parent = current.parent
        }

        return null
    }

    private fun captureParentPosition(
        activity: android.app.Activity,
        header: TextView,
        state: CategoryState
    ) {
        val master = homeMasterRecycler(activity) ?: return
        val itemView = directChildOf(master, header) ?: return
        val position = master.getChildAdapterPosition(itemView)

        if (position != RecyclerView.NO_POSITION) {
            state.parentAdapterPosition = position
            state.parentTopOffset = itemView.top - master.paddingTop
        }
    }

    private fun focusExactParentRow(
        activity: android.app.Activity,
        state: CategoryState,
        attempt: Int = 0
    ) {
        val master = homeMasterRecycler(activity) ?: return
        val position = state.parentAdapterPosition

        if (position == RecyclerView.NO_POSITION) {
            applyHeaders()
            headerRefs[state.request.name]
                ?.get()
                ?.takeIf { it.isAttachedToWindow }
                ?.requestFocus()
            return
        }

        val manager = master.layoutManager as? LinearLayoutManager
        manager?.scrollToPositionWithOffset(
            position,
            state.parentTopOffset
        )

        master.post {
            applyHeaders()

            val holder = master.findViewHolderForAdapterPosition(position)
            val itemView = holder?.itemView

            val headerId = activity.resources.getIdentifier(
                "home_child_more_info",
                "id",
                activity.packageName
            )

            val exactHeader = if (
                itemView != null &&
                headerId != 0
            ) {
                itemView.findViewById<TextView>(headerId)
            } else {
                null
            }

            if (exactHeader != null && exactHeader.isAttachedToWindow) {
                headerRefs[state.request.name] =
                    WeakReference(exactHeader)
                exactHeader.requestFocus()
            } else if (attempt < 8) {
                mainHandler.postDelayed({
                    focusExactParentRow(
                        activity,
                        state,
                        attempt + 1
                    )
                }, 90L)
            }
        }
    }

    fun restoreFocus(categoryName: String) {
        val currentActivity = activity ?: return
        // Never force master/child RecyclerViews to scroll as an aftereffect
        // of closing SHOW MORE. This previously caused horizontal drift.
        mainHandler.postDelayed({
            val master = homeMasterRecycler(currentActivity) ?: return@postDelayed
            if (!master.isShown || !currentActivity.hasWindowFocus()) {
                return@postDelayed
            }
            val header = headerRefs[categoryName]?.get()
            if (header?.isShown == true && header.isAttachedToWindow) {
                header.requestFocus()
            }
        }, 100L)
    }
}
