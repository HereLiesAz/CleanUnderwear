package com.hereliesaz.cleanunderwear.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Parser tests reach into private parse methods via reflection. We exercise the Jsoup branches
 * only — the live browser sessions are out of scope for unit tests.
 */
class HarvesterParseTest {

    @Test
    fun facebook_mbasicFriendsRows_extractDisplayNames() {
        val html = """
            <html><body>
              <table>
                <tr><td><a href="/alice.smith">Alice Smith</a></td></tr>
                <tr><td><a href="/profile.php?id=42">Bob Jones</a></td></tr>
                <tr><td><a href="/about/">About</a></td></tr>
                <tr><td><a href="/charlie">See more</a></td></tr>
              </table>
            </body></html>
        """.trimIndent()

        val targets = FacebookHarvester().parseFriendsHtml(html)

        val names = targets.map { it.displayName }.toSet()
        assertTrue("Alice Smith was missed: $names", "Alice Smith" in names)
        assertTrue("Bob Jones was missed: $names", "Bob Jones" in names)
        assertTrue("Navigation 'See more' should be filtered: $names", "See more" !in names)
        assertTrue(targets.all { it.sourceAccount == "Meta (Facebook)" })
    }

    @Test
    fun whatsapp_chatList_extractsTitlesIncludingPhoneOnlyEntries() {
        val html = """
            <html><body>
              <div role="grid" aria-label="Chat list">
                <div role="listitem"><span title="Alice"></span></div>
                <div role="listitem"><span title="+1 555 0000"></span></div>
                <div role="listitem"><span dir="auto">Bob</span></div>
              </div>
            </body></html>
        """.trimIndent()

        val targets = invokeParse(WhatsAppHarvester(), "parseChatListHtml", html)

        val names = targets.map { it.displayName }.toSet()
        assertTrue("Alice missing: $names", "Alice" in names)
        assertTrue("Phone-only entry missing: $names", "+1 555 0000" in names)
        assertTrue("Bob missing: $names", "Bob" in names)

        val phoneEntry = targets.first { it.displayName == "+1 555 0000" }
        assertEquals("+15550000", phoneEntry.phoneNumber)
    }

    @Test
    fun instagram_followerDialog_extractsHandles() {
        val html = """
            <html><body>
              <div role="dialog">
                <div>
                  <div>
                    <a role="link" href="/alice/"><span>alice</span></a>
                    <span>Alice Wonderland</span>
                  </div>
                  <div>
                    <a role="link" href="/bob/"><span>bob</span></a>
                  </div>
                  <a role="link" href="/explore/">Explore</a>
                </div>
              </div>
            </body></html>
        """.trimIndent()

        val targets = invokeParse(InstagramHarvester(), "parseFollowersHtml", html)

        val sources = targets.map { it.sourceAccount }.toSet()
        assertTrue("alice handle missed: $sources", "Meta (Instagram: @alice)" in sources)
        assertTrue("bob handle missed: $sources", "Meta (Instagram: @bob)" in sources)
        assertTrue("Explore link must be filtered: $sources", sources.none { it?.contains("explore") == true })
    }

    @Test
    fun google_contactsGrid_extractsPhonesAndEmails() {
        val html = """
            <html><body>
              <div role="grid">
                <div role="row">
                  <div role="columnheader">Name</div>
                </div>
                <div role="row">
                  <div role="gridcell">Alice Wonderland</div>
                  <div role="gridcell">alice@example.com</div>
                  <div role="gridcell">+1 (555) 123-4567</div>
                </div>
                <div role="row">
                  <div role="gridcell">Bob Builder</div>
                  <div role="gridcell">bob@builder.io</div>
                </div>
              </div>
            </body></html>
        """.trimIndent()

        val targets = invokeParse(GoogleContactsHarvester(), "parseContactsHtml", html)

        assertEquals(2, targets.size)
        val alice = targets.first { it.displayName == "Alice Wonderland" }
        assertEquals("+15551234567", alice.phoneNumber)
        assertEquals("alice@example.com", alice.email)

        val bob = targets.first { it.displayName == "Bob Builder" }
        assertEquals("bob@builder.io", bob.email)
        assertEquals(null, bob.phoneNumber)
    }

    @Suppress("UNCHECKED_CAST")
    private fun invokeParse(target: Any, methodName: String, html: String): List<Target> {
        val method = target.javaClass.declaredMethods.first { it.name == methodName }
        method.isAccessible = true
        val doc = org.jsoup.Jsoup.parse(html)
        return method.invoke(target, doc) as List<Target>
    }
}
