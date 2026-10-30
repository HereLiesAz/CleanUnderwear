package com.hereliesaz.cleanunderwear.ui

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.MutableContextWrapper
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceError
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.hereliesaz.aznavrail.AzButton
import com.hereliesaz.aznavrail.model.AzButtonShape
import com.hereliesaz.cleanunderwear.R
import com.hereliesaz.cleanunderwear.domain.BrowserMission
import com.hereliesaz.cleanunderwear.domain.PageProbe
import com.hereliesaz.cleanunderwear.util.DiagnosticLogger
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import java.io.ByteArrayInputStream

/**
 * Outcome of running one mission's extraction script. The screen reports this
 * to the caller after each page; the caller decides what to do with the HTML.
 */
sealed class MissionOutcome {
    data class Extracted(val html: String) : MissionOutcome()
    object Blocked : MissionOutcome()
}

/**
 * User-visible WebView for missions that require the user's real browser
 * session (cookies, captcha, login). Drives a queue of missions: loads each
 * URL, waits for the SPA to settle, auto-injects the extraction script, and
 * advances on success.
 *
 * If the page looks like a captcha / anti-bot wall / blank body
 * ([isBlocked] returns true), the screen pauses and surfaces a
 * "Solve & continue / Skip / Abort" overlay so the user can recover.
 *
 * An import that needs the user signed in (BrowserMission.requiresSignIn) is different: the
 * page is checked first, and while it is a sign-in or link-your-phone page the scrim stays off
 * and the page is the user's; the script runs once it is the page to read.
 *
 * Cookies persist across missions and Resolve contacts because the WebView is
 * reused. [batchId] resets the extraction state without rebuilding the browser, and a
 * rotation keeps the page rather than reloading it ([BrowserRetention]).
 * Delayed JavaScript results carry unique tags so they cannot cross lookups.
 */
@OptIn(ExperimentalMaterial3Api::class)
@SuppressLint("SetJavaScriptEnabled", "JavascriptInterface")
@Composable
fun BrowserScreen(
    batchId: Long,
    missions: List<BrowserMission>,
    onMissionResult: (BrowserMission, MissionOutcome) -> Unit,
    onAllComplete: () -> Unit,
    onCancel: () -> Unit,
    isBlocked: (String) -> Boolean = { false },
    /** Who or what this is for ("Looking up Jane Doe (4 of 212)"); the mission label if null. */
    context: String? = null,
    /** Whether backing out ends a whole run of lookups, not just this page. */
    stopsBatch: Boolean = false,
    /** What the run has turned up so far, shown under the loader while a page is read. */
    findings: List<String> = emptyList(),
) {
    if (missions.isEmpty()) {
        // Defensive: nothing to do.
        LaunchedEffect(Unit) { onAllComplete() }
        return
    }

    // Saved across rotation: a recreated screen resumes at the same search, not the first.
    var currentIndex by androidx.compose.runtime.saveable.rememberSaveable(batchId) { mutableIntStateOf(0) }
    var batchComplete by androidx.compose.runtime.saveable.rememberSaveable(batchId) { mutableStateOf(false) }
    val hostContext = LocalContext.current
    // The page and its bridge outlive a rotation (see BrowserRetention): a rebuilt screen takes
    // the same WebView back rather than reloading the mission's URL over a half-done sign-in.
    val host = remember { BrowserRetention.take() ?: BrowserHost() }
    val extractionGate = remember { BrowserExtractionGate(host.bridgeName) }
    var pageReady by remember { mutableStateOf(false) }
    var automationRan by remember { mutableStateOf(false) }
    var paused by remember { mutableStateOf(false) }
    // The page in front is a sign-in / link-your-phone page that an import can't read: the scrim
    // is off and the page is the user's until it changes (BrowserMission.requiresSignIn).
    var needsSignIn by remember { mutableStateOf(false) }
    // How often a dump has turned out to be a sign-in page; past a few, stop bouncing and pause.
    var signInBounces by remember { mutableIntStateOf(0) }
    var mainFrameFailed by remember { mutableStateOf(false) }
    // Increments on every page-finish. Drill-in missions (CBC) re-run their
    // extraction script on each navigation, keyed off this counter.
    var pageLoadCount by remember { mutableIntStateOf(0) }
    var webView: WebView? by remember { mutableStateOf(null) }

    val mission = missions[currentIndex]

    fun cancel() {
        batchComplete = true
        extractionGate.finish()
        webView?.stopLoading()
        onCancel()
    }

    fun advance() {
        if (batchComplete) return
        extractionGate.invalidate()
        if (currentIndex + 1 >= missions.size) {
            batchComplete = true
            extractionGate.finish()
            webView?.stopLoading()
            onAllComplete()
        } else {
            currentIndex += 1
        }
    }

    // Hands the page to the user to sign in: the scrim lifts, the prompt shows, and the page is
    // polled until it is no longer a sign-in page (a sign-in often changes the page without a
    // navigation, WhatsApp's link page most of all), when the import carries on.
    fun waitForSignIn() {
        extractionGate.invalidate()
        automationRan = false
        needsSignIn = true
    }

    // Wait for AndroidView to provide the WebView: launching on just the
    // mission index can silently skip the initial URL when the view is null.
    LaunchedEffect(batchId, currentIndex, webView) {
        val activeView = webView ?: return@LaunchedEffect
        val missionKey = "$batchId-$currentIndex"
        extractionGate.reset()
        batchComplete = false
        pageReady = false
        automationRan = false
        paused = false
        needsSignIn = false
        signInBounces = 0
        mainFrameFailed = false
        pageLoadCount = 0
        if (host.loadedKey == missionKey && !activeView.url.isNullOrEmpty()) {
            // A rotation rebuilt the screen around the page this mission already has open: pick
            // it up where it is rather than reloading it over a half-finished sign-in or CAPTCHA.
            // If it is still loading, onPageFinished takes it from here.
            if (activeView.progress >= 100) {
                pageReady = true
                pageLoadCount = 1
            }
            DiagnosticLogger.log("BrowserScreen: resumed mission ${currentIndex + 1}/${missions.size} after rotation")
        } else {
            activeView.stopLoading()
            activeView.loadUrl(mission.initialUrl)
            host.loadedKey = missionKey
            DiagnosticLogger.log("BrowserScreen: loading mission ${currentIndex + 1}/${missions.size}: ${mission.initialUrl}")
        }
    }

    // The JavaScript bridge was constructed with AndroidView's initial
    // mission. Always dispatch to the latest Compose callback instead, and
    // ignore callbacks from pages whose extraction is no longer underway.
    val onHtmlExtracted by rememberUpdatedState<(String?, String?, String?) -> Unit>(
        newValue = { token, missionKey, html ->
            if (!batchComplete && pageReady && automationRan && !paused && extractionGate.accept(token, missionKey)) {
                val safe = html.orEmpty()
                if (mission.requiresSignIn && mission.looksSignedOut(safe)) {
                    // The script dumped a sign-in page after all (a session that lapsed mid-read):
                    // not data. Hand the page back to the user instead of importing "nothing"; if
                    // it keeps happening while the page looks signed in, stop bouncing and pause.
                    DiagnosticLogger.log("BrowserScreen: mission ${currentIndex + 1} read a sign-in page; waiting for the user")
                    if (signInBounces < MAX_SIGN_IN_BOUNCES) {
                        signInBounces += 1
                        waitForSignIn()
                    } else {
                        paused = true
                    }
                } else if (isBlocked(safe)) {
                    DiagnosticLogger.log("BrowserScreen: mission ${currentIndex + 1} blocked, pausing for user")
                    paused = true
                } else {
                    onMissionResult(mission, MissionOutcome.Extracted(safe))
                    advance()
                }
            }
        }
    )

    // Once the page reports ready, give the SPA a moment to render results,
    // then auto-evaluate the extraction script. Browse-only missions
    // (autoExtract == false) skip this entirely — the user dismisses them
    // manually via the back arrow.
    //
    // Keyed on pageLoadCount so missions that re-run after navigation (CBC,
    // whose results may arrive only after a CAPTCHA or a redirect; imports whose
    // scripts walk to the list) extract from the new page. The rest extract
    // exactly once (the automationRan guard).
    //
    // An import (requiresSignIn) first checks the page is not a sign-in page: if it is, the page
    // is the user's (needsSignIn) and nothing is injected until it stops being one.
    LaunchedEffect(batchId, currentIndex, pageLoadCount, paused, needsSignIn) {
        if (!mission.autoExtract || batchComplete) return@LaunchedEffect
        if (paused || needsSignIn || !pageReady) return@LaunchedEffect
        if (!mission.rerunAfterNavigation && automationRan) return@LaunchedEffect
        // Wait for the page to settle rather than a fixed beat: a slow SPA would otherwise be
        // dumped empty and read as "nothing found".
        awaitRenderedContent(webView)
        if (!paused && !batchComplete && pageReady) {
            if (mission.requiresSignIn) {
                val page = probePage(webView)
                if (page != null && mission.isSignInPage(page)) {
                    DiagnosticLogger.log("BrowserScreen: mission ${currentIndex + 1} is on a sign-in page; waiting for the user")
                    waitForSignIn()
                    return@LaunchedEffect
                }
            }
            val missionKey = "$batchId-$currentIndex"
            val token = extractionGate.beginExtraction(missionKey) ?: return@LaunchedEffect
            val script = extractionGate.tagScript(
                mission.extractionScript,
                token,
                missionKey
            )
            automationRan = true
            webView?.evaluateJavascript(script, null)
        }
    }

    // While the user signs in: look at the page now and then, and take it back when it is the
    // page to read. Polling, because the page often changes without a navigation (the QR page
    // turns into the chat list in place) and then no page-finished event says so.
    LaunchedEffect(batchId, currentIndex, needsSignIn) {
        if (!needsSignIn) return@LaunchedEffect
        while (!batchComplete) {
            delay(SIGN_IN_POLL_MS)
            val page = probePage(webView) ?: continue
            if (!mission.isSignInPage(page)) {
                DiagnosticLogger.log("BrowserScreen: mission ${currentIndex + 1} is past the sign-in page; carrying on")
                needsSignIn = false
                return@LaunchedEffect
            }
        }
    }

    // Watchdog: never leave the screen darkened indefinitely if a page
    // fails to finish loading or an extraction never calls back. At 30s,
    // unmask the browser and let the user retry, skip, or abort.
    // Browse-only missions are exempt: there's no extraction to time out.
    // Time the user spends signing in is not counted (the clock restarts when the page is
    // handed back), so a slow sign-in or QR link never trips it.
    LaunchedEffect(batchId, currentIndex, paused, needsSignIn) {
        if (!mission.autoExtract || batchComplete) return@LaunchedEffect
        if (paused || needsSignIn) return@LaunchedEffect
        delay(mission.watchdogMs)
        if (!paused && !batchComplete) {
            DiagnosticLogger.log(
                "BrowserScreen: mission ${currentIndex + 1} timed out (pageReady=$pageReady, automationRan=$automationRan); pausing",
                DiagnosticLogger.LogEntry.LogLevel.WARN
            )
            paused = true
        }
    }

    // Pause the page with the app: no loading or scripts while it's in the background.
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            when (event) {
                androidx.lifecycle.Lifecycle.Event.ON_PAUSE -> webView?.onPause()
                androidx.lifecycle.Lifecycle.Event.ON_RESUME -> webView?.onResume()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    DisposableEffect(Unit) {
        onDispose {
            extractionGate.finish()
            CookieManager.getInstance().flush()
            val view = webView
            // The Activity is being rebuilt for a rotation (or another configuration change) and the
            // mission is still going: keep the page alive for the new screen instead of destroying
            // it, so a half-finished sign-in or CAPTCHA survives. Anything else ends the page.
            val rotating = hostContext.findActivity()?.isChangingConfigurations == true && !batchComplete
            if (view != null && rotating) {
                BrowserRetention.park(host, view)
            } else {
                BrowserRetention.destroy(view)
                host.view = null
            }
            webView = null
        }
    }

    // System Back: on a browse-only page, or one waiting for the user (a sign-in, a CAPTCHA), one
    // page back in the site, as in any browser; otherwise the same as the top bar's Stop, asked
    // first so a stray gesture doesn't end a run of lookups. Without this, Back closed the app
    // mid-run. A browse-only page never pauses, so it steps back whenever it can.
    var confirmStop by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(false) }
    androidx.activity.compose.BackHandler {
        val view = webView
        val userHasThePage = !mission.autoExtract || paused || needsSignIn
        if (userHasThePage && view?.canGoBack() == true) view.goBack() else confirmStop = true
    }
    if (confirmStop) {
        AppDialog(
            title = stringResource(
                when {
                    stopsBatch -> R.string.browser_stop_lookups_title
                    mission.requiresSignIn -> R.string.browser_stop_import_title
                    mission.autoExtract -> R.string.browser_stop_lookup_title
                    else -> R.string.browser_close_page_title
                }
            ),
            confirm = stringResource(if (mission.autoExtract || stopsBatch) R.string.browser_stop else R.string.close),
            onConfirm = { confirmStop = false; cancel() },
            onDismiss = { confirmStop = false },
            dismiss = stringResource(R.string.browser_keep_going)
        ) {
            if (stopsBatch) Text(stringResource(R.string.browser_stop_lookups_body))
        }
    }

    Scaffold(
        topBar = {
            AppTopBar(
                title = context?.takeIf { it.isNotBlank() }
                    ?: if (missions.size > 1) {
                        stringResource(R.string.browser_title_of, mission.displayLabel(), currentIndex + 1, missions.size)
                    } else {
                        mission.displayLabel()
                    },
                onBack = ::cancel,
                backLabel = stringResource(
                    when {
                        stopsBatch -> R.string.browser_stop_all_lookups
                        mission.autoExtract -> R.string.browser_stop
                        else -> R.string.close
                    }
                )
            )
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues),
            verticalArrangement = Arrangement.spacedBy(Spacing.sm)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
            ) {
            // See the darkening filter below: the page blurs behind it (Android 12+).
            // Never over a sign-in page (needsSignIn): the user is signing in there.
            val darkened = mission.autoExtract && !paused && !needsSignIn
            AndroidView(
                modifier = Modifier.fillMaxSize().then(if (darkened) Modifier.blur(16.dp) else Modifier),
                factory = { viewContext ->
                    val cookies = CookieManager.getInstance()
                    cookies.setAcceptCookie(true)

                    val kept = host.view
                    val view = if (kept != null) {
                        // A rotation: the same page (BrowserRetention), now in the new window.
                        (kept.context as? MutableContextWrapper)?.setBaseContext(viewContext)
                        (kept.parent as? android.view.ViewGroup)?.removeView(kept)
                        kept
                    } else {
                        // A wrapper whose base context can be swapped, so the page can outlive
                        // the Activity it was made in (and not leak it while parked).
                        WebView(MutableContextWrapper(viewContext)).also { fresh ->
                            cookies.setAcceptThirdPartyCookies(fresh, true)
                            fresh.settings.apply {
                                javaScriptEnabled = true
                                domStorageEnabled = true
                                // Default UA: this is a real, user-facing browser
                                // session — do NOT spoof Chrome the way the covert
                                // scraper does.
                            }
                            // Under a random name, once per page (the bridge stays registered
                            // through a rotation): see BrowserExtractionGate.
                            fresh.addJavascriptInterface(host.bridge, host.bridgeName)
                        }
                    }
                    host.view = view
                    // The bridge is the same object across a rotation; point it at this screen.
                    host.bridge.sink = { token, missionKey, html ->
                        // JavascriptInterface callbacks run off the UI
                        // thread. Never mutate Compose state or parse
                        // scraped HTML directly on that thread.
                        view.post {
                            if (webView === view) onHtmlExtracted(token, missionKey, html)
                        }
                    }
                    view.apply {
                        webViewClient = object : WebViewClient() {
                            override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                                if (batchComplete) return
                                extractionGate.invalidate()
                                pageReady = false
                                mainFrameFailed = false
                            }

                            override fun onReceivedError(
                                view: WebView?,
                                request: WebResourceRequest?,
                                error: WebResourceError?
                            ) {
                                if (request?.isForMainFrame == true) {
                                    mainFrameFailed = true
                                    DiagnosticLogger.log(
                                        "BrowserScreen: navigation error at ${request.url}: ${error?.description}",
                                        DiagnosticLogger.LogEntry.LogLevel.WARN
                                    )
                                    paused = true
                                }
                            }

                            override fun shouldInterceptRequest(
                                view: WebView?,
                                request: WebResourceRequest?
                            ): WebResourceResponse? {
                                if (request == null || request.isForMainFrame) return null
                                val scheme = request.url.scheme
                                if (scheme != "http" && scheme != "https") return null
                                if (!BrowserAdBlocker.blocks(request.url.host)) return null

                                // An empty response suppresses the ad resource without
                                // redirecting or altering the mission's main document.
                                return WebResourceResponse(
                                    "text/plain",
                                    "UTF-8",
                                    ByteArrayInputStream(ByteArray(0))
                                )
                            }

                            override fun onPageFinished(view: WebView?, url: String?) {
                                if (batchComplete) return
                                // Remove blank ad slots as well as blocking ad traffic.
                                // Conservative selectors avoid hiding page content,
                                // contact details, authentication or CAPTCHA controls.
                                view?.evaluateJavascript(
                                    """
                                    (function() {
                                      if (document.getElementById('cu-ad-filter')) return;
                                      var style = document.createElement('style');
                                      style.id = 'cu-ad-filter';
                                      style.textContent = 'ins.adsbygoogle, .adsbygoogle, [data-ad-client], [id^="div-gpt-ad"], [id^="google_ads_"], iframe[src*="doubleclick.net"], iframe[src*="googlesyndication.com"], [id^="taboola-"], .OUTBRAIN { display: none !important; }';
                                      (document.head || document.documentElement).appendChild(style);
                                    })();
                                    """.trimIndent(),
                                    null
                                )
                                pageReady = true
                                // Paused for a CAPTCHA or a slow page, and a page just finished
                                // loading: that is the user solving it (Cloudflare and friends
                                // navigate on success). Resume on our own instead of waiting
                                // for "Retry extraction"; a page that is still blocked pauses
                                // again. A failed load (mainFrameFailed) stays paused.
                                if (paused && !mainFrameFailed) {
                                    extractionGate.invalidate()
                                    automationRan = false
                                    paused = false
                                    DiagnosticLogger.log("BrowserScreen: page loaded while paused; resuming extraction")
                                }
                                // Each navigation bumps the counter so missions that
                                // re-run after navigation extract from the new page.
                                pageLoadCount += 1
                                DiagnosticLogger.log("BrowserScreen: loaded $url")
                            }
                        }
                        // Do NOT loadUrl here — the LaunchedEffect(currentIndex)
                        // above owns URL loading, including the very first one.
                    }
                    webView = view
                    // It was paused with the old window; this screen's lifecycle may already be resumed.
                    if (kept != null) view.onResume()
                    view
                }
            )

                // Darkening filter. While an auto-extract mission is loading or
                // running its extraction, scrim the page and swallow every touch
                // so the user cannot perturb the automation mid-flight. The
                // scrim lifts the instant the mission pauses for required
                // interaction (captcha / login) — see [paused] — or the page is a
                // sign-in page an import is waiting on — see [needsSignIn] —
                // handing the live page back to the user. Browse-only missions
                // are never darkened. "Let me use the page" lifts it on request
                // (a step the checks can't see: a CAPTCHA, a "was this you?" prompt).
                // Cancel at the bottom stops the lookup (the whole run, for Resolve).
                if (darkened) {
                    val blurs = android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(Color.Black.copy(alpha = if (blurs) 0.6f else 0.85f))
                            .pointerInput(Unit) {
                                awaitPointerEventScope {
                                    while (true) {
                                        awaitPointerEvent().changes.forEach { it.consume() }
                                    }
                                }
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        MaterialTheme(colorScheme = SurveillanceDarkColorScheme, typography = MaterialTheme.typography) {
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(Spacing.md)
                            ) {
                                LoadingCaption(
                                    stringResource(
                                        when {
                                            !pageReady -> R.string.browser_loading
                                            mission.requiresSignIn && !automationRan -> R.string.browser_checking_signed_in
                                            else -> R.string.browser_reading
                                        }
                                    )
                                )
                                FindingsSoFar(findings)
                            }
                            Column(
                                modifier = Modifier.align(Alignment.BottomCenter).padding(Spacing.lg),
                                verticalArrangement = Arrangement.spacedBy(Spacing.sm)
                            ) {
                                AppAction(
                                    stringResource(R.string.browser_let_me_use),
                                    {
                                        if (mission.requiresSignIn) {
                                            waitForSignIn()
                                        } else {
                                            paused = true
                                        }
                                    }
                                )
                                AppAction(stringResource(R.string.cancel), ::cancel)
                            }
                        }
                    }
                }
            }

            if (paused) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = Spacing.md, vertical = Spacing.sm),
                    verticalArrangement = Arrangement.spacedBy(Spacing.xs)
                ) {
                    Text(
                        stringResource(R.string.browser_paused),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    AppCaption(
                        stringResource(if (stopsBatch) R.string.browser_paused_caption_batch else R.string.browser_paused_caption)
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(Spacing.sm)
                    ) {
                        AzButton(
                            text = stringResource(R.string.try_again),
                            onClick = {
                                extractionGate.invalidate()
                                automationRan = false
                                paused = false
                                if (mainFrameFailed || !pageReady) {
                                    // The failed/unfinished document cannot be
                                    // extracted; request a fresh navigation.
                                    webView?.reload()
                                }
                                // A ready page re-triggers extraction when
                                // paused switches back to false.
                            },
                            modifier = Modifier.weight(1f),
                            shape = AzButtonShape.RECTANGLE
                        )
                        AzButton(
                            text = stringResource(R.string.browser_skip),
                            onClick = {
                                paused = false
                                onMissionResult(mission, MissionOutcome.Blocked)
                                advance()
                            },
                            modifier = Modifier.weight(1f),
                            shape = AzButtonShape.RECTANGLE
                        )
                        AzButton(
                            text = stringResource(R.string.browser_stop),
                            onClick = ::cancel,
                            modifier = Modifier.weight(1f),
                            shape = AzButtonShape.RECTANGLE
                        )
                    }
                }
            }
            // An import waiting on the user to sign in (or link WhatsApp): say so, plainly, under
            // the live page. It carries on by itself; Stop is the only control it needs.
            if (needsSignIn && !paused) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = Spacing.md, vertical = Spacing.sm),
                    verticalArrangement = Arrangement.spacedBy(Spacing.xs)
                ) {
                    Text(
                        mission.displaySignInPrompt(),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    AppCaption(stringResource(R.string.browser_signin_caption))
                    AppAction(stringResource(R.string.browser_stop), ::cancel)
                }
            }
            // During an auto-extract mission the darkening filter over the
            // WebView is the sole status surface ("Loading…" / "Reading…") and
            // blocks interference; its controls are Let me use the page and
            // Cancel. Browse-only missions show no bottom button; the user
            // dismisses via the up-arrow in the top bar when done reading.
        }
    }
}

/**
 * The words for a mission's name in the app bar. The mission carries its name in English (the
 * activity log uses it); what the user reads comes from the resources. A mission that is given its
 * own name at the call site (a search link from a contact's screen) is shown as given.
 */
@Composable
private fun BrowserMission.displayLabel(): String = when (this) {
    is BrowserMission.CbcByPhone -> stringResource(R.string.mission_lookup_phone)
    is BrowserMission.CbcByEmail -> stringResource(R.string.mission_lookup_email)
    is BrowserMission.CbcByAddress -> stringResource(R.string.mission_lookup_address)
    is BrowserMission.CbcByName -> stringResource(R.string.mission_lookup_name)
    BrowserMission.HarvestWhatsApp -> stringResource(R.string.settings_import_whatsapp)
    BrowserMission.HarvestInstagram -> stringResource(R.string.settings_import_instagram)
    BrowserMission.HarvestGoogleContacts -> stringResource(R.string.settings_import_google)
    BrowserMission.HarvestFacebookFriends -> stringResource(R.string.settings_import_facebook)
    else -> label
}

/** The words telling the user how to sign in while an import waits; see [displayLabel]. */
@Composable
private fun BrowserMission.displaySignInPrompt(): String = when (this) {
    BrowserMission.HarvestWhatsApp -> stringResource(R.string.mission_signin_whatsapp)
    BrowserMission.HarvestInstagram -> stringResource(R.string.mission_signin_instagram)
    BrowserMission.HarvestGoogleContacts -> stringResource(R.string.mission_signin_google)
    BrowserMission.HarvestFacebookFriends -> stringResource(R.string.mission_signin_facebook)
    else -> signInPrompt
}

/**
 * The JavaScript bridge, registered once per page under [BrowserHost.bridgeName] (never a fixed
 * name; see [BrowserExtractionGate]). [sink] is whichever screen is showing the page now, so
 * the same bridge keeps working through a rotation.
 */
private class HtmlDumpInterface {
    @Volatile
    var sink: ((String?, String?, String?) -> Unit)? = null

    @JavascriptInterface
    fun dumpTagged(token: String?, missionKey: String?, html: String?) {
        sink?.invoke(token, missionKey, html)
    }
}

/** A browser page and what is wired to it, the part of the screen that outlives a rotation. */
private class BrowserHost {
    val bridge = HtmlDumpInterface()
    val bridgeName: String = BrowserExtractionGate.newBridgeName()
    var view: WebView? = null

    /** The mission ("batch-index") whose URL [view] was last told to load. */
    var loadedKey: String? = null
}

/**
 * Keeps the browser page across a rotation. The Activity has no `configChanges` entry, so a
 * rotation destroys and rebuilds it, and a page rebuilt from scratch reloaded the mission's URL,
 * losing a half-finished sign-in or CAPTCHA. The screen [park]s its page as it goes (when the
 * Activity says it is being recreated, and the mission is still going) and the rebuilt screen
 * [take]s it back. The page is made over a [MutableContextWrapper] so that, while parked, it
 * holds the application and not the dying Activity. A page the rebuilt screen doesn't need
 * (the mission was ended in that instant) is simply taken by the next screen to ask, which
 * loads its own mission's URL into it (the loaded-key check in the load effect).
 */
private object BrowserRetention {
    private var parked: BrowserHost? = null

    fun take(): BrowserHost? {
        val host = parked
        parked = null
        return host
    }

    fun park(host: BrowserHost, view: WebView) {
        try {
            host.bridge.sink = null
            // Detach from the old window; leave the page loading, scripts running.
            (view.parent as? android.view.ViewGroup)?.removeView(view)
            (view.context as? MutableContextWrapper)?.setBaseContext(view.context.applicationContext)
            host.view = view
            parked = host
        } catch (e: Exception) {
            DiagnosticLogger.log("BrowserScreen: couldn't keep the page through the rotation: ${e.message}")
            destroy(view)
            host.view = null
        }
    }

    fun destroy(view: WebView?) {
        view ?: return
        try {
            view.stopLoading()
            // Detach before destroying, as Chromium expects.
            (view.parent as? android.view.ViewGroup)?.removeView(view)
            view.destroy()
        } catch (e: Exception) {
            DiagnosticLogger.log("BrowserScreen: WebView destroy failed: ${e.message}")
        }
    }
}

/** The Activity under [this], through any wrappers; null if there is none. */
private fun Context.findActivity(): Activity? {
    var current: Context? = this
    while (current is ContextWrapper) {
        if (current is Activity) return current
        current = current.baseContext
    }
    return null
}

/** How often the page is looked at while the user signs in. */
private const val SIGN_IN_POLL_MS = 1_500L

/** How many times a dumped sign-in page is handed back to the user before the screen just pauses. */
private const val MAX_SIGN_IN_BOUNCES = 3

/**
 * Looks at the page in front of the user: its address, whether it has a password field, a QR
 * code or a chat list on it (see [PageProbe]). Null when the page doesn't answer in time.
 */
private suspend fun probePage(webView: android.webkit.WebView?): PageProbe? {
    val view = webView ?: return null
    return withTimeoutOrNull(3_000L) {
        kotlinx.coroutines.suspendCancellableCoroutine<PageProbe?> { cont ->
            view.evaluateJavascript(PageProbe.SCRIPT) { raw ->
                if (cont.isActive) cont.resumeWith(Result.success(PageProbe.parse(raw)))
            }
        }
    }
}

/**
 * Suspends until [webView]'s page has finished loading and holds some text, polling every
 * 400 ms, for at most about six seconds; then returns either way (the extraction script and
 * the parser cope with a thin page, and the watchdog covers a dead one).
 */
private suspend fun awaitRenderedContent(webView: android.webkit.WebView?) {
    delay(600L)
    repeat(14) {
        val ready = webView?.let { wv ->
            kotlinx.coroutines.suspendCancellableCoroutine<Boolean> { cont ->
                wv.evaluateJavascript(
                    "(document.readyState === 'complete' && document.body && document.body.innerText.length > 200)"
                ) { result -> if (cont.isActive) cont.resumeWith(Result.success(result == "true")) }
            }
        } ?: return
        if (ready) return
        delay(400L)
    }
}
