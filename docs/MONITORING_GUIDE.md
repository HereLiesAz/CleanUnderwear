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

### Reading CyberBackgroundChecks pages

`CyberBackgroundChecksEnricher.parseFindings` reads name, address, phone and age/DOB with class selectors (`NAME_SELECTORS` etc.), which are guesses at CBC's markup and drift with its redesigns. Anything they miss is filled from schema.org `Person` data on the page (JSON-LD, including `@graph`, or microdata), which doesn't depend on CSS. Card names written "Last, First" are stored in natural order, so the middle name and the saved name come out right.

Every Resolve and alternates search logs one line, `CYBG parse <mode>: …`: which fields were found (never their values), or, when none were, whether there is a title (not its text, which names the person), heading classes, JSON-LD count and likely class names. That line is what to fix the selectors against. When a page carries several Person records, only one whose name matches the result card may fill fields.

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

## 5. Correction Step (Review)

Dedup is automatic, so its mistakes are silent. The correction step makes them visible and reversible.

**Merge ledger.** `DeduplicateTargetsUseCase` writes a `MergeRecord` before it updates the winner and deletes the loser: the loser as a JSON snapshot (`TargetSnapshot`), the winner fields the merge changed (`{field: {before, after}}`), and the `ProbabilisticScorer` verdict for the pair (probability, weight, per-field contributions). Merging a row into an exact copy of itself (same name, number and email) is not recorded; every re-harvest produces those, and there is nothing to get wrong or restore. Rows are `ACTIVE` until judged, then `SPLIT` or `KEPT`.

**Detection.** `SuspectDetector` is pure; each rule adds a weighted reason to a *suspicion* list or a *defense* list. A case surfaces only when the suspicion alone reaches `MIN_SUSPICION`; the screen then shows both lists and a lean (`net` of ±`LEAN_MARGIN`), never just a score.

| Signal | Counts as |
| --- | --- |
| Two different numbers on one contact | suspicion (2.0) |
| Both numbers in contact over the same 30+ days, 3+ events each | suspicion (3.0), plus 1.0 if both active in the last 90 days |
| Number histories strictly sequential | defense (2.5): one person changed numbers; the quiet number is stale |
| Surname conflict outvoted by other fields | suspicion (3.0) |
| Only one field agreed / merge probability < 0.99 / number shared with other contacts | suspicion (1.5 / 1.0 / 1.0) |
| Same phone, same email, names agree, address agrees, DOB agrees | defense (1.5 / 2.0 / 2.0 / 2.0 / 4.0) |
| No contact with a number in 18 / 36 months | stale suspicion (2.0 / 3.0) |
| Contact in the last 18 months | stale defense (4.0) |
| History window shorter than 24 months | defense (1.5): silence proves little |
| Number shared with a different surname / the same surname | stale suspicion (2.5) / defense (1.5) |
| Merged-in number with fresher traffic | stale suspicion (3.0), offers **Use that number** |

Liveness comes from `DeviceMessagingPresence`: the contact entries WhatsApp, Signal, Telegram and Viber keep for every saved number registered with them (READ_CONTACTS). Both numbers of a merge registered → suspicion (2.0), only one → defense (1.5); a stale-number candidate that is registered → defense (2.5), unregistered → weak suspicion (1.0). A Resolve phone lookup whose card named someone else (`enrichmentProvenance` "name conflict" with a phone mode) → stale suspicion (3.0). `DeviceCommsHistory` (call log + SMS) is still wired but inert: Google Play rejects releases that declare `READ_CALL_LOG` / `READ_SMS` unless the app is a default dialer or SMS handler, so the manifest leaves them out and the Review screen doesn't ask.

**Verdicts.** *Split* (`SuspectReview.split`) restores the absorbed row, reverts only the winner fields still holding the merge's value, and stores a veto: the signatures (name, number, email) of the two rows. `DeduplicateTargetsUseCase` consults the vetoes so the next sweep cannot re-merge them; signatures are used because harvesting re-inserts rows with fresh ids. *Keep merged*, *Number is fine* and *Mark stale* are recorded and stop the case reappearing. *Mark stale* keeps the number on the contact (removing it would only bring it back as a duplicate on the next harvest) but records it in `Target.stalePhone`. The mark is about that number, so it lapses by itself when the number changes. While it holds: `CyberBackgroundChecksEnricher` does not search it (except the alternates lookup, to learn who holds it now) and replaces it with any different number a verified card carries; dedup scoring ignores it (`Target.usablePhone`); triage no longer counts it as identifying; and `SystemContactSyncer` will not write status notes to whichever system contact it now resolves to. The area code is kept as a rough regional hint until the number is replaced. The contact screen shows the mark and a button to take it back.

**Alternates.** *Mark stale*, or a *Split* that leaves the survivor without a number, starts the same visible-browser CyberBackgroundChecks flow as Resolve (`MainViewModel.findAlternates`); a stale card also has a *Look up alternates* button. `AlternateFinder` judges the cards with the enricher's verify-before-merge rules. The old number is still searched: a different name on its card means it was recycled. That card is evidence only, never a source of replacement data. A verified number or address from the email, address and name searches (never the stale number itself) is offered in a dialog and written only on **Use these**.

---

## 6. Crash Reporting

The `GitHubCrashReporter` is an UncaughtExceptionHandler that triggers during fatal errors:
1. Captures the `Throwable` stack trace.
2. Formats a JSON payload with the exception message, stack trace, device model and Android version.
3. Posts it as a public issue via the GitHub `Issues` API, using a token compiled into release builds.
4. Allows the default handler to take over (triggering the standard Android ANR/Crash dialog).
