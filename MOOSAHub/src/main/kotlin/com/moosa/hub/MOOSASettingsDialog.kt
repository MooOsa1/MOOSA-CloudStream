package com.moosa.hub

import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.DialogFragment

class MOOSASettingsDialog : DialogFragment() {
    private val cards = mutableListOf<View>()

    private val bg = Color.rgb(10, 20, 31)
    private val panel = Color.rgb(22, 34, 48)
    private val panelFocus = Color.rgb(29, 71, 108)
    private val accent = Color.rgb(64, 151, 220)
    private val primary = Color.WHITE
    private val secondary = Color.rgb(174, 191, 207)

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

    private fun text(
        value: String,
        size: Float,
        color: Int = primary,
        bold: Boolean = false
    ) = TextView(requireContext()).apply {
        text = value
        textSize = size
        setTextColor(color)
        if (bold) {
            setTypeface(typeface, Typeface.BOLD)
        }
    }

    private fun card(
        icon: String,
        title: String,
        subtitle: String,
        action: () -> Unit
    ): View {
        val context = requireContext()

        return LinearLayout(context).apply {
            id = View.generateViewId()
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(9), dp(12), dp(9))
            isFocusable = true
            isFocusableInTouchMode = false
            isClickable = true
            background = rounded(
                panel,
                12,
                Color.argb(65, 255, 255, 255)
            )

            val iconView = TextView(context).apply {
                text = icon
                textSize = 18f
                gravity = Gravity.CENTER
                setTextColor(primary)
                setTypeface(typeface, Typeface.BOLD)
                background = rounded(
                    Color.rgb(31, 88, 132),
                    10
                )
            }
            addView(
                iconView,
                LinearLayout.LayoutParams(
                    dp(40),
                    dp(40)
                ).apply {
                    marginEnd = dp(10)
                }
            )

            val holder = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_VERTICAL
            }

            holder.addView(
                text(
                    title,
                    15.5f,
                    primary,
                    true
                )
            )
            holder.addView(
                text(
                    subtitle,
                    11.5f,
                    secondary
                ).apply {
                    maxLines = 1
                    ellipsize =
                        android.text.TextUtils.TruncateAt.END
                    setPadding(0, dp(2), 0, 0)
                }
            )

            addView(
                holder,
                LinearLayout.LayoutParams(
                    0,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    1f
                )
            )

            val arrow = text(
                "›",
                24f,
                Color.rgb(155, 191, 220),
                true
            )
            addView(
                arrow,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            )

            setOnClickListener {
                action()
            }

            setOnFocusChangeListener { view, focused ->
                view.scaleX = if (focused) 1.015f else 1f
                view.scaleY = if (focused) 1.015f else 1f
                view.background = rounded(
                    if (focused) panelFocus else panel,
                    12,
                    if (focused) accent
                    else Color.argb(65, 255, 255, 255),
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

        val height = dp(66)

        row.addView(
            left,
            LinearLayout.LayoutParams(
                0,
                height,
                1f
            ).apply {
                marginEnd = dp(5)
                bottomMargin = dp(7)
            }
        )

        row.addView(
            right,
            LinearLayout.LayoutParams(
                0,
                height,
                1f
            ).apply {
                marginStart = dp(5)
                bottomMargin = dp(7)
            }
        )

        container.addView(row)
    }

    override fun onCreateDialog(
        savedInstanceState: Bundle?
    ): Dialog {
        val context = requireContext()

        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(
                dp(17),
                dp(14),
                dp(17),
                dp(15)
            )
            background = rounded(
                bg,
                18,
                Color.rgb(56, 102, 137),
                1
            )
        }

        root.addView(
            text(
                "MOOSA HUB",
                11.5f,
                accent,
                true
            )
        )

        root.addView(
            text(
                "Settings",
                24f,
                primary,
                true
            ).apply {
                setPadding(
                    0,
                    dp(1),
                    0,
                    dp(11)
                )
            }
        )

        val sources = card(
            "S",
            "Sources",
            sourceSummary()
        ) {
            MOOSASourceTesterDialog.show(
                requireContext()
            )
        }

        val servers = card(
            "◉",
            "Servers",
            serverSummary()
        ) {
            MOOSAServerManagerDialog.show(
                requireContext()
            )
        }

        val performance = card(
            "⚡",
            "Performance",
            "${MOOSASettingsStore.providerConcurrency()} simultaneous providers"
        ) {
            openPerformance()
        }

        val playback = card(
            "▶",
            "Playback",
            playbackSummary()
        ) {
            openPlaybackOptions()
        }

        val subtitles = card(
            "CC",
            "Subtitles",
            subtitleSummary()
        ) {
            openSubtitleSources()
        }

        val webBridge = card(
            "W",
            "Web Bridge",
            MOOSACloudStreamBridge.status
        ) {
            openWebBridge()
        }

        val close = card(
            "×",
            "Close",
            "Return to CloudStream"
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
        root.addView(webBridge, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(66)
        ))
        wireFocus()
        close.nextFocusDownId = webBridge.id
        webBridge.nextFocusUpId = close.id

        android.os.Handler(
            android.os.Looper.getMainLooper()
        ).post {
            sources.requestFocus()
        }

        return Dialog(context).apply {
            requestWindowFeature(
                Window.FEATURE_NO_TITLE
            )
            setContentView(root)
            setCanceledOnTouchOutside(true)
        }
    }

    override fun onStart() {
        super.onStart()

        val metrics = resources.displayMetrics
        dialog?.window?.apply {
            setLayout(
                (metrics.widthPixels * 0.68f).toInt(),
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            setBackgroundDrawableResource(
                android.R.color.transparent
            )
        }
    }

    private fun sourceSummary(): String {
        val all = MOOSASourceTester.sources()
        val enabled = all.count {
            MOOSASourceTester.isEnabled(it)
        }
        return "$enabled / ${all.size} enabled"
    }

    private fun serverSummary(): String {
        val all = MOOSAServerRegistry.snapshot()
        if (all.isEmpty()) {
            return "No scan yet"
        }

        val online = all.count {
            it.lastResult == true
        }
        return "${all.size} servers • $online online"
    }

    private fun playbackSummary(): String {
        val verify =
            if (MOOSASettingsStore.verifyLinks()) {
                "Verify on"
            } else {
                "Verify off"
            }

        val arabic =
            if (MOOSASettingsStore.arabicFastMode()) {
                "Arabic fast"
            } else {
                "Arabic normal"
            }

        return "$verify • $arabic"
    }

    private fun subtitleSummary(): String {
        val wyzie =
            if (MOOSASettingsStore.wyzieKey() != null) {
                "Wyzie on"
            } else {
                "Wyzie off"
            }

        val subDl =
            if (MOOSASettingsStore.subdlKey() != null) {
                "SubDL on"
            } else {
                "SubDL off"
            }

        return "OpenSubs on • $wyzie • $subDl"
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


    private fun openWebBridge() {
        val input = EditText(requireContext()).apply {
            hint = "Paste 14-character pairing code"
            setSingleLine(true)
            inputType = android.text.InputType.TYPE_CLASS_TEXT or
                android.text.InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS
            setPadding(dp(16), dp(12), dp(16), dp(12))
        }
        val container = LinearLayout(requireContext()).apply {
            setPadding(dp(15), dp(8), dp(15), dp(8))
            addView(input)
        }
        AlertDialog.Builder(requireContext())
            .setTitle("MOOSA Web Bridge")
            .setMessage("Open moosa-web.onrender.com and select Connect CloudStream. " +
                "Enter the pairing code shown there. " +
                "Only enabled installed providers are used, and only when you press Play. " +
                "Current status: " + MOOSACloudStreamBridge.status)
            .setView(container)
            .setPositiveButton("Connect") { _, _ ->
                MOOSACloudStreamBridge.connect(requireContext(), input.text.toString())
            }
            .setNeutralButton("Disconnect") { _, _ ->
                MOOSACloudStreamBridge.disconnect(requireContext())
                Toast.makeText(requireContext(), "Bridge disconnected", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun openPerformance() {
        val values =
            intArrayOf(4, 6, 8, 10, 12, 16)

        val labels = values
            .map {
                "$it simultaneous providers"
            }
            .toTypedArray()

        val current =
            MOOSASettingsStore.providerConcurrency()

        val selected =
            values.indexOf(current)
                .takeIf { it >= 0 }
                ?: 3

        AlertDialog.Builder(requireContext())
            .setTitle("Performance")
            .setSingleChoiceItems(
                labels,
                selected
            ) { dialog, which ->
                MOOSASettingsStore
                    .setProviderConcurrency(
                        values[which]
                    )

                Toast.makeText(
                    requireContext(),
                    "Saved: ${values[which]} providers",
                    Toast.LENGTH_SHORT
                ).show()

                dialog.dismiss()
            }
            .setNegativeButton(
                "Cancel",
                null
            )
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
            .setPositiveButton(
                "Save"
            ) { _, _ ->
                MOOSASettingsStore
                    .setVerifyLinks(
                        checked[0]
                    )

                MOOSASettingsStore
                    .setArabicFastMode(
                        checked[1]
                    )

                Toast.makeText(
                    requireContext(),
                    "Playback settings saved",
                    Toast.LENGTH_SHORT
                ).show()
            }
            .setNegativeButton(
                "Cancel",
                null
            )
            .show()
    }

    private fun openSubtitleSources() {
        val entries = arrayOf(
            "Wyzie Key",
            "SubDL Key",
            "Find more Arabic subtitles (on demand)",
            "Subtitle synchronization tips",
            "Arabic subtitle priority",
            "Subtitle diagnostics (last playback)"
        )

        AlertDialog.Builder(requireContext())
            .setTitle("Subtitles")
            .setItems(entries) { _, which ->
                when (which) {
                    0 -> editKey(
                        title = "Wyzie Key",
                        current =
                            MOOSASettingsStore.wyzieKey(),
                        onSave =
                            MOOSASettingsStore::setWyzieKey
                    )

                    1 -> editKey(
                        title = "SubDL Key",
                        current =
                            MOOSASettingsStore.subdlKey(),
                        onSave =
                            MOOSASettingsStore::setSubdlKey
                    )

                    2 -> {
                        AlertDialog.Builder(requireContext())
                            .setTitle("More subtitles without slower servers")
                            .setMessage(
                                "In the CloudStream video player, open Subtitles, " +
                                "choose Load from Internet, and select SubSource " +
                                "(or SubDL). Search in Arabic and select a release " +
                                "matching the video, such as WEB-DL or BluRay. " +
                                "SubSource delivers ZIP archives that CloudStream " +
                                "unpacks on demand. MOOSA Hub deliberately does NOT " +
                                "download these archives during server discovery."
                            )
                            .setPositiveButton("OK", null)
                            .show()
                    }

                    3 -> {
                        AlertDialog.Builder(requireContext())
                            .setTitle("Keep subtitles in sync")
                            .setMessage(
                                "Match the subtitle release (WEB-DL, WEBRip, BluRay) " +
                                "to the selected video. FPS hints can help, but do " +
                                "not guarantee sync. For a fixed offset, adjust " +
                                "Subtitle delay in the CloudStream player. If the " +
                                "gap grows throughout the movie, select a different " +
                                "release instead of adding more delay."
                            )
                            .setPositiveButton("OK", null)
                            .show()
                    }

                    4 -> {
                        AlertDialog.Builder(requireContext())
                            .setTitle("Arabic subtitle priority")
                            .setMessage(
                                "Arabic and English subtitles are verified and sent " +
                                "individually as they become available. Arabic is " +
                                "prioritized in selection, but network arrival order " +
                                "can vary. Only two languages are emitted by MOOSA Hub."
                            )
                            .setPositiveButton("OK", null)
                            .show()
                    }

                    5 -> {
                        AlertDialog.Builder(requireContext())
                            .setTitle("Last subtitle search (no additional requests)")
                            .setMessage(MOOSASubtitleHealth.report())
                            .setPositiveButton("OK", null)
                            .show()
                    }
                }
            }
            .setNegativeButton(
                "Close",
                null
            )
            .show()
    }

    private fun editKey(
        title: String,
        current: String?,
        onSave: (String?) -> Unit
    ) {
        val input = EditText(
            requireContext()
        ).apply {
            hint = title
            setSingleLine(true)
            setText(current.orEmpty())
        }

        AlertDialog.Builder(requireContext())
            .setTitle(title)
            .setView(input)
            .setPositiveButton(
                "Save"
            ) { _, _ ->
                onSave(
                    input.text?.toString()
                )

                Toast.makeText(
                    requireContext(),
                    "$title saved",
                    Toast.LENGTH_SHORT
                ).show()
            }
            .setNeutralButton(
                "Clear"
            ) { _, _ ->
                onSave(null)

                Toast.makeText(
                    requireContext(),
                    "$title cleared",
                    Toast.LENGTH_SHORT
                ).show()
            }
            .setNegativeButton(
                "Cancel",
                null
            )
            .show()
    }

    companion object {
        fun show(context: Context) {
            val activity =
                context as? AppCompatActivity
                    ?: return

            if (
                activity.isFinishing ||
                activity.isDestroyed
            ) {
                return
            }

            MOOSASettingsDialog().show(
                activity.supportFragmentManager,
                "moosa_hub_settings"
            )
        }
    }
}
