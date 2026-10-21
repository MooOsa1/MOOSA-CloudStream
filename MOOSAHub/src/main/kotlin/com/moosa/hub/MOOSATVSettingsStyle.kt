package com.moosa.hub

import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.DisplayMetrics
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

/**
 * One TV-native visual language shared by the top-level settings and the
 * Sources/Server Manager screens. Designed around the approved mock:
 * black canvas, gray separators, white DPAD focus, breadcrumb, no artwork.
 *
 * All dimensions are inset inside a centered 80% × 82% TV-safe window.
 */
internal object MOOSATVSettingsStyle {
    val black: Int = Color.rgb(8, 8, 8)
    val panel: Int = Color.rgb(17, 17, 17)
    val white: Int = Color.rgb(242, 242, 242)
    val gray: Int = Color.rgb(158, 158, 158)
    val muted: Int = Color.rgb(116, 116, 116)
    val separator: Int = Color.rgb(48, 48, 48)

    fun dp(context: Context, value: Int): Int =
        (value * context.resources.displayMetrics.density).toInt()

    fun shape(context: Context, color: Int, radius: Int = 6,
              stroke: Int? = null): GradientDrawable = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(context, radius).toFloat()
        stroke?.let { setStroke(dp(context, 1), it) }
    }

    fun text(context: Context, value: String, size: Float,
             color: Int = white, bold: Boolean = false): TextView =
        TextView(context).apply {
            text = value
            textSize = size
            setTextColor(color)
            includeFontPadding = true
            if (bold) setTypeface(typeface, Typeface.BOLD)
        }

    fun focus(view: View, primary: Boolean = false) {
        view.id = if (view.id == View.NO_ID) View.generateViewId() else view.id
        view.isClickable = true
        view.isFocusable = true
        view.isFocusableInTouchMode = false
        view.stateListAnimator = null
        val color = if (primary) white else panel
        val stroke = if (primary) white else separator
        view.background = shape(view.context, color, 5, stroke)
        view.setOnFocusChangeListener { target, selected ->
            target.background = shape(
                target.context,
                if (selected && !primary) Color.rgb(37, 37, 37) else color,
                5,
                if (selected) white else stroke
            )
            if (target is TextView) {
                target.setTextColor(if (primary) Color.BLACK else white)
            }
            // No DPAD focus scaling: scaling clipped the edges on Fire TV.
        }
    }

    fun button(context: Context, title: String, primary: Boolean = false): Button =
        Button(context).apply {
            text = title
            isAllCaps = false
            textSize = 13f
            minWidth = 0
            minimumWidth = 0
            minHeight = 0
            minimumHeight = 0
            setPadding(dp(context, 11), dp(context, 9),
                       dp(context, 11), dp(context, 9))
            setTextColor(if (primary) Color.BLACK else white)
            focus(this, primary)
        }

    fun divider(context: Context): View =
        View(context).apply { setBackgroundColor(separator) }

    data class Shell(
        val root: LinearLayout,
        val content: LinearLayout,
        val breadcrumb: TextView,
        val back: TextView
    ) {
        fun path(value: String) {
            breadcrumb.text = value.uppercase(java.util.Locale.US)
        }
    }

    fun shell(context: Context, path: String, backAction: () -> Unit,
              closeAction: () -> Unit): Shell {
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = shape(context, black, 13, separator)
        }
        val header = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(context, 25), 0, dp(context, 22), 0)
        }
        header.addView(text(context, "MOOSA", 18f, white, true))
        header.addView(text(context, " CloudStream", 17f, gray),
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        header.addView(text(context, "SETTINGS", 10.5f, gray).apply {
            letterSpacing = 0.12f
        })
        val close = text(context, "×", 26f).apply {
            gravity = Gravity.CENTER
            isFocusable = true
            isClickable = true
            id = View.generateViewId()
            background = shape(context, panel, 24)
            setOnClickListener { closeAction() }
            setOnFocusChangeListener { v, focused ->
                (v as TextView).setTextColor(if (focused) Color.BLACK else white)
                v.background = shape(context, if (focused) white else panel, 24)
            }
        }
        header.addView(close, LinearLayout.LayoutParams(
            dp(context, 44), dp(context, 44)
        ).apply { marginStart = dp(context, 16) })
        root.addView(header, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(context, 64)
        ))
        root.addView(divider(context), LinearLayout.LayoutParams(-1, dp(context, 1)))

        val breadcrumbRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(context, 25), 0, dp(context, 22), 0)
        }
        val back = text(context, "‹", 31f, white).apply {
            gravity = Gravity.CENTER_VERTICAL
            isFocusable = true
            isClickable = true
            id = View.generateViewId()
            setOnClickListener { backAction() }
            setOnFocusChangeListener { v, focused ->
                (v as TextView).setTextColor(if (focused) white else gray)
                v.background = if (focused) shape(context, panel, 5, white)
                else shape(context, Color.TRANSPARENT)
            }
        }
        breadcrumbRow.addView(back, LinearLayout.LayoutParams(
            dp(context, 44), dp(context, 45)
        ))
        val breadcrumb = text(context, path.uppercase(java.util.Locale.US),
                              11f, gray).apply {
            letterSpacing = 0.14f
            isFocusable = false
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        breadcrumbRow.addView(breadcrumb, LinearLayout.LayoutParams(
            0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f
        ))
        root.addView(breadcrumbRow, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(context, 49)
        ))
        root.addView(divider(context), LinearLayout.LayoutParams(-1, dp(context, 1)))

        val content = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(context, 30), dp(context, 23),
                       dp(context, 30), dp(context, 23))
            clipToPadding = false
        }
        root.addView(content, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
        ))
        return Shell(root, content, breadcrumb, back)
    }

    fun heading(context: Context, parent: LinearLayout,
                tag: String, title: String, description: String) {
        parent.addView(text(context, tag, 10f, gray).apply {
            letterSpacing = 0.14f
        }, LinearLayout.LayoutParams(-1, -2).apply {
            bottomMargin = dp(context, 8)
        })
        parent.addView(text(context, title, 29f, white, true).apply {
            maxLines = 2
        }, LinearLayout.LayoutParams(-1, -2).apply {
            bottomMargin = dp(context, 7)
        })
        parent.addView(text(context, description, 13f, gray).apply {
            maxLines = 3
        }, LinearLayout.LayoutParams(-1, -2).apply {
            bottomMargin = dp(context, 17)
        })
    }

    fun window(dialog: Dialog?, metrics: DisplayMetrics) {
        dialog?.window?.apply {
            setLayout(
                (metrics.widthPixels * 0.80f).toInt(),
                (metrics.heightPixels * 0.82f).toInt()
            )
            setBackgroundDrawableResource(android.R.color.transparent)
            setDimAmount(0.70f)
            addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        }
    }
}
