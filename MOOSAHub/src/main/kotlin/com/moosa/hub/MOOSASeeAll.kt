package com.moosa.hub

import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.TextView
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
        val request: MainPageRequest
    )

    private val states = ConcurrentHashMap<String, CategoryState>()
    private val observedRoots =
        WeakHashMap<ViewGroup, ViewTreeObserver.OnGlobalLayoutListener>()
    private val headerRefs =
        ConcurrentHashMap<String, WeakReference<TextView>>()

    fun register(
        api: MOOSAHubProvider,
        request: MainPageRequest,
        items: List<SearchResponse>,
        hasNext: Boolean,
        horizontal: Boolean = false
    ) {
        if (items.isEmpty()) return

        states[request.name] = CategoryState(
            api = api,
            request = request
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
            val original =
                originalHeaderText(header.text?.toString().orEmpty())
            val state = states[original] ?: return@forEach

            header.text = "${state.request.name}  SHOW MORE  ›"
            header.contentDescription =
                "${state.request.name}. Show more."
            header.isClickable = true
            header.isFocusable = true
            header.isFocusableInTouchMode = false
            header.alpha = 0.92f

            header.setOnFocusChangeListener { view, focused ->
                view.alpha = if (focused) 1f else 0.92f
                view.setBackgroundColor(
                    if (focused) {
                        Color.argb(120, 24, 101, 180)
                    } else {
                        Color.TRANSPARENT
                    }
                )
            }

            headerRefs[state.request.name] = WeakReference(header)

            val open = {
                headerRefs[state.request.name] = WeakReference(header)
                MOOSABrowseDialog.show(
                    currentActivity,
                    state.api,
                    state.request
                )
            }

            header.setOnClickListener {
                open()
            }

            header.setOnKeyListener { _, keyCode, event ->
                if (
                    event.action ==
                        android.view.KeyEvent.ACTION_UP &&
                    (
                        keyCode ==
                            android.view.KeyEvent.KEYCODE_DPAD_CENTER ||
                            keyCode ==
                                android.view.KeyEvent.KEYCODE_ENTER
                        )
                ) {
                    open()
                    true
                } else {
                    false
                }
            }
        }
    }

    fun restoreFocus(categoryName: String) {
        mainHandler.postDelayed({
            val header = headerRefs[categoryName]?.get()
            if (header != null && header.isAttachedToWindow) {
                header.requestFocus()
                return@postDelayed
            }

            applyHeaders()

            mainHandler.postDelayed({
                headerRefs[categoryName]
                    ?.get()
                    ?.takeIf { it.isAttachedToWindow }
                    ?.requestFocus()
            }, 120L)
        }, 90L)
    }

}
