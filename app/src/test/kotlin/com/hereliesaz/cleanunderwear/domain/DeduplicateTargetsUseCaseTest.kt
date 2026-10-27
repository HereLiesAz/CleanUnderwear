package com.hereliesaz.cleanunderwear.domain

import androidx.paging.PagingSource
import com.hereliesaz.cleanunderwear.data.MergeLedger
import com.hereliesaz.cleanunderwear.data.MergeRecord
import com.hereliesaz.cleanunderwear.data.MergeSignature
import com.hereliesaz.cleanunderwear.data.MonitorabilityState
import com.hereliesaz.cleanunderwear.data.Target
import com.hereliesaz.cleanunderwear.data.TargetLite
import com.hereliesaz.cleanunderwear.data.TargetRepository
import com.hereliesaz.cleanunderwear.data.TargetSourceInfo
import com.hereliesaz.cleanunderwear.data.TargetStatus
import com.hereliesaz.cleanunderwear.data.TargetWorkInfo
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DeduplicateTargetsUseCaseTest {

    /** In-memory repository exercising only the reads/mutations the use case touches. */
    private class FakeRepository(initial: List<Target>) : TargetRepository {
        val store = initial.toMutableList()

        private fun lite(t: Target) = TargetLite(
            id = t.id,
            displayName = t.displayName,
            phoneNumber = t.phoneNumber,
            areaCode = t.areaCode,
            status = t.status,
            email = t.email,
            lastStatusChangeTimestamp = t.lastStatusChangeTimestamp,
            lastScrapedTimestamp = t.lastScrapedTimestamp
        )

        override suspend fun getTargetsPaged(limit: Int, offset: Int): List<TargetLite> =
            store.drop(offset).take(limit).map(::lite)

        override suspend fun getTargetsByIds(ids: List<Int>): List<Target> =
            store.filter { it.id in ids }

        override suspend fun updateTarget(target: Target) {
            val idx = store.indexOfFirst { it.id == target.id }
            if (idx >= 0) store[idx] = target
        }

        override suspend fun deleteTarget(target: Target) {
            store.removeAll { it.id == target.id }
        }

        // --- Unused by dedup ---
        override fun getAllTargetsLite(): Flow<List<TargetLite>> = throw NotImplementedError()
        override fun searchTargets(
            query: String, showIgnored: Boolean, googleF: Boolean?, metaF: Boolean?,
            appleF: Boolean?, deviceF: Boolean?, namelessF: Boolean?, emailOnlyF: Boolean?,
            hasEmailF: Boolean?, hasAddressF: Boolean?, pendingEnrichF: Boolean?, sort: String
        ): PagingSource<Int, TargetLite> = throw NotImplementedError()
        override suspend fun getTargetWorkInfoPaged(limit: Int, offset: Int): List<TargetWorkInfo> = throw NotImplementedError()
        override suspend fun getAllTargetSourceInfo(): List<TargetSourceInfo> = throw NotImplementedError()
        override suspend fun getTargetById(id: Int): Target? = throw NotImplementedError()
        override fun observeTargetById(id: Int): Flow<Target?> = throw NotImplementedError()
        override suspend fun getTargetsByMonitorability(state: MonitorabilityState): List<Target> = throw NotImplementedError()
        override suspend fun getReadyDueTargets(now: Long, limit: Int): List<Target> = throw NotImplementedError()
        override fun getAllTargets(): Flow<List<Target>> = throw NotImplementedError()
        override fun getTargetsByStatus(status: TargetStatus): Flow<List<Target>> = throw NotImplementedError()
        override suspend fun updateMonitorabilityState(id: Int, state: MonitorabilityState) = throw NotImplementedError()
        override suspend fun updateMonitorabilityStateBatch(ids: List<Int>, state: MonitorabilityState) = throw NotImplementedError()
        override suspend fun updateStatusOnly(id: Int, status: TargetStatus, timestamp: Long) = throw NotImplementedError()
        override suspend fun updateUrls(id: Int, lockupUrl: String?, obituaryUrl: String?) = throw NotImplementedError()
        override suspend fun insertTarget(target: Target) = throw NotImplementedError()
        override suspend fun insertTargets(targets: List<Target>) = throw NotImplementedError()
        override suspend fun wipeSlateClean() = throw NotImplementedError()
    }

    private class FakeLedger(val vetoes: Set<String> = emptySet()) : MergeLedger {
        val records = mutableListOf<MergeRecord>()
        val reassigned = mutableListOf<Pair<Int, Int>>()
        override suspend fun record(entry: MergeRecord) { records += entry }
        override suspend fun vetoedPairs(): Set<String> = vetoes
        override suspend fun reassignSurvivor(oldId: Int, newId: Int) { reassigned += oldId to newId }
    }

    @Test
    fun samePhoneAndName_areMerged() = runTest {
        val repo = FakeRepository(
            listOf(
                Target(id = 1, displayName = "John Smith", phoneNumber = "555-123-4567"),
                Target(id = 2, displayName = "John Smith", phoneNumber = "(555) 123-4567")
            )
        )

        val merged = DeduplicateTargetsUseCase(repo).invoke()

        assertEquals(1, merged)
        assertEquals(1, repo.store.size)
    }

    @Test
    fun confirmedStatusSurvivesAFresherCopy() = runTest {
        // The re-harvested copy has more fields, but the confirmed status and the dismissed
        // false positive live on the old row; neither may be lost.
        val repo = FakeRepository(
            listOf(
                Target(
                    id = 1, displayName = "Jane Doe", phoneNumber = "555-123-4567",
                    status = TargetStatus.DECEASED, dismissedMatchKeys = "bop:123", dateOfBirth = "1960"
                ),
                Target(
                    id = 2, displayName = "Jane Doe", phoneNumber = "555-123-4567",
                    status = TargetStatus.UNKNOWN, residenceInfo = "Austin, TX", email = "jane@example.com"
                )
            )
        )
        val ledger = FakeLedger()

        DeduplicateTargetsUseCase(repo, ledger = ledger).invoke()

        val survivor = repo.store.single()
        assertEquals(TargetStatus.DECEASED, survivor.status)
        assertEquals("bop:123", survivor.dismissedMatchKeys)
        assertEquals("1960", survivor.dateOfBirth)
        assertEquals("Austin, TX", survivor.residenceInfo)
        assertEquals(1, ledger.records.size)
    }

    @Test
    fun countryCodeFormatting_landsInOneBlock() = runTest {
        val repo = FakeRepository(
            listOf(
                Target(id = 1, displayName = "John Smith", phoneNumber = "+1 555-123-4567"),
                Target(id = 2, displayName = "John Smith", phoneNumber = "(555) 123-4567")
            )
        )

        assertEquals(1, DeduplicateTargetsUseCase(repo).invoke())
    }

    @Test
    fun recycledPhoneWithConflictingNames_areNotMerged() = runTest {
        // Same number, clearly different people — blocking proposes them, the scorer keeps them apart.
        val repo = FakeRepository(
            listOf(
                Target(id = 1, displayName = "John Smith", phoneNumber = "555-123-4567"),
                Target(id = 2, displayName = "Maria Gonzalez", phoneNumber = "555-123-4567")
            )
        )

        val merged = DeduplicateTargetsUseCase(repo).invoke()

        assertEquals(0, merged)
        assertEquals(2, repo.store.size)
    }

    @Test
    fun nameOnlyCollision_isNotMerged() = runTest {
        // No corroborating phone/email/address — a name alone is never enough to auto-merge.
        val repo = FakeRepository(
            listOf(
                Target(id = 1, displayName = "John Smith"),
                Target(id = 2, displayName = "John Smith")
            )
        )

        val merged = DeduplicateTargetsUseCase(repo).invoke()

        assertEquals(0, merged)
        assertEquals(2, repo.store.size)
    }

    @Test
    fun oversizedBlock_isSkippedForReview() = runTest {
        // Three rows that would otherwise collapse, but with the ceiling lowered to 2 the
        // candidate block (size 3) is treated as an ambiguous mass-collision and left untouched.
        val repo = FakeRepository(
            listOf(
                Target(id = 1, displayName = "John Smith", phoneNumber = "555-123-4567"),
                Target(id = 2, displayName = "John Smith", phoneNumber = "555-123-4567"),
                Target(id = 3, displayName = "John Smith", phoneNumber = "555-123-4567")
            )
        )

        val merged = DeduplicateTargetsUseCase(repo, maxPairwiseGroup = 2).invoke()

        assertEquals(0, merged)
        assertEquals(3, repo.store.size)
    }

    @Test
    fun recycledPhone_nicknameVariants_mergeOnlyWhenNicknamesWired() = runTest {
        // A heavily recycled phone (shared by many rows) is frequency-penalised toward the floor,
        // so the phone alone no longer forces a merge. Two rows that are the *same* person under
        // nickname expansion — "Robert Smith" / "Bob Smith" — then hinge entirely on whether the
        // scorer was handed a nickname map (production wires OnDeviceResearchAgent.getNicknames).
        // The unnamed filler rows only exist to inflate the phone's corpus frequency; they carry
        // no comparable name, so they never fuse with each other or with the Smiths.
        val phone = "555-123-4567"
        fun rows() = listOf(
            Target(id = 1, displayName = "Robert Smith", phoneNumber = phone),
            Target(id = 2, displayName = "Bob Smith", phoneNumber = phone)
        ) + (1..62).map { Target(id = 100 + it, displayName = "Unnamed Entity", phoneNumber = phone) }

        val nicknames: (String) -> List<String> = { name ->
            when (name.lowercase()) {
                "bob" -> listOf("robert")
                "robert" -> listOf("bob")
                else -> emptyList()
            }
        }

        // Without nickname expansion the variant first names are not comparable, so the recycled
        // phone is too weak on its own and the Smiths are left apart.
        val withoutNicknames = FakeRepository(rows())
        assertEquals(0, DeduplicateTargetsUseCase(withoutNicknames).invoke())
        assertEquals(64, withoutNicknames.store.size)

        // With nicknames wired, "Robert"/"Bob" agree on a full name and tip the pair over the bar.
        val withNicknames = FakeRepository(rows())
        assertEquals(1, DeduplicateTargetsUseCase(withNicknames, nicknames = nicknames).invoke())
        assertEquals(63, withNicknames.store.size)
    }

    @Test
    fun threeWaySharedPhone_collapseTransitively() = runTest {
        val repo = FakeRepository(
            listOf(
                Target(id = 1, displayName = "John Smith", phoneNumber = "555-123-4567"),
                Target(id = 2, displayName = "John Smith", phoneNumber = "555-123-4567"),
                Target(id = 3, displayName = "John Smith", phoneNumber = "555-123-4567")
            )
        )

        val merged = DeduplicateTargetsUseCase(repo).invoke()

        assertEquals(2, merged)
        assertEquals(1, repo.store.size)
    }

    @Test
    fun mergeOfDistinctRows_isRecordedWithItsEvidence() = runTest {
        val repo = FakeRepository(
            listOf(
                Target(id = 1, displayName = "John Smith", phoneNumber = "555-123-4567"),
                Target(id = 2, displayName = "John Smith", phoneNumber = "555-123-4567", email = "j@x.com")
            )
        )
        val ledger = FakeLedger()

        val merged = DeduplicateTargetsUseCase(repo, ledger = ledger).invoke()

        assertEquals(1, merged)
        val record = ledger.records.single()
        assertEquals("DEDUP", record.origin)
        assertTrue(record.contributions.contains("phone|AGREE"))
        assertTrue(record.probability >= 0.95)
        // Whichever row won, the ledger holds the other one in full and what it changed on the winner.
        assertTrue(record.absorbedJson.contains("John Smith"))
        assertEquals(record.survivorId, repo.store.single().id)
    }

    @Test
    fun mergeIntoExactCopy_isNotRecorded() = runTest {
        val repo = FakeRepository(
            listOf(
                Target(id = 1, displayName = "John Smith", phoneNumber = "555-123-4567"),
                Target(id = 2, displayName = "John Smith", phoneNumber = "(555) 123-4567")
            )
        )
        val ledger = FakeLedger()

        assertEquals(1, DeduplicateTargetsUseCase(repo, ledger = ledger).invoke())
        assertTrue(ledger.records.isEmpty())
    }

    @Test
    fun pairTheUserSplit_isNeverMergedAgain() = runTest {
        val a = Target(id = 1, displayName = "John Smith", phoneNumber = "555-123-4567")
        val b = Target(id = 2, displayName = "John Smith", phoneNumber = "555-123-4567", email = "j@x.com")
        val repo = FakeRepository(listOf(a, b))
        val ledger = FakeLedger(vetoes = setOf(MergeSignature.vetoKey(MergeSignature.of(a), MergeSignature.of(b))))

        assertEquals(0, DeduplicateTargetsUseCase(repo, ledger = ledger).invoke())
        assertEquals(2, repo.store.size)
        assertTrue(ledger.records.isEmpty())
    }

    @Test
    fun staleNumber_doesNotVouchForAMerge() = runTest {
        // Same name and number would merge. Once the user ruled the number stale it is no longer
        // evidence, and a name alone is never enough.
        val repo = FakeRepository(
            listOf(
                Target(id = 1, displayName = "John Smith", phoneNumber = "555-123-4567", stalePhone = "555-123-4567"),
                Target(id = 2, displayName = "John Smith", phoneNumber = "555-123-4567", email = "j@x.com")
            )
        )

        assertEquals(0, DeduplicateTargetsUseCase(repo).invoke())
        assertEquals(2, repo.store.size)
    }

    @Test
    fun aHouseholdOfTwoOnOneLine_staysTwoContacts() = runTest {
        val repo = FakeRepository(
            listOf(
                Target(id = 1, displayName = "John Smith", phoneNumber = "504-555-0100", areaCode = "504"),
                Target(id = 2, displayName = "Mary Smith", phoneNumber = "504-555-0100", areaCode = "504"),
                Target(id = 3, displayName = "Mom", phoneNumber = "504-555-0199"),
                Target(id = 4, displayName = "Dad", phoneNumber = "504-555-0199")
            )
        )

        assertEquals(0, DeduplicateTargetsUseCase(repo).invoke())
        assertEquals(4, repo.store.size)
    }

    @Test
    fun anArchivedCopyKeepsWhatItWasBeforeArchiving() = runTest {
        // The restored row holds a pending match; a re-read copy says Archived. The merge stays
        // archived, but remembers the match, so "Bring back" returns it.
        val repo = FakeRepository(
            listOf(
                Target(
                    id = 1, displayName = "Jane Doe", phoneNumber = "555-123-4567",
                    status = TargetStatus.POSSIBLE_MATCH, matchKind = "DEATH", matchConfidence = 0.9
                ),
                Target(id = 2, displayName = "Jane Doe", phoneNumber = "555-123-4567", status = TargetStatus.IGNORED)
            )
        )

        DeduplicateTargetsUseCase(repo).invoke()

        val survivor = repo.store.single()
        assertEquals(TargetStatus.IGNORED, survivor.status)
        assertEquals(TargetStatus.POSSIBLE_MATCH, survivor.statusBeforeIgnore)
        assertEquals("DEATH", survivor.matchKind)
    }
}
