package com.moosa.hub

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MOOSAHDHub4uPolicyTest {
    @Test fun exactMovieAndSeasonPagesOnly() {
        assertTrue(MOOSAHDHub4uPolicy.exactCatalogHeading(
            "The Invite", 2026, null,
            "The Invite (2026) BluRay [Hindi & English] 4K 1080p | Full Movie"
        ))
        assertFalse(MOOSAHDHub4uPolicy.exactCatalogHeading(
            "The Invite", 2026, null,
            "The Invite (2015) BluRay 4K"
        ))
        assertFalse(MOOSAHDHub4uPolicy.exactCatalogHeading(
            "The Invite", 2026, null,
            "The Invitation (2026) BluRay 4K"
        ))
        assertTrue(MOOSAHDHub4uPolicy.exactCatalogHeading(
            "Better Call Saul", 2015, 2,
            "Better Call Saul (Season 2) BluRay [Hindi & English] 1080p"
        ))
        assertFalse(MOOSAHDHub4uPolicy.exactCatalogHeading(
            "Better Call Saul", 2015, 2,
            "Better Call Saul (Season 3) BluRay 4K"
        ))
    }

    @Test fun movieAndEpisodeFilesCannotCrossTitlesYearsOrSeasons() {
        val invite = "The Invite (2026) 2160p 10bit SDR iTunes WEB-DL HEVC.mkv"
        assertTrue(MOOSAHDHub4uPolicy.exactFileIdentity(
            "The Invite", 2026, null, null, invite
        ))
        assertFalse(MOOSAHDHub4uPolicy.exactFileIdentity(
            "The Invite", 2025, null, null, invite
        ))
        assertFalse(MOOSAHDHub4uPolicy.exactFileIdentity(
            "The Invitation", 2026, null, null, invite
        ))
        val episode = "Better.Call.Saul.S02.E07.1080p.10bit.BluRay.Hindi-English.mkv"
        assertTrue(MOOSAHDHub4uPolicy.exactFileIdentity(
            "Better Call Saul", 2015, 2, 7, episode
        ))
        assertTrue(MOOSAHDHub4uPolicy.exactFileIdentity(
            "Better Call Saul", 2015, 2, 7, "Better Call Saul S02-E07 – HUBLinks"
        ))
        assertFalse(MOOSAHDHub4uPolicy.exactFileIdentity(
            "Better Call Saul", 2015, 2, 8, episode
        ))
        assertFalse(MOOSAHDHub4uPolicy.exactFileIdentity(
            "Better Call Saul", 2015, 1, 7, episode
        ))
        assertFalse(MOOSAHDHub4uPolicy.exactFileIdentity(
            "Better Call Saul", 2015, 2, 7,
            "Better.Call.Saul.S02.1080p.COMPLETE.SEASON.zip"
        ))
    }

    @Test fun publicCatalogSitemapIsAnExactTitleFilter() {
        assertEquals("better-call-saul", MOOSAHDHub4uPolicy.slug("Better Call Saul"))
        assertEquals("https://new9.hdhub4u.cl/better-call-saul-season-2-hindi-bluray-all-episodes/",
            MOOSAHDHub4uPolicy.matchingCatalogUrl(
                "https://new9.hdhub4u.cl/better-call-saul-season-2-hindi-bluray-all-episodes/",
                "Better Call Saul", 2015, 2
            )
        )
        assertNull(MOOSAHDHub4uPolicy.matchingCatalogUrl(
            "https://new9.hdhub4u.cl/better-call-saul-season-3-hindi-bluray-all-episodes/",
            "Better Call Saul", 2015, 2
        ))
        assertNull(MOOSAHDHub4uPolicy.matchingCatalogUrl(
            "https://new9.hdhub4u.cl/the-invitation-2026-hindi-full-movie/",
            "The Invite", 2026, null
        ))
        assertEquals("https://new9.hdhub4u.cl/the-invite-2026-bluray-hindi-full-movie/",
            MOOSAHDHub4uPolicy.matchingCatalogUrl(
                "https://new9.hdhub4u.cl/the-invite-2026-bluray-hindi-full-movie/",
                "The Invite", 2026, null
            )
        )
        assertNull(MOOSAHDHub4uPolicy.safeSitemap("http://new9.hdhub4u.cl/post-sitemap.xml"))
        assertNull(MOOSAHDHub4uPolicy.safeSitemap("https://fake.hdhub4u.cl/post-sitemap.xml"))
        assertNull(MOOSAHDHub4uPolicy.safeCatalog("https://new9.hdhub4u.cl/search.html?q=x"))
    }

    @Test fun onlyAllowlistedFileAndEpisodeGateways() {
        assertEquals("https://hubdrive.bar/file/1713898804",
            MOOSAHDHub4uPolicy.safeHubDrive("https://hubdrive.bar/file/1713898804"))
        assertEquals("https://hubcloud.ist/drive/s7tns2un11112dm",
            MOOSAHDHub4uPolicy.safeHubCloudDirect("https://hubcloud.ist/drive/s7tns2un11112dm"))
        assertNull(MOOSAHDHub4uPolicy.safeHubCloudDirect("http://hubcloud.ist/drive/s7tns2un11112dm"))
        assertNull(MOOSAHDHub4uPolicy.safeHubCloudDirect("https://hubcloud.ist.evil.tld/drive/s7tns2un11112dm"))
        assertNull(MOOSAHDHub4uPolicy.safeHubCloudDirect("https://hubcloud.ist/drive/s7tns2un11112dm?next=x"))
        assertNull(MOOSAHDHub4uPolicy.safeHubCloudDirect("https://hubcloud.ist/drive/../../admin"))
        assertEquals("https://hblinks.lol/archives/73611",
            MOOSAHDHub4uPolicy.safeArchive("https://hblinks.lol/archives/73611"))
        assertNull(MOOSAHDHub4uPolicy.safeArchive("https://hblinks.lol/archives/73611?next=evil"))
        assertNull(MOOSAHDHub4uPolicy.safeArchive("https://hblinks.lol.evil.tld/archives/73611"))
        assertNull(MOOSAHDHub4uPolicy.safeHubDrive("https://evil.tld/file/1713898804"))
        assertNull(MOOSAHDHub4uPolicy.safeHubDrive("http://hubdrive.bar/file/1713898804"))
        assertNull(MOOSAHDHub4uPolicy.safeHubDrive("https://hubdrive.bar/file/../../admin"))
        assertEquals(7, MOOSAHDHub4uPolicy.episodeLabel("EPiSODE 7"))
        assertNull(MOOSAHDHub4uPolicy.episodeLabel("Season 2 Episode 7"))
        assertEquals(2160, MOOSAHDHub4uPolicy.releaseQuality("4K [2160p SDR WEB-DL]"))
        assertEquals(1080, MOOSAHDHub4uPolicy.releaseQuality("1080p 10Bit HEVC"))
        assertNull(MOOSAHDHub4uPolicy.releaseQuality("720p Pack [2.8GB]"))
    }
}
