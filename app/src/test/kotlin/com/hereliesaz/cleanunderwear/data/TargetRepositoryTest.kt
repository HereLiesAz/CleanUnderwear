package com.hereliesaz.cleanunderwear.data

import androidx.paging.PagingSource
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class TargetRepositoryTest {

    private val fakeDao = object : TargetDao {
        var targets = mutableListOf<Target>()

        private fun toLite(t: Target): TargetLite = TargetLite(
            id = t.id,
            displayName = t.displayName,
            phoneNumber = t.phoneNumber,
            areaCode = t.areaCode,
            status = t.status,
            email = t.email,
            lastStatusChangeTimestamp = t.lastStatusChangeTimestamp,
            lastScrapedTimestamp = t.lastScrapedTimestamp
        )

        private fun toWorkInfo(t: Target): TargetWorkInfo = TargetWorkInfo(
            id = t.id,
            displayName = t.displayName,
            phoneNumber = t.phoneNumber,
            email = t.email,
            areaCode = t.areaCode,
            status = t.status,
            residenceInfo = t.residenceInfo,
            sourceAccount = t.sourceAccount,
            monitorabilityState = t.monitorabilityState,
            lastScrapedTimestamp = t.lastScrapedTimestamp
        )

        private fun toSourceInfo(t: Target): TargetSourceInfo = TargetSourceInfo(
            id = t.id,
            areaCode = t.areaCode,
            residenceInfo = t.residenceInfo,
            lockupUrl = t.lockupUrl,
            obituaryUrl = t.obituaryUrl
        )

        override fun getAllTargetsLite(): Flow<List<TargetLite>> = flowOf(targets.map(::toLite))

        override fun searchTargets(
            query: String,
            showIgnored: Boolean,
            googleF: Boolean?,
            metaF: Boolean?,
            appleF: Boolean?,
            deviceF: Boolean?,
            namelessF: Boolean?,
            emailOnlyF: Boolean?,
            hasEmailF: Boolean?,
            hasAddressF: Boolean?,
            pendingEnrichF: Boolean?,
            sort: String
        ): PagingSource<Int, TargetLite> = throw NotImplementedError("not exercised in unit tests")

        override suspend fun getTargetsPaged(limit: Int, offset: Int): List<TargetLite> =
            targets.drop(offset).take(limit).map(::toLite)

        override suspend fun getTargetWorkInfoPaged(limit: Int, offset: Int): List<TargetWorkInfo> =
            targets.drop(offset).take(limit).map(::toWorkInfo)

        override suspend fun getAllTargetWorkInfo(): List<TargetWorkInfo> = targets.map(::toWorkInfo)

        override suspend fun getAllTargetSourceInfo(): List<TargetSourceInfo> =
            targets.map(::toSourceInfo)

        override suspend fun getTargetById(id: Int): Target? = targets.firstOrNull { it.id == id }

        override fun observeTargetById(id: Int): Flow<Target?> =
            flowOf(targets.firstOrNull { it.id == id })

        override suspend fun getTargetsByIds(ids: List<Int>): List<Target> =
            targets.filter { it.id in ids }

        override suspend fun getTargetsByMonitorability(state: MonitorabilityState): List<Target> =
            targets.filter { it.monitorabilityState == state }

        override suspend fun getReadyDueTargets(now: Long): List<Target> = targets.filter {
            it.monitorabilityState == MonitorabilityState.READY &&
                it.status != TargetStatus.IGNORED &&
                it.nextScheduledCheck <= now
        }

        override fun getAllTargets(): Flow<List<Target>> = flowOf(targets)

        override fun getTargetsByStatus(status: TargetStatus): Flow<List<Target>> =
            flowOf(targets.filter { it.status == status })

        override suspend fun updateMonitorabilityState(id: Int, state: MonitorabilityState) {
            targets.replaceAll { if (it.id == id) it.copy(monitorabilityState = state) else it }
        }

        override suspend fun updateMonitorabilityStateBatch(ids: List<Int>, state: MonitorabilityState) {
            targets.replaceAll {
                if (it.id in ids) it.copy(monitorabilityState = state) else it
            }
        }

        override suspend fun updateStatusOnly(id: Int, status: TargetStatus, timestamp: Long) {
            targets.replaceAll {
                if (it.id == id) it.copy(status = status, lastStatusChangeTimestamp = timestamp)
                else it
            }
        }

        override suspend fun updateUrls(id: Int, lockupUrl: String?, obituaryUrl: String?) {
            targets.replaceAll {
                if (it.id == id) it.copy(lockupUrl = lockupUrl, obituaryUrl = obituaryUrl) else it
            }
        }

        override suspend fun insertTarget(target: Target) { targets.add(target) }
        override suspend fun insertTargets(newTargets: List<Target>) { targets.addAll(newTargets) }
        override suspend fun updateTarget(target: Target) {
            targets.replaceAll { if (it.id == target.id) target else it }
        }
        override suspend fun deleteTarget(target: Target) {
            targets.removeAll { it.id == target.id }
        }
        override suspend fun wipeSlateClean() { targets.clear() }
    }

    private val repository = OfflineTargetRepository(fakeDao)

    @Test
    fun repository_insertAndGetAll_worksCorrectly() = runTest {
        val target = Target(id = 1, displayName = "John Doe", phoneNumber = "123", areaCode = "123")
        repository.insertTarget(target)

        repository.getAllTargets().collect { list ->
            assertEquals(1, list.size)
            assertEquals("John Doe", list[0].displayName)
        }
    }

    @Test
    fun repository_update_worksCorrectly() = runTest {
        val target = Target(id = 1, displayName = "John Doe", phoneNumber = "123", areaCode = "123")
        repository.insertTarget(target)

        val updatedTarget = target.copy(status = TargetStatus.INCARCERATED)
        repository.updateTarget(updatedTarget)

        repository.getTargetsByStatus(TargetStatus.INCARCERATED).collect { list ->
            assertEquals(1, list.size)
            assertEquals(TargetStatus.INCARCERATED, list[0].status)
        }
    }
}
