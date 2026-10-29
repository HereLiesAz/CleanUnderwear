package com.hereliesaz.cleanunderwear.domain

import com.hereliesaz.cleanunderwear.data.WhatsAppHarvester
import com.hereliesaz.cleanunderwear.ui.BrowserExtractionGate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The sign-in handling of the four imports: telling a sign-in / link page from the page to read
 * (so the browser screen hands the former to the user, un-scrimmed, instead of scrimming it and
 * dumping it), and keeping the script and the watchdog coherent.
 */
class BrowserMissionSignInTest {

    private val imports = listOf(
        BrowserMission.HarvestFacebookFriends,
        BrowserMission.HarvestWhatsApp,
        BrowserMission.HarvestInstagram,
        BrowserMission.HarvestGoogleContacts,
    )

    private fun page(
        url: String,
        password: Boolean = false,
        qr: Boolean = false,
        chats: Boolean = false,
    ) = PageProbe(hasPasswordField = password, hasQrCode = qr, hasChatList = chats, url = url)

    // --- PageProbe ---

    @Test
    fun probeReadsWhatEvaluateJavascriptHandsBack() {
        val probe = PageProbe.parse("\"1|0|1|https:\\/\\/accounts.google.com\\/v3\\/signin?x=1|2\"")!!

        assertTrue(probe.hasPasswordField)
        assertFalse(probe.hasQrCode)
        assertTrue(probe.hasChatList)
        // The URL is last, so a '|' in it survives.
        assertEquals("https://accounts.google.com/v3/signin?x=1|2", probe.url)
        assertEquals("accounts.google.com", probe.host)
        assertEquals("/v3/signin", probe.path)
    }

    @Test
    fun probeOfNothingIsNull() {
        assertNull(PageProbe.parse(null))
        assertNull(PageProbe.parse("null"))
        assertNull(PageProbe.parse("\"\""))
        assertNull(PageProbe.parse("\"1|0\""))
    }

    @Test
    fun probeHostAndPathCopeWithOddUrls() {
        assertEquals("", page("about:blank").host)
        assertEquals("", page("").path)
        assertEquals("/", page("https://web.whatsapp.com").path)
        assertEquals("/", page("https://web.whatsapp.com?x=1").path)
        assertEquals("web.whatsapp.com", page("https://Web.WhatsApp.com:443/#frag").host)
        assertEquals("/login/", page("https://mbasic.facebook.com/login/?next=%2Fme").path)
        assertEquals("mbasic.facebook.com", page("https://user:pw@mbasic.facebook.com/login").host)
    }

    @Test
    fun probeScriptReturnsTheFourFieldsThatParseExpects() {
        // The script and the parser are written apart; keep them in step.
        // password flag | canvas flag | grid flag | url, '|'-joined, url last (it may hold a '|').
        assertEquals(3, Regex("\\+'\\|'\\+").findAll(PageProbe.SCRIPT).count())
        assertTrue(PageProbe.SCRIPT.endsWith("+location.href;})()"))
        // The selectors for the password field are the ones SignInPages.hasLoginForm looks for.
        assertTrue(PageProbe.SCRIPT.contains("input[type=password],input[name=pass],#login_form"))
        // No double quotes, so it is safe wherever a double-quoted string would be.
        assertFalse(PageProbe.SCRIPT.contains("\""))
    }

    // --- every import needs the user first; nothing else does ---

    @Test
    fun theFourImportsWaitForSignIn() {
        assertTrue(imports.all { it.requiresSignIn })
        assertTrue(imports.all { it.signInPrompt.isNotBlank() })
    }

    @Test
    fun lookupsAndBrowsingNeverWaitForSignIn() {
        val others = listOf(
            BrowserMission.CbcByPhone("5045550909"),
            BrowserMission.CbcByEmail("a@b.co"),
            BrowserMission.CbcByAddress("1 Main St"),
            BrowserMission.CbcByName("Dana Reyes"),
            BrowserMission.OpenInBrowser("https://example.com/", "Example"),
        )
        assertTrue(others.none { it.requiresSignIn })
        // And they would never call a page a sign-in page, whatever is on it.
        assertTrue(others.none { it.isSignInPage(page("https://example.com/login", password = true)) })
        assertTrue(others.none { it.looksSignedOut("<input type=\"password\">") })
    }

    // --- Facebook ---

    @Test
    fun facebookLoginAndCheckpointPagesAreSignIn() {
        val m = BrowserMission.HarvestFacebookFriends
        assertTrue(m.isSignInPage(page("https://mbasic.facebook.com/login.php?next=https%3A%2F%2Fmbasic.facebook.com%2Fme%2Ffriends")))
        assertTrue(m.isSignInPage(page("https://m.facebook.com/login/?next=%2Fme")))
        assertTrue(m.isSignInPage(page("https://mbasic.facebook.com/login/save-device/")))
        assertTrue(m.isSignInPage(page("https://www.facebook.com/checkpoint/1234/")))
        assertTrue(m.isSignInPage(page("https://www.facebook.com/recover/initiate")))
        assertTrue(m.isSignInPage(page("https://www.facebook.com/anything", password = true)))
    }

    @Test
    fun facebookFriendsListIsNotSignIn() {
        val m = BrowserMission.HarvestFacebookFriends
        assertFalse(m.isSignInPage(page("https://mbasic.facebook.com/me/friends")))
        assertFalse(m.isSignInPage(page("https://mbasic.facebook.com/profile.php?v=friends&id=42")))
        assertFalse(m.isSignInPage(page("https://www.facebook.com/")))
        // A friend whose vanity name merely contains the word is not a login page.
        assertFalse(m.isSignInPage(page("https://www.facebook.com/anna.login/friends")))
    }

    // --- WhatsApp ---

    @Test
    fun whatsAppIsSignInUntilTheChatListIsThere() {
        val m = BrowserMission.HarvestWhatsApp
        assertTrue(m.isSignInPage(page("https://web.whatsapp.com/", qr = true)))
        assertTrue(m.isSignInPage(page("https://web.whatsapp.com/")))
        // Bounced to a "use the app" page on another host.
        assertTrue(m.isSignInPage(page("https://www.whatsapp.com/download")))
        assertFalse(m.isSignInPage(page("https://web.whatsapp.com/", chats = true)))
        // Any stray canvas on the linked page does not unlink it.
        assertFalse(m.isSignInPage(page("https://web.whatsapp.com/", qr = true, chats = true)))
    }

    @Test
    fun whatsAppPromptSaysLinkWithPhoneNumber() {
        val prompt = BrowserMission.HarvestWhatsApp.signInPrompt
        assertTrue(prompt, prompt.contains("Link with phone number"))
        assertTrue(prompt, prompt.contains("second device"))
        // A code on this phone's own screen cannot be scanned by this phone.
        assertFalse(prompt, prompt.contains("with your phone"))
    }

    @Test
    fun whatsAppWatchdogOutlastsTheScript() {
        // The script gives up on its own (waiting for the list, then scrolling); the watchdog must
        // not call it stuck before it has answered.
        assertTrue(BrowserMission.HarvestWhatsApp.watchdogMs > WhatsAppHarvester.MAX_RUN_MS)
        assertEquals(WhatsAppHarvester.LIST_WAIT_MS + WhatsAppHarvester.SCROLL_BUDGET_MS, WhatsAppHarvester.MAX_RUN_MS)
    }

    @Test
    fun whatsAppScriptUsesItsBudgetsAndDoesNotNeedAMissingCanvas() {
        val script = WhatsAppHarvester.SCRIPT
        assertTrue(script.contains("waitForListMs = ${WhatsAppHarvester.LIST_WAIT_MS}"))
        assertTrue(script.contains("scrollBudgetMs = ${WhatsAppHarvester.SCROLL_BUDGET_MS}"))
        // "Linked" is the chat list being there, not the absence of any canvas on the page.
        assertFalse(script.contains("canvas"))
        // The old fixed 240 s budget, which ran out before the 300 s watchdog was even close.
        assertFalse(script.contains("240000"))
    }

    // --- Instagram ---

    @Test
    fun instagramLoginChallengeAndSignupAreSignIn() {
        val m = BrowserMission.HarvestInstagram
        assertTrue(m.isSignInPage(page("https://www.instagram.com/accounts/login/?next=%2Faccounts%2Fedit%2F")))
        assertTrue(m.isSignInPage(page("https://www.instagram.com/accounts/emailsignup/")))
        assertTrue(m.isSignInPage(page("https://www.instagram.com/challenge/action/xyz/")))
        assertTrue(m.isSignInPage(page("https://www.instagram.com/accounts/suspended/")))
        assertTrue(m.isSignInPage(page("https://www.instagram.com/", password = true)))
    }

    @Test
    fun instagramEditAndFollowersAreNotSignIn() {
        val m = BrowserMission.HarvestInstagram
        assertFalse(m.isSignInPage(page("https://www.instagram.com/accounts/edit/")))
        assertFalse(m.isSignInPage(page("https://www.instagram.com/dana/followers/")))
        // The post-login "save your login info" step carries on (the script goes on to the form).
        assertFalse(m.isSignInPage(page("https://www.instagram.com/accounts/onetap/?next=%2F")))
    }

    // --- Google ---

    @Test
    fun googleIsSignInAnywhereButTheContactsPage() {
        val m = BrowserMission.HarvestGoogleContacts
        assertTrue(m.isSignInPage(page("https://accounts.google.com/v3/signin/identifier")))
        assertTrue(m.isSignInPage(page("https://myaccount.google.com/signinoptions/rescuephone")))
        assertTrue(m.isSignInPage(page("https://contacts.google.com/", password = true)))
        assertFalse(m.isSignInPage(page("https://contacts.google.com/")))
        assertFalse(m.isSignInPage(page("https://contacts.google.com/u/0/?hl=en")))
        // No address yet (about:blank mid-navigation): not a verdict.
        assertFalse(m.isSignInPage(page("about:blank")))
    }

    // --- a dump that turns out to be a sign-in page ---

    @Test
    fun aDumpedLoginFormIsNotData() {
        assertTrue(SignInPages.hasLoginForm("<html><form><input type=\"password\" name=\"x\"></form></html>"))
        assertTrue(SignInPages.hasLoginForm("<input name=\"pass\" type=\"text\">"))
        assertTrue(SignInPages.hasLoginForm("<form id=\"login_form\" action=\"/login\"></form>"))
        assertFalse(SignInPages.hasLoginForm("<div role=\"grid\"><div role=\"row\">Dana Reyes</div></div>"))
        assertFalse(SignInPages.hasLoginForm(""))

        assertTrue(BrowserMission.HarvestFacebookFriends.looksSignedOut("<input type=\"password\">"))
        assertTrue(BrowserMission.HarvestInstagram.looksSignedOut("<input type=\"password\">"))
        assertFalse(BrowserMission.HarvestFacebookFriends.looksSignedOut("<a href=\"/dana\">Dana</a>"))
    }

    // --- the scripts themselves ---

    @Test
    fun everyImportScriptCanBeTaggedAndNoneNamesTheBridgeAfterwards() {
        val gate = BrowserExtractionGate("cuTest")
        for (m in imports) {
            val tagged = gate.tagScript(m.extractionScript, "ab12", "7-0")
            assertFalse("${m.label} still names the bridge", tagged.contains("HTMLOUT"))
            assertTrue("${m.label} doesn't dump through the closure", tagged.contains("cuDump("))
        }
    }

    @Test
    fun facebookScriptDoesNotDumpALoginPageOrReadOffTheFriendsList() {
        val script = BrowserMission.HarvestFacebookFriends.extractionScript
        // It checks for the login form before anything is collected...
        val guard = script.indexOf("input[name=pass]")
        val collect = script.indexOf("var collected")
        assertTrue(guard in 0 until collect)
        // ...and goes to the friends list from anywhere else.
        assertTrue(script.contains("/me/friends"))
        // So it must run again after that navigation.
        assertTrue(BrowserMission.HarvestFacebookFriends.rerunAfterNavigation)
    }

    @Test
    fun instagramScriptWaitsOnLoginPagesAndForTheHandle() {
        val script = com.hereliesaz.cleanunderwear.data.InstagramHarvester.SCRIPT
        assertTrue(script.contains("accounts\\/(login|emailsignup|suspended)"))
        assertTrue(script.contains("readHandle"))
        assertNotNull(script)
    }
}
