package com.moosa.hub

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class MOOSAReyaPolicyTest {
    private val masterUrl = "https://noisy-king-5904.streamed-7.workers.dev/master.m3u8"

    @Test fun apiRequiresExactTmdbAndTvEpisode() {
        assertEquals(
            "https://vidnst.reyamika.eu.cc/api/stream?tmdbId=60059&type=tv&season=2&episode=3",
            MOOSAReyaPolicy.apiUrl(60059, true, 2, 3)
        )
        assertNull(MOOSAReyaPolicy.apiUrl(null, true, 2, 3))
        assertNull(MOOSAReyaPolicy.apiUrl(60059, true, 2, null))
        val response = JSONObject(
            """{"tmdbId":60059,"mediaType":"tv","season":2,"episode":3,"streams":[]}"""
        )
        assertTrue(MOOSAReyaPolicy.identity(response, 60059, true, 2, 3))
        assertFalse(MOOSAReyaPolicy.identity(response, 60059, true, 2, 2))
        assertFalse(MOOSAReyaPolicy.identity(response, 60059, false, null, null))
        assertFalse(MOOSAReyaPolicy.identity(response, 60058, true, 2, 3))
    }

    @Test fun manifestHasRealHdVariantsAndRejectsAudioOnly() {
        val master = """#EXTM3U
#EXT-X-STREAM-INF:BANDWIDTH=10000000,RESOLUTION=3840x2160
2160p/index.m3u8
#EXT-X-STREAM-INF:RESOLUTION=1920x1080,CODECS="avc1.640028,mp4a.40.2"
1080p/index.m3u8
#EXT-X-STREAM-INF:RESOLUTION=3840x2160,CODECS="mp4a.40.2"
audio-only/index.m3u8
#EXT-X-STREAM-INF:RESOLUTION=3840x2160,CODECS="av01.0.08M.08,mp4a.40.2"
av1/index.m3u8
#EXT-X-STREAM-INF:RESOLUTION=854x480
sd/index.m3u8
"""
        val choices = MOOSAReyaPolicy.variants(masterUrl, master)
        assertEquals(listOf(2160, 1080), choices.map { it.height })
        assertTrue(choices[0].url.endsWith("/2160p/index.m3u8"))
        assertNull(MOOSAReyaPolicy.safeMedia("http://127.0.0.1/video.m3u8"))
        assertNull(MOOSAReyaPolicy.safeMedia("https://example.com/file.m3u8"))
        assertNull(MOOSAReyaPolicy.safeMedia("//evil.com/a", masterUrl))
    }

    @Test fun slow4kAnd1080ChecksHaveIndependentFallbackLanes() {
        val variants = listOf(
            MOOSAReyaPolicy.Variant("https://example.test/4ka", 3840, 2160),
            MOOSAReyaPolicy.Variant("https://example.test/4kb", 3840, 2160),
            MOOSAReyaPolicy.Variant("https://example.test/hd", 1920, 1080),
            MOOSAReyaPolicy.Variant("https://example.test/720", 1280, 720)
        )
        assertEquals(listOf(listOf(2160, 2160), listOf(1080, 720)),
            MOOSAReyaPolicy.verificationLanes(variants).map { lane ->
                lane.map { it.height }
            })
        assertEquals(1, MOOSAReyaPolicy.verificationLanes(variants.take(2)).size)
    }

    @Test fun nativeVttForExactEpisodeAndLanguagesOnly() {
        val payload = JSONObject("""{
            "subtitles": [
                {"source":"vidnest","label":"Arabic","url":"https://cache.vdrk.site/subtitles/60059/2/5/Arabic.vtt"},
                {"source":"vidnest","label":"English","url":"https://cache.vdrk.site/subtitles/60059/2/5/English.vtt"},
                {"source":"vidnest","label":"Chinese","url":"https://cache.vdrk.site/subtitles/60059/2/5/Chinese.vtt"},
                {"source":"vidnest","label":"Arabic","url":"https://evil.example/subtitles/60059/2/5/Arabic.vtt"},
                {"source":"vidnest","label":"Arabic","url":"https://cache.vdrk.site/subtitles/60059/2/3/Arabic.vtt"}
            ]
        }""")
        val matches = MOOSAReyaPolicy.subtitles(payload, true, 2, 5)
        assertEquals(listOf("Arabic", "English"), matches.map { it.language })
        assertTrue(matches.all { "/2/5/" in it.url })
        assertFalse(matches.any { "evil.example" in it.url })
        assertEquals(emptyList<MOOSAReyaPolicy.Subtitle>(),
            MOOSAReyaPolicy.subtitles(payload, true, 2, 9))
    }

    @Test fun rejectsUntrustedOrCompressedNativeCaptions() {
        val data = JSONObject("""{"subtitles":[
            {"label":"Arabic","url":"http://cache.vdrk.site/subtitles/2/5/Arabic.vtt"},
            {"label":"English","url":"https://cache.vdrk.site/subtitles/2/5/English.zip"},
            {"label":"Arabic","url":"https://cache.vdrk.site/subtitles/2/5/../6/Arabic.vtt"},
            {"label":"Arabic","url":"https://cache.vdrk.site/subtitles/2/5/Arabic.vtt#spoof"}
        ]}""")
        assertTrue(MOOSAReyaPolicy.subtitles(data, true, 2, 5).isEmpty())
    }

    @Test fun reject35MinuteIncompleteEpisodeWhenCaptionsRun45Minutes() {
        val lastCue = 2705.995
        assertTrue(MOOSAReyaPolicy.runtimeCompatible(2756.0, lastCue))
        assertTrue(MOOSAReyaPolicy.runtimeCompatible(2765.24, lastCue))
        assertFalse(MOOSAReyaPolicy.runtimeCompatible(2136.134, lastCue))
        assertFalse(MOOSAReyaPolicy.runtimeCompatible(2184.0, lastCue))
        // No playlist ENDLIST != proven bad video. Preserve playable 4K.
        assertTrue(MOOSAReyaPolicy.runtimeCompatible(null, lastCue))
        assertTrue(MOOSAReyaPolicy.runtimeCompatible(null, null))
        assertTrue(MOOSAReyaPolicy.runtimeCompatible(2526.98, 2499.831))
    }

    @Test fun differentArabicAndEnglishSubtitleCutsAreCheckedIndependently() {
        // BCS S02E05: a 35-minute video is not the ~46-minute subtitle cut.
        assertTrue(MOOSAReyaPolicy.provenWrongCut(
            2136.134, listOf(2759.668, 2799.424)
        ))
        // English can match while a different Arabic edition cannot.
        assertTrue(MOOSAReyaPolicy.subtitleCutMatches(2765.0, 2759.668))
        assertFalse(MOOSAReyaPolicy.subtitleCutMatches(2765.0, 2136.134))
        // An offline subtitle CDN must not make the 4K video disappear.
        assertFalse(MOOSAReyaPolicy.provenWrongCut(2136.134, emptyList()))
        // An incomplete/live playlist supplies no authoritative duration:
        // preserve the video but never claim a synchronized native subtitle.
        assertFalse(MOOSAReyaPolicy.provenWrongCut(null, listOf(2799.424)))
        assertFalse(MOOSAReyaPolicy.subtitleCutMatches(null, 2799.424))
    }

    @Test fun regressionPreserves4KWhenTimelineMetadataIsUnavailable() {
        val missingEndlist = buildString {
            append("#EXTM3U\n#EXT-X-VERSION:7\n")
            repeat(460) { i -> append("#EXTINF:6.0,\nseg").append(i).append(".m4s\n") }
        }
        // Actual video bytes may be perfectly fine even if the CDN omits
        // ENDLIST. This must not collapse all Reya links back to NetMirror.
        assertNull(MOOSAReyaPolicy.playlistSeconds(missingEndlist))
        assertTrue(MOOSAReyaPolicy.runtimeCompatible(
            MOOSAReyaPolicy.playlistSeconds(missingEndlist), 2705.995))
        // A FULL 35m VOD matched to a 45m subtitle really is truncated.
        assertFalse(MOOSAReyaPolicy.runtimeCompatible(2136.134, 2705.995))
        assertTrue(MOOSAReyaPolicy.runtimeCompatible(2756.0, 2705.995))
    }

    @Test fun calculateVideoHlsAndNativeVttTimelines() {
        val playlist = buildString {
            append("#EXTM3U\n#EXT-X-VERSION:7\n")
            repeat(460) { i ->
                append("#EXTINF:6.0,\nseg").append(i).append(".m4s\n")
            }
            append("#EXT-X-ENDLIST\n")
        }
        assertEquals(2760.0, MOOSAReyaPolicy.playlistSeconds(playlist)!!, 0.01)
        assertNull(MOOSAReyaPolicy.playlistSeconds(
            playlist.replace("#EXT-X-ENDLIST", "")
        ))
        val vtt = buildString {
            append("WEBVTT\n\n")
            repeat(24) { i ->
                append("%02d:00.000 --> %02d:01.000\nCaption\n\n".format(i + 1, i + 1))
            }
            append("45:03.550 --> 45:05.995\nEnd\n")
        }
        assertEquals(2705.995, MOOSAReyaPolicy.finalCueSeconds(vtt)!!, 0.001)
        assertNull(MOOSAReyaPolicy.finalCueSeconds("WEBVTT\n00:00.000 --> 00:01.000\nx"))
        assertNull(MOOSAReyaPolicy.finalCueSeconds("html"))
    }

    @Test fun captionsMustBeBoundToActualStreamProvider() {
        val body = JSONObject("""{"subtitles":[
          {"label":"Arabic","source":"vidnest","url":"https://cache.vdrk.site/subtitles/60059/2/5/Arabic.vtt"},
          {"label":"Arabic","source":"vidlink","url":"https://cache.vdrk.site/subtitles/60059/2/5/Arabic.vtt"},
          {"label":"English","source":"vidnest","url":"https://cache.vdrk.site/subtitles/60059/2/5/English.vtt"},
          {"label":"Arabic","url":"https://cache.vdrk.site/subtitles/60059/2/5/Arabic.vtt"}
        ]}""")
        val candidates = MOOSAReyaPolicy.subtitles(body, true, 2, 5)
        assertEquals(3, candidates.size)
        assertEquals(2, candidates.count { it.source == "vidnest" })
        assertEquals(1, candidates.count { it.source == "vidlink" })
    }

    @Test fun mediaPlaylistMustHaveMapAndRealSegments() {
        val playlist = """#EXTM3U
#EXT-X-VERSION:7
#EXT-X-MAP:URI="init.mp4"
#EXTINF:5.000,
seg0001.m4s
"""
        val url = masterUrl.replace("master", "1080/index")
        val result = MOOSAReyaPolicy.media(url, playlist)
        assertNotNull(result)
        assertTrue(result!!.init.endsWith("/1080/init.mp4"))
        assertTrue(result.segment.endsWith("/1080/seg0001.m4s"))
        assertNull(MOOSAReyaPolicy.media(url, playlist.replace(
            "#EXT-X-VERSION:7", "#EXT-X-KEY:METHOD=AES-128"
        )))
        assertNull(MOOSAReyaPolicy.media(url, playlist.replace(
            "#EXT-X-MAP:URI=\"init.mp4\"", ""
        )))
    }

    private fun box(name: String, body: ByteArray): ByteArray {
        val size = body.size + 8
        return byteArrayOf(
            (size ushr 24).toByte(), (size ushr 16).toByte(),
            (size ushr 8).toByte(), size.toByte()
        ) + name.toByteArray(Charsets.US_ASCII) + body
    }
    private fun mediaTrack(kind: String, width: Int = 0, height: Int = 0): ByteArray {
        val tkhd = ByteArray(84)
        val w = width shl 16
        val h = height shl 16
        for (i in 0..3) {
            tkhd[tkhd.size - 8 + i] = (w ushr (24 - 8 * i)).toByte()
            tkhd[tkhd.size - 4 + i] = (h ushr (24 - 8 * i)).toByte()
        }
        val hdlr = ByteArray(12)
        kind.toByteArray(Charsets.US_ASCII).copyInto(hdlr, 8)
        val mdhd = ByteArray(24)
        val lang = "eng".fold(0) { x, c -> (x shl 5) or (c.code - 96) }
        mdhd[20] = (lang ushr 8).toByte()
        mdhd[21] = lang.toByte()
        val codec = if (kind == "vide")
            box("stsd", "avc1".toByteArray()) else ByteArray(0)
        return box("trak", box("tkhd", tkhd) +
            box("mdia", box("mdhd", mdhd) + box("hdlr", hdlr) + codec))
    }

    @Test fun inspectRealTracksRejectsAudioOnlyAndBadDimensions() {
        val good = box("ftyp", "isom".toByteArray()) +
            box("moov",mediaTrack("vide",3840,2160) + mediaTrack("soun"))
        val info = MOOSAReyaPolicy.inspectInit(good)
        assertNotNull(info)
        assertEquals(3840, info!!.width)
        assertEquals(2160, info.height)
        assertEquals("AVC", info.codec)
        assertTrue(info.audioPresent)
        assertEquals(setOf("eng"), info.audioLanguages)
        assertNull(MOOSAReyaPolicy.inspectInit(box("ftyp", "isom".toByteArray()) +
            box("moov", mediaTrack("soun"))))
        val moof = box("moof", ByteArray(6000)) + box("mdat",ByteArray(32))
        assertTrue(MOOSAReyaPolicy.isFmp4Fragment(moof))
        assertFalse(MOOSAReyaPolicy.isFmp4Fragment(good))
    }
}
