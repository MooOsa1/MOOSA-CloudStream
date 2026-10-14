package com.moosa.hub

import android.net.Uri
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorLink
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.CancellationException
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

    fun setAllEnabled(enabled: Boolean) {
        if (enabled) {
            MOOSASettingsStore.setDisabledServers(emptySet())
        } else {
            MOOSASettingsStore.setDisabledServers(
                snapshot().map { it.key }.toSet()
            )
        }
    }

    fun disableOffline() {
        val disabled =
            MOOSASettingsStore.disabledServers().toMutableSet()

        snapshot()
            .filter { it.lastResult == false }
            .forEach { disabled += it.key }

        MOOSASettingsStore.setDisabledServers(disabled)
    }

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

    suspend fun testAll(
        concurrency: Int = 8,
        onProgress: ((done: Int, total: Int) -> Unit)? = null
    ) = coroutineScope {
        val keys = snapshot().map { it.key }
        val total = keys.size
        var done = 0

        for (batch in keys.chunked(concurrency.coerceIn(2, 12))) {
            batch.map { key ->
                async {
                    test(key)
                }
            }.awaitAll()

            done += batch.size
            onProgress?.invoke(done, total)
        }
    }

    private data class ProbeCheck(
        val playable: Boolean,
        val reason: String
    )

    // HEAD 200 and empty Content-Type never prove a playable video.
    private val probeReasons = ConcurrentHashMap<String, String>()

    fun lastProbeReason(link: ExtractorLink): String? =
        probeReasons[linkKey(link)]

    private suspend fun probeOnce(link: ExtractorLink): ProbeCheck {
        if (
            !link.url.startsWith("http://", ignoreCase = true) &&
            !link.url.startsWith("https://", ignoreCase = true)
        ) return ProbeCheck(false, "Invalid URL")

        return withTimeoutOrNull(6500L) {
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
                        if (!response.isSuccessful) {
                            ProbeCheck(false, "HLS HTTP ${response.code}")
                        } else {
                            val body = response.text
                                .trimStart('\uFEFF', '\n', '\r', ' ', '\t')
                            val valid = body.startsWith("#EXTM3U") &&
                                (
                                    body.contains("#EXTINF:") ||
                                        body.contains("#EXT-X-STREAM-INF:") ||
                                        body.contains("#EXT-X-MAP:")
                                    )
                            ProbeCheck(
                                valid,
                                if (valid) "HLS manifest confirmed" else "No valid HLS playlist"
                            )
                        }
                    }

                    link.isDash -> {
                        val response = app.get(
                            link.url,
                            headers = headers,
                            referer = link.referer,
                            verify = false
                        )
                        val valid = response.isSuccessful &&
                            response.text.contains("<MPD")
                        ProbeCheck(
                            valid,
                            if (valid) "DASH manifest confirmed"
                            else "Invalid DASH / HTTP ${response.code}"
                        )
                    }

                    else -> {
                        // A ranged GET must identify video. No HEAD-only approval.
                        val response = app.get(
                            link.url,
                            headers = headers + mapOf("Range" to "bytes=0-2047"),
                            referer = link.referer,
                            verify = false
                        )
                        val contentType = response.headers["Content-Type"]
                            .orEmpty().substringBefore(';').trim().lowercase()
                        val contentRange = response.headers["Content-Range"].orEmpty()
                        val isDownet = runCatching {
                            val host = Uri.parse(link.url).host.orEmpty().lowercase()
                            host == "downet.net" || host.endsWith(".downet.net")
                        }.getOrDefault(false)

                        // HTTP status, MIME and filename do not prove playability.
                        // Inspect the real first 32 binary bytes, not response.text.
                        val prefix = ByteArray(32)
                        val count = response.body.byteStream().use { stream ->
                            stream.read(prefix)
                        }.coerceAtLeast(0)

                        fun asciiAt(offset: Int, value: String): Boolean =
                            count >= offset + value.length &&
                                value.indices.all { i ->
                                    prefix[offset + i].toInt() == value[i].code
                                }

                        val mp4Signature = asciiAt(4, "ftyp") || asciiAt(4, "styp")
                        val ebmlSignature =
                            count >= 4 &&
                                (prefix[0].toInt() and 0xff) == 0x1a &&
                                (prefix[1].toInt() and 0xff) == 0x45 &&
                                (prefix[2].toInt() and 0xff) == 0xdf &&
                                (prefix[3].toInt() and 0xff) == 0xa3
                        val flvSignature = asciiAt(0, "FLV")
                        val aviSignature = asciiAt(0, "RIFF") && asciiAt(8, "AVI ")
                        val tsSignature = count >= 1 &&
                            (prefix[0].toInt() and 0xff) == 0x47
                        val recognizedVideoBytes =
                            mp4Signature || ebmlSignature || flvSignature ||
                                aviSignature || tsSignature
                        val isVideoMime =
                            contentType.startsWith("video/") ||
                                contentType == "application/mp4" ||
                                contentType == "application/x-matroska" ||
                                contentType == "application/octet-stream"
                        val validFirstChunk = response.isSuccessful &&
                            count > 0 && isVideoMime && recognizedVideoBytes

                        // This CDN may return 200 with a valid MP4 header but
                        // refuse byte-range seeking, breaking progressive playback.
                        var seekable = !isDownet
                        var seekReason = ""
                        if (validFirstChunk && isDownet) {
                            if (
                                response.code != 206 ||
                                !contentRange.startsWith("bytes 0-", true)
                            ) {
                                seekReason = "Downet ignored first byte-range request"
                            } else {
                                val seekResponse = app.get(
                                    link.url,
                                    headers = headers + mapOf(
                                        "Range" to "bytes=1048576-1048607"
                                    ),
                                    referer = link.referer,
                                    verify = false
                                )
                                val seekRange =
                                    seekResponse.headers["Content-Range"].orEmpty()
                                val seekByte = seekResponse.body.byteStream().use {
                                    it.read()
                                }
                                seekable = seekResponse.code == 206 &&
                                    seekRange.startsWith("bytes 1048576-", true) &&
                                    seekByte >= 0
                                if (!seekable) {
                                    seekReason =
                                        "Downet seek failed HTTP ${seekResponse.code}; range=$seekRange"
                                }
                            }
                        }

                        val valid = validFirstChunk && seekable
                        val reason = when {
                            !response.isSuccessful -> "Media HTTP ${response.code}"
                            count == 0 -> "Empty media response"
                            !isVideoMime -> "Non-video Content-Type: $contentType"
                            !recognizedVideoBytes ->
                                "Response has no recognized video signature"
                            !seekable -> seekReason
                            valid -> if (isDownet) {
                                "Video signature and two byte ranges confirmed"
                            } else {
                                "Video signature confirmed"
                            }
                            else -> "Media not verified"
                        }

                        ProbeCheck(valid, reason)
                    }
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                ProbeCheck(
                    false,
                    "Request error: ${error.javaClass.simpleName}"
                )
            }
        } ?: ProbeCheck(false, "Media check timed out")
    }

    suspend fun testLink(link: ExtractorLink): Boolean {
        val first = probeOnce(link)
        if (first.playable) {
            probeReasons[linkKey(link)] = first.reason
            return true
        }
        delay(300L)
        val second = probeOnce(link)
        probeReasons[linkKey(link)] = second.reason
        return second.playable
    }
}
