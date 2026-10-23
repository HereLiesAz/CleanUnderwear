package com.hereliesaz.cleanunderwear.ui

import android.app.Application
import android.content.Context
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.hereliesaz.cleanunderwear.data.Target
import com.hereliesaz.cleanunderwear.data.TargetLite
import com.hereliesaz.cleanunderwear.data.TargetRepository
import com.hereliesaz.cleanunderwear.data.TargetStatus
import kotlinx.coroutines.flow.Flow
import com.hereliesaz.cleanunderwear.domain.AlternateFinder
import com.hereliesaz.cleanunderwear.domain.AlternateInfo
import com.hereliesaz.cleanunderwear.domain.BrowserMission
import com.hereliesaz.cleanunderwear.domain.DeduplicateTargetsUseCase
import com.hereliesaz.cleanunderwear.domain.HarvestContactsUseCase
import com.hereliesaz.cleanunderwear.domain.PipelineCoordinator
import com.hereliesaz.cleanunderwear.domain.SuspectCase
import com.hereliesaz.cleanunderwear.domain.SuspectReport
import com.hereliesaz.cleanunderwear.domain.SuspectReview
import com.hereliesaz.cleanunderwear.domain.TriageTargetsUseCase
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.hereliesaz.cleanunderwear.worker.ScrapingWorker
import dagger.hilt.android.lifecycle.HiltViewModel
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.cachedIn
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.Calendar
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import androidx.core.content.edit

/**
 * The puppet master. Now capable of forcing the issue for the chronically impatient.
 */
@HiltViewModel
class MainViewModel @Inject constructor(
    private val application: Application,
    private val repository: TargetRepository,
    private val harvestContactsUseCase: HarvestContactsUseCase,
    private val deduplicateTargetsUseCase: DeduplicateTargetsUseCase,
    private val triageTargetsUseCase: TriageTargetsUseCase,
    private val fbHarvester: com.hereliesaz.cleanunderwear.data.FacebookHarvester,
    private val whatsAppHarvester: com.hereliesaz.cleanunderwear.data.WhatsAppHarvester,
    private val instagramHarvester: com.hereliesaz.cleanunderwear.data.InstagramHarvester,
    private val googleContactsHarvester: com.hereliesaz.cleanunderwear.data.GoogleContactsHarvester,
    private val researchAgent: com.hereliesaz.cleanunderwear.network.OnDeviceResearchAgent,
    val sourceCatalog: com.hereliesaz.cleanunderwear.network.SourceCatalog,
    private val cbcEnricher: com.hereliesaz.cleanunderwear.network.CyberBackgroundChecksEnricher,
    private val pipelineCoordinator: PipelineCoordinator,
    private val suspectReview: SuspectReview,
    private val alternateFinder: AlternateFinder,
    private val updateChecker: com.hereliesaz.cleanunderwear.update.UpdateChecker,
    private val contactSyncer: com.hereliesaz.cleanunderwear.util.SystemContactSyncer
) : AndroidViewModel(application) {

    enum class SortOrder { NAME, STATUS, DATE }

    private val workManager = WorkManager.getInstance(application)

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery

    private val _sortOrder = MutableStateFlow(SortOrder.NAME)
    val sortOrder: StateFlow<SortOrder> = _sortOrder

    private val _showIgnored = MutableStateFlow(false)
    val showIgnored: StateFlow<Boolean> = _showIgnored

    private val _showNamelessFilter = MutableStateFlow<Boolean?>(null)
    val showNamelessFilter: StateFlow<Boolean?> = _showNamelessFilter

    private val _showEmailOnlyFilter = MutableStateFlow<Boolean?>(null)
    val showEmailOnlyFilter: StateFlow<Boolean?> = _showEmailOnlyFilter

    private val _hasEmailFilter = MutableStateFlow<Boolean?>(null)
    val hasEmailFilter: StateFlow<Boolean?> = _hasEmailFilter

    private val _hasAddressFilter = MutableStateFlow<Boolean?>(null)
    val hasAddressFilter: StateFlow<Boolean?> = _hasAddressFilter

    private val prefs = application.getSharedPreferences("clean_underwear_prefs", Context.MODE_PRIVATE)
    private val _isOnboardingCompleted = MutableStateFlow(prefs.getBoolean("onboarding_done", false))
    val isOnboardingCompleted: StateFlow<Boolean> = _isOnboardingCompleted

    private val _googleFilter = MutableStateFlow<Boolean?>(null)
    val googleFilter: StateFlow<Boolean?> = _googleFilter

    private val _metaFilter = MutableStateFlow<Boolean?>(null)
    val metaFilter: StateFlow<Boolean?> = _metaFilter

    private val _appleFilter = MutableStateFlow<Boolean?>(null)
    val appleFilter: StateFlow<Boolean?> = _appleFilter

    private val _deviceFilter = MutableStateFlow<Boolean?>(null)
    val deviceFilter: StateFlow<Boolean?> = _deviceFilter

    /**
     * null = show all; true = only NEEDS_ENRICHMENT/ENRICHMENT_FAILED; false = only READY.
     */
    private val _pendingEnrichmentFilter = MutableStateFlow<Boolean?>(null)
    val pendingEnrichmentFilter: StateFlow<Boolean?> = _pendingEnrichmentFilter

    private val _isDarkTheme = MutableStateFlow(prefs.getBoolean("dark_theme", true))
    val isDarkTheme: StateFlow<Boolean> = _isDarkTheme
    
    private val _showDiagnosticLog = MutableStateFlow(prefs.getBoolean("show_diagnostic_log", false))
    val showDiagnosticLog: StateFlow<Boolean> = _showDiagnosticLog

    val diagnosticLogs = com.hereliesaz.cleanunderwear.util.DiagnosticLogger.logs

    // 0 = no limit (the default): every contact is checked.
    private val _globalTargetLimit = MutableStateFlow(prefs.getInt("global_limit", 0))
    val globalTargetLimit: StateFlow<Int> = _globalTargetLimit

    private val _showManualEntryDialog = MutableStateFlow(false)
    val showManualEntryDialog: StateFlow<Boolean> = _showManualEntryDialog

    /**
     * The browser mission queue currently being shown to the user, if any.
     * When non-null, the UI overlays [com.hereliesaz.cleanunderwear.ui.BrowserScreen]
     * over the rest of the app. Single-mission flows (Facebook harvest, the
     * manual "Research" buttons in TargetDetailScreen) reuse this state via
     * [launchMission], which wraps the mission as a singleton list.
     *
     * `onMissionExtracted` fires once per page that produced HTML. `onCancel`
     * fires if the user backs out before the queue finishes. `onAllComplete`
     * fires once after the last mission resolves (extracted or skipped).
     */
    data class ActiveMissionBatch(
        val id: Long,
        val retainBrowserForResolve: Boolean,
        val missions: List<com.hereliesaz.cleanunderwear.domain.BrowserMission>,
        val onMissionExtracted: (com.hereliesaz.cleanunderwear.domain.BrowserMission, String) -> Unit,
        val onAllComplete: () -> Unit,
        val onCancel: () -> Unit,
        val isBlocked: (String) -> Boolean,
    )

    private var missionBatchSequence = 0L
    private var resolveCancellationRequested = false
    private val _activeMissionBatch = MutableStateFlow<ActiveMissionBatch?>(null)
    val activeMissionBatch: StateFlow<ActiveMissionBatch?> = _activeMissionBatch

    /**
     * Queues a list of missions for the visible BrowserScreen. The screen
     * loads each URL in order, auto-extracts on settle, and advances. See
     * [ActiveMissionBatch] for callback semantics.
     */
    fun launchMissionQueue(
        missions: List<com.hereliesaz.cleanunderwear.domain.BrowserMission>,
        onMissionExtracted: (com.hereliesaz.cleanunderwear.domain.BrowserMission, String) -> Unit = { _, _ -> },
        onAllComplete: () -> Unit = {},
        onCancel: () -> Unit = {},
        isBlocked: (String) -> Boolean = { false },
        retainBrowserForResolve: Boolean = false,
    ) {
        _activeMissionBatch.value = ActiveMissionBatch(
            id = ++missionBatchSequence,
            retainBrowserForResolve = retainBrowserForResolve,
            missions = missions,
            onMissionExtracted = onMissionExtracted,
            onAllComplete = onAllComplete,
            onCancel = onCancel,
            isBlocked = isBlocked,
        )
    }

    /**
     * Single-mission convenience wrapper that preserves the older
     * `(html: String?) -> Unit` callback shape (null = user cancelled,
     * non-null = extracted HTML). Used by Facebook harvest and the
     * TargetDetailScreen "Research" buttons.
     */
    fun launchMission(
        mission: com.hereliesaz.cleanunderwear.domain.BrowserMission,
        onResult: (String?) -> Unit = {}
    ) {
        var captured: String? = null
        launchMissionQueue(
            missions = listOf(mission),
            onMissionExtracted = { _, html -> captured = html },
            onAllComplete = { onResult(captured) },
            onCancel = { onResult(null) },
        )
    }

    /** Called by the UI when the mission queue finishes naturally. */
    fun completeMissionBatch() {
        val active = _activeMissionBatch.value ?: return
        // The Resolve coroutine starts the next contact after parsing this one.
        // Keep the browser mounted during that gap; only replace its mission data.
        if (!active.retainBrowserForResolve) _activeMissionBatch.value = null
        active.onAllComplete.invoke()
    }

    /** Called by the UI when the user backs out of the queue. */
    fun cancelMissionBatch() {
        val active = _activeMissionBatch.value
        if (active?.retainBrowserForResolve == true) resolveCancellationRequested = true
        _activeMissionBatch.value = null
        active?.onCancel?.invoke()
    }

    data class OperationState(
        val isRunning: Boolean = false,
        val description: String = "",
        val progress: Float = 0f // 0.0 to 1.0, or -1f for indeterminate
    )

    /**
     * One-line results for the user ("Imported 42 contacts", "Nothing was due for a check"),
     * shown briefly once an operation ends so it never just goes quiet.
     */
    private val _messages = kotlinx.coroutines.flow.MutableSharedFlow<String>(extraBufferCapacity = 8)
    val messages: kotlinx.coroutines.flow.SharedFlow<String> = _messages

    private fun post(message: String) { _messages.tryEmit(message) }

    private val _operationState = MutableStateFlow(OperationState())
    val operationState: StateFlow<OperationState> = _operationState

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val targets: Flow<PagingData<TargetLite>> = combine(
        _searchQuery, _sortOrder, _showIgnored,
        _showNamelessFilter, _showEmailOnlyFilter, _hasEmailFilter, _hasAddressFilter,
        _googleFilter, _metaFilter, _appleFilter, _deviceFilter,
        _pendingEnrichmentFilter
    ) { args: Array<Any?> ->
        args
    }.flatMapLatest { args ->
        Pager(
            config = PagingConfig(pageSize = 50, enablePlaceholders = true),
            pagingSourceFactory = {
                repository.searchTargets(
                    query = args[0] as String,
                    sort = (args[1] as SortOrder).name,
                    showIgnored = args[2] as Boolean,
                    namelessF = args[3] as Boolean?,
                    emailOnlyF = args[4] as Boolean?,
                    hasEmailF = args[5] as Boolean?,
                    hasAddressF = args[6] as Boolean?,
                    googleF = args[7] as Boolean?,
                    metaF = args[8] as Boolean?,
                    appleF = args[9] as Boolean?,
                    deviceF = args[10] as Boolean?,
                    pendingEnrichF = args[11] as Boolean?
                )
            }
        ).flow
    }.cachedIn(viewModelScope)

    /**
     * Update check state. [prompt] is an update to announce in a dialog (automatic check, not yet
     * dismissed for this version); [lastResult] is what Settings shows.
     */
    data class UpdateState(
        val checking: Boolean = false,
        val lastResult: com.hereliesaz.cleanunderwear.update.UpdateResult? = null,
        val prompt: com.hereliesaz.cleanunderwear.update.UpdateResult.Available? = null
    )

    private val _updateState = MutableStateFlow(UpdateState())
    val updateState: StateFlow<UpdateState> = _updateState

    val installedVersion: String get() = updateChecker.currentVersion
    val updateChannel get() = updateChecker.channel()

    /**
     * Check the install's own channel for a newer version. Automatic checks run at most every
     * [AUTO_UPDATE_CHECK_INTERVAL_MS] and prompt once per version; a manual check always runs and
     * always prompts when something is available.
     */
    fun checkForUpdates(manual: Boolean) {
        val now = System.currentTimeMillis()
        if (!manual && now - prefs.getLong("last_update_check", 0L) < AUTO_UPDATE_CHECK_INTERVAL_MS) return
        if (_updateState.value.checking) return
        viewModelScope.launch {
            _updateState.value = _updateState.value.copy(checking = true)
            val result = updateChecker.check()
            prefs.edit { putLong("last_update_check", now) }
            val available = result as? com.hereliesaz.cleanunderwear.update.UpdateResult.Available
            val dismissed = prefs.getString("dismissed_update", null)
            val prompt = available?.takeIf { manual || "${it.channel}:${it.version}" != dismissed }
            _updateState.value = UpdateState(checking = false, lastResult = result, prompt = prompt)
        }
    }

    /** "Later": don't raise the automatic prompt again for this version. */
    fun dismissUpdatePrompt() {
        _updateState.value.prompt?.let { prefs.edit { putString("dismissed_update", "${it.channel}:${it.version}") } }
        _updateState.value = _updateState.value.copy(prompt = null)
    }

    fun playWebUrl(): String = updateChecker.playWebUrl()

    /** A contact to open, from a notification tap; consumed by the navigation once shown. */
    private val _openTarget = MutableStateFlow<Int?>(null)
    val openTarget: StateFlow<Int?> = _openTarget

    fun requestOpenTarget(id: Int) { _openTarget.value = id }
    fun consumeOpenTarget() { _openTarget.value = null }

    /** The correction step's queue: null until the first scan finishes. */
    data class ReviewState(val loading: Boolean = false, val report: SuspectReport? = null)

    private val _reviewState = MutableStateFlow(ReviewState())
    val reviewState: StateFlow<ReviewState> = _reviewState

    /** Re-scan for bad merges and stale numbers. Read-only; changes nothing. */
    fun refreshSuspects() {
        viewModelScope.launch {
            _reviewState.value = _reviewState.value.copy(loading = true)
            val report = pipelineCoordinator.runExclusive("suspect-scan") {
                withContext(Dispatchers.Default) { suspectReview.find() }
            }
            _reviewState.value = ReviewState(loading = false, report = report)
        }
    }

    /** Undo the merge behind [case]; the absorbed contact comes back as its own row. */
    fun splitMerge(case: SuspectCase) {
        applyVerdict(case, rescan = true) {
            case.ledgerId?.let { suspectReview.split(it) }
            // Undoing a merge can take back a number the survivor had only through it; look one
            // up, reading the survivor after the split has landed.
            val survivor = repository.getTargetById(case.targetId)
            if (survivor != null && survivor.phoneNumber.isNullOrBlank()) findAlternates(case.targetId, null)
        }
    }

    fun keepMerge(case: SuspectCase) = applyVerdict(case) {
        case.ledgerId?.let { suspectReview.keepMerged(it) }
    }

    fun markStale(case: SuspectCase) {
        applyVerdict(case) { suspectReview.markStale(case) }
        findAlternates(case.targetId, case.identities.firstOrNull()?.phone)
    }

    fun adoptReplacement(case: SuspectCase) = applyVerdict(case, rescan = true) {
        suspectReview.adoptReplacement(case)
    }

    fun dismissSuspect(case: SuspectCase) = applyVerdict(case) { suspectReview.dismiss(case) }

    private val _alternates = MutableStateFlow<AlternateInfo?>(null)

    /** The lookup's offer for a contact whose number was just ruled out; null when none is pending. */
    val alternates: StateFlow<AlternateInfo?> = _alternates

    /**
     * Search CyberBackgroundChecks for current info on a contact whose number is stale or was
     * split off. Same visible-browser flow as Resolve, so it needs the user's attention and is only
     * ever started by a review verdict or the "Look up alternates" button.
     */
    fun findAlternates(targetId: Int, stalePhone: String?) {
        viewModelScope.launch {
            pipelineCoordinator.runExclusive("find-alternates") {
                resolveCancellationRequested = false
                try {
                    val target = repository.getTargetById(targetId) ?: return@runExclusive
                    val missions = alternateFinder.missions(target)
                    if (missions.isEmpty()) {
                        _alternates.value = AlternateInfo(
                            target.id, target.displayName, stalePhone, null, false,
                            phone = null, address = null, basis = "nothing to search on"
                        )
                        return@runExclusive
                    }
                    _operationState.value = OperationState(
                        isRunning = true,
                        description = "Looking up ${target.displayName}...",
                        progress = -1f
                    )
                    val extracted = mutableListOf<Pair<BrowserMission, String>>()
                    val outcome = launchMissionsAndAwait(missions) { mission, html ->
                        extracted.add(mission to html)
                    }
                    if (outcome == BatchOutcome.UserCancelled || resolveCancellationRequested) return@runExclusive

                    val info = withContext(Dispatchers.Default) {
                        val cards = extracted.mapNotNull { (mission, html) ->
                            val findings = cbcEnricher.parseFindings(html)
                            com.hereliesaz.cleanunderwear.util.DiagnosticLogger.log(
                                "CYBG parse (alternates) ${mission.label}: ${cbcEnricher.describeParse(html, findings)}"
                            )
                            findings?.let { mission to it }
                        }
                        alternateFinder.assess(target, stalePhone, cards)
                    }
                    _alternates.value = info
                } finally {
                    if (_activeMissionBatch.value?.retainBrowserForResolve == true) {
                        _activeMissionBatch.value = null
                    }
                    resolveCancellationRequested = false
                    _operationState.value = OperationState(isRunning = false)
                }
            }
        }
    }

    /** Write the offered number and/or address onto the contact. Only fills what the lookup found. */
    fun applyAlternates(info: AlternateInfo) {
        viewModelScope.launch {
            pipelineCoordinator.runExclusive("apply-alternates") {
                val target = repository.getTargetById(info.targetId) ?: return@runExclusive
                repository.updateTarget(
                    target.copy(
                        phoneNumber = info.phone ?: target.phoneNumber,
                        areaCode = info.phone
                            ?.let { com.hereliesaz.cleanunderwear.match.StringSimilarity.nanpAreaCode(it) }
                            ?: target.areaCode,
                        residenceInfo = target.residenceInfo ?: info.address
                    )
                )
            }
            _alternates.value = null
            refreshSuspects()
        }
    }

    fun dismissAlternates() {
        _alternates.value = null
    }

    /** Runs a review verdict alongside the pipeline's other writers, then drops the case from the queue. */
    private fun applyVerdict(case: SuspectCase, rescan: Boolean = false, action: suspend () -> Unit) {
        viewModelScope.launch {
            pipelineCoordinator.runExclusive("review-verdict") { action() }
            val state = _reviewState.value
            _reviewState.value = state.copy(
                report = state.report?.let { r -> r.copy(cases = r.cases.filter { it.key != case.key }) }
            )
            if (rescan) refreshSuspects()
        }
    }

    fun updateTarget(target: Target) {
        viewModelScope.launch {
            val before = repository.getTargetById(target.id)
            val changed = before != null && before.status != target.status
            val stamped = if (changed) target.copy(lastStatusChangeTimestamp = System.currentTimeMillis()) else target
            repository.updateTarget(stamped)
            // A status the user just confirmed or cleared goes to the phone-book note now, not at
            // the next check.
            if (changed) syncNote(stamped.id)
        }
    }

    fun ignoreTarget(id: Int) {
        viewModelScope.launch {
            repository.ignore(id, System.currentTimeMillis())
            syncNote(id)
        }
    }

    fun restoreTarget(id: Int) {
        viewModelScope.launch {
            repository.unignore(id, System.currentTimeMillis())
            syncNote(id)
        }
    }

    private suspend fun syncNote(id: Int) {
        val target = repository.getTargetById(id) ?: return
        withContext(Dispatchers.IO) { contactSyncer.syncToSystem(target) }
    }

    /**
     * Reactive single-target stream for the detail screen. Avoids materializing the full list.
     */
    fun observeTarget(id: Int): Flow<Target?> = repository.observeTargetById(id)
    
    fun toggleShowIgnored() {
        _showIgnored.value = !_showIgnored.value
    }

    fun setNamelessFilter(filter: Boolean?) {
        _showNamelessFilter.value = filter
    }

    fun setEmailOnlyFilter(filter: Boolean?) {
        _showEmailOnlyFilter.value = filter
    }

    fun setEmailFilter(hasEmail: Boolean?) {
        _hasEmailFilter.value = hasEmail
    }

    fun setAddressFilter(hasAddress: Boolean?) {
        _hasAddressFilter.value = hasAddress
    }

    fun setGoogleFilter(filter: Boolean?) { _googleFilter.value = filter }
    fun setMetaFilter(filter: Boolean?) { _metaFilter.value = filter }
    fun setAppleFilter(filter: Boolean?) { _appleFilter.value = filter }
    fun setDeviceFilter(filter: Boolean?) { _deviceFilter.value = filter }
    fun setPendingEnrichmentFilter(filter: Boolean?) { _pendingEnrichmentFilter.value = filter }

    fun clearFilters() {
        listOf(
            _showNamelessFilter, _showEmailOnlyFilter, _hasEmailFilter, _hasAddressFilter,
            _googleFilter, _metaFilter, _appleFilter, _deviceFilter, _pendingEnrichmentFilter
        ).forEach { it.value = null }
    }

    fun setSearchQuery(query: String) {
        _searchQuery.value = query
    }
    
    fun setSortOrder(order: SortOrder) {
        _sortOrder.value = order
    }

    fun completeOnboarding() {
        prefs.edit {putBoolean("onboarding_done", true)}
        _isOnboardingCompleted.value = true
    }

    fun setDarkTheme(enabled: Boolean) {
        prefs.edit { putBoolean("dark_theme", enabled) }
        _isDarkTheme.value = enabled
    }

    /** Alerts about possible deaths, and about arrests and custody; matches still show in the app. */
    private val _notifyDeath = MutableStateFlow(prefs.getBoolean(NotificationHelper.PREF_NOTIFY_DEATH, true))
    val notifyDeath: StateFlow<Boolean> = _notifyDeath
    private val _notifyCustody = MutableStateFlow(prefs.getBoolean(NotificationHelper.PREF_NOTIFY_CUSTODY, true))
    val notifyCustody: StateFlow<Boolean> = _notifyCustody

    fun setNotifyDeath(on: Boolean) { prefs.edit { putBoolean(NotificationHelper.PREF_NOTIFY_DEATH, on) }; _notifyDeath.value = on }
    fun setNotifyCustody(on: Boolean) { prefs.edit { putBoolean(NotificationHelper.PREF_NOTIFY_CUSTODY, on) }; _notifyCustody.value = on }

    /** The hour of day the daily check aims for; the OS may run it later (battery, network). */
    private val _checkHour = MutableStateFlow(prefs.getInt("check_hour", 9))
    val checkHour: StateFlow<Int> = _checkHour

    fun setCheckHour(hour: Int) {
        prefs.edit { putInt("check_hour", hour) }
        _checkHour.value = hour
        scheduleDailyPanopticon(reschedule = true)
    }

    /**
     * Signs out of every site opened in the app's browser (Facebook, Google, Instagram,
     * WhatsApp Web, CyberBackgroundChecks): their cookies and stored data are deleted.
     */
    fun signOutOfSites() {
        android.webkit.CookieManager.getInstance().removeAllCookies { removed ->
            android.webkit.CookieManager.getInstance().flush()
            post(if (removed) "Signed out of every site used in the app's browser." else "There were no site sign-ins to remove.")
        }
        android.webkit.WebStorage.getInstance().deleteAllData()
    }

    /**
     * Deletes everything the app stored: its contact list, merge history and review decisions,
     * the notes it wrote into phone-book contacts, and site sign-ins. Settings are kept.
     */
    fun deleteEverything() {
        viewModelScope.launch {
            pipelineCoordinator.runExclusive("delete-everything") {
                _operationState.value = OperationState(isRunning = true, description = "Deleting…", progress = -1f)
                val notes = withContext(Dispatchers.IO) { contactSyncer.removeAllBlocks() }
                repository.wipeAll()
                _operationState.value = OperationState(isRunning = false)
                post("Deleted the app's data and its notes on $notes contact${if (notes == 1) "" else "s"}.")
            }
            signOutOfSites()
        }
    }

    fun setShowDiagnosticLog(enabled: Boolean) {
        prefs.edit { putBoolean("show_diagnostic_log", enabled) }
        _showDiagnosticLog.value = enabled
    }

    fun setGlobalTargetLimit(limit: Int) {
        prefs.edit { putInt("global_limit", limit) }
        _globalTargetLimit.value = limit
    }

    fun setShowManualEntryDialog(show: Boolean) {
        _showManualEntryDialog.value = show
    }

    fun addManualTarget(name: String, phone: String?, email: String?) {
        viewModelScope.launch {
            _operationState.value = OperationState(isRunning = true, description = "Processing manual ingestion...", progress = -1f)
            
            // Re-use de-duplication and AI validation logic via UseCase if possible, 
            // or just build a single Target and insert.
            val target = Target(
                displayName = name,
                phoneNumber = phone,
                email = email,
                areaCode = phone?.let {
                    com.hereliesaz.cleanunderwear.match.StringSimilarity.nanpAreaCode(it)
                        ?: "LOCAL".takeIf { _ -> it.count(Char::isDigit) == 7 }
                },
                sourceAccount = "Manual Entry",
                status = TargetStatus.UNKNOWN
            )
            
            // We pass it through the Harvester's processor by wrapping in a list
            harvestContactsUseCase.processManualTargets(listOf(target))

            _operationState.value = OperationState(isRunning = false)
            post("Added $name.")
        }
    }

    fun sweepContacts() {
        val activeSources = mutableSetOf<String>()
        if (_googleFilter.value != false) activeSources.add("Google")
        if (_metaFilter.value != false) activeSources.add("Meta")
        if (_appleFilter.value != false) activeSources.add("Apple")
        if (_deviceFilter.value != false) activeSources.add("Device")

        Log.d("MainViewModel", "Sweeping contacts with sources: $activeSources")
        // A sweep already queued or running covers this request too.
        if (sweepPending) return
        sweepPending = true
        viewModelScope.launch {
            try {
                // Serialised with the daily check and every other job that writes the registry,
                // and run off the main thread: harvest, name checks and dedup are heavy.
                pipelineCoordinator.runExclusive("contact-sweep") {
                    _operationState.value = OperationState(isRunning = true, description = "Reading contacts...", progress = 0.1f)
                    val before = repository.getTargetsPaged(limit = Int.MAX_VALUE, offset = 0).size
                    withContext(Dispatchers.Default) {
                        // 1. Ingest raw data from enabled sources
                        harvestContactsUseCase(activeSources)

                        // 2. Consolidate and deduplicate the full registry
                        _operationState.value = _operationState.value.copy(description = "Merging duplicates...", progress = 0.4f)
                        deduplicateTargetsUseCase { sub, _ ->
                            _operationState.value = _operationState.value.copy(progress = 0.4f + (sub * 0.3f))
                        }

                        // 3. Triage for monitorability
                        _operationState.value = _operationState.value.copy(description = "Preparing contacts for daily checks...", progress = 0.7f)
                        triageTargetsUseCase { sub, _ ->
                            _operationState.value = _operationState.value.copy(progress = 0.7f + (sub * 0.3f))
                        }
                    }
                    val after = repository.getTargetsPaged(limit = Int.MAX_VALUE, offset = 0).size
                    _operationState.value = OperationState(isRunning = false)
                    post(
                        if (after > before) "Imported ${after - before} new contact${if (after - before == 1) "" else "s"}; $after in all."
                        else "No new contacts; $after in all."
                    )
                }
            } finally {
                sweepPending = false
                if (_operationState.value.isRunning) _operationState.value = OperationState(isRunning = false)
            }
        }
    }

    @Volatile private var sweepPending = false

    fun harvestFacebook() {
        viewModelScope.launch {
            pipelineCoordinator.runExclusive("facebook-harvest") {
                try {
                    _operationState.value = OperationState(
                        isRunning = true,
                        description = "Awaiting visible Facebook session...",
                        progress = -1f
                    )
                    val html = launchMissionAndAwait(BrowserMission.HarvestFacebookFriends)
                    if (html.isNullOrBlank()) return@runExclusive
                    _operationState.value = _operationState.value.copy(
                        description = "Parsing Facebook friend list..."
                    )
                    val friends = fbHarvester.parseFriendsHtml(html)
                    harvestContactsUseCase.processManualTargets(friends)
                    post("Read ${friends.size} Facebook friend${if (friends.size == 1) "" else "s"}.")
                } finally {
                    com.hereliesaz.cleanunderwear.util.WebSessions.forget(com.hereliesaz.cleanunderwear.util.WebSessions.FACEBOOK)
                    _operationState.value = OperationState(isRunning = false)
                }
            }
        }
    }

    /**
     * Walks every UNVERIFIED contact, and every contact still missing an age or
     * date of birth, and enriches them through the visible cyberbackgroundchecks
     * browser flow, one mission at a time. Age is what lets
     * [com.hereliesaz.cleanunderwear.match.MatchVerifier] tell two people with
     * the same name apart, so it is gathered for everyone, not only on a match.
     *
     * Per the user's rule, this is *user-initiated*; the auto pipeline never
     * triggers it. If the auto pipeline (or any other coordinator-protected
     * job) is in flight, this batch queues until that finishes.
     */
    /** How many contacts "Fill in details" would look up, for the confirmation before it starts. */
    suspend fun resolveCandidateCount(): Int = resolveCandidates().size

    private suspend fun resolveCandidates(): List<Target> {
        val limit = _globalTargetLimit.value.takeIf { it > 0 } ?: Int.MAX_VALUE
        val ids = repository
            .getTargetWorkInfoPaged(limit = 5000, offset = 0)
            .filter { it.status != TargetStatus.IGNORED }
            .map { it.id }
            .sorted()
            .take(limit)
        return ids.chunked(500)
            .flatMap { repository.getTargetsByIds(it) }
            .filter { it.status == TargetStatus.UNVERIFIED || it.dateOfBirth.isNullOrBlank() }
    }

    fun resolveUnverifiedBatch() = resolveContacts { resolveCandidates() }

    /** Looks up one contact's missing details, from its detail screen. */
    fun resolveOne(id: Int) = resolveContacts { listOfNotNull(repository.getTargetById(id)) }

    private fun resolveContacts(select: suspend () -> List<Target>) {
        viewModelScope.launch {
            pipelineCoordinator.runExclusive("user-enrichment") {
                resolveCancellationRequested = false
                try {
                val unverified = select()

                if (unverified.isEmpty()) {
                    _operationState.value = OperationState(isRunning = false)
                    post("Every contact already has its details filled in.")
                    return@runExclusive
                }

                val total = unverified.size
                _operationState.value = OperationState(
                    isRunning = true,
                    description = "Looking up 1 of $total on CyberBackgroundChecks...",
                    progress = 0f
                )
                var filled = 0

                for ((index, lite) in unverified.withIndex()) {
                    if (resolveCancellationRequested) return@runExclusive
                    val target = repository.getTargetById(lite.id) ?: continue
                    val missions = cbcEnricher.pickAllMissions(target)
                    if (missions.isEmpty()) {
                        repository.updateMonitorabilityState(
                            target.id,
                            com.hereliesaz.cleanunderwear.data.MonitorabilityState.ENRICHMENT_FAILED
                        )
                        continue
                    }

                    _operationState.value = OperationState(
                        isRunning = true,
                        description = "Looking up ${target.displayName} (${index + 1} of $total)",
                        progress = index.toFloat() / total
                    )

                    // WebView callbacks run on the UI thread. Keep them cheap:
                    // Jsoup parsing can stall the entire browser on large pages.
                    val extracted = mutableListOf<Pair<BrowserMission, String>>()
                    val outcome = launchMissionsAndAwait(missions) { mission, html ->
                        extracted.add(mission to html)
                    }

                    if (outcome == BatchOutcome.UserCancelled || resolveCancellationRequested) {
                        // User backed out mid-target — stop the whole batch
                        // rather than barrel through every UNVERIFIED contact
                        // without their attention.
                        _operationState.value = OperationState(isRunning = false)
                        post("Stopped after ${index} of $total. Details found so far were kept.")
                        return@runExclusive
                    }

                    val collected = withContext(Dispatchers.Default) {
                        extracted.mapNotNull { (mission, html) ->
                            val findings = cbcEnricher.parseFindings(html)
                            com.hereliesaz.cleanunderwear.util.DiagnosticLogger.log(
                                "CYBG parse ${mission.label}: ${cbcEnricher.describeParse(html, findings)}"
                            )
                            findings?.let { mission to it }
                        }
                    }
                    if (resolveCancellationRequested) return@runExclusive

                    // Verify-before-merge: only promote the contact when the CBC
                    // card is corroborated as the *same person*. An unverified or
                    // conflicting card (e.g. a recycled phone number returning a
                    // stranger) is NOT written into the registry or the user's
                    // system contacts — we keep it UNVERIFIED and record why.
                    val enrichment = cbcEnricher.enrich(target, collected)
                    if (enrichment.verified) {
                        filled++
                        repository.updateTarget(
                            enrichment.target.copy(
                                // Enrichment resolves UNVERIFIED; any other status stands.
                                status = if (target.status == TargetStatus.UNVERIFIED) {
                                    TargetStatus.MONITORING
                                } else {
                                    target.status
                                },
                                monitorabilityState =
                                    com.hereliesaz.cleanunderwear.data.MonitorabilityState.READY
                            )
                        )
                    } else if (target.status == TargetStatus.UNVERIFIED) {
                        repository.updateTarget(
                            enrichment.target.copy(
                                monitorabilityState =
                                    com.hereliesaz.cleanunderwear.data.MonitorabilityState.ENRICHMENT_FAILED
                            )
                        )
                    } else {
                        // An unconfirmed card on an already-monitored contact is
                        // simply not used; the contact keeps monitoring as before.
                        repository.updateTarget(target.copy(enrichmentProvenance = enrichment.target.enrichmentProvenance))
                    }
                }

                _operationState.value = OperationState(isRunning = false)
                post("Looked up $total contact${if (total == 1) "" else "s"}; filled in details for $filled.")
                } finally {
                    // Do not destroy the shared browser between contacts, only
                    // when the complete Resolve run finishes or is cancelled.
                    if (_activeMissionBatch.value?.retainBrowserForResolve == true) {
                        _activeMissionBatch.value = null
                    }
                    resolveCancellationRequested = false
                    if (_operationState.value.isRunning) {
                        _operationState.value = OperationState(isRunning = false)
                    }
                }
            }
        }
    }

    /**
     * Launches a [BrowserMission] and suspends until BrowserScreen reports a
     * result (auto-extracted) or cancels (user tapped back / Done). Single-
     * mission flows still use this; the batch enricher uses
     * [launchMissionsAndAwait].
     */
    private suspend fun launchMissionAndAwait(mission: BrowserMission): String? {
        val deferred = CompletableDeferred<String?>()
        launchMission(mission) { html -> deferred.complete(html) }
        return deferred.await()
    }

    private enum class BatchOutcome { Completed, UserCancelled }

    /**
     * Launches a queue of missions in the visible BrowserScreen and suspends
     * until the queue completes or the user cancels. [onEachExtracted] is
     * invoked per page that produced HTML; the suspend value indicates
     * whether the queue ran to completion.
     */
    private suspend fun launchMissionsAndAwait(
        missions: List<BrowserMission>,
        onEachExtracted: (BrowserMission, String) -> Unit,
    ): BatchOutcome {
        val deferred = CompletableDeferred<BatchOutcome>()
        launchMissionQueue(
            missions = missions,
            onMissionExtracted = onEachExtracted,
            onAllComplete = { deferred.complete(BatchOutcome.Completed) },
            onCancel = { deferred.complete(BatchOutcome.UserCancelled) },
            isBlocked = cbcEnricher::looksLikeBlock,
            retainBrowserForResolve = true,
        )
        return deferred.await()
    }

    fun harvestWhatsApp() = harvestInBrowser("whatsapp-harvest", BrowserMission.HarvestWhatsApp, "WhatsApp chats", com.hereliesaz.cleanunderwear.util.WebSessions.WHATSAPP) {
        whatsAppHarvester.parse(it)
    }

    fun harvestInstagram() = harvestInBrowser("instagram-harvest", BrowserMission.HarvestInstagram, "Instagram followers", com.hereliesaz.cleanunderwear.util.WebSessions.INSTAGRAM) {
        instagramHarvester.parse(it)
    }

    fun harvestGoogleContacts() = harvestInBrowser("google-harvest", BrowserMission.HarvestGoogleContacts, "Google Contacts", com.hereliesaz.cleanunderwear.util.WebSessions.GOOGLE) {
        googleContactsHarvester.parse(it)
    }

    /**
     * Opens [mission] in the visible browser, where the user signs in (or scans a QR code) if the
     * service needs it, and imports what [parse] reads from the page it hands back.
     */
    private fun harvestInBrowser(key: String, mission: BrowserMission, what: String, hosts: List<String>, parse: (String) -> List<Target>) {
        viewModelScope.launch {
            pipelineCoordinator.runExclusive(key) {
                try {
                    _operationState.value = OperationState(isRunning = true, description = "Importing $what. Sign in if asked.", progress = -1f)
                    val html = launchMissionAndAwait(mission)
                    if (html.isNullOrBlank()) return@runExclusive
                    val found = parse(html)
                    com.hereliesaz.cleanunderwear.util.DiagnosticLogger.log("Import: $what yielded ${found.size} contact(s).")
                    harvestContactsUseCase.processManualTargets(found)
                    post(if (found.isEmpty()) "Nothing was read from $what. If you weren't signed in, try again." else "Read ${found.size} from $what.")
                } finally {
                    // The sign-in was for this import only.
                    com.hereliesaz.cleanunderwear.util.WebSessions.forget(hosts)
                    _operationState.value = OperationState(isRunning = false)
                }
            }
        }
    }

    fun triggerManualInterrogation() {
        val request = OneTimeWorkRequestBuilder<ScrapingWorker>().build()
        workManager.enqueue(request)
        
        viewModelScope.launch {
            workManager.getWorkInfoByIdFlow(request.id).collect { workInfo ->
                if (workInfo != null) {
                    when (workInfo.state) {
                        androidx.work.WorkInfo.State.RUNNING -> {
                            val progress = workInfo.progress.getFloat("progress", -1f)
                            val description = workInfo.progress.getString("description") ?: "Checking records..."
                            
                            _operationState.value = OperationState(
                                isRunning = true, 
                                description = description,
                                progress = progress
                            )
                        }
                        androidx.work.WorkInfo.State.SUCCEEDED -> {
                            _operationState.value = OperationState(isRunning = false)
                            workInfo.outputData.getString(ScrapingWorker.SUMMARY)?.let(::post)
                        }
                        androidx.work.WorkInfo.State.FAILED -> {
                            _operationState.value = OperationState(isRunning = false)
                            post("The check stopped with an error. It will run again with the next daily check.")
                        }
                        androidx.work.WorkInfo.State.CANCELLED -> {
                            _operationState.value = OperationState(isRunning = false)
                        }
                        else -> {}
                    }
                }
            }
        }
    }

    fun scheduleDailyPanopticon(reschedule: Boolean = false) {
        val calendar = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, _checkHour.value)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
        }
        
        if (calendar.timeInMillis <= System.currentTimeMillis()) {
            calendar.add(Calendar.DAY_OF_YEAR, 1)
        }
        
        val initialDelay = calendar.timeInMillis - System.currentTimeMillis()

        // Don't burn the user's mobile data or torch the battery while they're
        // mid-commute. Periodic work in WorkManager already drifts off the
        // wall-clock 9 AM target over time (it repeats N hours after the
        // previous run completes, not at the same time of day) — these
        // constraints just keep the run from being actively hostile when it
        // does fire.
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .setRequiresBatteryNotLow(true)
            .build()

        val periodicWorkRequest = PeriodicWorkRequestBuilder<ScrapingWorker>(24, TimeUnit.HOURS)
            .setInitialDelay(initialDelay, TimeUnit.MILLISECONDS)
            .setConstraints(constraints)
            .build()

        // UPDATE rather than KEEP so existing installs pick up new constraints
        // when the build changes them, instead of being stuck on whatever
        // policy was in effect the day they first installed.
        // A new check time needs a fresh schedule; UPDATE would keep the old start time.
        workManager.enqueueUniquePeriodicWork(
            "daily_panopticon",
            if (reschedule) ExistingPeriodicWorkPolicy.CANCEL_AND_REENQUEUE else ExistingPeriodicWorkPolicy.UPDATE,
            periodicWorkRequest
        )
    }
}

/** Automatic update checks run at most this often; the manual check in Settings ignores it. */
private const val AUTO_UPDATE_CHECK_INTERVAL_MS = 12L * 60 * 60 * 1000
