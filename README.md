# CleanUnderwear

**CleanUnderwear** keeps your contacts up to date and tells you when someone you know is arrested, taken into custody, or dies. It fills in missing details for the contacts you already have, then checks them daily against public arrest, inmate and obituary records.

---

## 👁️ How It Works
The app works only from contacts you already have. It runs local-first:
- **No backend, no account**: Your contact list, the Registry and every finding are stored on *your* device. There is no CleanUnderwear server.
- **Lookups leave the device**: To check a record, the app sends a contact's name (and, where it has one, a city, ZIP or area code) to the public-records, obituary or people-search site being queried. See [What leaves your device](#-what-leaves-your-device).
- **Daily checks**: A background worker checks public inmate rosters and obituary sources each morning, without manual intervention.
- **Evidence-First Verification**: Every status change is backed by a "Verification Snippet"—the raw text found during the scan—allowing you to manually verify findings and correct false positives (e.g., surviving relatives mentioned in an obituary).
- **Verify-before-merge enrichment**: **Resolve** enriches every UNVERIFIED contact and every contact still missing an age or date of birth via cyberbackgroundchecks (age is what separates two people who share a name). For each one, the result is only merged into the Registry if the candidate card is corroborated as the *same person* (a unique-identifier phone/email lookup, or a matching name). A conflicting card — e.g. a recycled phone number returning a stranger — is rejected, never written into your address book, and the reason is recorded as the contact's enrichment provenance.

---

## 🛠️ Core Features

### 1. Multi-Source Harvesting
Bring in the contacts you already have. Two surfaces:

**Passive (system contacts).** A single sweep of the Android contacts provider folds in everything your device already syncs — Google, Apple/iCloud (where an iCloud sync provider is installed), WhatsApp, Facebook, and any local-only entries — bucketed by account type.

**Active (web scrape).** Optional per-platform importers that drive a hidden WebView through each service's web surface and parse the DOM. Triggered on demand from Settings:
- **Facebook** — scrapes `mbasic.facebook.com/me/friends` (server-rendered, no React shell).
- **WhatsApp** — scrapes `web.whatsapp.com` chat list. Requires the user to QR-link the session in the WebView once.
- **Instagram** — scrapes the followers list at `instagram.com/<me>/followers/`. Requires an active IG session cookie.
- **Google Contacts** — scrapes `contacts.google.com`. Requires an active Google session cookie.

Apple iCloud has no first-party web surface that survives bot detection on Android, so Apple contacts remain in the passive bucket only.

### 2. Monitoring Engine
- **The Daily Check**: Every morning at 9:00 AM (battery-not-low + network constraints), the app runs the pipeline (dedupe → triage → scrape) over the Registry.
- **Obituary search (Echovita, ObitsArchive)**: Every contact is searched daily on the Echovita and ObitsArchive nationwide obituary indexes, read for name, city, age and death date, as death sources alongside Google News.
- **National news search**: Every US and Canadian contact is searched daily on Google News (`"First Last"` plus obituary/arrest terms). Google News indexes local papers, TV stations, Legacy.com, Dignity Memorial and funeral-home obituaries, so this one query reaches reports no single roster carries. `NewsFeed` classifies each headline as a death or arrest report (arrests older than a year are skipped), reads lifespan and place from obituary headlines ("Name Obituary (1961 - 2026) - Kenner, LA") or the funeral home's town, and treats each publisher as its own source.
- **State prison searches**: Each contact is checked against the public inmate search of every state they are tied to (address state and phone area-code state). Live and verified: AL, AR, CO, FL, ID, IL, IN, MD, ME, MS, MT, NC, ND, NV, OH, PA, SC, TX, WA, WY. Each is a `sources.json` entry: `JSON_RECORDS` (dot-path field map) or `HTML_TABLE` (column map by header text), with optional `token_page`, `results_url`, `released_pattern` / `custody_pattern`. Other states are blocked by captcha or bot walls, or have no public search (LA, AK, DE, HI, MA, DC rely on VINELink; DC prisoners are federal, covered by BOP). A few are reachable but withheld because their public output carries nothing to verify a match on — GA returns name and offense but no age or birth year, so every hit is name-only; KY and OR mix released supervision into the roster with no clean current-custody flag. Listing a state we cannot confirm on would manufacture false alarms, so it stays off until its output can be verified.
- **County jail rosters**: Current bookings from the jail platforms many counties share: CentralSquare P2C (`jqHandler.ashx` JSON), Zuercher Portal (`/api/portal/inmates/load` JSON) and Tyler New World Inmate Inquiry (table, `InCustody=True`), plus Connecticut's unified state system. Wired across 23 states: the P2C and New World counties above, plus ~40 Zuercher Portal counties (AR, CA, CO, GA, IA, ID, IL, KS, LA, ME, MI, MN, MT, NC, NE, NH, OH, SC, SD, TN, TX, WI). Each county is one `sources.json` entry; adding one needs no code. Each instance runs for every contact in its state; adding a county is one `sources.json` entry. JailTracker (image captcha) and VINELink (login) cannot be automated.
- **Court case searches**: Statewide criminal case searches report new cases as arrests (`event_kind: ARREST`), keeping only criminal case types (`case_type_pattern`) filed in the last 60 days (`max_age_days`); older cases are ignored. Live and verified: Wisconsin (WCCA), Arkansas (Search ARCourts), Indiana (MyCase), Oklahoma (OSCN). Most other states' court searches are behind captchas, Cloudflare, logins or paywalls.
- **Geographic source routing**: `SourceCatalog` maps a contact's ZIP/area code to county → state → multi-state records sources (`sources.json`), most-specific first.
- **Honest automatability**: The daily check can *automatically* confirm a status change only for sources it can fetch and verify on its own. The **Federal BOP Inmate Locator** is fully automated: every US contact is checked against the Bureau of Prisons public JSON API (`bop.gov/PublicInfo/execute/inmateloc`), and a match is only recorded when the located inmate's name corroborates *and* the record shows current custody (released inmates, which the API also returns, are ignored). Most county/state rosters and obituary aggregators bot-block non-browser requests or expose no name-queryable deep link, so they ship as **operator-launch-only** (`MANUAL_LANDING`) and are surfaced as in-app source chips you open in your own browser session. When a contact's locale has **no** automatable source, triage marks it `NO_AUTOMATED_SOURCE` and the profile says so plainly — the absence of an alert is never silently treated as "all clear." Adding a genuinely automatable source (a `QUERY_TEMPLATE`/`ROSTER_PAGE` entry, or a `multi_state_obituary` with a server-rendered result page) to `sources.json` turns its auto-path on with no code change.
- **Legitimate-identity fetching**: scraping never uses an obvious bot browser. `WebViewScraper` (JS-heavy pages) drives the WebView with the device's **default User-Agent** and the shared `CookieManager` session — i.e. the user's real browser identity and any login/captcha they already solved — rather than a spoofed Chrome string. `WebViewIdentityInterceptor` does the same for the OkHttp paths that target bot-defended hosts. `HtmlScraper` handles plain server-rendered HTML and JSON APIs (the BOP path uses a structured, custody-aware JSON verifier rather than text matching). All run only against automatable (non-`MANUAL_LANDING`) sources.
- **Reliable Resolve browser**: one visible WebView is retained for the entire Resolve run instead of created for every contact. Each contact/mode gets new mission state, its own CBC drill marker, and a unique extraction token so late JavaScript callbacks cannot attach another person's HTML to the current contact. Cookies and CAPTCHA sessions persist; HTML is parsed off the UI thread. Timed-out or failed pages still offer retry/skip/abort, and the WebView is destroyed at the end of the run.
- **In-app browser ad blocking**: the visible `BrowserScreen` blocks known advertising hosts at the WebView resource-request boundary and hides common empty ad slots. The main document, login/CAPTCHA traffic, and background scraping remain unchanged; first-party or unfamiliar ad delivery may still appear.
- **Darkening filter (interactive browser)**: when a flow runs in the app's own visible browser (`BrowserScreen` — e.g. enrichment), the page is overlaid with a darkening scrim that swallows all touch input while the automation loads and extracts, so the user can't perturb it mid-flight. The scrim lifts the instant the page needs the user (captcha/login), handing back the live page, then re-engages once they continue.

- **Automatic match verification**: Every automatable source is queried, and every record naming the contact becomes a candidate. `RecordExtractor` reads middle name, age, birth year, city/state and obituary survivors from the text around each mention; `MatchVerifier` scores each candidate (log-odds: a bare name starts far below even, agreeing age, middle name, city, survivors who are also your contacts, and other sources reporting the same event each add evidence). A confidently different age or middle name rejects the record outright.
  - **Strong** (≥ 80%): status `POSSIBLE_MATCH`, notified, with confidence and reasons shown for review.
  - **Weak**: status `WEAK_MATCH`, shown in the app flagged weak, never notified. Needs at least one agreeing fact beyond the name (age, middle initial or city); a name-only or name-and-state hit is dropped as insufficient, so a common name doesn't sit on a permanent weak match.
  - Another source counts as corroboration only when its record agrees on a fact beyond the name (birth year or city), so two obituaries for two different people with the same name never vouch for each other.
  - Nothing is set to Incarcerated or Deceased without your confirmation, and a status you confirmed is never overwritten by a later check.

### 3. Correction Step (Review)
The **Review** screen lists contacts the app suspects are wrong, and shows the case on both sides so you can decide:
- **Bad merges**: every dedup merge that could be wrong is recorded in a merge ledger (the absorbed row, what it changed, and the matcher's verdict). A merge is suspect when two different numbers ended up on one contact, the surnames disagreed, only one field backed it, or it barely cleared the bar. **Split them** restores the absorbed contact and puts back what the merge overwrote; the pair is never merged again. Merges made before the ledger existed were not recorded and cannot be reviewed.
- **Stale numbers**: a number is suspect when nothing has been called or texted in 18+ months, when the same number sits on a contact with a different surname (recycled), when a lookup found no one at it, or when a number merged into the contact has been in contact more recently.
- **Call and text history** (optional `READ_CALL_LOG` / `READ_SMS`, asked for on this screen only): two numbers on one contact that were both in use over the same stretch argue for two people; numbers whose traffic is strictly one after the other argue for one person who changed numbers, and the older number is the stale one. Only counts and first/last dates are read, in memory, for the scan.
- **Why it might be fine**: every card also lists the evidence for the contact (same email, names agree, sequential history, a household line, short history window), so a verdict is never just a score.

### 4. Decentralized Persistence (Note Injection)
The app uses your phone's native address book as its "external memory":
- **Write-Back**: Status changes are injected directly into the system contact's "Notes" field.
- **Seamless Recovery**: Upon re-installation or device migration, the app scans your contact notes to automatically reconstruct your Registry state (Status, URLs, and Timestamps).

### 5. Contact Profile
- **Status Badges**: Visual indicators for `Monitoring`, `Checking`, `Incarcerated`, `Deceased`, and `Archived`.
- **Evidence Card**: View the exact match snippet found during a scan.
- **Registry Actions**: Long-press any individual to Archive, Resume Monitoring, or view original verification sites.
- **Verify Identity**: To rule out false alarms, the profile of a contact with a records match to check (possible match, incarcerated or deceased) exposes launch-in-browser chips for the face-recognition, reverse-image-search, and name-based OSINT providers, used to confirm that a records match is really your contact, ported from the sister project [doxray](https://github.com/HereLiesAz/doxray) — PimEyes, FaceCheck.id, Lenso.ai, FaceSeek, Yandex Images, TinEye, Google Lens, CyberBackgroundChecks `/name/`, SmartBackgroundChecks `/name/`, GitHub user search, and `site:` Google searches for LinkedIn, Twitter/X, Instagram, and Facebook. These never auto-scrape. The face and reverse-image entries open the provider's home page only (no photo is passed; photo search is not implemented yet), and the name-based services bot-block raw HTTP; each chip opens the provider in the user's own browser so their session cookies apply. Defined in `app/src/main/assets/sources.json` under `identity_sources`; loaded by `SourceCatalog.identitySources()`.

---

## 🔐 What Leaves Your Device
| Data | Sent to | When |
| --- | --- | --- |
| Contact name, plus city/ZIP/area code where known | Public inmate, arrest and obituary sources (e.g. the Federal Bureau of Prisons inmate locator) | Daily check, for automatable sources |
| Contact name | Google News search | Daily check, every US and Canadian contact |
| Contact name | Echovita obituary search | Daily check, every contact |
| Contact name | State prison inmate searches for the contact's address and area-code states | Daily check |
| Contact name | State court case searches for the contact's address and area-code states | Daily check |
| Contact name, phone, email or address | CyberBackgroundChecks | When you run Resolve: every contact missing an age, and every unverified contact |
| Contact name | Sites you open from a profile's source chips | Only when you tap a chip; it opens in your browser |
| Your session on Facebook, WhatsApp, Instagram, Google Contacts | That service only | Only when you start an import from Settings |
| Status, record links and check date written to the contact's Notes field | Whatever account that contact syncs with (e.g. Google Contacts) | When a contact's status changes |
| Crash message, stack trace, device model, Android version | GitHub Issues (this repository) | When the app crashes |

Nothing else is uploaded. There is no analytics, advertising or tracking SDK.

---

## 🏗️ Technical Architecture

### Scrapers & Verifiers
- **`IdentityVerifier`**: Uses fuzzy regex matching to identify individuals amidst "sloppy" municipal nomenclature. Supports nickname variations and "Last, First" name inversions.
- **`GitHubCrashReporter`**: Posts fatal app crashes (exception message, stack trace, device model and Android version) as issues in this repository for triage. Release builds carry the token it uses.

### Local Infrastructure
- **WorkManager**: Orchestrates the 9 AM daily check.
- **Room Database**: Persists the Registry state with migrations from version 1 through 5.
- **Hilt**: Manages the dependency graph for scrapers, repositories, and use cases.

---

## 🚀 Setup & Deployment
1. **Clone & Build**: Standard Gradle build via `./gradlew assembleDebug`.
2. **Permissions**: The app requires `READ_CONTACTS`, `WRITE_CONTACTS`, and `POST_NOTIFICATIONS`. An instructional dialog explains these requirements upon first launch.
3. **Onboarding**: Follow the "Instructional Cards" upon first launch to understand the monitoring logic.

---

*“Keep up with the people who matter most.”*
