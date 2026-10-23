package com.hereliesaz.cleanunderwear.match

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RecordExtractorTest {

    private fun extract(text: String, kind: EventKind = EventKind.DEATH) =
        RecordExtractor.extract(text, "John", "Smith", kind, "src", "Source", "https://example.org/x")

    @Test
    fun `news style age and city`() {
        val r = extract("Police said John Smith, 45, of Metairie, was booked Tuesday.", EventKind.ARREST).single()
        assertEquals(45, r.age)
        assertEquals("Metairie", r.city)
    }

    @Test
    fun `obituary lifespan gives birth year and age`() {
        val r = extract("John Smith 1961 - 2026 passed away peacefully in Kenner, Louisiana.").single()
        assertEquals(1961, r.birthYear)
        assertEquals(65, r.age)
        assertEquals("Kenner", r.city)
        assertEquals("LA", r.state)
    }

    @Test
    fun `middle name between first and last`() {
        val r = extract("John Robert Smith, age 70, died Monday.").single()
        assertEquals("Robert", r.middleName)
        assertEquals(70, r.age)
    }

    @Test
    fun `middle initial with period`() {
        assertEquals("R", extract("John R. Smith died.").single().middleName)
    }

    @Test
    fun `inverted roster name`() {
        val r = extract("SMITH, JOHN  Booked 10/01  Age: 33", EventKind.CUSTODY).single()
        assertEquals(33, r.age)
    }

    @Test
    fun `survivors full names and bare first names`() {
        val r = extract(
            "John Smith, 80, of Gretna died. He is survived by his wife, Mary; his son Peter Jones; " +
                "and many grandchildren. Services will be held Friday."
        ).single()
        assertTrue(r.inferredRelatives.contains("Mary Smith"))
        assertTrue(r.relatives.contains("Peter Jones"))
        assertTrue("Mary Smith" !in r.relatives)
    }

    @Test
    fun `no survivors outside death records`() {
        val r = extract("John Smith, survived by his wife, Mary.", EventKind.ARREST).single()
        assertTrue(r.relatives.isEmpty())
    }

    @Test
    fun `state alone is not a city`() {
        val p = RecordExtractor.place(" of Louisiana died")
        assertNull(p?.city)
        assertEquals("LA", p?.state)
    }

    @Test
    fun `different first name does not match`() {
        assertTrue(extract("Jane Smith, 45, of Metairie").isEmpty())
    }

    @Test
    fun `two separated mentions yield two records`() {
        val filler = " lorem".repeat(200)
        assertEquals(2, extract("John Smith, 30, of Slidell.$filler John Smith, 72, of Houma.").size)
    }
}
