# Architecture Guide

A single-module Android app: Kotlin, Jetpack Compose, Hilt, Room, WorkManager, OkHttp. No server.

## Layers

### Presentation (`ui/`)
- **Compose** screens: `TargetListScreen`, `TargetDetailScreen`, `ReviewScreen`, `SettingsScreen`, `OnboardingScreen`, `BrowserScreen`, hosted by `CleanUnderwearApp` under an AzNavRail rail.
- **`MainViewModel`** (`@HiltViewModel`) holds UI state as `StateFlow`s and one-shot messages as a flow; screens collect them and call its functions. Preferences (theme, check hour, contact limit, alerts) are `SharedPreferences`.
- Shared components and the theme live in `Components.kt` and `Theme.kt` (see the Design Guide).

### Domain (`domain/`, `match/`)
- **Use cases**: `HarvestContactsUseCase` → `DeduplicateTargetsUseCase` → `TriageTargetsUseCase` → `ScrapeTargetsUseCase`, run in order by `RunPipelineUseCase`. Enrichment (CyberBackgroundChecks) is not part of the pipeline; it runs only when the user starts it.
- **`PipelineCoordinator`** serializes the pipeline, sweeps and enrichment, so two never write the Registry at once.
- **Matching** (`match/`): `RecordExtractor` turns record text into `CandidateRecord`s, `MatchVerifier` scores them, `ProbabilisticScorer` scores duplicate pairs.
- **Review**: `SuspectDetector` (pure) finds suspect merges and stale numbers; `SuspectReview` applies the user's verdicts.

### Data (`data/`, `network/`, `util/`)
- **Room** (`CleanUnderwearDatabase`, version 15): `Target` (the Registry) and `MergeRecord` (the merge ledger). Schemas are exported to `app/schemas` (only 15 so far); `MigrationTest` rebuilds versions 13 and 14 from 15's schema, less the columns later migrations added, and upgrades them. Migrations 1-12 are untested. `TargetRepository` wraps the DAO and runs multi-step writes in transactions.
- **Network**: raw OkHttp, built in `di/AppModule`. `SourceCatalog` reads `assets/sources.json` and plans which sources a contact is searched in; `SourceSearch` and `HtmlScraper` run them; `StructuredRecords` and `NewsFeed` parse the results. Gson parses JSON.
- **Contacts**: `ContactHarvester` reads the phone's contacts; `SystemContactSyncer` writes the status block back into their notes.

## Background work
`ScrapingWorker` (`@HiltWorker`) runs `RunPipelineUseCase` once a day; `MainViewModel` schedules it as unique periodic work.

## Dependency injection
Hilt: `AppModule` provides the OkHttp client, database and DAOs; everything else is `@Inject`-constructed. Interfaces with test doubles (`CommsHistory`, `MessagingPresenceSource`) are bound there.

## Tests
JVM unit tests under `app/src/test`: matching, parsing (two recorded obituary pages, and HTML and JSON samples in the tests), dedup, notes, the update checker, and Room (migrations, DAO, repository) on Robolectric. `./gradlew testDebugUnitTest`.

## Code quality
Android lint runs in CI. Detekt is configured at `config/detekt/detekt.yml`.
