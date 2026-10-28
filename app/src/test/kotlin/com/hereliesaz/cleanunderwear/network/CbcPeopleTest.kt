package com.hereliesaz.cleanunderwear.network

import com.hereliesaz.cleanunderwear.data.Target
import com.hereliesaz.cleanunderwear.domain.BrowserMission
import com.hereliesaz.cleanunderwear.domain.LookupChoice
import com.hereliesaz.cleanunderwear.domain.LookupChoiceStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** A results list is read whole, and the user picks from it: everyone on it, best fit first. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CbcPeopleTest {

    private val enricher = CyberBackgroundChecksEnricher()

    private val list = """
        <html><body>
          <div class="person-card"><h2 class="name">Dana Marie Reyes</h2><span>Age 44</span>
            <div class="address">12 Elm St, New Orleans, LA 70130</div><a class="phone">(504) 555-0909</a>
            <div class="person-card"><span class="name">Rosa Reyes</span></div>
          </div>
          <div class="person-card"><h2 class="name">Dana Reyes</h2><span>Age 71</span>
            <div class="address">9 Oak Ave, Tulsa, OK 74103</div></div>
          <div class="person-card"><h2 class="name">Reyes, Dana</h2><span>Age 44</span>
            <div class="address">12 Elm St, New Orleans, LA 70130</div></div>
          <div class="person-card"><div class="address">no name here</div></div>
        </body></html>
    """.trimIndent()

    @Test
    fun everyCardIsRead_relativesAndNamelessCardsAreNot_repeatsAreOne() {
        val people = enricher.parsePeople(list)
        // The relative inside the first card, the nameless card, and the repeat (same name, age
        // and address, written "Last, First") are not separate people.
        assertEquals(listOf("Dana Marie Reyes", "Dana Reyes"), people.map { it.name })
        assertEquals("Marie", people[0].middleName)
        assertEquals("5045550909", people[0].phone)
        assertTrue(people[1].dob!!.startsWith("~"))
    }

    @Test
    fun optionsPutWhatFitsFirst_andSayWhy() {
        val target = Target(displayName = "Dana Reyes", phoneNumber = "5045550909", areaCode = "504")
        val people = enricher.parsePeople(list)
        val found = people.map { BrowserMission.CbcByName("Dana Reyes") to it } +
            (BrowserMission.CbcByPhone("5045550909") to people[0])
        val options = enricher.options(target, found)
        assertEquals(2, options.size)
        val best = options.first()
        assertEquals("Dana Marie Reyes", best.name)
        assertEquals(listOf("name", "phone"), best.foundBy)
        assertTrue(best.fits.containsAll(listOf("Same name", "Same phone number")))
        assertTrue(options[1].fits == listOf("Same name"))
    }

    @Test
    fun aStrangersCard_isOfferedButSaysTheNameDiffers() {
        val target = Target(displayName = "Dana Reyes", phoneNumber = "5045550909")
        val stranger = CyberBackgroundChecksEnricher.Findings("Mark Stone", null, "5045550909")
        val option = enricher.options(target, listOf(BrowserMission.CbcByPhone("5045550909") to stranger)).single()
        assertEquals(listOf("A different name"), option.differs)
    }

    @Test
    fun pickingFillsGaps_andNeverOverwrites() {
        val target = Target(displayName = "Dana Reyes", phoneNumber = "5045550101", residenceInfo = "Metairie, LA")
        val option = enricher.options(target, enricher.parsePeople(list).take(1).map { BrowserMission.CbcByName("Dana Reyes") to it }).single()
        val after = enricher.adopt(target, option)
        assertEquals("5045550101", after.phoneNumber)
        assertEquals("Metairie, LA", after.residenceInfo)
        assertEquals("Marie", after.middleName)
        assertTrue(after.dateOfBirth!!.startsWith("~"))
        assertTrue(after.enrichmentProvenance!!.contains(CyberBackgroundChecksEnricher.CHOSEN_PREFIX))
    }

    @Test
    fun choicesSurviveTheAppClosing_andGoWhenAnswered() {
        val context = RuntimeEnvironment.getApplication()
        val option = enricher.options(Target(displayName = "Dana Reyes"), enricher.parsePeople(list).map { BrowserMission.CbcByName("Dana Reyes") to it })
        LookupChoiceStore(context).put(LookupChoice(7, "Dana Reyes", option))
        LookupChoiceStore(context).put(LookupChoice(8, "Al Reyes", option.take(1)))
        val reopened = LookupChoiceStore(context).load()
        assertEquals(listOf(7, 8), reopened.map { it.targetId })
        assertEquals(option, reopened.first().options)
        assertEquals(listOf(8), LookupChoiceStore(context).remove(setOf(7)).map { it.targetId })
        LookupChoiceStore(context).clear()
        assertTrue(LookupChoiceStore(context).load().isEmpty())
    }
}
