package com.hereliesaz.cleanunderwear.ui

import android.app.Application
import android.content.Context
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
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import com.hereliesaz.cleanunderwear.worker.ScrapingWorker
import dagger.hilt.android.lifecycle.HiltViewModel
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.cachedIn
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.receiveAsFlow
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
    private val contactSyncer: com.hereliesaz.cleanunderwear.util.SystemContactSyncer,
    private val lookupChoices: com.hereliesaz.cleanunderwear.domain.LookupChoiceStore
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
        val progress: Float = 0f, // 0.0 to 1.0, or -1f for indeterminate
        /** Whether Cancel can stop it partway without leaving a mess. */
        val cancellable: Boolean = true,
        /** Whether it keeps going if the user stops watching it. */
        val continues: Continues = Continues.NO,
        /** What has turned up so far, a line each, shown under what is happening. */
        val findings: List<String> = emptyList()
    )

    enum class Continues {
        /** Needs the user's attention; the busy screen stays until it ends or is cancelled. */
        NO,
        /** Keeps going while the user does other things in the app. */
        IN_APP,
        /** Keeps going in the app and after the user leaves it (WorkManager). */
        ANYWHERE
    }

    /**
     * Every operation runs under this: an error nothing else caught (a bad page, a full disk) is
     * logged and reported in a line, and the busy screen cleared, instead of closing the app.
     * Cancellation never reaches it.
     */
    private val failSafe = kotlinx.coroutines.CoroutineExceptionHandler { _, e ->
        com.hereliesaz.cleanunderwear.util.DiagnosticLogger.log(
            "Operation failed: ${e.javaClass.simpleName}", com.hereliesaz.cleanunderwear.util.DiagnosticLogger.LogEntry.LogLevel.ERROR
        )
        _operationState.value = OperationState(isRunning = false)
        post("Something went wrong and that stopped. What was done before is kept; the activity log has details.")
    }

    /** Stops the operation now running; set by [begin]. */
    private var cancelRunning: (() -> Unit)? = null

    /** The user put the running operation aside to keep using the app; a bar shows it instead. */
    private val _operationInBackground = MutableStateFlow(false)
    val operationInBackground: StateFlow<Boolean> = _operationInBackground

    /**
     * Shows [state] as the running operation. Cancel stops the calling coroutine, or runs [cancel]
     * when the work lives elsewhere. A new operation starts in front of the user, unless
     * [inBackground]: then only the bar shows it, for work the app starts by itself (the contact
     * read at launch) that mustn't cover what the user opened the app for.
     */
    private suspend fun begin(state: OperationState, cancel: (() -> Unit)? = null, inBackground: Boolean = false) {
        val job = kotlinx.coroutines.currentCoroutineContext()[kotlinx.coroutines.Job]
        cancelRunning = cancel ?: { job?.cancel() }
        if (!_operationState.value.isRunning) _operationInBackground.value = inBackground && state.continues != Continues.NO
        _operationState.value = state.copy(isRunning = true)
    }

    /** Cancel on the busy screen or its bar. What was finished before stays. */
    fun cancelOperation() {
        val state = _operationState.value
        if (!state.isRunning || !state.cancellable) return
        resolveCancellationRequested = true
        cancelRunning?.invoke()
        cancelRunning = null
        _operationState.value = OperationState(isRunning = false)
        _operationInBackground.value = false
        post("Stopped. What was done before stopping is kept.")
    }

    fun sendOperationToBackground() {
        if (_operationState.value.continues != Continues.NO) _operationInBackground.value = true
    }

    fun showOperation() {
        _operationInBackground.value = false
    }

    /**
     * One-line results for the user ("Imported 42 contacts", "Nothing was due for a check"),
     * shown briefly once an operation ends so it never just goes quiet.
     */
    // A channel, not a SharedFlow: a message posted while no screen is collecting (the browser
    // covers the list during a lookup) waits for the next collector instead of being dropped.
    private val _messages = kotlinx.coroutines.channels.Channel<String>(capacity = 16, onBufferOverflow = kotlinx.coroutines.channels.BufferOverflow.DROP_OLDEST)
    val messages: kotlinx.coroutines.flow.Flow<String> = _messages.receiveAsFlow()

    private fun post(message: String) { _messages.trySend(message) }

    private val _operationState = MutableStateFlow(OperationState())
    val operationState: StateFlow<OperationState> = _operationState

    /**
     * [PipelineCoordinator.runExclusive], saying so when it has to wait. Jobs queue rather than
     * drop, and the one in front may be the browser waiting on the user (a CAPTCHA left
     * unanswered), which used to leave a later Delete everything or contact read sitting behind it
     * with nothing on screen. [what] names the job that waits ("Importing contacts"); the waits on
     * the user are time-limited too ([awaitWithin]), so the queue always moves on in the end.
     */
    private suspend fun <T> exclusive(label: String, what: String, block: suspend () -> T): T {
        noteIfQueued(what)
        return pipelineCoordinator.runExclusive(label, block)
    }

    /** Tells the user [what] is about to wait behind the job now holding the pipeline, if one is. */
    private fun noteIfQueued(what: String) {
        if (pipelineCoordinator.isBusy) {
            post(queuedNotice(what, _operationState.value.takeIf { it.isRunning }?.description))
        }
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val targets: Flow<PagingData<TargetLite>> = combine(
        // Typing settles before the list is re-queried (each query scans the table).
        @OptIn(kotlinx.coroutines.FlowPreview::class) _searchQuery.debounce(250), _sortOrder, _showIgnored,
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
        viewModelScope.launch(failSafe) {
            _updateState.value = _updateState.value.copy(checking = true)
            val result = updateChecker.check()
            // A failed check (offline, rate-limited) doesn't count: the next launch tries again.
            if (result !is com.hereliesaz.cleanunderwear.update.UpdateResult.Failed) prefs.edit { putLong("last_update_check", now) }
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
        viewModelScope.launch(failSafe) {
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
            if (case.ledgerId?.let { suspectReview.split(it) } != true) {
                post("Couldn't separate ${case.title}: that entry changed since the list was made. The list was refreshed.")
                return@applyVerdict
            }
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

    /**
     * Does what each of [cases] recommends, one after another, under the busy screen; cases with no
     * recommendation (too close to call) are left alone. Merges go first: a number case on a contact
     * that was just separated is set aside, since separating changes what that contact's numbers
     * are, and the rescan afterwards raises it again if it still applies. Out-of-date numbers are
     * only marked: looking up a current one needs the browser, contact by contact.
     */
    fun applyRecommendations(cases: List<SuspectCase>) {
        val todo = cases.filter { it.recommendation != null }
            .sortedBy { if (it.kind == com.hereliesaz.cleanunderwear.domain.SuspicionKind.BAD_MERGE) 0 else 1 }
        if (todo.isEmpty()) return
        viewModelScope.launch(failSafe) {
            exclusive("review-all", "Applying the recommendations") {
                val done = linkedMapOf<com.hereliesaz.cleanunderwear.domain.Recommendation, Int>()
                val separated = mutableSetOf<Int>()
                var setAside = 0
                var notApplied = 0
                fun lines() = done.map { (rec, n) -> rec.done(n) } +
                    listOfNotNull(
                        setAside.takeIf { it > 0 }?.let { "$it left for a fresh look after separating" },
                        notApplied.takeIf { it > 0 }?.let { "$it couldn't be done: the contact changed since the list was made" }
                    )
                try {
                    begin(OperationState(description = "Applying recommendations…", progress = 0f))
                    for ((i, case) in todo.withIndex()) {
                        val rec = case.recommendation!!
                        _operationState.value = _operationState.value.copy(
                            description = "${rec.label}: ${case.title} (${i + 1} of ${todo.size})",
                            progress = i.toFloat() / todo.size,
                            findings = lines()
                        )
                        if (case.kind == com.hereliesaz.cleanunderwear.domain.SuspicionKind.STALE_NUMBER && case.targetId in separated) {
                            setAside++
                            continue
                        }
                        // Only what actually happened is counted: a merge already undone, or a
                        // number no longer on the contact, is reported as not done.
                        val applied = when (rec) {
                            com.hereliesaz.cleanunderwear.domain.Recommendation.SEPARATE ->
                                (case.ledgerId?.let { suspectReview.split(it) } == true).also { if (it) separated += case.targetId }
                            com.hereliesaz.cleanunderwear.domain.Recommendation.KEEP_COMBINED ->
                                case.ledgerId?.let { suspectReview.keepMerged(it); true } == true
                            com.hereliesaz.cleanunderwear.domain.Recommendation.USE_REPLACEMENT -> suspectReview.adoptReplacement(case)
                            com.hereliesaz.cleanunderwear.domain.Recommendation.MARK_OUT_OF_DATE -> { suspectReview.markStale(case); true }
                            com.hereliesaz.cleanunderwear.domain.Recommendation.KEEP_NUMBER -> { suspectReview.dismiss(case); true }
                        }
                        if (applied) done[rec] = (done[rec] ?: 0) + 1 else notApplied++
                    }
                    _operationState.value = OperationState(isRunning = false)
                    post(lines().joinToString("; ").replaceFirstChar { it.uppercase() } + ".")
                } finally {
                    if (_operationState.value.isRunning) _operationState.value = OperationState(isRunning = false)
                    // Cancelled or not, what was done is done; the list shows what is left.
                    refreshSuspects()
                }
            }
        }
    }

    private val _choices = MutableStateFlow<List<com.hereliesaz.cleanunderwear.domain.LookupChoice>>(emptyList())

    /** Contacts a details lookup found people for, waiting for the user to say which is them. */
    val choices: StateFlow<List<com.hereliesaz.cleanunderwear.domain.LookupChoice>> = _choices

    private val _openChoices = MutableStateFlow(false)

    /** Set when a run of lookups ends with people to choose from; the app then opens the choices. */
    val openChoices: StateFlow<Boolean> = _openChoices
    fun consumeOpenChoices() { _openChoices.value = false }

    init {
        viewModelScope.launch(failSafe) { _choices.value = withContext(Dispatchers.IO) { lookupChoices.load() } }
    }

    /**
     * The user's answer for one contact: [option] is them (its details fill the contact's gaps, as
     * a confirmed lookup's always did), or, when null, none of the people found is. "None" is
     * remembered, so later runs don't look the contact up again; its own Look up details still can.
     */
    fun chooseLookup(targetId: Int, option: com.hereliesaz.cleanunderwear.domain.LookupOption?) {
        val choice = _choices.value.firstOrNull { it.targetId == targetId } ?: return
        _choices.value = _choices.value.filter { it.targetId != targetId }
        viewModelScope.launch(failSafe) {
            withContext(Dispatchers.IO) { lookupChoices.remove(setOf(targetId)) }
            pipelineCoordinator.runExclusive("lookup-choice") {
                repository.inTransaction {
                    val target = repository.getTargetById(targetId) ?: return@inTransaction
                    repository.updateTarget(
                        if (option != null) {
                            cbcEnricher.adopt(target, option).copy(
                                // Details resolve UNVERIFIED; any other status stands.
                                status = if (target.status == TargetStatus.UNVERIFIED) TargetStatus.MONITORING else target.status,
                                monitorabilityState = com.hereliesaz.cleanunderwear.data.MonitorabilityState.READY
                            )
                        } else {
                            target.copy(
                                enrichmentProvenance = cbcEnricher.noneOfThem(choice.options.size),
                                monitorabilityState = if (target.status == TargetStatus.UNVERIFIED) {
                                    com.hereliesaz.cleanunderwear.data.MonitorabilityState.ENRICHMENT_FAILED
                                } else {
                                    target.monitorabilityState
                                }
                            )
                        }
                    )
                }
            }
        }
    }

    private val _alternates = MutableStateFlow<AlternateInfo?>(null)

    /** The lookup's offer for a contact whose number was just ruled out; null when none is pending. */
    val alternates: StateFlow<AlternateInfo?> = _alternates

    /**
     * Search CyberBackgroundChecks for current info on a contact whose number is stale or was
     * split off. Same visible-browser flow as Resolve, so it needs the user's attention and is only
     * ever started by a review verdict or the "Look up alternates" button.
     */
    fun findAlternates(targetId: Int, stalePhone: String?) {
        viewModelScope.launch(failSafe) {
            exclusive("find-alternates", "The lookup for a replacement number") {
                resolveCancellationRequested = false
                try {
                    val target = repository.getTargetById(targetId) ?: return@exclusive
                    val missions = alternateFinder.missions(target)
                    if (missions.isEmpty()) {
                        _alternates.value = AlternateInfo(
                            target.id, target.displayName, stalePhone, null, false,
                            phone = null, address = null, basis = "nothing to search on"
                        )
                        return@exclusive
                    }
                    begin(OperationState(
                        description = "Looking up ${target.displayName}…",
                        progress = -1f
                    ))
                    val extracted = mutableListOf<Pair<BrowserMission, String>>()
                    val outcome = launchMissionsAndAwait(missions) { mission, html ->
                        // One result per search: a page re-extracted after a rotation replaces the first.
                        extracted.removeAll { it.first == mission }
                        extracted.add(mission to html)
                    }
                    if (outcome == BatchOutcome.UserCancelled || resolveCancellationRequested) return@exclusive

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
        viewModelScope.launch(failSafe) {
            pipelineCoordinator.runExclusive("apply-alternates") {
                val target = repository.getTargetById(info.targetId) ?: return@runExclusive
                repository.updateTarget(
                    target.copy(
                        phoneNumber = info.phone ?: target.phoneNumber,
                        // The replaced number is remembered (see SuspectReview.adoptReplacement).
                        stalePhone = if (info.phone != null && info.phone != target.phoneNumber) target.phoneNumber ?: target.stalePhone else target.stalePhone,
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
        viewModelScope.launch(failSafe) {
            pipelineCoordinator.runExclusive("review-verdict") { action() }
            val state = _reviewState.value
            _reviewState.value = state.copy(
                report = state.report?.let { r -> r.copy(cases = r.cases.filter { it.key != case.key }) }
            )
            if (rescan) refreshSuspects()
        }
    }

    /**
     * Applies the user's [change] to the contact as it is now, not to the copy on screen ([shown]),
     * so a daily check or lookup that wrote it meanwhile isn't undone. A verdict on a match (one
     * that changes the status) is refused if the match itself changed since the user saw it.
     */
    fun updateTarget(shown: Target, change: (Target) -> Target) {
        viewModelScope.launch(failSafe) {
            val verdict = change(shown).status != shown.status
            val written = repository.inTransaction {
                val current = repository.getTargetById(shown.id) ?: return@inTransaction null
                val matchMoved = current.status != shown.status ||
                    current.matchRecordKey != shown.matchRecordKey ||
                    current.lastVerificationSnippet != shown.lastVerificationSnippet
                if (verdict && matchMoved) return@inTransaction null
                val next = change(current)
                val stamped = if (next.status != current.status) next.copy(lastStatusChangeTimestamp = System.currentTimeMillis()) else next
                repository.updateTarget(stamped)
                stamped.takeIf { it.status != current.status }
            }
            if (verdict && written == null) post("${shown.displayName} changed while you were looking. Check it again.")
            // A status the user just confirmed or cleared goes to the phone-book note now, not at
            // the next check.
            written?.let { syncNote(it.id) }
        }
    }

    fun ignoreTarget(id: Int) {
        viewModelScope.launch(failSafe) {
            repository.ignore(id, System.currentTimeMillis())
            syncNote(id)
        }
    }

    fun restoreTarget(id: Int) {
        viewModelScope.launch(failSafe) {
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
        viewModelScope.launch(failSafe) {
            exclusive("delete-everything", "Delete everything") {
                // Half a deletion is worse than none; it's quick, so it runs to the end.
                begin(OperationState(description = "Deleting…", progress = -1f, cancellable = false))
                val notes = withContext(Dispatchers.IO) { contactSyncer.removeAllBlocks() }
                repository.wipeAll()
                // What the app holds in memory goes too: the activity log names contacts, and the
                // Review list and any lookup offer are about the list just deleted.
                com.hereliesaz.cleanunderwear.util.DiagnosticLogger.clear()
                _reviewState.value = ReviewState()
                _alternates.value = null
                withContext(Dispatchers.IO) { lookupChoices.clear() }
                _choices.value = emptyList()
                _operationState.value = OperationState(isRunning = false)
                fun contacts(n: Int) = "$n contact${if (n == 1) "" else "s"}"
                post(
                    when {
                        notes.noAccess -> "Deleted the app's data. The notes it added to your contacts weren't removed: " +
                            "the app can't see your contacts. Allow contacts access and run Delete everything again to remove them."
                        notes.failed > 0 -> "Deleted the app's data and the notes on ${contacts(notes.removed)}. " +
                            "The notes on ${contacts(notes.failed)} couldn't be changed; run Delete everything again to retry."
                        else -> "Deleted the app's data and its notes on ${contacts(notes.removed)}."
                    }
                )
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

    /**
     * Adds one person. A single insert, so it doesn't take the busy screen (which belongs to
     * whatever longer operation may be running, and its Cancel); the next read of contacts
     * folds the row in with any duplicate.
     */
    fun addManualTarget(name: String, phone: String?, email: String?) {
        viewModelScope.launch(failSafe) {
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
            
            try {
                harvestContactsUseCase.processManualTargets(listOf(target))
                post("Added $name.")
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                post("Couldn't add $name. Try again.")
            }
        }
    }

    /**
     * Reads the phone's contacts (and the other enabled sources) into the list.
     *
     * [atLaunch] is the read the app starts by itself when it opens: it shows as the bar at the
     * bottom ([operationInBackground]) instead of the full-screen busy dialog, which otherwise
     * covered whatever the user opened the app for (a notification's contact most of all). A tap
     * on Import contacts is the user asking, and keeps the full screen. The launch call is not
     * told apart by its caller (MainActivity and the rail both call this with no argument), so it
     * is the first call within [LAUNCH_SWEEP_WINDOW_MS] of this ViewModel's creation: nobody can
     * reach the rail's menu that fast.
     */
    fun sweepContacts(
        atLaunch: Boolean = isLaunchSweep(viewModelCreatedAt, android.os.SystemClock.elapsedRealtime(), sweepRequestedBefore)
    ) {
        sweepRequestedBefore = true
        // Every account, always: the list's filter chips change what is shown, never what is
        // read (hiding Google contacts from view used to skip them on the next import).
        // A sweep already queued or running covers this request too.
        if (sweepPending) return
        sweepPending = true
        viewModelScope.launch(failSafe) {
            try {
                // Serialised with the daily check and every other job that writes the registry,
                // and run off the main thread: harvest, name checks and dedup are heavy.
                exclusive("contact-sweep", "Reading your contacts") { try {
                    begin(
                        OperationState(description = "Reading contacts…", progress = 0.1f, continues = Continues.IN_APP),
                        inBackground = atLaunch
                    )
                    val before = repository.getTargetsPaged(limit = Int.MAX_VALUE, offset = 0).size
                    withContext(Dispatchers.Default) {
                        // What has turned up so far, under the loader.
                        val found = mutableListOf<String>()
                        fun note(line: String) {
                            found += line
                            _operationState.value = _operationState.value.copy(findings = found.toList())
                        }

                        // 1. Ingest raw data from enabled sources
                        val harvest = harvestContactsUseCase()
                        val read = repository.getTargetsPaged(limit = Int.MAX_VALUE, offset = 0).size
                        note("$read contact${if (read == 1) "" else "s"} in the list (${(read - before + harvest.removed).coerceAtLeast(0)} new)")
                        if (harvest.removed > 0) {
                            note("${harvest.removed} no longer in your phone's contacts, so no longer checked")
                        }

                        // 2. Consolidate and deduplicate the full registry
                        _operationState.value = _operationState.value.copy(description = "Merging duplicates…", progress = 0.4f)
                        val merged = deduplicateTargetsUseCase { sub, _ ->
                            _operationState.value = _operationState.value.copy(progress = 0.4f + (sub * 0.3f))
                        }
                        note(if (merged == 0) "No duplicates" else "Merged $merged duplicate${if (merged == 1) "" else "s"}")

                        // 3. Triage for monitorability
                        _operationState.value = _operationState.value.copy(description = "Preparing contacts for daily checks…", progress = 0.7f)
                        val triaged = triageTargetsUseCase { sub, _ ->
                            _operationState.value = _operationState.value.copy(progress = 0.7f + (sub * 0.3f))
                        }
                        note(
                            "${triaged.ready} ready for daily checks" +
                                if (triaged.needsEnrichment > 0) " · ${triaged.needsEnrichment} need more details" else ""
                        )
                    }
                    val after = repository.getTargetsPaged(limit = Int.MAX_VALUE, offset = 0).size
                    _operationState.value = OperationState(isRunning = false)
                    post(
                        if (after > before) "Imported ${after - before} new contact${if (after - before == 1) "" else "s"}; $after in all."
                        else "No new contacts; $after in all."
                    )
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    com.hereliesaz.cleanunderwear.util.DiagnosticLogger.log("Import failed: ${e.javaClass.simpleName}", com.hereliesaz.cleanunderwear.util.DiagnosticLogger.LogEntry.LogLevel.ERROR)
                    post("Importing contacts failed. Try again; if it keeps failing, the activity log shows where.")
                } }
            } finally {
                sweepPending = false
                if (_operationState.value.isRunning) _operationState.value = OperationState(isRunning = false)
            }
        }
    }

    @Volatile private var sweepPending = false

    /** When this ViewModel was made, to tell the contact read at launch from one the user asked for. */
    private val viewModelCreatedAt = android.os.SystemClock.elapsedRealtime()

    /** Whether [sweepContacts] has been called yet; only the first call can be the launch one. */
    @Volatile private var sweepRequestedBefore = false

    fun harvestFacebook() = harvestInBrowser(
        "facebook-harvest", BrowserMission.HarvestFacebookFriends, "Facebook friends",
        com.hereliesaz.cleanunderwear.util.WebSessions.FACEBOOK
    ) { fbHarvester.parseFriendsHtml(it) }

    /** How many contacts "Fill in details" would look up, for the confirmation before it starts. */
    suspend fun resolveCandidateCount(): Int = resolveCandidates().size

    private suspend fun resolveCandidates(): List<Target> {
        val limit = _globalTargetLimit.value.takeIf { it > 0 } ?: Int.MAX_VALUE
        // Every contact, a page at a time; large phone books run past one page.
        val all = mutableListOf<com.hereliesaz.cleanunderwear.data.TargetWorkInfo>()
        var offset = 0
        while (true) {
            val page = repository.getTargetWorkInfoPaged(limit = 5000, offset = offset)
            if (page.isEmpty()) break
            all += page
            offset += page.size
        }
        val ids = all
            .filter { it.status != TargetStatus.IGNORED }
            .map { it.id }
            .sorted()
            .take(limit)
        return ids.chunked(500)
            .flatMap { repository.getTargetsByIds(it) }
            .filter { it.status == TargetStatus.UNVERIFIED || it.dateOfBirth.isNullOrBlank() }
            // A contact the site already said it has nobody for, or whose results the user has
            // answered, isn't looked up again in every run; "Look up details" on its own
            // screen still tries it. Nor is one whose results are still waiting for an answer.
            .filterNot {
                it.enrichmentProvenance?.startsWith("nobody found by") == true ||
                    it.enrichmentProvenance?.startsWith(com.hereliesaz.cleanunderwear.network.CyberBackgroundChecksEnricher.NONE_PREFIX) == true ||
                    // Someone the user already picked: another search finds the same people.
                    it.enrichmentProvenance?.contains(com.hereliesaz.cleanunderwear.network.CyberBackgroundChecksEnricher.CHOSEN_PREFIX) == true ||
                    _choices.value.any { c -> c.targetId == it.id }
            }
    }

    fun resolveUnverifiedBatch() = resolveContacts { resolveCandidates() }

    /** Looks up one contact's missing details, from its detail screen. */
    fun resolveOne(id: Int) = resolveContacts { listOfNotNull(repository.getTargetById(id)) }

    @Volatile private var resolvePending = false

    /**
     * Looks up every contact [select] picks (UNVERIFIED ones, and any still missing an age, which
     * is what lets [com.hereliesaz.cleanunderwear.match.MatchVerifier] tell two people with the
     * same name apart) through the visible cyberbackgroundchecks browser, one contact at a time.
     * Nothing is decided during the run: everyone each search turns up is kept in [choices], and
     * when the queue is done (or stopped) the user is taken to choose, contact by contact, which
     * person is theirs, if any. Contacts no search found anyone for are noted and passed over.
     *
     * Per the user's rule, this is *user-initiated*; the auto pipeline never triggers it. If the
     * auto pipeline (or any other coordinator-protected job) is in flight, it queues until that finishes.
     */
    private fun resolveContacts(select: suspend () -> List<Target>) {
        // One run of lookups at a time; a tap while one waits or runs doesn't queue another.
        if (resolvePending) {
            post("A lookup is already running or waiting to start.")
            return
        }
        resolvePending = true
        viewModelScope.launch(failSafe) {
            try {
            noteIfQueued("The lookups")
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
                begin(OperationState(
                    description = "Looking up 1 of $total on CyberBackgroundChecks…",
                    progress = 0f
                ))
                // Contacts with people to choose from. Nobody is asked mid-run: every contact is
                // looked up first, and the choosing comes after, all together.
                var toChoose = 0
                fun finish(message: String) {
                    _operationState.value = OperationState(isRunning = false)
                    if (toChoose > 0) {
                        post("$message Choose who's who for $toChoose contact${if (toChoose == 1) "" else "s"}.")
                        _openChoices.value = true
                    } else {
                        post(message)
                    }
                }
                // What has turned up so far, newest first, under the loader and in the browser.
                val results = ArrayDeque<String>()
                fun noteResult(done: Int, line: String) {
                    results.addFirst(line)
                    while (results.size > RECENT_RESULTS) results.removeLast()
                    _operationState.value = _operationState.value.copy(
                        findings = listOf("$done of $total looked up · people found for $toChoose, to choose from at the end") + results
                    )
                }

                for ((index, lite) in unverified.withIndex()) {
                    if (resolveCancellationRequested) { finish("Stopped after $index of $total."); return@runExclusive }
                    val target = repository.getTargetById(lite.id) ?: continue
                    val missions = cbcEnricher.pickAllMissions(target)
                    if (missions.isEmpty()) {
                        repository.updateMonitorabilityState(
                            target.id,
                            com.hereliesaz.cleanunderwear.data.MonitorabilityState.ENRICHMENT_FAILED
                        )
                        noteResult(index + 1, "${target.displayName}: nothing to search by")
                        continue
                    }

                    _operationState.value = _operationState.value.copy(
                        description = "Looking up ${target.displayName} (${index + 1} of $total)",
                        progress = index.toFloat() / total
                    )

                    // WebView callbacks run on the UI thread. Keep them cheap:
                    // Jsoup parsing can stall the entire browser on large pages.
                    val extracted = mutableListOf<Pair<BrowserMission, String>>()
                    val outcome = launchMissionsAndAwait(missions) { mission, html ->
                        // One result per search: a page re-extracted after a rotation replaces the first.
                        extracted.removeAll { it.first == mission }
                        extracted.add(mission to html)
                    }

                    if (outcome == BatchOutcome.UserCancelled || resolveCancellationRequested) {
                        // User backed out mid-target — stop the whole batch
                        // rather than barrel through every UNVERIFIED contact
                        // without their attention.
                        finish("Stopped after $index of $total.")
                        return@runExclusive
                    }

                    // A search that says it found nobody is noted and passed over, not parsed:
                    // its page chrome would only be mistaken for a person.
                    val nobody = withContext(Dispatchers.Default) {
                        extracted.filter { (_, html) -> cbcEnricher.foundNobody(html) }.map { it.first }
                    }
                    if (nobody.isNotEmpty()) {
                        com.hereliesaz.cleanunderwear.util.DiagnosticLogger.log(
                            "CYBG: nobody found for ${target.displayName} (${nobody.joinToString { it.label }})"
                        )
                    }
                    // Every person on every results page, not only the first: which one is the
                    // contact is the user's call, made after the run.
                    val collected = withContext(Dispatchers.Default) {
                        extracted.filterNot { (mission, _) -> mission in nobody }.flatMap { (mission, html) ->
                            val people = cbcEnricher.parsePeople(html)
                            com.hereliesaz.cleanunderwear.util.DiagnosticLogger.log(
                                "CYBG parse ${mission.label}: ${people.size} people · ${cbcEnricher.describeParse(html, people.firstOrNull())}"
                            )
                            people.map { mission to it }
                        }
                    }
                    if (resolveCancellationRequested) { finish("Stopped after $index of $total."); return@runExclusive }

                    if (collected.isNotEmpty()) {
                        val options = cbcEnricher.options(target, collected)
                        _choices.value = withContext(Dispatchers.IO) {
                            lookupChoices.put(com.hereliesaz.cleanunderwear.domain.LookupChoice(target.id, target.displayName, options))
                        }
                        toChoose++
                        noteResult(index + 1, "${target.displayName}: ${options.size} ${if (options.size == 1) "person" else "people"} found")
                        continue
                    }

                    // Nobody to choose from: the search found no one, or its page couldn't be read.
                    // Noted against the contact as it is now: the user may have spent minutes in
                    // the browser, and a check or an edit may have written it meanwhile.
                    val id = target.id
                    repository.inTransaction {
                        @Suppress("NAME_SHADOWING")
                        val target = repository.getTargetById(id) ?: return@inTransaction
                        val outcome = cbcEnricher.enrich(target, emptyList(), foundNobody = nobody)
                        repository.updateTarget(
                            outcome.target.copy(
                                monitorabilityState = if (target.status == TargetStatus.UNVERIFIED) {
                                    com.hereliesaz.cleanunderwear.data.MonitorabilityState.ENRICHMENT_FAILED
                                } else {
                                    target.monitorabilityState
                                }
                            )
                        )
                    }
                    noteResult(index + 1, "${target.displayName}: " + if (nobody.isNotEmpty()) "nobody found" else "couldn't read the page")
                }

                finish("Looked up $total contact${if (total == 1) "" else "s"}.")
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
            } finally {
                resolvePending = false
            }
        }
    }

    /**
     * Launches a [BrowserMission] and suspends until BrowserScreen reports a
     * result (auto-extracted) or cancels (user tapped back / Done). Single-
     * mission flows still use this; the batch enricher uses
     * [launchMissionsAndAwait].
     *
     * The wait is on a person, and it holds the pipeline lock, so it has a limit
     * ([USER_WAIT_IMPORT_MS]): a sign-in or CAPTCHA left unanswered ends the page and the job,
     * with a message, rather than keeping the daily check and Delete everything queued behind it.
     */
    private suspend fun launchMissionAndAwait(mission: BrowserMission): String? {
        val deferred = CompletableDeferred<String?>()
        launchMission(mission) { html -> deferred.complete(html) }
        return awaitWithin(deferred, USER_WAIT_IMPORT_MS) { giveUpWaiting(USER_WAIT_IMPORT_MS); null }
    }

    /** The user left the browser waiting for [limitMs]: close it, so the job holding the pipeline ends. */
    private fun giveUpWaiting(limitMs: Long) {
        cancelMissionBatch()
        post("The browser sat waiting for you for ${limitMs / 60_000} minutes, so that was stopped. Start it again when you're ready.")
    }

    private enum class BatchOutcome { Completed, UserCancelled }

    /**
     * Launches a queue of missions in the visible BrowserScreen and suspends
     * until the queue completes or the user cancels. [onEachExtracted] is
     * invoked per page that produced HTML; the suspend value indicates
     * whether the queue ran to completion. Like [launchMissionAndAwait], the wait is limited
     * ([USER_WAIT_LOOKUP_MS]) and counts as the user having stopped.
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
        return awaitWithin(deferred, USER_WAIT_LOOKUP_MS) { giveUpWaiting(USER_WAIT_LOOKUP_MS); BatchOutcome.UserCancelled }
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
     * Opens [mission] in the visible browser, where the user signs in (or links WhatsApp) if the
     * service needs it, and imports what [parse] reads from the page it hands back. The browser
     * leaves the sign-in page to the user, un-scrimmed, and reads only once they are in
     * (BrowserMission.requiresSignIn), so the page is never dumped while they are still signing in.
     */
    private fun harvestInBrowser(key: String, mission: BrowserMission, what: String, hosts: List<String>, parse: (String) -> List<Target>) {
        viewModelScope.launch(failSafe) {
            exclusive(key, "Importing $what") {
                try {
                    begin(OperationState(description = "Importing $what. Sign in if asked.", progress = -1f))
                    val html = launchMissionAndAwait(mission)
                    if (html.isNullOrBlank()) return@exclusive
                    _operationState.value = _operationState.value.copy(description = "Reading what was found in $what…")
                    // Parsing a followers or contacts page is heavy; not on the main thread.
                    val found = withContext(Dispatchers.Default) { parse(html) }
                    com.hereliesaz.cleanunderwear.util.DiagnosticLogger.log("Import: $what yielded ${found.size} contact(s).")
                    harvestContactsUseCase.processManualTargets(found)
                    post(if (found.isEmpty()) "Nothing was read from $what. If you weren't signed in, try again." else "Read ${found.size} from $what.")
                } finally {
                    // The sign-in was for this import only. By now the page has been read and the
                    // browser closed, so this never wipes a sign-in the import is still using.
                    com.hereliesaz.cleanunderwear.util.WebSessions.forget(hosts)
                    _operationState.value = OperationState(isRunning = false)
                }
            }
        }
    }

    fun triggerManualInterrogation() {
        // One manual check at a time: a second tap while one runs joins it.
        manualCheckCancelled = false
        val request = OneTimeWorkRequestBuilder<ScrapingWorker>().build()
        workManager.enqueueUniqueWork("manual_check", androidx.work.ExistingWorkPolicy.KEEP, request)
        if (watchingManualCheck) return
        watchingManualCheck = true

        viewModelScope.launch(failSafe) {
            try {
                workManager.getWorkInfosForUniqueWorkFlow("manual_check")
                    // This run, or one already in progress that the tap joined; never an old result.
                    .map { infos -> infos.firstOrNull { it.id == request.id || !it.state.isFinished } }
                    .onEach { onManualCheck(it) }
                    .first { it != null && it.state.isFinished }
            } finally {
                watchingManualCheck = false
            }
        }
    }

    @Volatile private var watchingManualCheck = false

    /** Set by Cancel, so a last progress report from the stopping work doesn't bring the busy screen back. */
    @Volatile private var manualCheckCancelled = false

    private fun onManualCheck(workInfo: androidx.work.WorkInfo?) {
        when (workInfo?.state) {
            androidx.work.WorkInfo.State.RUNNING -> {
                if (manualCheckCancelled) return
                // The worker reports once it has the pipeline; until then it is queued behind
                // whatever holds the screen (a contact read), which keeps it.
                val started = workInfo.progress.getString(ScrapingWorker.DESCRIPTION) != null
                if (!started && _operationState.value.isRunning) return
                if (!_operationState.value.isRunning) _operationInBackground.value = false
                // The check runs in WorkManager, not here: Cancel stops the work itself.
                cancelRunning = {
                    manualCheckCancelled = true
                    workManager.cancelUniqueWork("manual_check")
                }
                _operationState.value = OperationState(
                    isRunning = true,
                    description = workInfo.progress.getString(ScrapingWorker.DESCRIPTION) ?: "Checking records…",
                    progress = workInfo.progress.getFloat(ScrapingWorker.PROGRESS, -1f),
                    continues = Continues.ANYWHERE,
                    findings = workInfo.progress.getStringArray(ScrapingWorker.FINDINGS)?.toList().orEmpty()
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
            androidx.work.WorkInfo.State.CANCELLED -> _operationState.value = OperationState(isRunning = false)
            else -> Unit
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

/** How many contacts' results Fill in details keeps on screen, newest first. */
private const val RECENT_RESULTS = 5

/** Automatic update checks run at most this often; the manual check in Settings ignores it. */
private const val AUTO_UPDATE_CHECK_INTERVAL_MS = 12L * 60 * 60 * 1000

/**
 * How long a contact's lookups may sit waiting on the user in the browser (a CAPTCHA, a stray tap
 * on Back) before the run gives up. The wait holds the pipeline lock, so without a limit an
 * abandoned browser keeps the daily check and Delete everything queued behind it for good.
 */
internal const val USER_WAIT_LOOKUP_MS = 15L * 60 * 1000

/** The same limit for an import, where the user may be signing in, linking WhatsApp, or answering 2FA. */
internal const val USER_WAIT_IMPORT_MS = 20L * 60 * 1000

/** A contacts read requested within this long of the ViewModel's creation, and the first, is the launch one. */
internal const val LAUNCH_SWEEP_WINDOW_MS = 10_000L

/**
 * Awaits [deferred] for at most [limitMs]; past that, runs [onTimeout] and returns what it gives.
 * The deferred itself is left alone (a late answer is ignored by whoever completes it).
 */
internal suspend fun <T> awaitWithin(deferred: Deferred<T>, limitMs: Long, onTimeout: () -> T): T =
    try {
        withTimeout(limitMs) { deferred.await() }
    } catch (e: TimeoutCancellationException) {
        onTimeout()
    }

/**
 * Whether a contacts read requested at [now] (both in elapsed-realtime milliseconds) is the one
 * the app starts by itself at launch: the first request ([alreadyRequested] false), arriving
 * within [LAUNCH_SWEEP_WINDOW_MS] of [createdAt]. See MainViewModel.sweepContacts.
 */
internal fun isLaunchSweep(createdAt: Long, now: Long, alreadyRequested: Boolean): Boolean =
    !alreadyRequested && now - createdAt in 0..LAUNCH_SWEEP_WINDOW_MS

/** What to tell the user when [what] has to wait for [running] (the busy job's description, if known). */
internal fun queuedNotice(what: String, running: String?): String =
    if (running.isNullOrBlank()) "$what will start once the job now running is done."
    else "$what will start once this is done: $running"
