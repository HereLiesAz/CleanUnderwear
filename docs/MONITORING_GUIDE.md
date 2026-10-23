# Technical Guide: The Monitoring Pipeline

This document details the internal logic of the CleanUnderwear daily records-check pipeline.

## 1. The Scraping Pipeline

### `HtmlScraper` (the daily check)
- **Method**: OkHttp + Jsoup, with a desktop browser `User-Agent`, over https.
- **Sources**: every automatable (non-`MANUAL_LANDING`) entry in `sources.json`: rosters, court searches, obituary indexes, Google News and the BOP locator. Each search is bounded by a 60-second call timeout.
- **Failures**: a failed request or a bot wall (`BotWall`: captcha, Cloudflare, an empty page) is recorded as unread on the contact, not as "no match", and retried within six hours.

### Visible browser (imports and people-search)
Imports (Facebook, WhatsApp, Instagram, Google Contacts) and every CyberBackgroundChecks lookup run in the app's own visible `BrowserScreen`, where the user can sign in or solve a challenge. Each mission has a watchdog; a page that never settles offers retry, skip or abort.

### Visible Resolve browser (user-initiated)

- **Lifetime**: `MainViewModel.completeMissionBatch()` retains the active batch between contacts while Resolve parses results. `CleanUnderwearApp` therefore keeps the same `BrowserScreen` and WebView mounted until Resolve completes or is cancelled. The ordinary one-off browser flows are not retained.
- **Lookup isolation**: Each batch has a monotonic id. `BrowserScreen` resets mission/page state on id changes and `BrowserExtractionGate` tags every JavaScript extraction. Stale, duplicate, cancelled and previous-page callbacks are discarded.
- **CBC drill state**: The one-result-navigation marker in `sessionStorage` is scoped to the batch and mission, so an interrupted lookup cannot suppress drilling for another contact. It is never cleared during the lookup, so a CAPTCHA on the detail page can't reset it and send the script drilling into a related person's page once solved.
- **CAPTCHA resume**: while paused, any page that finishes loading (other than a failed load) resumes extraction by itself, since solving a challenge navigates to the real page. A page that is still blocked pauses again; **Retry extraction** remains for challenges that resolve without navigating.
- **Busy indicator**: the rail's global `azAdvanced(isLoading)` overlay is not used, because it swallows every touch, including the pause controls. The same `AzLoad` is drawn in the content area with no input handling instead, captioned with the running operation's description (as the old indicator was), and the browser's automation scrim uses it too with its own "Loading…" / "Working" caption.
- **Session**: Browser cookies, authentication, CAPTCHA handling, advertising filters and the visible pause/retry/skip/abort controls remain available throughout a run. Destruction happens on completion or cancellation.

---

### Reading CyberBackgroundChecks pages

`CyberBackgroundChecksEnricher.parseFindings` reads name, address, phone and age/DOB with class selectors (`NAME_SELECTORS` etc.), which are guesses at CBC's markup and drift with its redesigns. Anything they miss is filled from schema.org `Person` data on the page (JSON-LD, including `@graph`, or microdata), which doesn't depend on CSS. Card names written "Last, First" are stored in natural order, so the middle name and the saved name come out right.

Every Resolve and alternates search logs one line, `CYBG parse <mode>: …`: which fields were found (never their values), or, when none were, whether there is a title (not its text, which names the person), heading classes, JSON-LD count and likely class names. That line is what to fix the selectors against. When a page carries several Person records, only one whose name matches the result card may fill fields.

---

## 2. Identity Verification & Evidence

Because human names are ambiguous, no record is taken on its name alone:

1. **BOP** (`IdentityVerifier`): the locator's JSON must carry the contact's exact first name and surname and show current custody. Nicknames are not expanded, so a common nickname can't widen a national search. A capped or captcha'd response is a failed lookup.
2. **Everything else** (`RecordExtractor` → `MatchVerifier`): each record naming the contact becomes a candidate with its middle name, age, birth year, place and, for obituaries, the survivors it names. Agreeing facts raise the score; a confidently different age or middle name rejects it; a name alone stays below a weak match.
3. **Evidence**: the text around the match is stored as `lastVerificationSnippet`, with the confidence, the reasons and the record's link, and shown to the user to confirm or dismiss. A dismissed record (`matchRecordKey`) is not raised again.

---

## 3. Background Scheduling

The system uses `WorkManager` for guaranteed execution:

- **PeriodicWorkRequest**: 24-hour interval, only while online and the battery isn't low.
- **Check hour**: the first run is delayed to the next occurrence of the hour chosen in Settings (9 AM by default). Later runs follow the 24-hour period and can drift; changing the hour re-enqueues the work.
- **Contact limit**: the check takes only the first N contacts (by id, i.e. when they were added) that are due.
- **Semaphore Concurrency**: The `ScrapeTargetsUseCase` uses a `Semaphore(3)` to limit concurrent network requests, keeping load on public records sites low.

---

## 4. State Recovery from Contact Notes

CleanUnderwear treats the Android `Contacts` database as a persistent data store:

- **Write-back** (`SystemContactSyncer`, `ContactNote`): a settled status (Monitoring, Incarcerated, Deceased, Archived) is written as one block at the end of the contact's note, after the user's own text, and rewritten only when it changes:
  ~~~
  [Registry Status: Incarcerated]
  Status Since: 04/29/2026
  Records: https://...
  — CleanUnderwear
  ~~~
  Possible and weak matches are never written. The block goes to the phone contact that has the contact's usable (not stale) number *and* the same name.
- **Recovery**: during harvest, `ContactHarvester` reads the block back. A confirmed status in an unsigned block (text that only looks like the app's) comes back as a possible match to review.
- **Removal**: Delete everything strips the block from every note and deletes a note that held nothing else. Notes sync with the contact's account (e.g. Google Contacts).

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

Liveness comes from `DeviceMessagingPresence`: the contact entries WhatsApp, Signal, Telegram and Viber keep for every saved number registered with them (READ_CONTACTS). Both numbers of a merge registered → suspicion (2.0), only one → defense (1.5); a stale-number candidate that is registered → defense (2.5), unregistered → weak suspicion (1.0). A Resolve phone lookup whose card named someone else (`enrichmentProvenance` "name conflict" with a phone mode) → stale suspicion (3.0). Call-log and SMS history are not read: Google Play rejects releases that declare `READ_CALL_LOG` / `READ_SMS` unless the app is a default dialer or SMS handler. `CommsHistory` is bound to `NoCommsHistory`, so the history rows in the table above never fire.

**Verdicts.** *Split* (`SuspectReview.split`) restores the absorbed row, reverts only the winner fields still holding the merge's value, and stores a veto: the signatures (name, number, email) of the two rows. `DeduplicateTargetsUseCase` consults the vetoes so the next sweep cannot re-merge them; signatures are used because harvesting re-inserts rows with fresh ids. *Keep merged*, *Number is fine* and *Mark stale* are recorded and stop the case reappearing. *Mark stale* keeps the number on the contact (removing it would only bring it back as a duplicate on the next harvest) but records it in `Target.stalePhone`. The mark is about that number, so it lapses by itself when the number changes. While it holds: `CyberBackgroundChecksEnricher` does not search it (except the alternates lookup, to learn who holds it now) and replaces it with any different number a verified card carries; dedup scoring ignores it (`Target.usablePhone`); triage no longer counts it as identifying; and `SystemContactSyncer` will not write status notes to whichever system contact it now resolves to. The area code is kept as a rough regional hint until the number is replaced. The contact screen shows the mark and a button to take it back.

**Alternates.** *Mark stale*, or a *Split* that leaves the survivor without a number, starts the same visible-browser CyberBackgroundChecks flow as Resolve (`MainViewModel.findAlternates`); a stale card also has a *Look up alternates* button. `AlternateFinder` judges the cards with the enricher's verify-before-merge rules. The old number is still searched: a different name on its card means it was recycled. That card is evidence only, never a source of replacement data. A verified number or address from the email, address and name searches (never the stale number itself) is offered in a dialog and written only on **Use these**.

---

## 6. Crash Reporting

`CrashReport` installs an uncaught-exception handler that:
1. Writes a scrubbed report to the app's private storage: app and Android versions, exception types and stack frames. Exception messages are dropped, since they can echo a contact's name, number or a scraped page.
2. Hands the crash to the previous handler (the standard Android crash dialog).

On the next launch the user is asked whether to send it. **Send report** opens GitHub's new-issue page in their browser with the report filled in; they read it and post it, or not. The app carries no token.
