package com.hereliesaz.cleanunderwear.ui

import android.annotation.SuppressLint
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.hereliesaz.aznavrail.AzButton
import com.hereliesaz.aznavrail.model.AzButtonShape
import com.hereliesaz.cleanunderwear.domain.BrowserMission
import com.hereliesaz.cleanunderwear.util.DiagnosticLogger
import kotlinx.coroutines.delay
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
 * Cookies persist across missions and Resolve contacts because the WebView is
 * reused. [batchId] resets the extraction state without rebuilding the browser.
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
) {
    if (missions.isEmpty()) {
        // Defensive: nothing to do.
        LaunchedEffect(Unit) { onAllComplete() }
        return
    }

    var currentIndex by remember(batchId) { mutableIntStateOf(0) }
    var batchComplete by remember { mutableStateOf(false) }
    val extractionGate = remember { BrowserExtractionGate() }
    var pageReady by remember { mutableStateOf(false) }
    var automationRan by remember { mutableStateOf(false) }
    var paused by remember { mutableStateOf(false) }
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

    // Wait for AndroidView to provide the WebView: launching on just the
    // mission index can silently skip the initial URL when the view is null.
    LaunchedEffect(batchId, currentIndex, webView) {
        val activeView = webView ?: return@LaunchedEffect
        extractionGate.reset()
        batchComplete = false
        pageReady = false
        automationRan = false
        paused = false
        mainFrameFailed = false
        pageLoadCount = 0
        activeView.stopLoading()
        activeView.loadUrl(mission.initialUrl)
        DiagnosticLogger.log("BrowserScreen: loading mission ${currentIndex + 1}/${missions.size}: ${mission.initialUrl}")
    }

    // The JavaScript bridge was constructed with AndroidView's initial
    // mission. Always dispatch to the latest Compose callback instead, and
    // ignore callbacks from pages whose extraction is no longer underway.
    val onHtmlExtracted by rememberUpdatedState<(String?, String?) -> Unit>(
        newValue = { token, html ->
            if (!batchComplete && pageReady && automationRan && !paused && extractionGate.accept(token)) {
                val safe = html.orEmpty()
                if (isBlocked(safe)) {
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
    // Keyed on pageLoadCount so drill-in missions (CBC) re-run after each
    // navigation: the script walks the results list into the first result's
    // detail page, dumping only once it arrives. Non-drill missions still
    // extract exactly once (the automationRan guard).
    LaunchedEffect(batchId, currentIndex, pageLoadCount, paused) {
        if (!mission.autoExtract || batchComplete) return@LaunchedEffect
        if (paused || !pageReady) return@LaunchedEffect
        if (!mission.drillToFirstResult && automationRan) return@LaunchedEffect
        // Wait for the page to settle rather than a fixed beat: a slow SPA would otherwise be
        // dumped empty and read as "nothing found".
        awaitRenderedContent(webView)
        if (!paused && !batchComplete && pageReady) {
            val token = extractionGate.beginExtraction() ?: return@LaunchedEffect
            val script = extractionGate.tagScript(
                mission.extractionScript,
                token,
                "$batchId-$currentIndex"
            )
            automationRan = true
            webView?.evaluateJavascript(script, null)
        }
    }

    // Watchdog: never leave the screen darkened indefinitely if a page
    // fails to finish loading or an extraction never calls back. At 30s,
    // unmask the browser and let the user retry, skip, or abort.
    // Browse-only missions are exempt: there's no extraction to time out.
    LaunchedEffect(batchId, currentIndex, paused) {
        if (!mission.autoExtract || batchComplete) return@LaunchedEffect
        if (paused) return@LaunchedEffect
        delay(mission.watchdogMs)
        if (!paused && !batchComplete) {
            DiagnosticLogger.log(
                "BrowserScreen: mission ${currentIndex + 1} timed out (pageReady=$pageReady, automationRan=$automationRan); pausing",
                DiagnosticLogger.LogEntry.LogLevel.WARN
            )
            paused = true
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            extractionGate.finish()
            CookieManager.getInstance().flush()
            webView?.let {
                try {
                    it.stopLoading()
                    it.destroy()
                } catch (e: Exception) {
                    DiagnosticLogger.log("BrowserScreen: WebView destroy failed: ${e.message}")
                }
            }
            webView = null
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("${mission.label} (${currentIndex + 1}/${missions.size})") },
                navigationIcon = {
                    IconButton(onClick = ::cancel) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Cancel")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    titleContentColor = MaterialTheme.colorScheme.primary,
                    navigationIconContentColor = MaterialTheme.colorScheme.primary
                )
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
            ) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { context ->
                    val cookies = CookieManager.getInstance()
                    cookies.setAcceptCookie(true)

                    val view = WebView(context)
                    view.apply {
                        cookies.setAcceptThirdPartyCookies(this, true)

                        settings.apply {
                            javaScriptEnabled = true
                            domStorageEnabled = true
                            // Default UA: this is a real, user-facing browser
                            // session — do NOT spoof Chrome the way the covert
                            // scraper does.
                        }

                        addJavascriptInterface(
                            HtmlDumpInterface { token, html ->
                                // JavascriptInterface callbacks run off the UI
                                // thread. Never mutate Compose state or parse
                                // scraped HTML directly on that thread.
                                view.post {
                                    if (webView === view) onHtmlExtracted(token, html)
                                }
                            },
                            "HTMLOUT"
                        )

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
                                // Each navigation bumps the counter so drill-in
                                // missions re-trigger extraction on the new page.
                                pageLoadCount += 1
                                DiagnosticLogger.log("BrowserScreen: loaded $url")
                            }
                        }
                        // Do NOT loadUrl here — the LaunchedEffect(currentIndex)
                        // above owns URL loading, including the very first one.
                    }
                    webView = view
                    view
                }
            )

                // Darkening filter. While an auto-extract mission is loading or
                // running its extraction, scrim the page and swallow every touch
                // so the user cannot perturb the automation mid-flight. The
                // scrim lifts the instant the mission pauses for required
                // interaction (captcha / login) — see [paused] — handing the
                // live page back to the user. Browse-only missions are never
                // darkened.
                val darkened = mission.autoExtract && !paused
                if (darkened) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.75f))
                            .pointerInput(Unit) {
                                awaitPointerEventScope {
                                    while (true) {
                                        awaitPointerEvent().changes.forEach { it.consume() }
                                    }
                                }
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            com.hereliesaz.aznavrail.AzLoad()
                            Spacer(modifier = Modifier.height(12.dp))
                            Text(
                                text = if (!pageReady) "Loading…" else "Working — please don't touch the screen",
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }
                }
            }

            if (paused) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        "This page may be blocked, have failed to load, or timed out. Solve any CAPTCHA, retry, skip, or abort.",
                        color = MaterialTheme.colorScheme.primary
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        AzButton(
                            text = "Retry extraction",
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
                            text = "Skip search",
                            onClick = {
                                paused = false
                                onMissionResult(mission, MissionOutcome.Blocked)
                                advance()
                            },
                            modifier = Modifier.weight(1f),
                            shape = AzButtonShape.RECTANGLE
                        )
                        AzButton(
                            text = "Abort",
                            onClick = ::cancel,
                            modifier = Modifier.weight(1f),
                            shape = AzButtonShape.RECTANGLE
                        )
                    }
                }
            }
            // During an auto-extract mission the darkening filter over the
            // WebView is the sole status surface ("Loading…" / "Working…") and
            // also blocks interference — there is no bottom button to tap, by
            // design. Browse-only missions show no bottom button either; the
            // user dismisses via the up-arrow in the top bar when done reading.
        }
    }
}

private class HtmlDumpInterface(private val onResult: (String?, String?) -> Unit) {
    @JavascriptInterface
    fun dumpTagged(token: String?, html: String?) {
        onResult(token, html)
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
