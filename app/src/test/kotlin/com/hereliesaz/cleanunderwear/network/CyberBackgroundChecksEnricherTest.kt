package com.hereliesaz.cleanunderwear.network

import com.hereliesaz.cleanunderwear.data.Target
import com.hereliesaz.cleanunderwear.domain.BrowserMission
import com.hereliesaz.cleanunderwear.domain.LookupOption
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CyberBackgroundChecksEnricherTest {

    private val enricher = CyberBackgroundChecksEnricher()

    private fun target(
        displayName: String = "Jane Roe",
        phone: String? = null,
        email: String? = null,
        residence: String? = null,
        areaCode: String? = null,
    ) = Target(
        displayName = displayName,
        phoneNumber = phone,
        email = email,
        residenceInfo = residence,
        areaCode = areaCode,
    )

    // ---- parseFindings: detail page captures middle name + DOB ----

    @Test
    fun parseFindings_detailPage_capturesMiddleNameAndDob() {
        val detailHtml = """
            <html><body>
              <h1 class="full-name">John Alan Smith</h1>
              <div class="current-address">100 Oak St, Austin, TX 78701</div>
              <a href="tel:+15125550199" class="phone">(512) 555-0199</a>
              <span class="dob">Age 45</span>
            </body></html>
        """.trimIndent()

        val f = enricher.parseFindings(detailHtml)
        assertNotNull(f)
        assertEquals("John Alan Smith", f!!.name)
        assertEquals("Alan", f.middleName)
        // A bare age is pinned to a birth year so it doesn't read as "45" forever.
        assertEquals("~${java.time.Year.now().value - 45}", f.dob)
    }

    @Test
    fun parseFindings_blankPage_returnsNull() {
        assertNull(enricher.parseFindings(""))
    }

    @Test
    fun parseFindings_noResultsPage_doesNotAdoptHeaderAsName() {
        // A "No Results Found" page has no result card, so root falls back to
        // <body>. The generic h1/h2/h3 fallback must NOT fire there, otherwise
        // the contact would be falsely renamed to the page header.
        val noResults = """
            <html><body>
              <h1>No Results Found</h1>
              <p>We couldn't find anyone matching your search.</p>
            </body></html>
        """.trimIndent()
        assertNull(enricher.parseFindings(noResults))
    }

    @Test
    fun parseFindings_cardWithPlainHeader_stillCapturesName() {
        // Inside a real result card the generic header fallback is still allowed.
        val cardHtml = """
            <html><body>
              <div class="person-card">
                <h2>Jane Roe</h2>
                <span class="phone">555-123-4567</span>
              </div>
            </body></html>
        """.trimIndent()
        val f = enricher.parseFindings(cardHtml)
        assertNotNull(f)
        assertEquals("Jane Roe", f!!.name)
    }

    @Test
    fun merge_gapFillsMiddleNameAndDob_nonDestructively() {
        val existing = target(displayName = "John Smith").copy(middleName = "Existing")
        val findings = CyberBackgroundChecksEnricher.Findings(
            name = "John Alan Smith",
            address = null,
            phone = null,
            middleName = "Alan",
            dob = "45"
        )
        val merged = enricher.merge(existing, findings)
        // Existing middle name is preserved (non-destructive); DOB fills the gap.
        assertEquals("Existing", merged.middleName)
        assertEquals("45", merged.dateOfBirth)
    }

    // ---- pickAllMissions ----

    @Test
    fun pickAllMissions_allFieldsPresent_returnsFourMissionsInPriorityOrder() {
        val t = target(
            displayName = "Jane Roe",
            phone = "+1 (555) 123-4567",
            email = "jane@example.com",
            residence = "1 Main St, Anytown, NY 10001",
        )

        val missions = enricher.pickAllMissions(t)

        assertEquals(4, missions.size)
        assertTrue(missions[0] is BrowserMission.CbcByPhone)
        assertTrue(missions[1] is BrowserMission.CbcByEmail)
        assertTrue(missions[2] is BrowserMission.CbcByAddress)
        assertTrue(missions[3] is BrowserMission.CbcByName)
    }

    @Test
    fun pickAllMissions_singleWordName_dropsNameMission() {
        val missions = enricher.pickAllMissions(target(displayName = "Jane"))
        assertTrue(missions.isEmpty())
    }

    @Test
    fun pickAllMissions_phoneTooShort_dropsPhoneMission() {
        val missions = enricher.pickAllMissions(
            target(displayName = "Jane Roe", phone = "555-12")
        )
        assertEquals(1, missions.size)
        assertTrue(missions[0] is BrowserMission.CbcByName)
    }

    @Test
    fun pickAllMissions_placeholderName_dropsNameMission() {
        val missions = enricher.pickAllMissions(
            target(displayName = "Unnamed Entity", email = "x@y.com")
        )
        assertEquals(1, missions.size)
        assertTrue(missions[0] is BrowserMission.CbcByEmail)
    }

    // ---- mergeAll ----

    @Test
    fun mergeAll_consensusWinsOverHigherPrioritySingleton() {
        val t = target(displayName = "Unnamed Entity")
        val phoneFinding = CyberBackgroundChecksEnricher.Findings(
            name = "Alpha", address = null, phone = null
        )
        val emailFinding = CyberBackgroundChecksEnricher.Findings(
            name = "Bravo", address = null, phone = null
        )
        val addrFinding = CyberBackgroundChecksEnricher.Findings(
            name = "Bravo", address = null, phone = null
        )

        val merged = enricher.mergeAll(
            t,
            listOf(
                BrowserMission.CbcByPhone("5551234567") to phoneFinding,
                BrowserMission.CbcByEmail("a@b.com") to emailFinding,
                BrowserMission.CbcByAddress("1 Main") to addrFinding,
            )
        )

        // "Bravo" appears twice (email + address), "Alpha" only once (phone),
        // so consensus picks "Bravo" even though phone is the highest source.
        assertEquals("Bravo", merged.displayName)
    }

    @Test
    fun mergeAll_allDistinct_picksHighestPrioritySource() {
        val t = target(displayName = "Unnamed Entity")
        val results = listOf(
            BrowserMission.CbcByPhone("5551234567") to
                CyberBackgroundChecksEnricher.Findings("From Phone", null, null),
            BrowserMission.CbcByEmail("a@b.com") to
                CyberBackgroundChecksEnricher.Findings("From Email", null, null),
            BrowserMission.CbcByAddress("1 Main") to
                CyberBackgroundChecksEnricher.Findings("From Addr", null, null),
            BrowserMission.CbcByName("Jane Roe") to
                CyberBackgroundChecksEnricher.Findings("From Name", null, null),
        )

        val merged = enricher.mergeAll(t, results)
        assertEquals("From Phone", merged.displayName)
    }

    @Test
    fun mergeAll_doesNotOverwriteExistingTargetFields() {
        val t = target(
            displayName = "Real Name",
            phone = "5559999999",
            residence = "Existing Address",
        )
        val results = listOf(
            BrowserMission.CbcByPhone("5559999999") to
                CyberBackgroundChecksEnricher.Findings(
                    name = "Different Name",
                    address = "Different Address",
                    phone = "5550000000",
                ),
        )

        val merged = enricher.mergeAll(t, results)

        assertEquals("Real Name", merged.displayName)        // not a placeholder
        assertEquals("5559999999", merged.phoneNumber)        // existing wins
        assertEquals("Existing Address", merged.residenceInfo)
    }

    @Test
    fun mergeAll_emptyResults_leavesTargetUnchangedExceptDerivedFields() {
        val t = target(displayName = "Jane Roe", phone = "5551234567")
        val merged = enricher.mergeAll(t, emptyList())
        assertEquals("Jane Roe", merged.displayName)
        assertEquals("5551234567", merged.phoneNumber)
    }

    @Test
    fun mergeAll_fillsNullAreaCodeFromConsensusPhone() {
        val t = target(displayName = "Unnamed Entity")
        val results = listOf(
            BrowserMission.CbcByPhone("5551234567") to
                CyberBackgroundChecksEnricher.Findings(
                    name = null, address = null, phone = "(212) 555-7777"
                ),
        )
        val merged = enricher.mergeAll(t, results)
        assertEquals("212", merged.areaCode)
    }

    // ---- A real results list ----
    //
    // CyberBackgroundChecks' name-search results as the app's browser shows them, with invented
    // people: each person a `div.card.card-hover` (name in `.name-given`, the age in `.age`, which
    // reads "Deceased" for someone listed as dead, the address in `a.address` followed by a county
    // note, phones as `a.phone`), plus unnamed ad widgets that share the `.card-hover` class.

    private val cbcResultsList = """
        <html><head><script>
          (function(){var a=document.createElement('script');
          a.src='/cdn-cgi/challenge-platform/h/b/scripts/jsd/abc123/main.js';document.head.appendChild(a);})();
        </script></head><body>
        <h4>3 results for <b>Jane Roe</b></h4>
        <div class="search-results__content">
        <div class="card card-hover"><div class="card-header"><h2 class="mb-0">
          <a class="name-link" href="/detail/jane-roe/1"><span class="name-given"> Jane Roe </span></a>
          <span class="age-label">Age: </span><span class="badge badge-danger age">Deceased</span></h2></div>
          <div class="card-body"><h3 class="section-label"><span class="address-current-label">Lives at</span></h3>
          <span>No addresses available</span></div></div>
        <div class="card card-hover wam-pro-max-widget"><h2>Find out more</h2><span class="age">Deceased</span></div>
        <div class="card card-hover"><div class="card-header"><h2 class="mb-0">
          <a class="name-link" href="/detail/jane-a-roe/2"><span class="name-given">Jane A Roe</span></a>
          <span class="age-label">Age: </span><span class="age">40</span></h2></div>
          <div class="card-body">
            <p class="address-current max-lines-1 d-inline"><a class="address" href="/address/x">12 Elm St, Springfield, IL 62701 1234</a>
              <small class="text-muted county">[Sangamon County]</small></p>
            <span class="phones-label">Phones</span> <a class="phone" href="#">(217) 555-0142</a> <a class="phone" href="#">(217) 555-0199</a>
            <span class="relatives-label">Related to</span> <a class="relative" href="/d/3">Rhea Roe</a>
          </div></div>
        <div class="card card-hover"><div class="card-header"><h2 class="mb-0">
          <a class="name-link" href="/detail/jane-b-roe/3"><span class="name-given">Jane B Roe</span></a>
          <span class="age-label">Age: </span><span class="age">24</span></h2></div>
          <div class="card-body"><p class="address-current"><a class="address" href="/address/y">9 Oak Ave, Austin, TX 78701 5555</a>
            <small class="county">[Travis County]</small></p></div></div>
        </div></body></html>
    """.trimIndent()

    @Test
    fun parsePeople_readsEveryNamedCardAndSkipsTheAdWidgets() {
        val people = enricher.parsePeople(cbcResultsList)
        assertEquals(listOf("Jane Roe", "Jane A Roe", "Jane B Roe"), people.map { it.name })
    }

    @Test
    fun parsePeople_readsAddressWithoutTheCountyNoteAndThePhones() {
        val people = enricher.parsePeople(cbcResultsList)
        val a = people[1]
        assertEquals("12 Elm St, Springfield, IL 62701 1234", a.address)
        assertEquals("2175550142", a.phone)
        assertEquals("Jane", a.name!!.split(" ").first())
        assertEquals("A", a.middleName)
        assertEquals("9 Oak Ave, Austin, TX 78701 5555", people[2].address)
    }

    @Test
    fun parsePeople_turnsAnAgeIntoABirthYearAndKeepsDeceasedOutOfIt() {
        val people = enricher.parsePeople(cbcResultsList)
        val thisYear = java.time.Year.now().value
        assertEquals("~${thisYear - 40}", people[1].dob)
        assertEquals("~${thisYear - 24}", people[2].dob)
        // "Deceased" fills the age slot for the first person: not an age, but a flag.
        assertNull(people[0].dob)
        assertTrue(people[0].deceased)
        assertFalse(people[1].deceased)
    }

    @Test
    fun theResultsPageIsNeitherNobodyNorABlock() {
        assertFalse(enricher.foundNobody(cbcResultsList))
        assertFalse(enricher.looksLikeBlock(cbcResultsList))
    }

    @Test
    fun options_carryTheDeceasedFlagToWhatTheUserChoosesFrom() {
        val found = enricher.parsePeople(cbcResultsList).map { BrowserMission.CbcByName("Jane Roe") to it }
        val options = enricher.options(target(displayName = "Jane Roe"), found)
        assertTrue(options.single { it.name == "Jane Roe" }.deceased)
        assertFalse(options.single { it.name == "Jane A Roe" }.deceased)
        val adopted = enricher.adopt(target(displayName = "Jane Roe"), options.single { it.name == "Jane Roe" })
        assertTrue(adopted.enrichmentProvenance!!.endsWith("listed as deceased"))
    }

    // ---- Each person's own page ----
    //
    // Invented, in the markup a result card uses (the page's own markup is assumed to reuse it:
    // describeDetail's log line says which parts a real page gave, to tune these against).

    private val cbcDetailPage = """
        <html><head><script>var a = 1;</script></head><body>
        <nav>Menu <a class="phone" href="#">(999) 555-0000</a></nav>
        <h1><span class="name-given">Jane A Roe</span> <span class="age-label">Age:</span> <span class="age">40</span></h1>
        <p class="address-current"><a class="address" href="/a/1">12 Elm St, Springfield, IL 62701 1234</a> <small>[Sangamon County]</small></p>
        <a class="address" href="/a/2">400 Pine Rd, Chicago, IL 60601 0001</a>
        <a class="address" href="/a/2">400 Pine Rd, Chicago, IL 60601 0001</a>
        <span class="phones-label">Phones</span>
        <a class="phone" href="#">(217) 555-0142</a> <a class="phone" href="#">(312) 555-0188</a> <a class="phone" href="#">(312) 555-0188</a>
        <a href="mailto:Jane.Roe@Example.com">Jane.Roe@Example.com</a> <span class="email">j***@example.com</span>
        <a class="relative" href="/detail/rhea-roe/9">Rhea Roe</a> <a class="relative" href="/detail/sam-roe/8">Sam Roe</a>
        <div class="card card-hover wam-pro-max-widget"><a class="phone" href="#">(000) 555-0999</a></div>
        <footer><a class="phone" href="#">(888) 555-0111</a></footer>
        </body></html>
    """.trimIndent()

    @Test
    fun parseDetail_readsEveryPhoneEmailAddressAndRelativeOnThePersonsPage() {
        val d = enricher.parseDetail(cbcDetailPage)!!
        assertEquals(listOf("2175550142", "3125550188"), d.phones)
        assertEquals(listOf("jane.roe@example.com"), d.emails)
        assertEquals(listOf("12 Elm St, Springfield, IL 62701 1234", "400 Pine Rd, Chicago, IL 60601 0001"), d.addresses)
        assertEquals(listOf("Rhea Roe", "Sam Roe"), d.relatives)
        assertEquals("~${java.time.Year.now().value - 40}", d.dob)
        assertFalse(d.deceased)
    }

    @Test
    fun parseDetail_ignoresTheNavigationFooterAndAdWidgets() {
        val d = enricher.parseDetail(cbcDetailPage)!!
        assertTrue(d.phones.none { it.startsWith("999") || it.startsWith("000") || it.startsWith("888") })
    }

    @Test
    fun parseDetail_aPageWithNothingOnItIsNull() {
        assertNull(enricher.parseDetail("<html><body><p>Not found</p></body></html>"))
        assertNull(enricher.parseDetail(""))
    }

    @Test
    fun parseDetail_readsDeceasedFromTheAgeSlot() {
        val d = enricher.parseDetail("<html><body><span class=\"age\">Deceased</span><a class=\"address\">1 Main St, Town, ST 11111</a></body></html>")!!
        assertTrue(d.deceased)
        assertNull(d.dob)
    }

    @Test
    fun describeDetail_namesThePartsNeverTheValues() {
        val d = enricher.parseDetail(cbcDetailPage)
        val line = enricher.describeDetail(cbcDetailPage, d)
        assertEquals("phones=2 emails=1 addresses=2 relatives=2 age/dob=\u2713", line)
    }

    @Test
    fun parsePeople_linksEachCardToItsOwnPageAndNotToARelative() {
        val html = """<html><body>
            <div class="card card-hover"><h2><a class="name-link" href="https://www.cyberbackgroundchecks.com/detail/jane-a-roe/pid1"><span class="name-given">Jane A Roe</span></a></h2>
              <a class="relative" href="https://www.cyberbackgroundchecks.com/detail/rhea-roe/pid9">Rhea Roe</a></div>
            <div class="card card-hover"><h2><span class="name-given">Jane B Roe</span></h2>
              <a class="relative" href="https://www.cyberbackgroundchecks.com/detail/rhea-roe/pid9">Rhea Roe</a>
              <a class="btn btn-primary" href="/detail/jane-b-roe/pid2?ref=x#top">VIEW DETAILS</a></div>
            </body></html>"""
        val people = enricher.parsePeople(html)
        assertEquals("https://www.cyberbackgroundchecks.com/detail/jane-a-roe/pid1", people[0].detailUrl)
        assertEquals("https://www.cyberbackgroundchecks.com/detail/jane-b-roe/pid2", people[1].detailUrl)
    }

    @Test
    fun safeDetailUrl_followsOnlyThisSitesDetailPagesOverHttps() {
        assertEquals("https://www.cyberbackgroundchecks.com/detail/x/y", enricher.safeDetailUrl("/detail/x/y"))
        assertEquals("https://www.cyberbackgroundchecks.com/detail/x/y", enricher.safeDetailUrl("https://WWW.cyberbackgroundchecks.com/detail/x/y?a=1#b"))
        for (bad in listOf(
            "http://www.cyberbackgroundchecks.com/detail/x/y",
            "https://evil.example.com/detail/x/y",
            "https://www.cyberbackgroundchecks.com.evil.example/detail/x/y",
            "https://www.cyberbackgroundchecks.com/address/x",
            "https://www.cyberbackgroundchecks.com/detail/",
            "javascript:alert(1)",
            "//evil.example.com/detail/x",
            "",
        )) assertNull(bad, enricher.safeDetailUrl(bad))
    }

    @Test
    fun options_matchOnEveryPhoneEmailAndAddressOnThePersonsPage() {
        val url = "https://www.cyberbackgroundchecks.com/detail/jane-a-roe/pid1"
        val listed = CyberBackgroundChecksEnricher.Findings(
            name = "Jane A Roe", address = "12 Elm St, Springfield, IL 62701 1234", phone = "2175550142", detailUrl = url
        )
        val other = CyberBackgroundChecksEnricher.Findings(
            name = "Jane B Roe", address = "9 Oak Ave, Austin, TX 78701 5555", phone = "5125550100",
            detailUrl = "https://www.cyberbackgroundchecks.com/detail/jane-b-roe/pid2"
        )
        val mission = BrowserMission.CbcByName("Jane Roe")
        // The contact's number is the person's SECOND phone, which the list did not show.
        val contact = target(displayName = "Jane Roe", phone = "312-555-0188", email = "jane.roe@example.com", residence = "400 Pine Rd, Chicago, IL 60601")
        val without = enricher.options(contact, listOf(mission to listed, mission to other))
        assertFalse(without.first { it.name == "Jane A Roe" }.fits.contains("Same phone number"))

        val details = mapOf(url to enricher.parseDetail(cbcDetailPage)!!)
        val with = enricher.options(contact, listOf(mission to listed, mission to other), details)
        val jane = with.first { it.name == "Jane A Roe" }
        assertTrue(jane.fits.contains("Same phone number"))
        assertTrue(jane.fits.contains("Same email address"))
        assertTrue(jane.fits.contains("Same city or ZIP"))
        assertEquals("Jane A Roe", with.first().name)
        // What the page added is carried for the user to choose by.
        assertEquals(listOf("2175550142", "3125550188"), jane.phones)
        assertEquals(listOf("Rhea Roe", "Sam Roe"), jane.relatives)
        assertEquals(listOf("400 Pine Rd, Chicago, IL 60601 0001"), jane.otherAddresses)
        assertNull(with.first { it.name == "Jane B Roe" }.phones)
    }

    @Test
    fun adopt_fillsAnEmailGapFromThePersonsPageButNeverOverwrites() {
        val option = LookupOption(
            "Jane A Roe", "A", null, "12 Elm St, Springfield, IL", null, listOf("name"), emptyList(), emptyList(),
            emails = listOf("j***@example.com", "jane.roe@example.com"), phones = listOf("2175550142")
        )
        val filled = enricher.adopt(target(displayName = "Unnamed Entity"), option)
        assertEquals("jane.roe@example.com", filled.email)
        assertEquals("2175550142", filled.phoneNumber?.filter { it.isDigit() })
        val kept = enricher.adopt(target(displayName = "Jane Roe", email = "mine@example.com"), option)
        assertEquals("mine@example.com", kept.email)
    }

    // ---- looksLikeBlock ----

    @Test
    fun looksLikeBlock_emptyHtml_isBlocked() {
        assertTrue(enricher.looksLikeBlock(""))
        assertTrue(enricher.looksLikeBlock("   \n  "))
    }

    @Test
    fun looksLikeBlock_captchaMarker_isBlocked() {
        assertTrue(enricher.looksLikeBlock("<html><body>Please solve the CAPTCHA</body></html>"))
        assertTrue(enricher.looksLikeBlock("<html>verify you are human</html>"))
        assertTrue(enricher.looksLikeBlock("<script src='/cdn-cgi/challenge-platform/h/g/orchestrate/jsch/v1'></script>"))
    }

    @Test
    fun looksLikeBlock_normalResultPage_isNotBlocked() {
        val html = """
            <html><body>
              <div class="person-card">
                <h2 class="full-name">Jane Roe</h2>
                <span class="phone">555-123-4567</span>
              </div>
            </body></html>
        """.trimIndent()
        assertFalse(enricher.looksLikeBlock(html))
    }

    @Test
    fun pickAllMissions_malformedEmail_dropsEmailMission() {
        // A bare "@" in a contact's email field used to start a search that could only be empty.
        for (bad in listOf("@", "name@", "@example.com", "a@b", "a b@example.com", "a@@example.com")) {
            val missions = enricher.pickAllMissions(target(displayName = "Jane Roe", email = bad))
            assertTrue(bad, missions.none { it is BrowserMission.CbcByEmail })
            assertEquals(bad, null, enricher.pickMission(target(displayName = "Unnamed Entity", email = bad)))
        }
        assertTrue(enricher.pickAllMissions(target(displayName = "Jane Roe", email = " jane@example.co.uk ")).any { it is BrowserMission.CbcByEmail })
    }

    // ---- Sanity: pickAllMissions feeds reasonable URLs ----

    @Test
    fun pickAllMissions_phoneMissionUrlStripsCountryCode() {
        val missions = enricher.pickAllMissions(
            target(displayName = "Jane Roe", phone = "+1 555-123-4567")
        )
        val phoneMission = missions.first { it is BrowserMission.CbcByPhone } as BrowserMission.CbcByPhone
        // The mission stores the raw digit string; the URL builder applies the
        // strip. Check the URL the mission resolves to.
        assertEquals(
            "https://www.cyberbackgroundchecks.com/phone/555-123-4567",
            phoneMission.initialUrl
        )
    }

    @Test
    fun pickAllMissions_emailMissionUrlPreservesAtSign() {
        val missions = enricher.pickAllMissions(
            target(displayName = "Jane Roe", email = "jane@example.com")
        )
        val emailMission = missions.first { it is BrowserMission.CbcByEmail } as BrowserMission.CbcByEmail
        assertEquals(
            "https://www.cyberbackgroundchecks.com/email/jane@example.com",
            emailMission.initialUrl
        )
    }

    @Test
    fun mergeAll_returnsNullForFieldNoSourceProvided() {
        val t = target(displayName = "Unnamed Entity")
        val merged = enricher.mergeAll(
            t,
            listOf(
                BrowserMission.CbcByPhone("5551234567") to
                    CyberBackgroundChecksEnricher.Findings(
                        name = null, address = null, phone = null
                    )
            )
        )
        assertEquals("Unnamed Entity", merged.displayName)
        assertNull(merged.phoneNumber)
        assertNull(merged.residenceInfo)
    }

    // ---- enrich (verify-before-merge) ----

    @Test
    fun enrich_phoneLookupResolvesPlaceholderIdentity_isVerifiedAndAdoptsName() {
        val t = target(displayName = "Unnamed Entity")
        val outcome = enricher.enrich(
            t,
            listOf(
                BrowserMission.CbcByPhone("5551234567") to
                    CyberBackgroundChecksEnricher.Findings(
                        name = "Jane Roe", address = "1 Main St", phone = null
                    )
            )
        )
        assertTrue(outcome.verified)
        assertEquals("Jane Roe", outcome.target.displayName)
        assertEquals("1 Main St", outcome.target.residenceInfo)
        assertNotNull(outcome.target.enrichmentProvenance)
    }

    @Test
    fun enrich_nameSearchConsistentWithRealName_isVerifiedAndFillsGaps() {
        val t = target(displayName = "John Smith", phone = "512-555-0199")
        val outcome = enricher.enrich(
            t,
            listOf(
                BrowserMission.CbcByName("John Smith") to
                    CyberBackgroundChecksEnricher.Findings(
                        name = "John Smith", address = "5 Oak St", phone = "(512) 555-0100"
                    )
            )
        )
        assertTrue(outcome.verified)
        assertEquals("John Smith", outcome.target.displayName)
        assertEquals("5 Oak St", outcome.target.residenceInfo)
    }

    @Test
    fun enrich_nameSearchWithNothingElseInCommon_isNotMerged() {
        // Whoever the site ranks first among every John Smith is a guess, not the contact.
        val t = target(displayName = "John Smith", phone = "512-555-0199")
        val outcome = enricher.enrich(
            t,
            listOf(
                BrowserMission.CbcByName("John Smith") to
                    CyberBackgroundChecksEnricher.Findings(
                        name = "John Smith", address = "9 Elm St, Tulsa, OK", phone = "918-555-0100", dob = "1950"
                    )
            )
        )
        assertFalse(outcome.verified)
        assertEquals(null, outcome.target.dateOfBirth)
    }

    @Test
    fun enrich_conflictingPhoneCardContributesNothing() {
        // A recycled number's new owner must not supply the address, even when a name card verifies.
        val t = target(displayName = "John Smith", phone = "512-555-0199")
        val outcome = enricher.enrich(
            t,
            listOf(
                BrowserMission.CbcByPhone("5125550199") to
                    CyberBackgroundChecksEnricher.Findings(name = "Mary Jones", address = "1 Stranger Rd", phone = null),
                BrowserMission.CbcByEmail("j@x.com") to
                    CyberBackgroundChecksEnricher.Findings(name = "John Smith", address = "5 Oak St", phone = null)
            )
        )
        assertTrue(outcome.verified)
        assertEquals("5 Oak St", outcome.target.residenceInfo)
    }

    @Test
    fun names_shortFormsOnlyMatchTrueLongForms() {
        assertTrue(enricher.namesConsistent("Ron Smith", "Ronald Smith"))
        assertFalse(enricher.namesConsistent("Eric Smith", "Erica Smith"))
        assertFalse(enricher.namesConsistent("Don Smith", "Donna Smith"))
    }

    @Test
    fun enrich_phoneCardNameConflictsWithRealName_isRejectedAndNotMerged() {
        // Recycled phone number: the card belongs to a different person, so
        // nothing must be written into the contact.
        val t = target(displayName = "John Smith")
        val outcome = enricher.enrich(
            t,
            listOf(
                BrowserMission.CbcByPhone("5551234567") to
                    CyberBackgroundChecksEnricher.Findings(
                        name = "Bob Jones", address = "9 Elm Ave", phone = "5551234567"
                    )
            )
        )
        assertFalse(outcome.verified)
        assertEquals("John Smith", outcome.target.displayName)
        assertNull(outcome.target.residenceInfo)
        assertTrue(outcome.provenance.contains("conflict"))
    }

    @Test
    fun enrich_placeholderWithOnlyAddressCard_isRejected() {
        val t = target(displayName = "Unnamed Entity")
        val outcome = enricher.enrich(
            t,
            listOf(
                BrowserMission.CbcByAddress("1 Main St") to
                    CyberBackgroundChecksEnricher.Findings(
                        name = "Stranger Person", address = "1 Main St", phone = "5550001111"
                    )
            )
        )
        assertFalse(outcome.verified)
        assertEquals("Unnamed Entity", outcome.target.displayName)
        assertNull(outcome.target.phoneNumber)
    }

    @Test
    fun enrich_firstInitialMatchCountsAsConsistent() {
        // "Jon Smith" vs "Jonathan Smith" — same last name, matching first
        // initial (a common nickname/long-form variation).
        val t = target(displayName = "Jon Smith", phone = "512-555-0199")
        val outcome = enricher.enrich(
            t,
            listOf(
                BrowserMission.CbcByName("Jon Smith") to
                    CyberBackgroundChecksEnricher.Findings(
                        name = "Jonathan Smith", address = "5 Oak St", phone = "512-555-0199"
                    )
            )
        )
        assertTrue(outcome.verified)
        assertEquals("5 Oak St", outcome.target.residenceInfo)
    }

    @Test
    fun enrich_sharedFirstInitialButDifferentName_isRejected() {
        // "James Smith" must NOT merge into "John Smith" just because they share
        // the surname and first initial 'J'.
        val t = target(displayName = "John Smith")
        val outcome = enricher.enrich(
            t,
            listOf(
                BrowserMission.CbcByName("John Smith") to
                    CyberBackgroundChecksEnricher.Findings(
                        name = "James Smith", address = "5 Oak St", phone = null
                    )
            )
        )
        assertFalse(outcome.verified)
        assertNull(outcome.target.residenceInfo)
    }

    @Test
    fun enrich_emptyResults_isRejectedWithProvenance() {
        val outcome = enricher.enrich(target(displayName = "Jane Roe"), emptyList())
        assertFalse(outcome.verified)
        assertEquals("no results parsed", outcome.provenance)
    }
}
