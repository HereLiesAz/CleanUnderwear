package com.hereliesaz.cleanunderwear.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class SourceUrlBuilderTest {

    private fun source(
        kind: SourceKind = SourceKind.QUERY_TEMPLATE,
        urlTemplate: String,
        evidenceUrlTemplate: String? = null,
        formFields: Map<String, String> = emptyMap()
    ): Source = Source(
        id = "test",
        label = "Test",
        kind = kind,
        method = "GET",
        urlTemplate = urlTemplate,
        formFields = formFields,
        render = RenderMode.WEBVIEW,
        resultFormat = ResultFormat.HTML,
        renderSettleMs = 1500,
        readySelector = null,
        evidenceUrlTemplate = evidenceUrlTemplate ?: urlTemplate,
        scope = SourceScope.STATE,
        appliesToAreaCodes = emptySet(),
        coversCountry = null
    )

    @Test
    fun buildFetchUrl_fillsBothSlots_andUrlEncodes() {
        val src = source(urlTemplate = "https://x.example/?f={first}&l={last}")
        val url = SourceUrlBuilder.buildFetchUrl(src, "Mary Jane", "O'Brien")
        // Spaces and apostrophes must be percent-encoded for URL safety.
        assertEquals("https://x.example/?f=Mary+Jane&l=O%27Brien", url)
    }

    @Test
    fun buildEvidenceUrl_prefersEvidenceTemplate() {
        val src = source(
            urlTemplate = "https://x.example/api?f={first}&l={last}",
            evidenceUrlTemplate = "https://x.example/?f={first}&l={last}"
        )
        val url = SourceUrlBuilder.buildEvidenceUrl(src, "John", "Doe")
        assertTrue(url.startsWith("https://x.example/?"))
    }

    @Test
    fun buildFormFields_substitutes_withoutEncoding() {
        val src = source(
            urlTemplate = "https://x.example/post",
            formFields = mapOf("firstName" to "{first}", "lastName" to "{last}", "fixed" to "ALL")
        )
        val fields = SourceUrlBuilder.buildFormFields(src, "Mary Jane", "O'Brien")
        // POST form fields are sent as-is (the HTTP layer encodes them).
        assertEquals("Mary Jane", fields["firstName"])
        assertEquals("O'Brien", fields["lastName"])
        assertEquals("ALL", fields["fixed"])
    }

    @Test
    fun buildFetchUrl_blankFirst_throwsForQueryTemplate() {
        val src = source(
            kind = SourceKind.QUERY_TEMPLATE,
            urlTemplate = "https://x.example/?f={first}&l={last}"
        )
        assertThrows(IllegalArgumentException::class.java) {
            SourceUrlBuilder.buildFetchUrl(src, "", "Doe")
        }
    }

    @Test
    fun buildFetchUrl_manualLandingAllowsBlankNames() {
        val src = source(
            kind = SourceKind.MANUAL_LANDING,
            urlTemplate = "https://x.example/landing"
        )
        val url = SourceUrlBuilder.buildFetchUrl(src, "", "")
        assertEquals("https://x.example/landing", url)
    }

    @Test
    fun buildFetchUrl_rosterPageWithoutSlots_returnsTemplateAsIs() {
        val src = source(
            kind = SourceKind.ROSTER_PAGE,
            urlTemplate = "https://x.example/roster.html"
        )
        val url = SourceUrlBuilder.buildFetchUrl(src, "", "")
        assertEquals("https://x.example/roster.html", url)
    }
}
