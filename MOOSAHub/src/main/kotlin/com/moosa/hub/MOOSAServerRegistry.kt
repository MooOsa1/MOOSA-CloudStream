package com.moosa.hub

import android.net.Uri
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorLink
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap

object MOOSAServerRegistry {
    data class Entry(
        val key: String,
        val label: String,
        val link: ExtractorLink,
        val lastSeen: Long,
        val lastResult: Boolean?,
        val variants: Int
    )

    private class MutableEntry(
        val key: String,
        @Volatile var label: String,
        @Volatile var link: ExtractorLink,
        @Volatile var lastSeen: Long,
        @Volatile var lastResult: Boolean?
    ) {
        val links = Collections.synchronizedList(
            mutableListOf<ExtractorLink>()
        )
    }

    private val entries = ConcurrentHashMap<String, MutableEntry>()

    private fun normalize(value: String): String =
        value.lowercase()
            .replace(Regex("""\s+"""), " ")
            .trim()

    private fun hostOf(link: ExtractorLink): String =
        try {
            Uri.parse(link.url).host.orEmpty()
                .removePrefix("www.")
                .lowercase()
        } catch (_: Throwable) {
            ""
        }

    private fun cleanServerName(value: String): String {
        return value
            .replace(Regex("""\[[^\]]*]"""), " ")
            .replace(Regex("""\([^)]*(?:\d{3,4}p|mix|mb|gb|web[- ]?dl|hdr|hevc)[^)]*\)""", RegexOption.IGNORE_CASE), " ")
            .replace(Regex("""\b(?:2160p|1440p|1080p|720p|480p|4k|uhd|fhd|hdr|hevc)\b""", RegexOption.IGNORE_CASE), " ")
            .replace(Regex("""\s*[•|:/-]+\s*$"""), "")
            .replace(Regex("""\s+"""), " ")
            .trim()
    }

    private fun displayLabel(link: ExtractorLink): String {
        val source = cleanServerName(link.source.trim())
        val name = cleanServerName(link.name.trim())

        return when {
            source.isNotBlank() -> source
            name.isNotBlank() -> name
            hostOf(link).isNotBlank() -> hostOf(link)
            else -> "Unknown server"
        }
    }

    private fun legacyLabel(link: ExtractorLink): String {
        val source = link.source.trim()
        val name = link.name.trim()

        return when {
            source.isNotBlank() && name.isNotBlank() &&
                !source.equals(name, ignoreCase = true) -> "$source • $name"
            name.isNotBlank() -> name
            source.isNotBlank() -> source
            else -> "Unknown server"
        }
    }

    private fun linkKey(link: ExtractorLink): String =
        "${link.url}|${link.referer}|${link.headers.hashCode()}"

    fun keyFor(link: ExtractorLink): String {
        val label = normalize(displayLabel(link))
        val host = hostOf(link)

        return when {
            label.isNotBlank() && host.isNotBlank() -> "$label|$host"
            label.isNotBlank() -> label
            host.isNotBlank() -> host
            else -> normalize(legacyLabel(link))
        }
    }

    private fun legacyKeyFor(link: ExtractorLink): String =
        normalize(legacyLabel(link))

    fun record(link: ExtractorLink) {
        val key = keyFor(link)
        val now = System.currentTimeMillis()

        val item = entries.computeIfAbsent(key) {
            MutableEntry(
                key = key,
                label = displayLabel(link),
                link = link,
                lastSeen = now,
                lastResult = null
            )
        }

        synchronized(item) {
            item.label = displayLabel(link)
            item.link = link
            item.lastSeen = now

            val newKey = linkKey(link)
            item.links.removeAll { linkKey(it) == newKey }
            item.links.add(0, link)

            while (item.links.size > 8) {
                item.links.removeAt(item.links.lastIndex)
            }
        }

        val legacyKey = legacyKeyFor(link)
        if (
            legacyKey != key &&
            legacyKey in MOOSASettingsStore.disabledServers()
        ) {
            val disabled = MOOSASettingsStore.disabledServers().toMutableSet()
            disabled += key
            MOOSASettingsStore.setDisabledServers(disabled)
        }
    }

    fun isDisabled(link: ExtractorLink): Boolean {
        val disabled = MOOSASettingsStore.disabledServers()
        return keyFor(link) in disabled ||
            legacyKeyFor(link) in disabled
    }

    fun snapshot(): List<Entry> =
        entries.values
            .sortedWith(
                compareByDescending<MutableEntry> {
                    it.lastResult == true
                }.thenByDescending {
                    it.lastSeen
                }
            )
            .map {
                Entry(
                    key = it.key,
                    label = it.label,
                    link = it.link,
                    lastSeen = it.lastSeen,
                    lastResult = it.lastResult,
                    variants = synchronized(it) { it.links.size }
                )
            }

    fun recordResult(link: ExtractorLink, result: Boolean) {
        val key = keyFor(link)
        record(link)
        val item = entries[key] ?: return

        synchronized(item) {
            item.lastResult = when {
                result -> true
                item.lastResult == true -> true
                else -> false
            }
        }
    }

    fun setDisabled(key: String, disabled: Boolean) {
        val set = MOOSASettingsStore.disabledServers().toMutableSet()
        if (disabled) {
            set += key
        } else {
            set -= key
        }
        MOOSASettingsStore.setDisabledServers(set)
    }

    fun isDisabled(key: String): Boolean =
        key in MOOSASettingsStore.disabledServers()

    suspend fun test(key: String): Boolean? {
        val item = entries[key] ?: return null

        val candidates = synchronized(item) {
            item.links.toList()
        }.ifEmpty {
            listOf(item.link)
        }.take(5)

        item.lastResult = null

        for (candidate in candidates) {
            if (testLink(candidate)) {
                item.lastResult = true
                item.link = candidate
                return true
            }
        }

        item.lastResult = false
        return false
    }

    private suspend fun probeOnce(link: ExtractorLink): Boolean {
        if (
            !link.url.startsWith("http://", ignoreCase = true) &&
            !link.url.startsWith("https://", ignoreCase = true)
        ) return false

        return withTimeoutOrNull(4500L) {
            try {
                val headers = link.getAllHeaders()

                when {
                    link.isM3u8 -> {
                        val response = app.get(
                            link.url,
                            headers = headers,
                            referer = link.referer,
                            verify = false
                        )

                        response.isSuccessful &&
                            response.text.contains(
                                "#EXTM3U",
                                ignoreCase = true
                            )
                    }

                    link.isDash -> {
                        val response = app.get(
                            link.url,
                            headers = headers,
                            referer = link.referer,
                            verify = false
                        )

                        response.isSuccessful &&
                            response.text.contains(
                                "<MPD",
                                ignoreCase = true
                            )
                    }

                    else -> {
                        val head = app.head(
                            link.url,
                            headers = headers,
                            referer = link.referer,
                            verify = false
                        )

                        if (head.isSuccessful) {
                            val contentType =
                                head.headers["Content-Type"]
                                    .orEmpty()
                                    .lowercase()

                            contentType.isBlank() ||
                                (
                                    !contentType.contains("text/html") &&
                                        !contentType.contains(
                                            "application/json"
                                        )
                                    )
                        } else {
                            val ranged = app.get(
                                link.url,
                                headers = headers + mapOf(
                                    "Range" to "bytes=0-2047"
                                ),
                                referer = link.referer,
                                verify = false
                            )

                            val contentType =
                                ranged.headers["Content-Type"]
                                    .orEmpty()
                                    .lowercase()

                            ranged.isSuccessful &&
                                !contentType.contains("text/html") &&
                                !contentType.contains(
                                    "application/json"
                                )
                        }
                    }
                }
            } catch (_: Throwable) {
                false
            }
        } ?: false
    }

    suspend fun testLink(link: ExtractorLink): Boolean {
        if (probeOnce(link)) return true
        delay(300L)
        return probeOnce(link)
    }
}
