# scripts/ (historical)

Nothing here is run by the build, CI or the app. These are leftovers from earlier versions, kept as history (see `docs/MODEL_CARD.md`):

- `train_research_ai.py` trains the TFLite classifier of official sheriff/government sites versus commercial ones that older versions shipped and later removed.
- `scraper_utils.py` is an early Python prototype of authenticated scraping and result parsing; the app does this in Kotlin now (`network/`).
- `fb_injection_script.js` is an early WebView scroll-and-capture script. It calls `AndroidInterface.processHtml`, a JavaScript interface the app no longer registers, so it does nothing if injected.
