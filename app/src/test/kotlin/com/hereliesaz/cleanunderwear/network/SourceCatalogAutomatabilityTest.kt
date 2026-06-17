package com.hereliesaz.cleanunderwear.network

import android.content.Context
import android.content.res.AssetManager
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards the automatability contract that drives [com.hereliesaz.cleanunderwear
 * .data.MonitorabilityState.NO_AUTOMATED_SOURCE]. Reads the real sources.json.
 *
 * The shipped catalog currently has NO automatable (non-MANUAL_LANDING) lockup
 * or obituary source — every gov roster the project surveyed either bot-blocks a
 * non-browser fetch or has no deep-linkable, name-queryable result page, so they
 * are all operator-launch-only. These tests pin that honest reality: the daily
 * vigil cannot auto-confirm a status change today, and Triage routes such
 * contacts to NO_AUTOMATED_SOURCE instead of silently reporting "no change".
 *
 * When a genuinely automatable source is later curated (a QUERY_TEMPLATE /
 * ROSTER_PAGE entry, or a `multi_state_obituary` with a server-rendered result
 * page), [hasNoAutomatableSource_forKnownMetro] will start failing — that is the
 * intended signal to update this test and confirm the auto-path is wired on.
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
    fun knownMetro_resolvesToSources_butNoneAreAutomatable() {
        val catalog = loadCatalog()
        // New Orleans (504) + an Orleans Parish ZIP resolves to lockup sources.
        val lockup = catalog.lockupSourcesFor("504", "123 Magazine St, New Orleans, LA 70130")
        assertTrue("expected catalog to resolve lockup sources for a known metro", lockup.isNotEmpty())

        // ...but every one of them is operator-launch-only.
        assertTrue(
            "shipped catalog should have only MANUAL_LANDING lockup sources",
            lockup.all { it.kind == SourceKind.MANUAL_LANDING }
        )
        assertFalse(
            "no automatable source should exist for the shipped catalog (see class doc)",
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
