package com.moosa.hub

import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorLink
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap

object MOOSAServerRegistry {
    data class Entry(
        val key: String,
        val label: String,
        val link: ExtractorLink,
        val lastSeen: Long,
        val lastResult: Boolean?
    )

    private data class MutableEntry(
        val key: String,
        val label: String,
        val link: ExtractorLink,
        var lastSeen: Long,
        var lastResult: Boolean?
    )

    private val entries = ConcurrentHashMap<String, MutableEntry>()

    private fun displayLabel(link: ExtractorLink): String {
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

    private fun normalize(value: String): String =
        value.lowercase()
            .replace(Regex("""\s+"""), " ")
            .trim()

    fun keyFor(link: ExtractorLink): String =
        normalize(displayLabel(link))

    fun record(link: ExtractorLink) {
        val key = keyFor(link)
        val now = System.currentTimeMillis()
        entries.compute(key) { _, old ->
            if (old == null) {
                MutableEntry(
                    key = key,
                    label = displayLabel(link),
                    link = link,
                    lastSeen = now,
                    lastResult = null
                )
            } else {
                old.copy(
                    link = link,
                    lastSeen = now
                )
            }
        }
    }

    fun isDisabled(link: ExtractorLink): Boolean =
        keyFor(link) in MOOSASettingsStore.disabledServers()

    fun snapshot(): List<Entry> =
        entries.values
            .sortedByDescending { it.lastSeen }
            .map {
                Entry(
                    key = it.key,
                    label = it.label,
                    link = it.link,
                    lastSeen = it.lastSeen,
                    lastResult = it.lastResult
                )
            }

    fun recordResult(link: ExtractorLink, result: Boolean) {
        val key = keyFor(link)
        record(link)
        entries[key]?.lastResult = result
    }

    fun setDisabled(key: String, disabled: Boolean) {
        val set = MOOSASettingsStore.disabledServers().toMutableSet()
        if (disabled) set += key else set -= key
        MOOSASettingsStore.setDisabledServers(set)
    }

    fun isDisabled(key: String): Boolean =
        key in MOOSASettingsStore.disabledServers()

    suspend fun test(key: String): Boolean? {
        val item = entries[key] ?: return null
        val result = testLink(item.link)
        item.lastResult = result
        return result
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
                            response.text.contains("#EXTM3U", ignoreCase = true)
                    }

                    link.isDash -> {
                        val response = app.get(
                            link.url,
                            headers = headers,
                            referer = link.referer,
                            verify = false
                        )
                        response.isSuccessful &&
                            response.text.contains("<MPD", ignoreCase = true)
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
                                head.headers["Content-Type"].orEmpty().lowercase()

                            contentType.isBlank() ||
                                (
                                    !contentType.contains("text/html") &&
                                        !contentType.contains("application/json")
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
                                ranged.headers["Content-Type"].orEmpty().lowercase()

                            ranged.isSuccessful &&
                                !contentType.contains("text/html") &&
                                !contentType.contains("application/json")
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
        kotlinx.coroutines.delay(300L)
        return probeOnce(link)
    }
}
