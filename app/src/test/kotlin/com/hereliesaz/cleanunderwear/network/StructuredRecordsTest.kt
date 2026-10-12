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

    @Test
    fun `responsive cells drop their repeated column label`() {
        val html = """
            <table><thead><tr><th>IDOC Number</th><th>Last Name</th><th>First</th><th>Birth Year</th><th>Status</th></tr></thead>
            <tr><td><div class="col-header">IDOC Number</div>111194</td><td><div class="col-header">Last Name</div>Smith</td>
            <td><div class="col-header">First Name</div>John</td><td><div class="col-header">Birth Year</div>1991</td>
            <td><div class="col-header">Status</div>In custody</td></tr></table>
        """
        val s = source(emptyMap()).copy(
            resultFormat = ResultFormat.HTML_TABLE,
            custodyPattern = "In custody",
            tableColumns = mapOf("id" to "IDOC", "last" to "Last", "first" to "^First", "dob" to "Birth", "status" to "Status")
        )
        val r = StructuredRecords.fromTable(html, s, "John", "Smith", EventKind.CUSTODY, "u").single()
        assertEquals(1991, r.birthYear)
        assertEquals("xx_doc:111194", r.recordKey)
    }

    @Test
    fun `empty release date means held, not a fallback to the row text`() {
        val f = mapOf("records" to "records", "name" to "name", "status" to "release_date", "dob" to "dob")
        val body = """{"records":[{"name":"SMITH, JOHN LEE","dob":"1980-01-29","release_date":null},
            {"name":"SMITH, JOHN","dob":"1970-01-01","release_date":"2026-01-02"}]}"""
        val r = StructuredRecords.fromJson(body, source(f, released = "\\d"), "John", "Smith", EventKind.CUSTODY, "u").single()
        assertEquals(1980, r.birthYear)
    }

    @Test
    fun `court rows keep only recent criminal cases`() {
        StructuredRecords.today = { java.time.LocalDate.of(2026, 10, 12) }
        val html = """
            <table><tr><th>Party Name</th><th>Case Number</th><th>Case Type</th><th>Filed</th></tr>
            <tr><td>SMITH, JOHN A</td><td>CR-1</td><td>Criminal Felony</td><td>09/30/2026</td></tr>
            <tr><td>SMITH, JOHN</td><td>CV-2</td><td>Civil</td><td>09/30/2026</td></tr>
            <tr><td>SMITH, JOHN</td><td>CR-3</td><td>Criminal Misdemeanor</td><td>01/15/2019</td></tr></table>
        """
        val s = source(emptyMap()).copy(
            resultFormat = ResultFormat.HTML_TABLE,
            eventKind = EventKind.ARREST,
            caseTypePattern = "criminal",
            maxAgeDays = 365,
            tableColumns = mapOf("name" to "Party", "id" to "Case Number", "case_type" to "Case Type", "date" to "Filed")
        )
        val r = StructuredRecords.fromTable(html, s, "John", "Smith", EventKind.ARREST, "u").single()
        assertEquals("xx_doc:CR-1", r.recordKey)
        StructuredRecords.today = { java.time.LocalDate.now() }
    }

    @Test
    fun `date formats`() {
        assertEquals(java.time.LocalDate.of(2026, 9, 3), StructuredRecords.parseDate("9/3/2026 12:00:00 AM"))
        assertEquals(java.time.LocalDate.of(2026, 9, 3), StructuredRecords.parseDate("2026-09-03"))
        assertEquals(java.time.LocalDate.of(2026, 9, 3), StructuredRecords.parseDate("Filed Sep. 3, 2026"))
    }

    @Test
    fun `regex records split and field-match a non-table page`() {
        val html = """
            <select><option>A1234  |  10-31-1987  |  SMITH, AARON</option>
            <option>B5678  |  01-02-1990  |  SMITH, JOHN</option>
            <option>C9012  |  05-05-1980  |  JONES, MARY</option></select>
        """
        val s = source(emptyMap()).copy(
            resultFormat = ResultFormat.REGEX_RECORDS,
            recordRegex = "([A-Z]\\d{3,6})\\s*\\|\\s*(\\d{2}-\\d{2}-\\d{4})\\s*\\|\\s*([^<|]+?)</option>",
            fieldRegexes = mapOf("id" to "^([A-Z]\\d{3,6})", "dob" to "(\\d{2}-\\d{2}-\\d{4})", "name" to "\\|\\s*([A-Za-z, ]+?)</option>")
        )
        val r = StructuredRecords.fromRegex(html, s, "John", "Smith", EventKind.CUSTODY, "u").single()
        assertEquals("xx_doc:B5678", r.recordKey)
        assertEquals(1990, r.birthYear)
    }
}
