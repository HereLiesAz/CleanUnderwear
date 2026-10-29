package com.hereliesaz.cleanunderwear.domain

import com.hereliesaz.cleanunderwear.data.ContactHarvester
import com.hereliesaz.cleanunderwear.data.MonitorabilityState
import com.hereliesaz.cleanunderwear.data.Target
import com.hereliesaz.cleanunderwear.data.TargetRepository
import com.hereliesaz.cleanunderwear.data.TargetStatus
import com.hereliesaz.cleanunderwear.data.TargetWorkInfo
import com.hereliesaz.cleanunderwear.network.OnDeviceResearchAgent
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Reading the phone's contacts: what is added, what is recognised, and what is let go. */
class HarvestContactsUseCaseTest {

    private fun row(id: Int, name: String, phone: String?, source: String = "Google", stale: String? = null, status: TargetStatus = TargetStatus.MONITORING) =
        TargetWorkInfo(id, name, phone, null, null, status, null, source, MonitorabilityState.READY, 0, stale, null)

    private fun useCase(phoneBook: List<Target>, registry: List<TargetWorkInfo>, inserted: MutableList<Target>, deleted: MutableList<Int>, lookupSizes: MutableList<Int> = mutableListOf()): HarvestContactsUseCase {
        val harvester = mockk<ContactHarvester>()
        coEvery { harvester.harvestContacts(any()) } returns phoneBook
        val names = mockk<OnDeviceResearchAgent>()
        every { names.validatePersonName(any()) } returns true
        val repo = mockk<TargetRepository>(relaxed = true)
        coEvery { repo.getTargetWorkInfoPaged(any(), any()) } returns registry
        val batch = slot<List<Target>>()
        coEvery { repo.insertTargets(capture(batch)) } answers { inserted += batch.captured }
        coEvery { repo.getTargetsByIds(any()) } answers {
            lookupSizes += firstArg<List<Int>>().size
            firstArg<List<Int>>().map { Target(id = it, displayName = "x") }
        }
        coEvery { repo.deleteTarget(any()) } answers { deleted += firstArg<Target>().id }
        return HarvestContactsUseCase(harvester, repo, names)
    }

    private fun contact(name: String, phone: String) = Target(displayName = name, phoneNumber = phone, sourceAccount = "Google")

    @Test
    fun aReplacedNumberIsNotReadBackAsANewContact() = runBlocking {
        val inserted = mutableListOf<Target>()
        val registry = listOf(row(1, "Jane Doe", "504-555-0222", stale = "504-555-0111"))
        useCase(listOf(contact("Jane Doe", "504-555-0111")), registry, inserted, mutableListOf()).invoke()
        assertTrue(inserted.isEmpty())
    }

    @Test
    fun aNumberMarkedOutOfDateWithNoReplacementIsNotReadBackAsANewContact() = runBlocking {
        // Marked stale and nothing adopted: the row's own number is the stale one.
        val inserted = mutableListOf<Target>()
        val registry = listOf(row(1, "Jane Doe", "504-555-0111", stale = "504-555-0111"))
        useCase(listOf(contact("Jane Doe", "(504) 555-0111")), registry, inserted, mutableListOf()).invoke()
        assertTrue(inserted.isEmpty())
    }

    @Test
    fun aStaleNumberHeldByAnotherNameIsStillAddedForThatName() = runBlocking {
        val inserted = mutableListOf<Target>()
        val registry = listOf(row(1, "Jane Doe", "504-555-0111", stale = "504-555-0111"))
        useCase(listOf(contact("John Roe", "504-555-0111")), registry, inserted, mutableListOf()).invoke()
        assertEquals(listOf("John Roe"), inserted.map { it.displayName })
    }

    @Test
    fun aLargePruneLooksRowsUpInChunksSmallEnoughForSqlite() = runBlocking {
        val deleted = mutableListOf<Int>()
        val sizes = mutableListOf<Int>()
        // 5000 phone-book rows, 1100 of them gone (22%, under the 25% guard).
        val registry = (1..5000).map { row(it, "Person $it", "504-%07d".format(it)) }
        val phoneBook = registry.drop(1100).map { contact(it.displayName, it.phoneNumber!!) }
        val result = useCase(phoneBook, registry, mutableListOf(), deleted, sizes).invoke()
        assertEquals(1100, result.removed)
        assertEquals(1100, deleted.size)
        assertEquals(listOf(900, 200), sizes)
        assertTrue(sizes.all { it <= 900 })
    }

    @Test
    fun aContactDeletedFromThePhoneStopsBeingChecked_importsAndConfirmedStay() = runBlocking {
        val deleted = mutableListOf<Int>()
        val registry = listOf(
            row(1, "Kept Here", "504-555-0001"),
            row(2, "Gone Person", "504-555-0002"),
            row(3, "From Facebook", null, source = "Meta (Facebook)"),
            row(4, "Gone Confirmed", "504-555-0004", status = TargetStatus.DECEASED),
            row(5, "Also Here", "504-555-0005"), row(6, "Here Too", "504-555-0006"), row(7, "Still Here", "504-555-0007")
        )
        val phoneBook = listOf("Kept Here" to "504-555-0001", "Also Here" to "504-555-0005", "Here Too" to "504-555-0006", "Still Here" to "504-555-0007")
            .map { (n, p) -> contact(n, p) }
        val result = useCase(phoneBook, registry, mutableListOf(), deleted).invoke()
        assertEquals(listOf(2), deleted)
        assertEquals(1, result.removed)
    }

    @Test
    fun whenMuchOfTheListVanishesAtOnce_nothingIsRemoved() = runBlocking {
        val deleted = mutableListOf<Int>()
        val registry = (1..10).map { row(it, "Person $it", "504-555-00%02d".format(it)) }
        useCase(listOf(contact("Person 1", "504-555-0001")), registry, mutableListOf(), deleted).invoke()
        assertTrue(deleted.isEmpty())
    }
}
