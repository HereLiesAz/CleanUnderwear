# Technical Guide: The Monitoring Pipeline

This document details the internal logic of the CleanUnderwear daily records-check pipeline.

## 1. The Scraping Pipeline

The app uses a tiered scraping strategy to balance speed and reliability:

### `HtmlScraper` (Tier 1)
- **Method**: Standard OkHttp + Jsoup.
- **Sources**: Static municipal rosters and simple arrest logs.
- **User-Agent**: Sends a desktop browser `User-Agent`.
- **Use Case**: High-speed, low-overhead scans of primitive government sites.

### `WebViewScraper` (Tier 2)
- **Method**: Headless `WebView` with JavaScript injection.
- **Sources**: Sites protected by Cloudflare, Turnstile, or dynamic JS rendering (most funeral homes).
- **Rendering**: Waits for 5 seconds after `onPageFinished` to allow JS challenges to resolve before extracting the DOM via a JavaScript Interface (`HTMLOUT`).
- **Timeout**: Enforces a 30-second hard timeout to prevent background hangs.

### Visible Resolve browser (user-initiated)

- **Lifetime**: `MainViewModel.completeMissionBatch()` retains the active batch between contacts while Resolve parses results. `CleanUnderwearApp` therefore keeps the same `BrowserScreen` and WebView mounted until Resolve completes or is cancelled. The ordinary one-off browser flows are not retained.
- **Lookup isolation**: Each batch has a monotonic id. `BrowserScreen` resets mission/page state on id changes and `BrowserExtractionGate` tags every JavaScript extraction. Stale, duplicate, cancelled and previous-page callbacks are discarded.
- **CBC drill state**: The one-result-navigation marker in `sessionStorage` is scoped to the batch and mission, so an interrupted lookup cannot suppress drilling for another contact.
- **Session**: Browser cookies, authentication, CAPTCHA handling, advertising filters and the visible pause/retry/skip/abort controls remain available throughout a run. Destruction happens on completion or cancellation.

---

## 2. Identity Verification & Evidence

Because human names are ambiguous, the `IdentityVerifier` performs the following checks before declaring a match:

1. **Variations**: Checks for "First Last", "Last, First", and "First Middle Last".
2. **Nicknames**: Uses the `OnDeviceResearchAgent` to expand first names (e.g., "Bob" -> "Robert").
3. **Evidence Snippet**: Extracts ~200 characters of text surrounding the match. This is stored as `lastVerificationSnippet` and presented to the user for confirmation.

---

## 3. Background Scheduling

The system uses `WorkManager` for guaranteed execution:

- **PeriodicWorkRequest**: Fixed 24-hour interval.
- **Daily 9 AM Trigger**: The initial delay is calculated dynamically to align with the next 9:00 AM window.
- **Semaphore Concurrency**: The `ScrapeTargetsUseCase` uses a `Semaphore(3)` to limit concurrent network requests, keeping load on public records sites low.

---

## 4. State Recovery from Contact Notes

CleanUnderwear treats the Android `Contacts` database as a persistent data store:

- **Write-back**: Every registry update is written to the system contact's `Note` field in a structured format:
  ```
  [Registry Status: Incarcerated]
  Last Check: 04/29/2026
  Records: https://...
  ```
- **Recovery**: During the `harvestContacts` phase, the `ContactHarvester` regex-parses these notes. This allows the app to restore its entire monitoring state without a backup or login. Notes sync with the contact's account (e.g. Google Contacts).

---

## 5. Crash Reporting

The `GitHubCrashReporter` is an UncaughtExceptionHandler that triggers during fatal errors:
1. Captures the `Throwable` stack trace.
2. Formats a JSON payload with the exception message, stack trace, device model and Android version.
3. Posts it as a public issue via the GitHub `Issues` API, using a token compiled into release builds.
4. Allows the default handler to take over (triggering the standard Android ANR/Crash dialog).
