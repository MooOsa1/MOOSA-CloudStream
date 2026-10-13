package com.moosa.hub

import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.DialogFragment

class MOOSASettingsDialog : DialogFragment() {
    private val cards = mutableListOf<View>()

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

    private fun label(
        value: String,
        size: Float,
        color: Int = Color.WHITE,
        bold: Boolean = false
    ) = TextView(requireContext()).apply {
        text = value
        textSize = size
        setTextColor(color)
        if (bold) setTypeface(typeface, android.graphics.Typeface.BOLD)
    }

    private fun card(
        icon: String,
        title: String,
        description: String,
        baseColor: Int,
        action: () -> Unit
    ): View {
        val context = requireContext()

        return LinearLayout(context).apply {
            id = View.generateViewId()
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(13), dp(14), dp(13))
            isFocusable = true
            isFocusableInTouchMode = false
            isClickable = true
            background = rounded(
                baseColor,
                14,
                Color.argb(80, 255, 255, 255)
            )

            val iconView = TextView(context).apply {
                text = icon
                textSize = 25f
                gravity = Gravity.CENTER
                setTextColor(Color.WHITE)
                background = rounded(
                    Color.argb(115, 255, 255, 255),
                    999
                )
            }
            addView(
                iconView,
                LinearLayout.LayoutParams(dp(54), dp(54)).apply {
                    marginEnd = dp(14)
                }
            )

            val textHolder = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
            }
            textHolder.addView(
                label(title, 18f, Color.WHITE, true)
            )
            textHolder.addView(
                label(
                    description,
                    12.5f,
                    Color.rgb(210, 225, 240)
                ).apply {
                    setPadding(0, dp(3), 0, 0)
                    maxLines = 2
                }
            )
            addView(
                textHolder,
                LinearLayout.LayoutParams(
                    0,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    1f
                )
            )

            setOnClickListener { action() }

            setOnFocusChangeListener { view, focused ->
                view.scaleX = if (focused) 1.02f else 1f
                view.scaleY = if (focused) 1.02f else 1f
                view.background = rounded(
                    if (focused) Color.rgb(42, 117, 190)
                    else baseColor,
                    14,
                    if (focused) Color.WHITE
                    else Color.argb(80, 255, 255, 255),
                    if (focused) 2 else 1
                )
            }
        }
    }

    private fun addGridRow(
        container: LinearLayout,
        left: View,
        right: View
    ) {
        val row = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
        }

        row.addView(
            left,
            LinearLayout.LayoutParams(
                0,
                dp(92),
                1f
            ).apply {
                marginEnd = dp(6)
                bottomMargin = dp(8)
            }
        )
        row.addView(
            right,
            LinearLayout.LayoutParams(
                0,
                dp(92),
                1f
            ).apply {
                marginStart = dp(6)
                bottomMargin = dp(8)
            }
        )
        container.addView(row)
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val context = requireContext()

        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(18), dp(22), dp(20))
            background = rounded(
                Color.rgb(12, 36, 62),
                20,
                Color.rgb(88, 165, 225),
                2
            )
        }

        root.addView(
            label("MOOSA HUB", 13f, Color.rgb(145, 215, 255), true)
        )
        root.addView(
            label("Settings", 30f, Color.WHITE, true).apply {
                setPadding(0, dp(2), 0, dp(14))
            }
        )

        val sources = card(
            "☰",
            "Sources",
            "Enable and accurately test providers",
            Color.rgb(22, 85, 125)
        ) {
            MOOSASourceTesterDialog.show(requireContext())
        }

        val servers = card(
            "◉",
            "Servers",
            "Discover and test final stream servers",
            Color.rgb(40, 72, 128)
        ) {
            MOOSAServerManagerDialog.show(requireContext())
        }

        val performance = card(
            "◴",
            "Performance",
            "Set provider concurrency",
            Color.rgb(74, 69, 125)
        ) {
            openPerformance()
        }

        val playback = card(
            "▶",
            "Playback",
            "Verification and Arabic fast mode",
            Color.rgb(31, 103, 97)
        ) {
            openPlaybackOptions()
        }

        val subtitles = card(
            "ع",
            "Subtitles",
            "Wyzie and SubDL integrations",
            Color.rgb(102, 72, 108)
        ) {
            openSubtitleSources()
        }

        val close = card(
            "×",
            "Close",
            "Apply settings and return",
            Color.rgb(75, 75, 82)
        ) {
            dismiss()
        }

        cards += listOf(
            sources,
            servers,
            performance,
            playback,
            subtitles,
            close
        )

        addGridRow(root, sources, servers)
        addGridRow(root, performance, playback)
        addGridRow(root, subtitles, close)

        wireFocus()

        android.os.Handler(android.os.Looper.getMainLooper()).post {
            sources.requestFocus()
        }

        return Dialog(context).apply {
            requestWindowFeature(Window.FEATURE_NO_TITLE)
            setContentView(root)
            setCanceledOnTouchOutside(true)
        }
    }

    override fun onStart() {
        super.onStart()
        val metrics = resources.displayMetrics
        dialog?.window?.apply {
            setLayout(
                (metrics.widthPixels * 0.88f).toInt(),
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            setBackgroundDrawableResource(android.R.color.transparent)
        }
    }

    private fun wireFocus() {
        if (cards.size < 6) return

        val a = cards[0]
        val b = cards[1]
        val c = cards[2]
        val d = cards[3]
        val e = cards[4]
        val f = cards[5]

        a.nextFocusRightId = b.id
        a.nextFocusDownId = c.id

        b.nextFocusLeftId = a.id
        b.nextFocusDownId = d.id

        c.nextFocusUpId = a.id
        c.nextFocusRightId = d.id
        c.nextFocusDownId = e.id

        d.nextFocusUpId = b.id
        d.nextFocusLeftId = c.id
        d.nextFocusDownId = f.id

        e.nextFocusUpId = c.id
        e.nextFocusRightId = f.id

        f.nextFocusUpId = d.id
        f.nextFocusLeftId = e.id
    }

    private fun openPerformance() {
        val values = intArrayOf(4, 6, 8, 10, 12, 16)
        val labels = values
            .map { "$it simultaneous providers" }
            .toTypedArray()

        val current = MOOSASettingsStore.providerConcurrency()
        val selected = values.indexOf(current)
            .takeIf { it >= 0 }
            ?: 3

        AlertDialog.Builder(requireContext())
            .setTitle("Performance")
            .setSingleChoiceItems(
                labels,
                selected
            ) { dialog, which ->
                MOOSASettingsStore.setProviderConcurrency(values[which])
                dialog.dismiss()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun openPlaybackOptions() {
        val labels = arrayOf(
            "Verify stream links before showing them",
            "Fast Arabic subtitle mode"
        )
        val checked = booleanArrayOf(
            MOOSASettingsStore.verifyLinks(),
            MOOSASettingsStore.arabicFastMode()
        )

        AlertDialog.Builder(requireContext())
            .setTitle("Playback")
            .setMultiChoiceItems(
                labels,
                checked
            ) { _, which, enabled ->
                checked[which] = enabled
            }
            .setPositiveButton("Save") { _, _ ->
                MOOSASettingsStore.setVerifyLinks(checked[0])
                MOOSASettingsStore.setArabicFastMode(checked[1])
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun openSubtitleSources() {
        val entries = arrayOf(
            "Wyzie Key",
            "SubDL Key",
            "Arabic subtitle priority"
        )

        AlertDialog.Builder(requireContext())
            .setTitle("Subtitles")
            .setItems(entries) { _, which ->
                when (which) {
                    0 -> editKey(
                        title = "Wyzie Key",
                        current = MOOSASettingsStore.wyzieKey(),
                        onSave = MOOSASettingsStore::setWyzieKey
                    )
                    1 -> editKey(
                        title = "SubDL Key",
                        current = MOOSASettingsStore.subdlKey(),
                        onSave = MOOSASettingsStore::setSubdlKey
                    )
                    2 -> {
                        AlertDialog.Builder(requireContext())
                            .setTitle("Arabic subtitle priority")
                            .setMessage(
                                "Arabic subtitles are requested first and emitted before English whenever available."
                            )
                            .setPositiveButton("OK", null)
                            .show()
                    }
                }
            }
            .setNegativeButton("Close", null)
            .show()
    }

    private fun editKey(
        title: String,
        current: String?,
        onSave: (String?) -> Unit
    ) {
        val input = EditText(requireContext()).apply {
            hint = title
            setSingleLine(true)
            setText(current.orEmpty())
        }

        AlertDialog.Builder(requireContext())
            .setTitle(title)
            .setView(input)
            .setPositiveButton("Save") { _, _ ->
                onSave(input.text?.toString())
            }
            .setNeutralButton("Clear") { _, _ ->
                onSave(null)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    companion object {
        fun show(context: Context) {
            val activity = context as? AppCompatActivity ?: return
            if (activity.isFinishing || activity.isDestroyed) return

            MOOSASettingsDialog().show(
                activity.supportFragmentManager,
                "moosa_hub_settings"
            )
        }
    }
}
