package com.moosa.hub

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.SystemClock
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.view.KeyEvent
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

    private fun findHorizontalCarousel(root: View): RecyclerView? {
        if (root is RecyclerView) {
            val layout = root.layoutManager as? LinearLayoutManager
            if (layout?.orientation == LinearLayoutManager.HORIZONTAL) return root
        }
        if (root is ViewGroup) {
            for (i in 0 until root.childCount) {
                findHorizontalCarousel(root.getChildAt(i))?.let { return it }
            }
        }
        return null
    }

    private fun requestCardFocus(view: View): Boolean {
        if (!view.isShown) return false
        if (view.isFocusable && view.requestFocus()) return true
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                if (requestCardFocus(view.getChildAt(i))) return true
            }
        }
        return false
    }

    private fun focusCardDirectlyBelowHeader(
        host: android.app.Activity,
        header: TextView
    ): Boolean {
        val master = homeMasterRecycler(host) ?: return false
        val parentRow = directChildOf(master, header) ?: return false
        val carousel = findHorizontalCarousel(parentRow) ?: return false
        val manager = carousel.layoutManager as? LinearLayoutManager ?: return false

        // Explicitly target a card already on screen, not Android's
        // nearest-focus guess at the extreme right of the horizontal row.
        val first = manager.findFirstVisibleItemPosition().coerceAtLeast(0)
        val last = manager.findLastVisibleItemPosition()
        if (last < first) return false
        for (position in first..last) {
            val card = manager.findViewByPosition(position) ?: continue
            if (card.width == 0 || card.left < carousel.paddingLeft ||
                card.right > carousel.width - carousel.paddingRight
            ) continue
            if (requestCardFocus(card)) return true
        }
        // The first/last partially clipped cards may be all that is visible.
        // Prefer a currently attached one; never issue horizontal scroll.
        return manager.findViewByPosition(first)?.let(::requestCardFocus) ?: false
    }

    private fun focusInPreviousVisibleRow(
        host: android.app.Activity,
        header: TextView
    ): Boolean {
        val master = homeMasterRecycler(host) ?: return false
        val row = directChildOf(master, header) ?: return false
        val currentIndex = master.getChildAdapterPosition(row)
        if (currentIndex <= 0) return false

        val previous = master.findViewHolderForAdapterPosition(currentIndex - 1)
            ?.itemView ?: return false
        if (!previous.isShown) return false

        // Request the previous category header, which is not inside the
        // horizontal RecyclerView. Never let FocusFinder request an offscreen
        // right-hand poster and scroll its parent sideways.
        val headerId = host.resources.getIdentifier(
            "home_child_more_info", "id", host.packageName
        )
        if (headerId != 0) {
            val previousHeader = previous.findViewById<View>(headerId)
            if (previousHeader?.isShown == true && previousHeader.isFocusable) {
                return previousHeader.requestFocus()
            }
        }

        val carousel = findHorizontalCarousel(previous) ?: return false
        val manager = carousel.layoutManager as? LinearLayoutManager ?: return false
        val first = manager.findFirstVisibleItemPosition().coerceAtLeast(0)
        return manager.findViewByPosition(first)?.let(::requestCardFocus) ?: false
    }

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
            header.setOnKeyListener { _, keyCode, event ->
                if (keyCode !in listOf(
                        KeyEvent.KEYCODE_DPAD_UP,
                        KeyEvent.KEYCODE_DPAD_DOWN,
                        KeyEvent.KEYCODE_DPAD_LEFT,
                        KeyEvent.KEYCODE_DPAD_RIGHT
                    )
                ) {
                    false
                } else if (event.action == KeyEvent.ACTION_UP) {
                    // Consume matching key-up events: the app should not
                    // perform a second default focus search afterwards.
                    true
                } else if (event.action == KeyEvent.ACTION_DOWN) {
                    when (keyCode) {
                        KeyEvent.KEYCODE_DPAD_UP ->
                            focusInPreviousVisibleRow(currentActivity, header)
                        KeyEvent.KEYCODE_DPAD_DOWN,
                        KeyEvent.KEYCODE_DPAD_LEFT ->
                            focusCardDirectlyBelowHeader(currentActivity, header)
                        else -> true // RIGHT on the rightmost header stays put.
                    }
                    // Even if the target isn't loaded, do not delegate
                    // to FocusFinder (which causes horizontal row drift).
                    true
                } else {
                    true
                }
            }
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
