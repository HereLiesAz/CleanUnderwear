package com.hereliesaz.cleanunderwear.data

import com.hereliesaz.cleanunderwear.util.DiagnosticLogger
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Reads the WhatsApp Web chat sidebar for distinct conversation partners.
 *
 * Runs in the visible browser ([com.hereliesaz.cleanunderwear.domain.BrowserMission.HarvestWhatsApp]):
 * web.whatsapp.com shows its QR code there for the user to scan with their phone, and [SCRIPT]
 * waits for the chat list to render after linking before handing the page back.
 */
@Singleton
class WhatsAppHarvester @Inject constructor() {
    companion object {
        const val URL = "https://web.whatsapp.com/"

        val SCRIPT = """
        (function() {
            const start = Date.now();
            const giveUpAfterMs = 240000;

            function listGrid() {
                return document.querySelector('div[aria-label="Chat list"][role="grid"]')
                    || document.querySelector('div[role="grid"]');
            }

            function poll() {
                const grid = listGrid();
                const linked = !document.querySelector('canvas');
                if (!linked || !grid) {
                    if (Date.now() - start > giveUpAfterMs) {
                        window.HTMLOUT.dump(document.documentElement.outerHTML);
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
                        window.HTMLOUT.dump(document.documentElement.outerHTML);
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
    }

    fun parse(html: String): List<Target> = parseChatListHtml(Jsoup.parse(html))

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

        // Chat titles are the only identifier WhatsApp Web exposes, so a repeated
        // title (pagination overlap) is treated as the same chat.
        val deduped = contacts.distinctBy { it.displayName }
        DiagnosticLogger.log("WhatsApp Harvest: Identified ${deduped.size} chat partners.")
        return deduped
    }
}
