package com.hereliesaz.cleanunderwear.network

import android.content.Context
import android.content.res.AssetManager
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards the automatability contract that drives [com.hereliesaz.cleanunderwear
 * .data.MonitorabilityState.NO_AUTOMATED_SOURCE]. Reads the real sources.json.
 *
 * The federal BOP inmate-locator JSON API is automatable (a QUERY_TEMPLATE with
 * result_format BOP_JSON), so every US contact resolves to at least one source
 * the daily vigil can fetch + verify on its own. County/state gov rosters remain
 * operator-launch-only (MANUAL_LANDING) — they bot-block non-browser fetches or
 * expose no name-queryable deep link — and surface as in-app chips. These tests
 * pin that contract: a US locale is automatable via BOP, while the locale-
 * specific rosters stay manual.
 */
class SourceCatalogAutomatabilityTest {

    private fun loadCatalog(): SourceCatalog {
        val stream = javaClass.getResourceAsStream("/sources.json")
        assertNotNull("sources.json not on the test classpath", stream)
        val bytes = stream!!.use { it.readBytes() }

        val context = mockk<Context>()
        val assets = mockk<AssetManager>()
        every { context.assets } returns assets
        every { assets.open("sources.json") } answers { bytes.inputStream() }
        return SourceCatalog(context)
    }

    @Test
    fun usMetro_isAutomatableViaFederalBop_whileLocalRostersStayManual() {
        val catalog = loadCatalog()
        // New Orleans (504) + an Orleans Parish ZIP resolves to lockup sources.
        val lockup = catalog.lockupSourcesFor("504", "123 Magazine St, New Orleans, LA 70130")
        assertTrue("expected catalog to resolve lockup sources for a known metro", lockup.isNotEmpty())

        // The federal BOP locator is automatable and covers every US contact.
        val bop = lockup.firstOrNull { it.id == "bop_inmate_locator" }
        assertNotNull("federal BOP source should resolve for a US locale", bop)
        assertEquals(SourceKind.QUERY_TEMPLATE, bop!!.kind)
        assertEquals(ResultFormat.BOP_JSON, bop.resultFormat)
        assertTrue(
            "BOP fetch URL should query the JSON API with name slots",
            bop.urlTemplate.contains("output=json") &&
                bop.urlTemplate.contains("{first}") &&
                bop.urlTemplate.contains("{last}")
        )

        // The locale-specific gov rosters remain operator-launch-only.
        val localRosters = lockup.filter { it.id != "bop_inmate_locator" }
        assertTrue("expected county/state rosters alongside BOP", localRosters.isNotEmpty())
        assertTrue(
            "county/state rosters should stay MANUAL_LANDING",
            localRosters.all { it.kind == SourceKind.MANUAL_LANDING }
        )

        assertTrue(
            "a US contact should be automatable via the federal BOP path",
            catalog.hasAutomatableSourceFor("504", "123 Magazine St, New Orleans, LA 70130")
        )
    }

    @Test
    fun obituarySources_readFromMultiStateObituaryBlock() {
        // The multi_state_obituary block is now wired (was hardcoded empty). It
        // ships empty, so obituary lookups resolve to nothing until a source is
        // curated. This guards the wiring: a malformed/renamed JSON key would
        // make this throw or change behavior.
        val catalog = loadCatalog()
        val obit = catalog.obituarySourcesFor("504", "123 Magazine St, New Orleans, LA 70130")
        assertTrue("obituary sources ship empty until a per-name source is curated", obit.isEmpty())
    }
}
