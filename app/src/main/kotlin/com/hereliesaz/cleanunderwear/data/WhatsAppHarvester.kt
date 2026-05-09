package com.hereliesaz.cleanunderwear.data

import com.hereliesaz.cleanunderwear.network.WebViewScraper
import com.hereliesaz.cleanunderwear.util.DiagnosticLogger
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Scrapes the WhatsApp Web chat sidebar for distinct conversation partners.
 *
 * Caveat: web.whatsapp.com requires a one-time QR linkage with the user's phone. The injection
 * script will wait until the chat list grid renders (post-link) before dumping. If the user has
 * never linked, this returns an empty list silently — by design; the user is expected to link
 * via the app's WebView surface beforehand.
 */
@Singleton
class WhatsAppHarvester @Inject constructor(
    private val scraper: WebViewScraper
) {
    private val INJECTION_SCRIPT = """
        (function() {
            const start = Date.now();
            const giveUpAfterMs = 90000;

            function listGrid() {
                return document.querySelector('div[aria-label="Chat list"][role="grid"]')
                    || document.querySelector('div[role="grid"]');
            }

            function poll() {
                const grid = listGrid();
                const linked = !document.querySelector('canvas');
                if (!linked || !grid) {
                    if (Date.now() - start > giveUpAfterMs) {
                        AndroidInterface.processHtml(document.documentElement.outerHTML);
                        return;
                    }
                    setTimeout(poll, 1500);
                    return;
                }
                let lastCount = -1;
                function scroll() {
                    grid.scrollTop = grid.scrollHeight;
                    const rows = grid.querySelectorAll('div[role="listitem"]');
                    if (rows.length === lastCount) {
                        AndroidInterface.processHtml(document.documentElement.outerHTML);
                        return;
                    }
                    lastCount = rows.length;
                    setTimeout(scroll, 1200);
                }
                scroll();
            }
            poll();
        })();
    """.trimIndent()

    suspend fun harvestContacts(): List<Target> {
        val html = scraper.scrapeWithInjection(
            "https://web.whatsapp.com/",
            INJECTION_SCRIPT
        ) ?: return emptyList()
        return parseChatListHtml(Jsoup.parse(html))
    }

    private fun parseChatListHtml(doc: Document): List<Target> {
        val contacts = mutableListOf<Target>()

        val rows = doc.select("div[role=listitem]")
        for (row in rows) {
            val title = row.select("span[title]").firstOrNull()?.attr("title")?.trim()
                ?: row.select("span[dir=auto]").firstOrNull()?.text()?.trim()
                ?: continue
            if (title.isBlank()) continue

            // A title that's all phone-shaped characters means WhatsApp didn't
            // resolve the contact to a name (chat saved by phone number only).
            // Capture the digits as the phoneNumber so downstream identity
            // matching can still work; everything else gets the title as-is.
            if (title.startsWith("+") || title.matches(Regex("^[0-9 +()\\-]+$"))) {
                contacts += Target(
                    displayName = title,
                    phoneNumber = title.filter { it.isDigit() || it == '+' },
                    sourceAccount = "Meta (WhatsApp)",
                    status = TargetStatus.UNKNOWN
                )
            } else {
                contacts += Target(
                    displayName = title,
                    sourceAccount = "Meta (WhatsApp)",
                    status = TargetStatus.UNKNOWN
                )
            }
        }

        val deduped = contacts.distinctBy { it.displayName }
        DiagnosticLogger.log("WhatsApp Harvest: Identified ${deduped.size} chat partners.")
        return deduped
    }
}
