package com.hereliesaz.cleanunderwear.network

import com.hereliesaz.cleanunderwear.match.EventKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZonedDateTime

class NewsFeedTest {

    private val now = ZonedDateTime.parse("2026-10-11T00:00:00Z")

    private fun item(title: String, date: String = "Thu, 01 Oct 2026 07:00:00 GMT", publisher: String = "Legacy") = """
        <item>
          <title>$title</title>
          <link>https://news.google.com/rss/articles/${title.hashCode()}?oc=5</link>
          <pubDate>$date</pubDate>
          <description>&lt;a href="https://x"&gt;$title&lt;/a&gt;&amp;nbsp;&amp;nbsp;&lt;font color="#6f6f6f"&gt;$publisher&lt;/font&gt;</description>
          <source url="https://x">$publisher</source>
        </item>
    """

    private fun feed(vararg items: String) = "<rss><channel>${items.joinToString("")}</channel></rss>"

    @Test
    fun `obituary headline yields a death record with lifespan and place`() {
        val c = NewsFeed.candidates(
            feed(item("John Smith Obituary (1961 - 2026) - Kenner, LA - Legacy")),
            "John", "Smith", "google_news_us", "Google News", now
        ).single()
        assertEquals(EventKind.DEATH, c.kind)
        assertEquals(1961, c.birthYear)
        assertEquals("Kenner", c.city)
        assertEquals("LA", c.state)
        assertEquals("google_news_us:legacy", c.sourceId)
        assertTrue(c.evidenceUrl!!.startsWith("https://news.google.com/"))
    }

    @Test
    fun `arrest headline yields an arrest record`() {
        val c = NewsFeed.candidates(
            feed(item("John Smith, 45, of Metairie arrested after chase", publisher = "WWL")),
            "John", "Smith", "google_news_us", "Google News", now
        ).single()
        assertEquals(EventKind.ARREST, c.kind)
        assertEquals(45, c.age)
        assertEquals("Metairie", c.city)
    }

    @Test
    fun `old arrests are skipped but old obituaries are kept`() {
        val old = "Mon, 01 Jan 2018 07:00:00 GMT"
        val out = NewsFeed.candidates(
            feed(
                item("John Smith arrested on warrant", date = old),
                item("John Smith Obituary (1940 - 2018) - Houma, LA", date = old)
            ),
            "John", "Smith", "g", "Google News", now
        )
        assertEquals(listOf(EventKind.DEATH), out.map { it.kind })
    }

    @Test
    fun `irrelevant headlines and other people are ignored`() {
        val out = NewsFeed.candidates(
            feed(item("John Smith wins regional chess title"), item("Jane Smith Obituary (1950 - 2026)")),
            "John", "Smith", "g", "Google News", now
        )
        assertTrue(out.isEmpty())
    }

    @Test
    fun `publisher town fills a headline with no place`() {
        val c = NewsFeed.candidates(
            feed(item("John Smith Obituary", publisher = "Russell Funeral Home | winston-salem, nc")),
            "John", "Smith", "g", "Google News", now
        ).single()
        assertEquals("Winston-Salem", c.city)
        assertEquals("NC", c.state)
    }

    @Test
    fun `classification prefers obituary words`() {
        assertEquals(EventKind.DEATH, NewsFeed.classify("John Smith obituary: retired police officer"))
        assertEquals(EventKind.ARREST, NewsFeed.classify("Man charged in Kenner burglary"))
        assertEquals(EventKind.DEATH, NewsFeed.classify("Local teacher dies at 70"))
        assertNull(NewsFeed.classify("Smith named principal"))
    }

    @Test
    fun `a death notice naming the contact by title or funeral is kept`() {
        for (title in listOf("Funeral services for John Smith, 82, of Kenner", "Longtime coach John Smith dies at 70")) {
            val found = NewsFeed.candidates(feed(item(title)), "John", "Smith", "google_news_us", "Google News", now)
            assertEquals(title, 1, found.size)
            assertEquals(EventKind.DEATH, found.single().kind)
        }
    }

    @Test
    fun `an official speaking about a death or an arrest is not the subject`() {
        assertTrue(NewsFeed.namedInRole("Man dies in crash, Sheriff John Smith said", "John", "Smith", EventKind.DEATH))
        assertTrue(NewsFeed.namedInRole("Judge John Smith sentenced the man", "John", "Smith", EventKind.ARREST))
        assertTrue(NewsFeed.namedInRole("Man arrested at the funeral of John Smith", "John", "Smith", EventKind.ARREST))
    }
}
