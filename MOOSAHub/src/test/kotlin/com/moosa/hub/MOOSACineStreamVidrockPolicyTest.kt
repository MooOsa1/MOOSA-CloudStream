package com.moosa.hub

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import java.util.Base64

class MOOSACineStreamVidrockPolicyTest {
    private val p = MOOSACineStreamVidrockPolicy

    @Test fun betterCallSaulEpisodeUsesOnlyExactTmdbIdentity() {
        assertEquals(
            "https://vidrock.ru/api/tv/60059_1_1/",
            p.requestUrl(60059, true, 1, 1)
        )
        assertEquals(
            "https://vidrock.ru/api/tv/60059_6_13/",
            p.requestUrl(60059, true, 6, 13)
        )
        assertEquals(
            "https://vidrock.ru/api/movie/238/",
            p.requestUrl(238, false, null, null)
        )
        assertNull(p.requestUrl(null, true, 1, 1))
        assertNull(p.requestUrl(0, true, 1, 1))
        assertNull(p.requestUrl(60059, true, null, null))
        assertNull(p.requestUrl(60059, true, 1, null))
        assertNull(p.requestUrl(60059, false, 1, 1))
        assertNull(p.requestUrl(60059, true, 0, 1))
        assertNull(p.requestUrl(60059, true, 1, 0))
    }

    @Test fun rejectNonHttpsUnknownMediaAndEmbeddedCredentials() {
        assertEquals(
            "https://cdn.example/hls/main.m3u8?t=a",
            p.hlsUrl("https://cdn.example/hls/main.m3u8?t=a")
        )
        assertEquals(
            "https://cdn.example/HLS/MAIN.M3U8",
            p.hlsUrl(" https://cdn.example/HLS/MAIN.M3U8 ")
        )
        for (input in listOf(
            "", "magnet:?xt=urn:btih:", "http://cdn.example/hls.m3u8",
            "https://cdn.example/player", "https://cdn.example/hd.mp4",
            "https://user:pass@cdn.example/a.m3u8",
            "https://cdn.example/a.m3u8.exe",
            "file:///tmp/master.m3u8"
        )) {
            assertNull("should reject: " + input, p.hlsUrl(input))
        }
    }

    @Test fun authenticatedAesGcmPayloadIsRequiredBeforeLinkCanBeAccepted() {
        val keyHex = "7f3e9c2a8b5d1f4e6a9c3b7d2e5f8a1c4b6d9e2f5a8c1b4d7e9f2a5c8b1d4e7f"
        val key = keyHex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        val iv = ByteArray(12) { it.toByte() }
        val url = "https://media.example.com/master.m3u8?e=12345"
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(
            Cipher.ENCRYPT_MODE,
            SecretKeySpec(key, "AES"),
            GCMParameterSpec(128, iv)
        )
        val encrypted = iv + cipher.doFinal(url.toByteArray(Charsets.UTF_8))
        val encoded = Base64.getUrlEncoder().withoutPadding().encodeToString(encrypted)
        assertEquals(url, p.decryptUrl(encoded))
        assertEquals(url, p.decryptUrl(Base64.getUrlEncoder().encodeToString(encrypted)))

        // A one-byte GCM tag modification must be rejected, not played.
        val tampered = encrypted.copyOf()
        tampered[tampered.lastIndex] = (tampered.last().toInt() xor 1).toByte()
        assertNull(p.decryptUrl(Base64.getUrlEncoder().withoutPadding()
            .encodeToString(tampered)))
        assertNull(p.decryptUrl("not valid base64!!!"))
    }
}
