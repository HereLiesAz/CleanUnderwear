package com.hereliesaz.cleanunderwear.network

import android.content.Context
import android.content.res.AssetManager
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Validates the catalog correctly loads the identity_sources block ported
 * from doxray, and that every entry is well-formed (https URL, MANUAL_LANDING
 * kind, slot-fills with a real name without throwing).
 *
 * Reads the real sources.json from the module's main assets so a typo in the
 * JSON breaks this test, not a runtime crash on first device launch.
 */
class SourceCatalogIdentityTest {

    private fun loadCatalog(): SourceCatalog {
        val assetFile = File("src/main/assets/sources.json")
        assertTrue("sources.json not found at ${assetFile.absolutePath}", assetFile.exists())

        val context = mockk<Context>()
        val assets = mockk<AssetManager>()
        every { context.assets } returns assets
        every { assets.open("sources.json") } answers { assetFile.inputStream() }
        return SourceCatalog(context)
    }

    @Test
    fun identitySources_loadsFromJson_andContainsDoxrayProviders() {
        val identity = loadCatalog().identitySources()
        assertTrue("identity_sources must be non-empty after porting doxray", identity.isNotEmpty())

        val ids = identity.map { it.id }.toSet()
        // The four face-recognition providers from doxray Tier 1.
        assertTrue("pimeyes_face missing", "pimeyes_face" in ids)
        assertTrue("facecheck_id_face missing", "facecheck_id_face" in ids)
        assertTrue("lenso_face missing", "lenso_face" in ids)
        assertTrue("faceseek_face missing", "faceseek_face" in ids)
        // The three reverse-image providers from doxray Tier 2.
        assertTrue("yandex_reverse_image missing", "yandex_reverse_image" in ids)
        assertTrue("tineye_reverse_image missing", "tineye_reverse_image" in ids)
        assertTrue("google_lens_reverse_image missing", "google_lens_reverse_image" in ids)
        // Name-based OSINT from doxray Tier 3.
        assertTrue("cyberbgc_people_lookup missing", "cyberbgc_people_lookup" in ids)
        assertTrue("smartbgc_people_lookup missing", "smartbgc_people_lookup" in ids)
        assertTrue("github_user_search missing", "github_user_search" in ids)
    }

    @Test
    fun identitySources_everyEntry_isManualLandingHttpsAndSafe() {
        loadCatalog().identitySources().forEach { source ->
            assertEquals(
                "identity source ${source.id} must be MANUAL_LANDING — auto-scrape is unsafe for face/OSINT providers",
                SourceKind.MANUAL_LANDING,
                source.kind
            )
            assertTrue(
                "identity source ${source.id} URL must be https",
                source.urlTemplate.startsWith("https://")
            )
            assertNotNull("identity source ${source.id} label must be set", source.label)
            assertTrue("identity source ${source.id} label is blank", source.label.isNotBlank())
        }
    }

    @Test
    fun identitySources_slotFilled_producesValidUrls_forKnownName() {
        val identity = loadCatalog().identitySources()
        identity.forEach { source ->
            val url = SourceUrlBuilder.buildFetchUrl(source, "John", "Smith")
            assertTrue(
                "identity source ${source.id} produced non-https URL: $url",
                url.startsWith("https://")
            )
            // Slot-fill must remove every {first}/{last} placeholder.
            assertTrue(
                "identity source ${source.id} left a slot unfilled: $url",
                !url.contains("{first}") && !url.contains("{last}")
            )
        }
    }

    @Test
    fun identitySources_areCatalogRecognized() {
        // isFromCatalog must accept every identity URL so the prefix-whitelist
        // used by ContactHarvester / UI doesn't reject identity-source links.
        val catalog = loadCatalog()
        catalog.identitySources().forEach { source ->
            val url = SourceUrlBuilder.buildFetchUrl(source, "John", "Smith")
            assertTrue(
                "isFromCatalog rejected identity URL: $url",
                catalog.isFromCatalog(url)
            )
        }
    }
}
