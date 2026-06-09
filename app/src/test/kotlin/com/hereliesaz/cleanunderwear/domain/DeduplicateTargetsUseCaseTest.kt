package com.hereliesaz.cleanunderwear.domain

import androidx.paging.PagingSource
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
        override suspend fun getReadyDueTargets(now: Long): List<Target> = throw NotImplementedError()
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
}
