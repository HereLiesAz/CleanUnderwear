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
                // The list is virtualised: only the rows in view exist. Walk it a screen at a
                // time, keeping every row seen (by title), and hand back all of them at the end.
                // A group chat's row is marked, so it isn't read as a person.
                const seen = new Map();
                function collect() {
                    grid.querySelectorAll('div[role="listitem"]').forEach(function(row) {
                        const t = row.querySelector('span[title]');
                        const key = t ? t.getAttribute('title') : row.textContent.slice(0, 80);
                        if (!key || seen.has(key)) return;
                        const copy = row.cloneNode(true);
                        if (row.querySelector('[data-icon*="group"]')) copy.setAttribute('data-cu-group', '1');
                        seen.set(key, copy.outerHTML);
                    });
                }
                let lastTop = -1;
                function step() {
                    collect();
                    if (grid.scrollTop === lastTop || Date.now() - start > giveUpAfterMs) {
                        window.HTMLOUT.dump('<div>' + Array.from(seen.values()).join('') + '</div>');
                        return;
                    }
                    lastTop = grid.scrollTop;
                    grid.scrollTop = grid.scrollTop + Math.max(grid.clientHeight - 40, 200);
                    setTimeout(step, 900);
                }
                step();
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
            // A group chat ("Book Club") is not a person.
            if (row.hasAttr("data-cu-group")) continue
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
