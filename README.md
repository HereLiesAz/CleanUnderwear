# CleanUnderwear

**CleanUnderwear** is a localized, autonomous surveillance and monitoring platform designed to help you keep a protective vigil over your loved ones. It transforms your passive contact list into an active **Registry**, capable of automatically detecting life-status changes (incarceration or passing) across a variety of public records.

---

## 👁️ The Panopticon Philosophy
Most people use "The Registry" to keep tabs on elderly relatives, estranged family, or vulnerable friends. The app operates on a "Zero-Knowledge" local-first architecture:
- **No Cloud Backend**: All surveillance data, contact details, and findings stay on *your* device.
- **Autonomous Scything**: Background workers (the "Panopticon") perform routine scans of municipal rosters and obituary registries without manual intervention.
- **Evidence-First Verification**: Every status change is backed by a "Verification Snippet"—the raw text found during the scan—allowing you to manually verify findings and correct false positives (e.g., surviving relatives mentioned in an obituary).

---

## 🛠️ Core Surveillance Features

### 1. Multi-Source Harvesting
Scythe through your digital ecosystems to build your Registry. Two surfaces:

**Passive (system contacts).** A single sweep of the Android contacts provider folds in everything your device already syncs — Google, Apple/iCloud (where an iCloud sync provider is installed), WhatsApp, Facebook, and any local-only entries — bucketed by account type.

**Active (web scrape).** Optional per-platform interrogators that drive a hidden WebView through each service's web surface and parse the DOM. Triggered on demand from Settings:
- **Facebook** — scrapes `mbasic.facebook.com/me/friends` (server-rendered, no React shell).
- **WhatsApp** — scrapes `web.whatsapp.com` chat list. Requires the user to QR-link the session in the WebView once.
- **Instagram** — scrapes the followers list at `instagram.com/<me>/followers/`. Requires an active IG session cookie.
- **Google Contacts** — scrapes `contacts.google.com`. Requires an active Google session cookie.

Apple iCloud has no first-party web surface that survives bot detection on Android, so Apple contacts remain in the passive bucket only.

### 2. Autonomous Monitoring Engine
- **The Daily Vigil**: Every morning at 9:00 AM, the app triggers a comprehensive scan of all active individuals in the Registry.
- **Dynamic URL Discovery**: The `OnDeviceResearchAgent` automatically maps an individual's area code and location to relevant municipal arrest logs and funeral home registries.
- **Stealth Scraping**: Uses a hybrid approach of `HtmlScraper` (fast) and `WebViewScraper` (stealth) to bypass modern bot-detection like Cloudflare and Turnstile.

### 3. Decentralized Persistence (Note Injection)
The app uses your phone's native address book as its "external memory":
- **Write-Back**: Status changes are injected directly into the system contact's "Notes" field.
- **Seamless Recovery**: Upon re-installation or device migration, the app scans your contact notes to automatically reconstruct your Registry state (Status, URLs, and Timestamps).

### 4. Interactive Dossier (Profile)
- **Status Badges**: Visual indicators for `Monitoring`, `Checking`, `Incarcerated`, `Deceased`, and `Archived`.
- **Evidence Card**: View the exact match snippet found during a scan.
- **Registry Actions**: Long-press any individual to Archive, Resume Monitoring, or view original verification sites.
- **Identity Correlation (doxray)**: Each profile exposes launch-in-browser chips for the face-recognition, reverse-image-search, and name-based OSINT providers ported from the sister project [doxray](https://github.com/HereLiesAz/doxray) — PimEyes, FaceCheck.id, Lenso.ai, FaceSeek, Yandex Images, TinEye, Google Lens, CyberBackgroundChecks `/people/`, SmartBackgroundChecks `/people/`, GitHub user search, and `site:` Google searches for LinkedIn, Twitter/X, Instagram, and Facebook. These never auto-scrape (face services need a photo the Registry doesn't carry, and the name-based services bot-block raw HTTP); each chip opens the provider in the user's own browser so their session cookies apply. Defined in `app/src/main/assets/sources.json` under `identity_sources`; loaded by `SourceCatalog.identitySources()`.

---

## 🏗️ Technical Architecture

### Scrapers & Verifiers
- **`IdentityVerifier`**: Uses fuzzy regex matching to identify individuals amidst "sloppy" municipal nomenclature. Supports nickname variations and "Last, First" name inversions.
- **`GitHubCrashReporter`**: An automated "Digital Canary" that posts fatal app crashes as issues in this repository for triage.

### Local Infrastructure
- **WorkManager**: Orchestrates the 9 AM periodic vigil.
- **Room Database**: Persists the Registry state with migrations from version 1 through 5.
- **Hilt**: Manages the dependency graph for scrapers, repositories, and use cases.

---

## 🚀 Setup & Deployment
1. **Clone & Build**: Standard Gradle build via `./gradlew assembleDebug`.
2. **Permissions**: The app requires `READ_CONTACTS`, `WRITE_CONTACTS`, and `POST_NOTIFICATIONS`. An instructional dialog explains these requirements upon first launch.
3. **Onboarding**: Follow the "Instructional Cards" upon first launch to understand the monitoring logic.

---

*“A localized vigil for the people who matter most.”*
