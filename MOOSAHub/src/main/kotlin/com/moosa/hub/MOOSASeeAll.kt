package com.moosa.hub

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.graphics.Color
import android.content.res.ColorStateList
import android.graphics.drawable.Drawable
import android.text.Editable
import android.text.TextWatcher
import android.text.TextUtils
import android.view.animation.DecelerateInterpolator
import android.graphics.drawable.GradientDrawable
import android.os.SystemClock
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.view.KeyEvent
import android.view.Gravity
import android.widget.LinearLayout
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
    private val observedHomeLists =
        WeakHashMap<RecyclerView, RecyclerView.OnScrollListener>()
    private val observedRowAttachments =
        WeakHashMap<RecyclerView, RecyclerView.OnChildAttachStateChangeListener>()
    private val headerRefs =
        ConcurrentHashMap<String, WeakReference<TextView>>()
    // The host adapter rewrites home_child_more_info.text on rebind.
    // Never mutate that text to add SHOW MORE: a separate persistent view
    // survives a recycled item's text reset without flickering.
    private val actionForTitle = WeakHashMap<TextView, WeakReference<TextView>>()
    // The title and selected-provider text can change without an attach/detach
    // event (RecyclerView rebind). Watch the few visible labels, not the layout.
    private val watchedTitles = WeakHashMap<TextView, TextWatcher>()
    private val watchedSelectors = WeakHashMap<TextView, TextWatcher>()
    private data class AdapterWatch(
        val adapter: WeakReference<RecyclerView.Adapter<*>>,
        val observer: RecyclerView.AdapterDataObserver
    )
    private val watchedAdapters = WeakHashMap<RecyclerView, AdapterWatch>()
    private data class OriginalTitle(
        val params: LinearLayout.LayoutParams,
        val clickable: Boolean,
        val focusable: Boolean,
        val background: Drawable?,
        val colors: ColorStateList
    )
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
        }, 240L)
    }

    private fun providerName(host: android.app.Activity): String? {
        // CloudStream's HomeFragment observes homeViewModel.apiName and writes
        // this TextView on every provider switch. It stays in the view tree
        // when the TV navigation header scrolls out of sight.
        for (name in listOf("home_change_api", "home_api_fab")) {
            val id = host.resources.getIdentifier(name, "id", host.packageName)
            if (id == 0) continue
            val selected = host.findViewById<TextView>(id)
                ?.text?.toString()?.trim()
            if (!selected.isNullOrBlank()) return selected
        }
        return null
    }

    private fun isMoosaSelected(host: android.app.Activity): Boolean =
        MOOSAShowMorePolicy.shouldShow(providerName(host), true)

    private fun watchText(view: TextView, selectors: Boolean) {
        val watches = if (selectors) watchedSelectors else watchedTitles
        synchronized(watches) {
            if (watches.containsKey(view)) return
            val watcher = object : TextWatcher {
                override fun beforeTextChanged(
                    s: CharSequence?, start: Int, count: Int, after: Int
                ) = Unit
                override fun onTextChanged(
                    s: CharSequence?, start: Int, before: Int, count: Int
                ) {
                    scheduleHeaderRefresh()
                }
                override fun afterTextChanged(s: Editable?) = Unit
            }
            watches[view] = watcher
            view.addTextChangedListener(watcher)
        }
    }

    private fun watchProvider(host: android.app.Activity) {
        for (name in listOf("home_change_api", "home_api_fab")) {
            val id = host.resources.getIdentifier(name, "id", host.packageName)
            if (id != 0) {
                host.findViewById<TextView>(id)?.let { watchText(it, true) }
            }
        }
    }

    private fun removeAction(header: TextView) {
        val toolbar = header.parent as? LinearLayout ?: return
        val saved = toolbar.tag as? OriginalTitle ?: return
        val parent = toolbar.parent as? LinearLayout ?: return
        val index = parent.indexOfChild(toolbar)
        if (index < 0) return
        val action = actionFor(header)
        val actionHadFocus = action?.hasFocus() == true
        (action?.tag as? ValueAnimator)?.cancel()
        action?.tag = null
        header.setOnFocusChangeListener(null)
        header.setOnClickListener(null)
        header.setOnKeyListener(null)

        toolbar.removeView(header)
        parent.removeView(toolbar)
        parent.addView(header, index, saved.params)
        header.background = saved.background
        header.setTextColor(saved.colors)
        header.isClickable = saved.clickable
        header.isFocusable = saved.focusable
        actionForTitle.remove(header)
        // Never leave native CloudStream's focus link pointed at a removed ID.
        findHorizontalCarousel(parent)?.nextFocusUpId = header.id
        if (actionHadFocus && header.isShown) header.requestFocus()
    }

    private fun watchAdapter(master: RecyclerView) {
        val adapter = master.adapter ?: return
        synchronized(watchedAdapters) {
            val existing = watchedAdapters[master]
            if (existing?.adapter?.get() === adapter) return
            existing?.adapter?.get()?.unregisterAdapterDataObserver(existing.observer)
            val watcher = object : RecyclerView.AdapterDataObserver() {
                override fun onChanged() = scheduleHeaderRefresh()
                override fun onItemRangeChanged(start: Int, count: Int) =
                    scheduleHeaderRefresh()
                override fun onItemRangeInserted(start: Int, count: Int) =
                    scheduleHeaderRefresh()
                override fun onItemRangeRemoved(start: Int, count: Int) =
                    scheduleHeaderRefresh()
                override fun onItemRangeMoved(from: Int, to: Int, count: Int) =
                    scheduleHeaderRefresh()
            }
            adapter.registerAdapterDataObserver(watcher)
            watchedAdapters[master] = AdapterWatch(WeakReference(adapter), watcher)
        }
    }

    private fun styleAction(
        button: TextView,
        focused: Boolean,
        animate: Boolean = true
    ) {
        // Animate only the width of the existing v74 focusable/clickable
        // TextView. The right edge stays anchored in the horizontal toolbar,
        // while text is progressively revealed toward the left.
        // Never animate the original CloudStream title or the carousel.
        val density = button.resources.displayMetrics.density
        val collapsedWidth = (44f * density).toInt()
        val expandedText = "SHOW MORE  ›"
        val expandedWidth = (
            button.paint.measureText(expandedText) +
                button.paddingLeft + button.paddingRight + 4f * density
            ).toInt().coerceAtLeast(collapsedWidth + (24f * density).toInt())
        val targetWidth = if (focused) expandedWidth else collapsedWidth

        // Cancelling a previous focus transition must not let its completion
        // callback overwrite the next transition's text or width.
        val previous = button.tag as? ValueAnimator
        button.tag = null
        previous?.cancel()

        button.background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 6f * density
            setColor(if (focused) Color.rgb(245, 245, 246) else Color.TRANSPARENT)
        }
        button.setTextColor(if (focused) Color.rgb(16, 16, 18) else Color.WHITE)

        val params = button.layoutParams ?: return
        val fromWidth = if (params.width > 0) params.width else collapsedWidth
        if (focused && button.text?.toString() != expandedText) {
            button.text = expandedText
        }
        if (!animate || fromWidth == targetWidth) {
            params.width = targetWidth
            button.layoutParams = params
            button.text = if (focused) expandedText else "›"
            return
        }

        ValueAnimator.ofInt(fromWidth, targetWidth).apply {
            duration = if (focused) 190L else 150L
            interpolator = DecelerateInterpolator()
            addUpdateListener { animator ->
                if (button.tag !== this) return@addUpdateListener
                val width = animator.animatedValue as Int
                if (button.layoutParams.width != width) {
                    button.layoutParams.width = width
                    button.requestLayout()
                }
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    if (button.tag !== animation) return
                    button.tag = null
                    button.layoutParams.width = targetWidth
                    button.requestLayout()
                    if (!focused && !button.hasFocus()) {
                        button.text = "›"
                    }
                }
            })
            button.tag = this
            start()
        }
    }

    private fun actionFor(header: TextView): TextView? =
        actionForTitle[header]?.get()?.takeIf { it.parent === header.parent }

    private fun installAction(header: TextView): TextView? {
        val host = activity ?: return null
        val originalName = header.text?.toString()?.trim().orEmpty()
        // RecyclerView reuses the same header when the user switches sources.
        // First undo any MOOSA wrapper that now belongs to another provider
        // or a different category, rather than trusting its old button.
        if (!MOOSAShowMorePolicy.shouldShow(
                providerName(host), states.containsKey(originalName)
            )
        ) {
            removeAction(header)
            return null
        }
        actionFor(header)?.let { return it }

        val oldParent = header.parent as? LinearLayout ?: return null
        // The host's TV row is a vertical LinearLayout; the action is
        // inserted once per row and never changes the host's bound title.
        if (oldParent.orientation != LinearLayout.VERTICAL) return null

        val titleIndex = oldParent.indexOfChild(header)
        if (titleIndex < 0) return null
        val oldParams = header.layoutParams as? LinearLayout.LayoutParams ?: return null
        val original = OriginalTitle(
            params = LinearLayout.LayoutParams(oldParams),
            clickable = header.isClickable,
            focusable = header.isFocusable,
            background = header.background,
            colors = header.textColors
        )
        val context = header.context
        val density = context.resources.displayMetrics.density
        fun dp(value: Int) = (value * density).toInt()

        val toolbar = LinearLayout(context).apply {
            tag = original
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            clipChildren = false
            clipToPadding = false
        }

        oldParent.removeViewAt(titleIndex)
        oldParent.addView(
            toolbar, titleIndex,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )
        toolbar.addView(
            header,
            LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f
            ).apply {
                // Keep the TV navigation rail inset on the title.
                marginStart = oldParams.marginStart
                topMargin = oldParams.topMargin
                bottomMargin = oldParams.bottomMargin
            }
        )

        // CloudStream hardcodes nextFocusUp to the ORIGINAL title ID.
        // Removing focusability here in v73 made the adjacent action
        // unreachable by the remote. Keep that native focus anchor alive.
        header.setOnFocusChangeListener(null)
        header.setOnClickListener(null)
        header.setOnKeyListener(null)
        header.isClickable = true
        header.isFocusable = true
        header.isFocusableInTouchMode = false
        header.alpha = 1f
        header.background = null
        header.setTextColor(Color.WHITE)

        val action = TextView(context).apply {
            id = View.generateViewId()
            text = "›"
            contentDescription = "Show more in $originalName"
            textSize = 17f
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.START
            minWidth = dp(44)
            minHeight = dp(40)
            setPadding(dp(11), dp(8), dp(11), dp(8))
            isClickable = true
            isFocusable = true
            isFocusableInTouchMode = false
            setOnFocusChangeListener { view, focused ->
                styleAction(view as TextView, focused)
            }
        }
        toolbar.addView(
            action,
            LinearLayout.LayoutParams(
                dp(44),
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { marginEnd = dp(16) }
        )
        actionForTitle[header] = WeakReference(action)
        styleAction(action, false, animate = false)
        wireAction(host, header, action)

        // HomeChildItemAdapter.nextFocusUp also points at the original
        // home_child_more_info ID. Redirect its focus to the actual
        // SHOW MORE button while keeping original title and ID intact.
        header.setOnFocusChangeListener { view, focused ->
            if (focused) {
                view.post {
                    if (view.hasFocus() && view.isAttachedToWindow &&
                        action.isAttachedToWindow && action.isShown
                    ) action.requestFocus()
                }
            }
        }
        // Fallback: even if a TV firmware dispatches OK before the focus
        // handoff, the native title performs exactly the same action.
        header.setOnClickListener { action.performClick() }
        header.setOnKeyListener { _, keyCode, event ->
            if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER ||
                keyCode == KeyEvent.KEYCODE_ENTER ||
                keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER
            ) {
                if (event.action == KeyEvent.ACTION_UP) {
                    action.performClick()
                }
                true
            } else {
                false
            }
        }
        // Explicit link for native TV focus search; covers variants
        // where focusFromTouch bypasses the title's posted redirect.
        findHorizontalCarousel(oldParent)?.nextFocusUpId = action.id
        return action
    }

    private fun wireAction(
        host: android.app.Activity,
        title: TextView,
        button: TextView
    ) {
        button.setOnKeyListener { _, keyCode, event ->
            when (keyCode) {
                KeyEvent.KEYCODE_DPAD_UP -> {
                    if (event.action == KeyEvent.ACTION_DOWN)
                        focusInPreviousVisibleRow(host, title)
                    true
                }
                KeyEvent.KEYCODE_DPAD_DOWN,
                KeyEvent.KEYCODE_DPAD_LEFT -> {
                    if (event.action == KeyEvent.ACTION_DOWN)
                        focusCardDirectlyBelowHeader(host, title)
                    true
                }
                KeyEvent.KEYCODE_DPAD_RIGHT -> true
                else -> false
            }
        }
        button.setOnClickListener {
            val now = SystemClock.uptimeMillis()
            val state = states[title.text?.toString()?.trim().orEmpty()]
                ?: return@setOnClickListener
            if (!isMoosaSelected(host) || state.api.name !=
                MOOSAShowMorePolicy.OWNER_API_NAME
            ) return@setOnClickListener
            if (now - lastBrowseOpenAt < 650L ||
                !button.isAttachedToWindow || !button.isShown ||
                !host.hasWindowFocus() ||
                homeMasterRecycler(host)?.isShown != true
            ) return@setOnClickListener

            lastBrowseOpenAt = now
            headerRefs[state.request.name] = WeakReference(title)
            captureParentPosition(host, title, state)
            MOOSABrowseDialog.show(host, state.api, state.request)
        }
    }

    fun registerCategories(api: MOOSAHubProvider) {
        // CloudStream can reuse cached home rows before asynchronous home()
        // callbacks register each category. Pre-register known MOOSA rows once
        // so Show More cannot disappear while an otherwise valid row renders.
        for (category in api.mainPage) {
            states[category.name] = CategoryState(
                api = api,
                request = MainPageRequest(
                    category.name, category.data, category.horizontalImages
                )
            )
        }
        mainHandler.post {
            installObserver()
            scheduleHeaderRefresh()
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
            // Several home categories register together. Rebuild the
            // action toolbar only once, instead of one full scan per row.
            scheduleHeaderRefresh()
        }
    }

    private fun installObserver() {
        val current = activity ?: return
        watchProvider(current)
        // Never subscribe to EVERY layout frame after the home list exists.
        // Poster loading and the Show More width animation otherwise keep
        // waking the main thread every 240ms for the entire home session.
        homeMasterRecycler(current)?.let { master ->
            installHomeIdleListener(master)
            scheduleHeaderRefresh()
            return
        }
        val root = current.findViewById<ViewGroup>(android.R.id.content)
            ?: return
        synchronized(observedRoots) {
            if (observedRoots.containsKey(root)) return
            // One-shot fallback only while CloudStream creates its home
            // RecyclerView. Unregister immediately when the list exists.
            val listener = object : ViewTreeObserver.OnGlobalLayoutListener {
                override fun onGlobalLayout() {
                    val master = homeMasterRecycler(current) ?: return
                    if (root.viewTreeObserver.isAlive) {
                        root.viewTreeObserver.removeOnGlobalLayoutListener(this)
                    }
                    synchronized(observedRoots) { observedRoots.remove(root) }
                    installHomeIdleListener(master)
                    scheduleHeaderRefresh()
                }
            }
            observedRoots[root] = listener
            root.viewTreeObserver.addOnGlobalLayoutListener(listener)
        }
    }

    private fun installHomeIdleListener(master: RecyclerView) {
        watchAdapter(master)
        synchronized(observedHomeLists) {
            if (!observedHomeLists.containsKey(master)) {
                val listener = object : RecyclerView.OnScrollListener() {
                    override fun onScrollStateChanged(
                        recyclerView: RecyclerView,
                        newState: Int
                    ) {
                        if (newState == RecyclerView.SCROLL_STATE_IDLE) {
                            // Do not rewrite a row while the remote flings it.
                            scheduleHeaderRefresh()
                        }
                    }
                }
                observedHomeLists[master] = listener
                master.addOnScrollListener(listener)
            }
        }
        synchronized(observedRowAttachments) {
            if (!observedRowAttachments.containsKey(master)) {
                val listener = object : RecyclerView.OnChildAttachStateChangeListener {
                    override fun onChildViewAttachedToWindow(view: View) {
                        val recycler = view.parent as? RecyclerView ?: return
                        if (recycler.scrollState == RecyclerView.SCROLL_STATE_IDLE) {
                            // A newly bound home category needs Show More.
                            // No refresh is needed for ordinary poster/layout frames.
                            scheduleHeaderRefresh()
                        }
                    }

                    override fun onChildViewDetachedFromWindow(view: View) = Unit
                }
                observedRowAttachments[master] = listener
                master.addOnChildAttachStateChangeListener(listener)
            }
        }
    }

    private fun collectHeaders(
        master: RecyclerView,
        output: MutableList<TextView>
    ) {
        // Earlier this walked EVERY descendant view, including every poster
        // in every nested horizontal RecyclerView, on each global layout.
        // Only the attached VERTICAL home rows can contain a section title.
        val headerId = master.resources.getIdentifier(
            "home_child_more_info", "id", master.context.packageName
        )
        if (headerId == 0) return
        for (index in 0 until master.childCount) {
            val row = master.getChildAt(index)
            row.findViewById<TextView>(headerId)?.let(output::add)
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

    private fun focusPreviousTarget(
        host: android.app.Activity,
        previous: View
    ): Boolean {
        val headerId = host.resources.getIdentifier(
            "home_child_more_info", "id", host.packageName
        )
        if (headerId != 0) {
            val title = previous.findViewById<TextView>(headerId)
            if (title != null && title.isShown) {
                val button = installAction(title)
                if (button?.isShown == true && button.requestFocus()) return true
            }
        }

        val carousel = findHorizontalCarousel(previous) ?: return false
        val manager = carousel.layoutManager as? LinearLayoutManager ?: return false
        val first = manager.findFirstVisibleItemPosition().coerceAtLeast(0)
        val last = manager.findLastVisibleItemPosition()
        if (last < first) return false
        for (index in first..last) {
            val card = manager.findViewByPosition(index) ?: continue
            if (card.left >= carousel.paddingLeft &&
                card.right <= carousel.width - carousel.paddingRight &&
                requestCardFocus(card)
            ) return true
        }
        return manager.findViewByPosition(first)?.let(::requestCardFocus) ?: false
    }

    private fun focusHomeSelector(host: android.app.Activity): Boolean {
        for (name in listOf("home_change_api", "navigation_home", "nav_rail_view")) {
            val id = host.resources.getIdentifier(name, "id", host.packageName)
            if (id != 0) {
                val control = host.findViewById<View>(id)
                if (control?.isShown == true && control.requestFocus()) return true
            }
        }
        return false
    }

    private fun focusInPreviousVisibleRow(
        host: android.app.Activity,
        title: TextView
    ): Boolean {
        val master = homeMasterRecycler(host) ?: return focusHomeSelector(host)
        val row = directChildOf(master, title) ?: return focusHomeSelector(host)
        val position = master.getChildAdapterPosition(row)
        if (position == RecyclerView.NO_POSITION) return focusHomeSelector(host)
        val target = position - 1

        // The first adapter slot is CloudStream's preview header, not a
        // content row; UP should exit to the provider selector.
        if (target <= 0) return focusHomeSelector(host)

        master.findViewHolderForAdapterPosition(target)
            ?.itemView?.takeIf { it.isShown }
            ?.let { if (focusPreviousTarget(host, it)) return true }

        // RecyclerView recycles the previous row when it moves offscreen.
        // UP should scroll the VERTICAL master list to attach it, then focus
        // its button. Do not ask Android's FocusFinder for a distant card.
        val manager = master.layoutManager as? LinearLayoutManager
            ?: return focusHomeSelector(host)
        manager.scrollToPositionWithOffset(target, 0)

        fun retry(remaining: Int) {
            master.post {
                if (host.isFinishing || host.isDestroyed ||
                    !master.isShown || !host.hasWindowFocus()
                ) return@post
                val item = master.findViewHolderForAdapterPosition(target)?.itemView
                if (item != null && item.isShown &&
                    focusPreviousTarget(host, item)
                ) return@post
                if (remaining > 0) {
                    mainHandler.postDelayed({ retry(remaining - 1) }, 60L)
                } else {
                    focusHomeSelector(host)
                }
            }
        }
        retry(4)
        return true
    }

    private fun applyHeaders() {
        val host = activity ?: return
        val master = homeMasterRecycler(host) ?: return
        if (!master.isShown) return
        watchProvider(host)
        installHomeIdleListener(master)
        val ownsHome = isMoosaSelected(host)
        // Avoid changing wrappers during an ordinary MOOSA carousel fling.
        // On a provider switch, however, remove stale buttons promptly.
        if (ownsHome &&
            master.scrollState != RecyclerView.SCROLL_STATE_IDLE
        ) return
        val titles = mutableListOf<TextView>()
        collectHeaders(master, titles)
        titles.forEach { title ->
            watchText(title, false)
            if (MOOSAShowMorePolicy.shouldShow(
                    providerName(host),
                    states.containsKey(title.text?.toString()?.trim().orEmpty())
                )
            ) {
                installAction(title)
            } else {
                removeAction(title)
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
        val host = activity ?: return
        mainHandler.postDelayed({
            val master = homeMasterRecycler(host) ?: return@postDelayed
            if (!master.isShown || !host.hasWindowFocus()) return@postDelayed
            val title = headerRefs[categoryName]?.get()
            if (title?.isShown == true && title.isAttachedToWindow) {
                actionFor(title)?.requestFocus()
            }
        }, 100L)
    }
}
