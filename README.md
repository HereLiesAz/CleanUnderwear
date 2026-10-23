# CleanUnderwear

**CleanUnderwear** keeps your contacts up to date and tells you when someone you know is arrested, taken into custody, or dies. It fills in missing details for the contacts you already have, then checks them daily against public arrest, inmate and obituary records.

---

## 👁️ How It Works
The app works only from contacts you already have. It runs local-first:
- **No backend, no account**: Your contact list, the Registry and every finding are stored on *your* device. There is no CleanUnderwear server.
- **Lookups leave the device**: To check a record, the app sends a contact's first and last name to the public-records, obituary or news site being queried. Location is never in the request, but it decides *which* sites are asked (the county and state ones for the contact's ZIP, area code and address), so the set of sites that receive a name says roughly where the contact lives. See [What leaves your device](#-what-leaves-your-device).
- **Daily checks**: A background worker checks public inmate rosters, court searches, obituaries and the news once a day, around the hour chosen in Settings (6 AM to 9 PM; 9 AM by default).
- **Evidence-First Verification**: Every status change is backed by a "Verification Snippet"—the raw text found during the scan—allowing you to manually verify findings and correct false positives (e.g., surviving relatives mentioned in an obituary).
- **Verify-before-merge enrichment**: **Fill in details** (the Resolve run) enriches every UNVERIFIED contact and every contact still missing an age or date of birth (archived contacts aside, up to the Contact limit) via cyberbackgroundchecks (age is what separates two people who share a name). For each one, the result is only merged into the Registry if the candidate card is corroborated as the *same person* (a unique-identifier phone/email lookup, or a matching name). A conflicting card — e.g. a recycled phone number returning a stranger — is rejected, never written into your address book, and the reason is recorded as the contact's enrichment provenance.

---

## 🛠️ Core Features

### 1. Multi-Source Harvesting
Bring in the contacts you already have. Two surfaces:

**Passive (system contacts).** A single sweep of the Android contacts provider folds in everything your device already syncs — Google, Apple/iCloud (where an iCloud sync provider is installed), WhatsApp, Facebook, and any local-only entries — bucketed by account type.

**Active (web scrape).** Optional per-platform importers, started from Settings. Each opens the service in the app's visible browser, where the user signs in (or scans WhatsApp's QR code) if asked, then reads the names from the page:
- **Facebook** — scrapes `mbasic.facebook.com/me/friends` (server-rendered, no React shell).
- **WhatsApp** — scrapes `web.whatsapp.com` chat list. The QR code shows in the browser; scan it with the phone's WhatsApp.
- **Instagram** — scrapes the followers list at `instagram.com/<me>/followers/`. Sign in when asked.
- **Google Contacts** — scrapes `contacts.google.com`. Sign in when asked.

Apple iCloud has no first-party web surface that survives bot detection on Android, so Apple contacts remain in the passive bucket only.

### 2. Monitoring Engine
- **The Daily Check**: Once a day, around the chosen hour, when the phone is online and its battery isn't low, the app runs the pipeline (dedupe → triage → scrape) over the Registry. WorkManager's 24-hour period can drift from the hour; changing the hour in Settings re-anchors it. **Contact limit** in Settings caps the check (and Fill in details) at the first N contacts, by when they were added.
- **Obituary search (Echovita, ObitsArchive)**: Every US contact the check covers is searched daily on the Echovita and ObitsArchive nationwide obituary indexes, read for name, city, age and death date, as death sources alongside Google News. Only obituaries from the last 60 days count; an older death is not reported.
- **National news search**: Every US and Canadian contact the check covers is searched daily on Google News (`"First Last"` plus obituary/arrest terms). Google News indexes local papers, TV stations, Legacy.com, Dignity Memorial and funeral-home obituaries, so this one query reaches reports no single roster carries. `NewsFeed` classifies each headline as a death or arrest report (arrests older than a year are skipped), reads lifespan and place from obituary headlines ("Name Obituary (1961 - 2026) - Kenner, LA") or the funeral home's town, and treats each publisher as its own source.
- **State prison searches**: Each contact is checked against the public inmate search of every state they are tied to (address state and phone area-code state). Automated: AL, AR, CO, FL, ID, IL, IN, MD, ME, MS, MT, NC, ND, NV, OH, PA, SC, TX, WA, WY. Each is a `sources.json` entry: `JSON_RECORDS` (dot-path field map) or `HTML_TABLE` (column map by header text), with optional `token_page`, `results_url`, `released_pattern` / `custody_pattern`. Other states are blocked by captcha or bot walls, or have no public search (LA, AK, DE, HI, MA, DC rely on VINELink; DC prisoners are federal, covered by BOP). A few are reachable but withheld because their public output carries nothing to verify a match on — GA returns name and offense but no age or birth year, so every hit is name-only; KY and OR mix released supervision into the roster with no clean current-custody flag. Listing a state we cannot confirm on would manufacture false alarms, so it stays off until its output can be verified.
- **County jail rosters**: Current bookings from the jail platforms many counties share: CentralSquare P2C (`jqHandler.ashx` JSON), Zuercher Portal (`/api/portal/inmates/load` JSON) and Tyler New World Inmate Inquiry (table, `InCustody=True`), plus Connecticut's unified state system. Wired across 24 states: 4 P2C counties (NC), 2 New World counties (FL, GA), Connecticut, and 44 Zuercher Portal counties (AR, CA, CO, GA, IA, ID, IL, KS, LA, ME, MI, MN, MT, NC, NE, NH, OH, SC, SD, TN, TX, WI). Each county runs for every contact tied to its state, and is one `sources.json` entry; adding one needs no code. JailTracker (image captcha) and VINELink (login) cannot be automated.
- **Court case searches**: Statewide criminal case searches report new cases as arrests (`event_kind: ARREST`), keeping only criminal case types (`case_type_pattern`) filed in the last 60 days (`max_age_days`); older cases are ignored. Automated: Wisconsin (WCCA), Arkansas (Search ARCourts), Indiana (MyCase), Oklahoma (OSCN). Most other states' court searches are behind captchas, Cloudflare, logins or paywalls.
- **Geographic source routing**: `SourceCatalog.searchPlan` ties a contact to states through their ZIP (via the county map), their phone's area code and the state named in their address, and gathers county → state → multi-state sources (`sources.json`) for each. A contact tied to no US or Canadian state gets no automated search.
- **Honest automatability**: The daily check can *automatically* confirm a status change only for sources it can fetch and verify on its own. The **Federal BOP Inmate Locator** is fully automated: every US contact the check covers is checked against the Bureau of Prisons public JSON API (`bop.gov/PublicInfo/execute/inmateloc`), and a match is only recorded when the located inmate's name corroborates *and* the record shows current custody (released inmates, which the API also returns, are ignored). Rosters that bot-block non-browser requests or expose no name-queryable deep link (21 of the 22 listed counties without a jail platform, 5 states, VINELink and Canada's offender search) ship as **operator-launch-only** (`MANUAL_LANDING`) and appear as links on the contact's profile that you open in your browser. A search that fails or hits a bot wall (some sites block some phones) is recorded on the contact as unread, not as "no match": a pending match it might have cleared stays, and is looked at again within six hours. When a contact's locale has **no** automatable source, triage marks it `NO_AUTOMATED_SOURCE` and the profile says so plainly — the absence of an alert is never silently treated as "all clear." Adding a genuinely automatable source (a `QUERY_TEMPLATE`/`ROSTER_PAGE` entry, or a `multi_state_obituary` with a server-rendered result page) to `sources.json` turns its auto-path on with no code change.
- **Fetching**: the daily check runs on OkHttp. `HtmlScraper` handles server-rendered HTML and JSON APIs (the BOP path uses a structured, custody-aware JSON verifier rather than text matching), only against automatable (non-`MANUAL_LANDING`) sources. `WebViewIdentityInterceptor` gives requests to the bot-defended people-search hosts (CyberBackgroundChecks, SmartBackgroundChecks) the WebView's User-Agent and cookies, so they look like the user's own browser session.
- **Reliable Resolve browser**: one visible WebView is retained for the entire Resolve run instead of created for every contact. Each contact/mode gets new mission state, its own CBC drill marker, and a unique extraction token so late JavaScript callbacks cannot attach another person's HTML to the current contact. Cookies and CAPTCHA sessions persist; HTML is parsed off the UI thread. Timed-out or failed pages still offer retry/skip/abort, and the WebView is destroyed at the end of the run.
- **In-app browser ad blocking**: the visible `BrowserScreen` blocks known advertising hosts at the WebView resource-request boundary and hides common empty ad slots. The main document, login/CAPTCHA traffic, and background scraping remain unchanged; first-party or unfamiliar ad delivery may still appear.
- **Darkening filter (interactive browser)**: when a flow runs in the app's own visible browser (`BrowserScreen` — e.g. enrichment), the page is overlaid with a darkening scrim that swallows all touch input while the automation loads and extracts, so the user can't perturb it mid-flight. The scrim lifts the instant the page needs the user (captcha/login), handing back the live page, then re-engages once they continue.

- **Automatic match verification**: Every automatable source is queried, and every record naming the contact becomes a candidate. `RecordExtractor` reads middle name, age, birth year, city/state and obituary survivors from the text around each mention; `MatchVerifier` scores each candidate (log-odds: a bare name starts far below even, agreeing age, middle name, city, survivors who are also your contacts, and other sources reporting the same event each add evidence). A confidently different age or middle name rejects the record outright.
  - **Strong** (≥ 80%): status `POSSIBLE_MATCH`, notified, with confidence and reasons shown for review.
  - **Weak**: status `WEAK_MATCH`, shown in the app flagged weak, never notified. Needs at least one agreeing fact beyond the name (age, middle initial or city); a name-only or name-and-state hit is dropped as insufficient, so a common name doesn't sit on a permanent weak match.
  - Another source counts as corroboration only when both records agree with the contact beyond the name and describe the same event: same kind, same city, and no conflicting birth year. Two obituaries for two different people with the same name never vouch for each other.
  - Nothing is set to Incarcerated or Deceased without your confirmation, and a status you confirmed is never overwritten by a later check.

### 3. Correction Step (Review)
The **Review** screen lists contacts the app suspects are wrong, and shows the case on both sides so you can decide:
- **Bad merges**: every dedup merge that could be wrong is recorded in a merge ledger (the absorbed row, what it changed, and the matcher's verdict). A merge is suspect when two different numbers ended up on one contact, the surnames disagreed, only one field backed it, or it barely cleared the bar. **Split them** restores the absorbed contact and puts back what the merge overwrote; the pair is never merged again. Merges made before the ledger existed were not recorded and cannot be reviewed.
- **Stale numbers**: a number is suspect when the same number sits on a contact with a different surname (recycled), when a lookup found no one at it, or when a number merged into the contact has been in contact more recently.
- **Is the line live?** Messaging apps (WhatsApp, Signal, Telegram, Viber) add a contact entry for every saved number registered with them, read here under the contacts permission the app already has. Two numbers on one contact that are both registered argue for two people; one registered and one not argues for a number change, with the unregistered number the stale one. A people-search phone lookup that came back to someone else marks the number as likely reassigned. (Call log and SMS history would be sharper, but Google Play restricts those permissions to default dialer and SMS apps, so they are not requested, and the rules that would use them never fire.)
- **Mark stale** is acted on: the number stays on the contact but is no longer searched, matched on, or written to a system contact's notes, and the next verified lookup replaces it.
- **Alternates**: marking a number stale (or splitting a merge that leaves a contact without one) runs the Resolve lookup and offers a verified replacement number or address, and says whether the old number now belongs to someone else. Nothing is written until you accept.
- **Why it might be fine**: every card also lists the evidence for the contact (same email, names agree, sequential history, a household line, short history window), so a verdict is never just a score.

### 4. Decentralized Persistence (Note Injection)
The app uses your phone's native address book as its "external memory":
- **Write-Back**: A settled status (Monitoring, or a Confirmed deceased / Confirmed in custody / Archived the user chose) is written into the system contact's Notes as one signed block, rewritten when it changes. The user's own note text is kept, above it. A possible match is never written: notes sync to the contact's account. The block goes only to the phone contact that has the number *and* the same name, and never through a number marked stale.
- **Recovery**: On reinstall or a new phone syncing the same account, the harvester reads the block back (status, the date it was set, and the record link of a confirmed status). A confirmed status in a block the app didn't sign comes back as a possible match to review, not as fact.
- **Removal**: Settings → Delete everything removes the block from every note, along with the app's own data.

### 5. Contact Profile
- **Status badges**: Checked daily, Possible match, Weak match, Confirmed deceased, Confirmed in custody, Archived, Manual checks only, Needs a full name, Needs more details, Not checked yet. Told apart by label and icon; colour is kept for what needs the user.
- **Evidence**: the record text the match was based on, its confidence and reasons, and a link to the record. The user confirms, dismisses (that record is not raised again) or opens it.
- **List actions**: long-press a contact to open, archive, or bring it back to daily checks.
- **Look them up yourself**: links that search CyberBackgroundChecks by name, phone, email or address, the records sites for the contact's area, and Google News (name and address). Each opens in the user's browser only when tapped.
- **Verify Identity**: To rule out false alarms, the profile of a contact with a records match to check (possible match, incarcerated or deceased) (or weak match) exposes launch-in-browser chips for the face-recognition, reverse-image-search, and name-based OSINT providers, used to confirm that a records match is really your contact, ported from the sister project [doxray](https://github.com/HereLiesAz/doxray) — PimEyes, FaceCheck.id, Lenso.ai, FaceSeek, Yandex Images, TinEye, Google Lens, CyberBackgroundChecks `/name/`, SmartBackgroundChecks `/name/`, GitHub user search, and `site:` Google searches for LinkedIn, Twitter/X, Instagram, and Facebook. These never auto-scrape. The face and reverse-image entries open the provider's home page only (no photo is passed; photo search is not implemented yet), and the name-based services bot-block raw HTTP; CyberBackgroundChecks and SmartBackgroundChecks open in the app's browser (they block other clients); the rest open in the user's own browser. Defined in `app/src/main/assets/sources.json` under `identity_sources`; loaded by `SourceCatalog.identitySources()`.

### 6. Updates
The app checks the channel it was installed from. A Play install asks Google Play (in-app update API) and opens the Play listing; any other install (sideloaded APK from GitHub) reads the latest non-prerelease GitHub release and opens its page. Play installs are never offered GitHub builds: the signing keys differ, so Android would refuse the install. A dismissed version is not offered again until a newer one appears; Settings → Updates checks on demand. Code: `update/UpdateChecker.kt`, `ui/UpdateUi.kt`.

---

## 🔐 What Leaves Your Device
| Data | Sent to | When |
| --- | --- | --- |
| Contact's first and last name | Federal Bureau of Prisons inmate locator | Daily check, every US contact checked |
| Contact's first and last name | Google News search | Daily check, every US and Canadian contact checked |
| Contact's first and last name | Echovita and ObitsArchive obituary searches | Daily check, every US contact checked |
| Contact's first and last name | State prison and court searches, and county jail rosters, for each state the contact is tied to (ZIP, area code, address) | Daily check |
| Contact name, phone, email or address | CyberBackgroundChecks | When you run Fill in details (or look up one contact, or alternates for a stale number): contacts missing an age, and unverified contacts |
| Contact name, phone, email or address, depending on the link | The site you open from a contact's profile | Only when you tap the link |
| Your session on Facebook, WhatsApp, Instagram, Google Contacts | That service only | Only when you start an import from Settings |
| A settled status, the date it was set, and for a confirmed status the record link, in the contact's Notes field | Whatever account that contact syncs with (e.g. Google Contacts) | When the status changes |
| App and Android version, exception types and stack frames (no messages, no contact data) | GitHub Issues, as a pre-filled issue you read and post yourself | After a crash, only if you choose Send report |
| Nothing but the request itself (no contact data) | Google Play (Play installs) or the GitHub releases API (all other installs) | Update check: at launch, at most every 12 hours, and from Settings |

Nothing else is uploaded. There is no analytics, advertising or tracking SDK. Android backup and device transfer exclude the app's data, and release builds log no contact data.

---

## 🏗️ Technical Architecture

### Scrapers & Verifiers
- **`IdentityVerifier`**: Reads the BOP locator's JSON: exact first name and surname, current custody only, and a middle name or age (from Fill in details) that agrees; a name-only hit is never raised. When a common name fills the locator's 100 rows, it asks again narrowed by that middle name or age. A captcha'd response is a failed lookup, not a miss.
- **`RecordExtractor` / `MatchVerifier`**: Read every other source's records and score them (above).
- **`CrashReport`**: Keeps a scrubbed report of a crash on the device and, on the next launch, asks whether to send it. Sending opens GitHub's new-issue page, filled in, in the user's browser. The app carries no token.

### Local Infrastructure
- **WorkManager**: Runs the daily check.
- **Room Database**: Persists the Registry (schema version 15, every migration from 1 registered, schemas exported to `app/schemas`; the upgrades from 13 and 14 are tested against them).
- **Hilt**: Manages the dependency graph for scrapers, repositories, and use cases.

---

## 🚀 Setup & Deployment
1. **Clone & Build**: Standard Gradle build via `./gradlew assembleDebug`.
2. **Tests**: `./gradlew testDebugUnitTest` (JVM; Room tests run on Robolectric).
3. **Permissions**: `READ_CONTACTS`, `WRITE_CONTACTS` and `POST_NOTIFICATIONS`, asked for at the end of onboarding, after the app explains itself. The app runs (with an empty list) if they are refused.
4. **Versioning**: `version.properties` holds the version. The release workflows bump it and pass the released name and code to Gradle; a local build reads the file as is.

---

*“Keep up with the people who matter most.”*
