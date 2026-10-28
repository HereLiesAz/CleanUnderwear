package com.hereliesaz.cleanunderwear.network

import android.content.Context
import android.content.res.AssetManager
import com.hereliesaz.cleanunderwear.match.EventKind
import io.mockk.every
import io.mockk.mockk
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The obituary parsers against result pages captured from the live sites (fixtures dated in
 * their file names), through the real sources.json. When a site's markup drifts, re-capture
 * the page and this test shows which field broke.
 */
class ObituaryFixtureTest {

    private val catalog: SourceCatalog by lazy {
        val bytes = javaClass.getResourceAsStream("/sources.json")!!.use { it.readBytes() }
        val context = mockk<Context>()
        val assets = mockk<AssetManager>()
        every { context.assets } returns assets
        every { assets.open("sources.json") } answers { bytes.inputStream() }
        SourceCatalog(context)
    }

    private fun source(id: String) = catalog.obituarySourcesFor("504").first { it.id == id }
    private fun fixture(name: String) = javaClass.getResourceAsStream("/fixtures/$name")!!.bufferedReader().readText()

    @Before
    fun pinClock() {
        StructuredRecords.today = { java.time.LocalDate.of(2026, 10, 23) }
    }

    @After
    fun resetClock() {
        StructuredRecords.today = { java.time.LocalDate.now() }
    }

    @Test
    fun echovita_readsEachObituaryWithItsOwnLinkAndState() {
        val src = source("echovita_obit")
        val records = StructuredRecords.fromRegex(
            fixture("echovita_john_smith_2026-10-23.html"), src, "John", "Smith", EventKind.DEATH, "https://www.echovita.com/"
        )
        assertTrue(records.size >= 10)
        val first = records.first()
        assertEquals("https://www.echovita.com/us/obituaries/ky/london/john-randell-smith-22298307", first.evidenceUrl)
        assertEquals("KY", first.state)
        assertEquals(51, first.age)
        assertEquals("London", first.city)
        assertTrue(records.map { it.recordKey }.distinct().size == records.size)
    }

    @Test
    fun obitsarchive_readsLinksAndStatesButNotTheStateAsCity() {
        val src = source("obitsarchive_obit")
        val records = StructuredRecords.fromRegex(
            fixture("obitsarchive_john_smith_2026-10-23.html"), src, "John", "Smith", EventKind.DEATH, "https://www.obitsarchive.com/"
        )
        assertTrue(records.isNotEmpty())
        assertTrue(records.all { it.evidenceUrl!!.startsWith("https://www.obitsarchive.com/obituaries/obit/") })
        assertTrue(records.none { it.city != null && it.city == it.state })
    }

    @Test
    fun firstPageStillInWindow_soTheNextPageIsFetched() {
        assertTrue(StructuredRecords.reachesIntoWindow(fixture("obitsarchive_john_smith_2026-10-23.html"), source("obitsarchive_obit")))
    }

    @Test
    fun aPageWithRecords_orSayingItFoundNobody_isRead_andAnythingElseIsNot() {
        for (id in listOf("echovita_obit", "obitsarchive_obit")) {
            val src = source(id)
            val hits = fixture(if (id == "echovita_obit") "echovita_john_smith_2026-10-23.html" else "obitsarchive_john_smith_2026-10-23.html")
            val none = fixture(if (id == "echovita_obit") "echovita_no_results.html" else "obitsarchive_no_results.html")
            assertTrue(id, StructuredRecords.regexReadable(hits, src))
            assertTrue(id, !StructuredRecords.regexReadable(none, src) && BotWall.saysNoResults(none))
            val redesigned = "<html><body><div class=\"new-layout\">Results for John Smith</div></body></html>"
            assertTrue(id, !StructuredRecords.regexReadable(redesigned, src) && !BotWall.saysNoResults(redesigned))
        }
    }
}
