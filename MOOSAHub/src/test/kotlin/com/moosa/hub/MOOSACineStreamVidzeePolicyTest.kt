package com.moosa.hub

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

class MOOSACineStreamVidzeePolicyTest {
    private val p = MOOSACineStreamVidzeePolicy

    @Test fun exactSeasonTwoEpisodeOneAndSourceIds() {
        assertEquals(
            "https://player.vidzee.wtf/api/server?id=60059&sr=0&ss=2&ep=1",
            p.requestUrl(60059, true, 2, 1, 0)
        )
        assertEquals(
            "https://player.vidzee.wtf/api/server?id=60059&sr=7&ss=2&ep=4",
            p.requestUrl(60059, true, 2, 4, 7)
        )
        assertEquals(
            "https://player.vidzee.wtf/api/server?id=238&sr=5",
            p.requestUrl(238, false, null, null, 5)
        )
        assertNull(p.requestUrl(60059, true, null, 1, 0))
        assertNull(p.requestUrl(60059, true, 1, null, 0))
        assertNull(p.requestUrl(60059, false, 2, 1, 0))
        assertNull(p.requestUrl(60059, true, 0, 1, 0))
        assertNull(p.requestUrl(60059, true, 2, 0, 0))
        assertNull(p.requestUrl(60059, true, 2, 1, 3))
        assertNull(p.requestUrl(-1, true, 2, 1, 0))
    }

    @Test fun rejectWrongMediaAndNonHttps() {
        val safe = "https://cdn.example/playlist/master.m3u8?token=xyz"
        assertEquals(safe, p.secureHls(safe))
        for (x in listOf(
            "http://cdn.example/v.m3u8", "file:///sdcard/a.m3u8",
            "magnet:?xt=urn:btih:123", "https://cdn.example/file.mp4",
            "https://a:b@cdn.example/hls.m3u8", "https://cdn.example/a.m3u8#fake"
        )) {
            assertNull("Bad source accepted: $x", p.secureHls(x))
        }
    }

    @Test fun nestedBase64AesCbcRoundTripMustBeExact() {
        val expected = "https://cdn.example/video/hls.m3u8?x=abc"
        val iv = ByteArray(16) { it.toByte() }
        val key = "A7kP9xM2Qv8Lr4Nz1Ht6Yc3Bw5Jf0DsU".toByteArray(Charsets.UTF_8)
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
        val encrypted = cipher.doFinal(expected.toByteArray(Charsets.UTF_8))
        val inner = Base64.getEncoder().encodeToString(iv) +
            ":" + Base64.getEncoder().encodeToString(encrypted)
        val wrapped = Base64.getEncoder()
            .encodeToString(inner.toByteArray(Charsets.UTF_8))
        assertEquals(expected, p.decryptHls(wrapped))
        val tampered = encrypted.clone()
        tampered[tampered.lastIndex] = (tampered.last().toInt() xor 3).toByte()
        val corrupt = Base64.getEncoder().encodeToString(
            (Base64.getEncoder().encodeToString(iv) + ":" +
             Base64.getEncoder().encodeToString(tampered)).toByteArray()
        )
        assertNull(p.decryptHls(corrupt))
        assertNull(p.decryptHls("not base64 :!?"))
        assertNull(p.decryptHls(Base64.getEncoder().encodeToString(
            "wrong".toByteArray()
        )))
    }

    @Test fun sevenDistinctIdsDoNotAllowUnboundedRequests() {
        assertEquals(listOf(0, 1, 2, 4, 5, 6, 7), p.SERVERS)
        assertTrue(p.base64("QQ==")!!.contentEquals(byteArrayOf(65)))
        assertNull(p.base64("QQ=== "))
        assertNull(p.base64("C".repeat(33000)))
    }
}
