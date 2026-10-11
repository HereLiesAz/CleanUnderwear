package com.hereliesaz.cleanunderwear.network

import com.hereliesaz.cleanunderwear.match.EventKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StructuredRecordsTest {

    private fun source(fields: Map<String, String>, released: String? = null) = Source(
        id = "xx_doc", label = "XX DOC", kind = SourceKind.QUERY_TEMPLATE, method = "GET",
        urlTemplate = "https://example.org/?l={last}", formFields = emptyMap(), render = RenderMode.BASIC,
        resultFormat = ResultFormat.JSON_RECORDS, renderSettleMs = 0, readySelector = null,
        evidenceUrlTemplate = "https://example.org/", scope = SourceScope.STATE,
        appliesToAreaCodes = emptySet(), coversCountry = "US", jsonFields = fields, releasedPattern = released
    )

    private val fields = mapOf(
        "records" to "data.offenders", "first" to "name.first", "last" to "name.last",
        "middle" to "name.middle", "age" to "age", "status" to "status", "id" to "docNumber"
    )

    private val body = """
        {"data":{"offenders":[
          {"name":{"first":"JOHN","last":"SMITH","middle":"ROBERT"},"age":"45","status":"Incarcerated","docNumber":"A1"},
          {"name":{"first":"JOHN","last":"SMITH"},"age":"60","status":"Released","docNumber":"A2"},
          {"name":{"first":"JOHNNY","last":"SMITH"},"age":"30","status":"Incarcerated","docNumber":"A3"}
        ]}}
    """

    @Test
    fun `maps fields, drops released and other first names`() {
        val out = StructuredRecords.fromJson(body, source(fields, "released|discharged"), "John", "Smith", EventKind.CUSTODY, "https://example.org/")
        val r = out.single()
        assertEquals("ROBERT", r.middleName)
        assertEquals(45, r.age)
        assertEquals("xx_doc:A1", r.recordKey)
    }

    @Test
    fun `without a released pattern every matching name is kept`() {
        assertEquals(2, StructuredRecords.fromJson(body, source(fields), "John", "Smith", EventKind.CUSTODY, "u").size)
    }

    @Test
    fun `top level array and malformed body`() {
        val f = mapOf("first" to "FirstName", "last" to "LastName")
        assertEquals(1, StructuredRecords.fromJson("""[{"FirstName":"John","LastName":"Smith"}]""", source(f), "John", "Smith", EventKind.CUSTODY, "u").size)
        assertTrue(StructuredRecords.fromJson("<html>", source(f), "John", "Smith", EventKind.CUSTODY, "u").isEmpty())
    }

    @Test
    fun `html table with combined name column and custody filter`() {
        val html = """
            <table><tr><th>DOC Number</th><th>Name</th><th>Age</th><th>Location</th></tr>
            <tr><td>331958</td><td>SMITH, JOHN E</td><td>37</td><td>Coyote Ridge</td></tr>
            <tr><td>2</td><td>SMITH, JOHN</td><td>50</td><td>INACTIVE-DSCHREL</td></tr>
            <tr><td>3</td><td>SMITH, JANE</td><td>40</td><td>Coyote Ridge</td></tr></table>
        """
        val s = source(emptyMap(), released = "INACTIVE|PAROLE").copy(
            resultFormat = ResultFormat.HTML_TABLE,
            tableColumns = mapOf("id" to "DOC", "name" to "^Name", "age" to "Age", "status" to "Location")
        )
        val r = StructuredRecords.fromTable(html, s, "John", "Smith", EventKind.CUSTODY, "u").single()
        assertEquals("E", r.middleName)
        assertEquals(37, r.age)
        assertEquals("xx_doc:331958", r.recordKey)
    }

    @Test
    fun `split name forms`() {
        assertEquals(Triple("JOHN", "MICHAEL", "SMITHERS"), StructuredRecords.splitName("SMITHERS, JOHN MICHAEL"))
        assertEquals(Triple("John", "Q", "Public"), StructuredRecords.splitName("John Q Public"))
        assertEquals(Triple("John", null, "Smith"), StructuredRecords.splitName("John Smith"))
    }
}
