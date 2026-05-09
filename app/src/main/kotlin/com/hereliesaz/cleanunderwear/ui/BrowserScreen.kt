package com.hereliesaz.cleanunderwear.ui

import android.annotation.SuppressLint
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.hereliesaz.aznavrail.AzButton
import com.hereliesaz.aznavrail.model.AzButtonShape
import com.hereliesaz.cleanunderwear.domain.BrowserMission
import com.hereliesaz.cleanunderwear.util.DiagnosticLogger
import kotlinx.coroutines.delay

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
 * Cookies persist across mission transitions because the WebView is reused —
 * only `loadUrl` changes between missions.
 */
@OptIn(ExperimentalMaterial3Api::class)
@SuppressLint("SetJavaScriptEnabled", "JavascriptInterface")
@Composable
fun BrowserScreen(
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

    var currentIndex by remember { mutableIntStateOf(0) }
    var pageReady by remember { mutableStateOf(false) }
    var automationRan by remember { mutableStateOf(false) }
    var paused by remember { mutableStateOf(false) }
    var webView: WebView? by remember { mutableStateOf(null) }

    val mission = missions[currentIndex]

    fun advance() {
        if (currentIndex + 1 >= missions.size) {
            onAllComplete()
        } else {
            currentIndex += 1
        }
    }

    // Drive each mission transition: load the next URL, reset per-page state.
    // The first mission is also loaded here (the factory below no longer races
    // ahead of this effect).
    LaunchedEffect(currentIndex) {
        pageReady = false
        automationRan = false
        paused = false
        webView?.stopLoading()
        webView?.loadUrl(mission.initialUrl)
        DiagnosticLogger.log("BrowserScreen: loading mission ${currentIndex + 1}/${missions.size}: ${mission.initialUrl}")
    }

    // Once the page reports ready, give the SPA a moment to render results,
    // then auto-evaluate the extraction script.
    LaunchedEffect(currentIndex, pageReady, paused) {
        if (pageReady && !automationRan && !paused) {
            delay(1200L)
            if (!automationRan && !paused) {
                automationRan = true
                webView?.evaluateJavascript(mission.extractionScript, null)
            }
        }
    }

    // Watchdog: if a mission gets stuck (page never finishes loading, or the
    // extraction script silently fails to post back), don't hang the queue
    // forever. After 30s, report the mission as Blocked and advance.
    // Pausing for a captcha resets the clock — the user is in control then.
    LaunchedEffect(currentIndex, paused) {
        if (paused) return@LaunchedEffect
        delay(30_000L)
        if (!paused) {
            DiagnosticLogger.log(
                "BrowserScreen: mission ${currentIndex + 1} timed out (pageReady=$pageReady, automationRan=$automationRan); skipping",
                DiagnosticLogger.LogEntry.LogLevel.WARN
            )
            onMissionResult(mission, MissionOutcome.Blocked)
            advance()
        }
    }

    DisposableEffect(Unit) {
        onDispose {
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
                    IconButton(onClick = onCancel) {
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
            AndroidView(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                factory = { context ->
                    val cookies = CookieManager.getInstance()
                    cookies.setAcceptCookie(true)

                    val view = WebView(context).apply {
                        cookies.setAcceptThirdPartyCookies(this, true)

                        settings.apply {
                            javaScriptEnabled = true
                            domStorageEnabled = true
                            // Default UA: this is a real, user-facing browser
                            // session — do NOT spoof Chrome the way the covert
                            // scraper does.
                        }

                        addJavascriptInterface(
                            HtmlDumpInterface { html ->
                                val safe = html.orEmpty()
                                if (isBlocked(safe)) {
                                    DiagnosticLogger.log("BrowserScreen: mission ${currentIndex + 1} blocked, pausing for user")
                                    paused = true
                                } else {
                                    onMissionResult(mission, MissionOutcome.Extracted(safe))
                                    advance()
                                }
                            },
                            "HTMLOUT"
                        )

                        webViewClient = object : WebViewClient() {
                            override fun onPageFinished(view: WebView?, url: String?) {
                                pageReady = true
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

            if (paused) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        "This page looks like a captcha or block. Solve it in the browser, then continue.",
                        color = MaterialTheme.colorScheme.primary
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        AzButton(
                            text = "Retry extraction",
                            onClick = {
                                automationRan = false
                                paused = false
                                // Reuse the existing pageReady=true to trigger
                                // the auto-extraction LaunchedEffect.
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
                            onClick = onCancel,
                            modifier = Modifier.weight(1f),
                            shape = AzButtonShape.RECTANGLE
                        )
                    }
                }
            } else {
                AzButton(
                    text = if (!pageReady) "Loading…" else "Auto-extracting…",
                    onClick = {
                        // Manual fallback: re-fire the extraction in case the
                        // settle delay raced the page. No-op while running.
                        if (pageReady && !automationRan) {
                            automationRan = true
                            webView?.evaluateJavascript(mission.extractionScript, null)
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    shape = AzButtonShape.RECTANGLE
                )
            }
        }
    }
}

private class HtmlDumpInterface(private val onResult: (String?) -> Unit) {
    @JavascriptInterface
    fun dump(html: String?) {
        onResult(html)
    }
}
