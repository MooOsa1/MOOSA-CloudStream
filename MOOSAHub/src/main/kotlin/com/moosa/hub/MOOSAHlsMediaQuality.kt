package com.moosa.hub

import java.io.ByteArrayOutputStream
import java.net.URI
import java.util.Locale

/**
 * Conservative fallback for a verified HLS MEDIA playlist without a master
 * RESOLUTION tag. Inspect actual H.264 SPS bytes in one MPEG-TS media segment.
 * No HD claim from CDN titles, codecs, bitrate, or URLs. If unavailable,
 * encrypted, fragmented MP4 or unrecognized, leave quality UNKNOWN.
 */
internal object MOOSAHlsMediaQuality {
    private const val SEGMENT_LIMIT = 65536

    fun firstTsSegmentUrl(playlistUrl: String, playlist: String): String? {
        val url = runCatching { URI(playlistUrl) }.getOrNull() ?: return null
        if (!url.scheme.equals("https", true) || url.host.isNullOrBlank()) return null
        val lines = playlist.lineSequence().map { it.trim() }.toList()
        if (lines.firstOrNull()?.trimStart('\uFEFF') != "#EXTM3U") return null
        if (lines.any { line ->
            line.startsWith("#EXT-X-STREAM-INF:", true) ||
                line.startsWith("#EXT-X-MAP:", true) ||
                (line.startsWith("#EXT-X-KEY:", true) &&
                    !line.contains("METHOD=NONE", true))
        }) return null

        var afterDuration = false
        for (line in lines.drop(1)) {
            if (line.startsWith("#EXTINF:", true)) {
                afterDuration = true
                continue
            }
            if (!afterDuration || line.isEmpty() || line.startsWith("#")) continue
            val target = runCatching { url.resolve(line) }.getOrNull() ?: return null
            val path = target.path.orEmpty().lowercase(Locale.ROOT)
            if (!target.scheme.equals("https", true) ||
                target.host.isNullOrBlank() || target.userInfo != null ||
                !path.endsWith(".ts")
            ) return null
            return target.toString()
        }
        return null
    }

    fun h264HeightFromTs(bytes: ByteArray): Int? {
        if (bytes.size < 188 * 3 || bytes.size > SEGMENT_LIMIT) return null
        // MPEG-TS: three aligned 188-byte packets, not arbitrary CDN bytes.
        val start = (0 until 188).firstOrNull { offset ->
            offset + 376 < bytes.size &&
                bytes[offset] == 0x47.toByte() &&
                bytes[offset + 188] == 0x47.toByte() &&
                bytes[offset + 376] == 0x47.toByte()
        } ?: return null

        // Assemble elementary stream payload per PID so Annex-B SPS that
        // spans TS packets is still recognized. No decryption involved.
        val streams = linkedMapOf<Int, ByteArrayOutputStream>()
        var packet = start
        while (packet + 188 <= bytes.size) {
            if (bytes[packet] != 0x47.toByte()) break
            val b1 = bytes[packet + 1].toInt() and 255
            val b2 = bytes[packet + 2].toInt() and 255
            val b3 = bytes[packet + 3].toInt() and 255
            val pid = ((b1 and 31) shl 8) or b2
            val hasPayload = (b3 and 0x10) != 0
            if (hasPayload && pid !in listOf(0, 0x1fff)) {
                var offset = packet + 4
                if ((b3 and 0x20) != 0) {
                    val length = bytes[offset].toInt() and 255
                    offset += 1 + length
                }
                if (offset in (packet + 4) until (packet + 188)) {
                    val sink = streams.getOrPut(pid) { ByteArrayOutputStream() }
                    if (sink.size() < SEGMENT_LIMIT) {
                        sink.write(bytes, offset,
                            minOf(packet + 188 - offset, SEGMENT_LIMIT - sink.size()))
                    }
                }
            }
            packet += 188
        }

        return streams.values.mapNotNull { stream ->
            scanAnnexBForSps(stream.toByteArray())
        }.maxOrNull()
    }

    private fun scanAnnexBForSps(data: ByteArray): Int? {
        var i = 0
        var found: Int? = null
        while (i + 8 < data.size) {
            val start = when {
                data[i] == 0.toByte() && data[i + 1] == 0.toByte() &&
                    data[i + 2] == 1.toByte() -> i + 3
                i + 4 < data.size &&
                    data[i] == 0.toByte() && data[i + 1] == 0.toByte() &&
                    data[i + 2] == 0.toByte() && data[i + 3] == 1.toByte() -> i + 4
                else -> { i++; continue }
            }
            val nal = data[start].toInt() and 255
            if (nal and 0x1f == 7) {
                val payload = data.copyOfRange(start + 1, minOf(start + 1 + 160, data.size))
                val height = parseSpsHeight(payload)
                if (height != null && (found == null || height > found)) found = height
            }
            i = start + 1
        }
        return found
    }

    private class Bits(private val bytes: ByteArray) {
        private var pos = 0
        fun bit(): Int? {
            if (pos >= bytes.size * 8) return null
            val value = (bytes[pos / 8].toInt() ushr (7 - pos % 8)) and 1
            pos++
            return value
        }
        fun bits(n: Int): Int? {
            if (n !in 0..31) return null
            var v = 0
            repeat(n) { v = (v shl 1) or (bit() ?: return null) }
            return v
        }
        fun ue(): Int? {
            var zeros = 0
            while (true) {
                val bitValue = bit() ?: return null
                if (bitValue == 1) break
                zeros++
                if (zeros > 24) return null
            }
            val rest = bits(zeros) ?: return null
            return ((1 shl zeros) - 1) + rest
        }
        fun se(): Int? {
            val code = ue() ?: return null
            return if (code % 2 == 0) -(code / 2) else (code + 1) / 2
        }
    }

    private fun parseSpsHeight(raw: ByteArray): Int? {
        if (raw.size < 7) return null
        // Remove H.264 emulation-prevention bytes (00 00 03 -> 00 00).
        val bytes = ByteArrayOutputStream()
        var zeroCount = 0
        for (b in raw) {
            val n = b.toInt() and 255
            if (zeroCount >= 2 && n == 3) {
                zeroCount = 0
                continue
            }
            bytes.write(n)
            zeroCount = if (n == 0) zeroCount + 1 else 0
        }
        val reader = Bits(bytes.toByteArray())
        val profile = reader.bits(8) ?: return null
        if (profile !in setOf(66, 77, 88, 100, 110, 122, 244, 44, 83, 86, 118, 128)) return null
        reader.bits(8) ?: return null // constraint set flags
        reader.bits(8) ?: return null // level_idc
        if ((reader.ue() ?: return null) > 31) return null // SPS identifier

        var chromaFormat = 1
        var separateColourPlane = 0
        if (profile in setOf(100, 110, 122, 244, 44, 83, 86, 118, 128)) {
            chromaFormat = reader.ue() ?: return null
            if (chromaFormat !in 0..3) return null
            if (chromaFormat == 3) separateColourPlane = reader.bit() ?: return null
            reader.ue() ?: return null // bit depth luma
            reader.ue() ?: return null // bit depth chroma
            reader.bit() ?: return null
            val scaling = reader.bit() ?: return null
            if (scaling == 1) {
                for (index in 0 until if (chromaFormat == 3) 12 else 8) {
                    if (reader.bit() != 1) continue
                    val count = if (index < 6) 16 else 64
                    var lastScale = 8
                    var nextScale = 8
                    repeat(count) {
                        if (nextScale != 0) {
                            val delta = reader.se() ?: return null
                            nextScale = (lastScale + delta + 256) % 256
                        }
                        lastScale = if (nextScale == 0) lastScale else nextScale
                    }
                }
            }
        }
        reader.ue() ?: return null // log2 max frame number minus 4
        when (reader.ue() ?: return null) {
            0 -> { reader.ue() ?: return null }
            1 -> {
                reader.bit() ?: return null
                reader.se() ?: return null
                reader.se() ?: return null
                val refs = reader.ue() ?: return null
                if (refs > 32) return null
                repeat(refs) { reader.se() ?: return null }
            }
            2 -> Unit
            else -> return null
        }
        reader.ue() ?: return null // max num reference frames
        reader.bit() ?: return null // gaps frame num allowed
        val widthMbs = (reader.ue() ?: return null) + 1
        val heightMapUnits = (reader.ue() ?: return null) + 1
        if (widthMbs !in 20..512 || heightMapUnits !in 8..270) return null
        val frameOnly = reader.bit() ?: return null
        if (frameOnly == 0) reader.bit() ?: return null
        reader.bit() ?: return null // direct 8x8 inference
        val crop = reader.bit() ?: return null
        var left = 0; var right = 0; var top = 0; var bottom = 0
        if (crop == 1) {
            left = reader.ue() ?: return null
            right = reader.ue() ?: return null
            top = reader.ue() ?: return null
            bottom = reader.ue() ?: return null
        }
        val effectiveChroma = if (separateColourPlane == 1) 0 else chromaFormat
        val cropX = if (effectiveChroma == 0) 1 else if (effectiveChroma == 3) 1 else 2
        val cropY = when (effectiveChroma) {
            0, 3 -> 2 - frameOnly
            1 -> 2 * (2 - frameOnly)
            2 -> 2 - frameOnly
            else -> return null
        }
        val width = widthMbs * 16 - (left + right) * cropX
        val height = heightMapUnits * 16 * (2 - frameOnly) - (top + bottom) * cropY
        if (width !in 320..8192 || height !in 240..4320) return null
        return height
    }
}
