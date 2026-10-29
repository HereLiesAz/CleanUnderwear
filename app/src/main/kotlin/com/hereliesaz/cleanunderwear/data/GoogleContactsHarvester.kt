package com.hereliesaz.cleanunderwear.data

import com.hereliesaz.cleanunderwear.util.DiagnosticLogger
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Reads the user's contact list from contacts.google.com.
 *
 * Runs in the visible browser ([com.hereliesaz.cleanunderwear.domain.BrowserMission.HarvestGoogleContacts]),
 * so the user signs in there when Google redirects to its sign-in page: the browser screen leaves
 * any page off contacts.google.com to them (un-scrimmed) and runs [SCRIPT] once they are back on
 * it. [SCRIPT] is re-run after every page load and only reads on contacts.google.com.
 */
@Singleton
class GoogleContactsHarvester @Inject constructor() {
    companion object {
        const val URL = "https://contacts.google.com/"

        val SCRIPT = """
        (function() {
            if (window.location.hostname !== 'contacts.google.com') return;
            function rowsContainer() {
                return document.querySelector('main [role="grid"]')
                    || document.querySelector('[role="main"] [role="grid"]')
                    || document.querySelector('[role="grid"]');
            }
            let lastCount = -1;
            function step(attempts) {
                const grid = rowsContainer();
                if (!grid) {
                    if (attempts > 60) {
                        window.HTMLOUT.dump(document.documentElement.outerHTML);
                        return;
                    }
                    setTimeout(() => step(attempts + 1), 1000);
                    return;
                }
                window.scrollTo(0, document.body.scrollHeight);
                grid.scrollTop = grid.scrollHeight;
                const rows = grid.querySelectorAll('[role="row"]');
                if (rows.length === lastCount) {
                    window.HTMLOUT.dump(document.documentElement.outerHTML);
                    return;
                }
                lastCount = rows.length;
                setTimeout(() => step(0), 1500);
            }
            step(0);
        })();
    """.trimIndent()
    }

    fun parse(html: String): List<Target> = parseContactsHtml(Jsoup.parse(html))

    private fun parseContactsHtml(doc: Document): List<Target> {
        val targets = mutableListOf<Target>()
        val phonePattern = Regex("[+]?[0-9][0-9 ()\\-]{6,}")

        val rows = doc.select("[role=row]")
        for (row in rows) {
            // Skip the header row.
            if (row.select("[role=columnheader]").isNotEmpty()) continue

            val cells = row.select("[role=gridcell]")
            if (cells.isEmpty()) continue

            val nameCell = cells.firstOrNull() ?: continue
            val displayName = nameCell.text().trim()
            if (displayName.isBlank()) continue

            val rowText = row.text()
            val emailMatch = Regex("[\\w._%+-]+@[\\w.-]+\\.[A-Za-z]{2,}").find(rowText)?.value
            val phoneMatch = phonePattern.find(rowText)?.value?.filter {
                it.isDigit() || it == '+'
            }?.takeIf { it.length >= 7 }

            targets += Target(
                displayName = displayName,
                email = emailMatch,
                phoneNumber = phoneMatch,
                sourceAccount = "Google",
                status = TargetStatus.UNKNOWN
            )
        }

        val deduped = targets.distinctBy { (it.phoneNumber ?: it.email ?: it.displayName) }
        DiagnosticLogger.log("Google Contacts Harvest: Identified ${deduped.size} entries.")
        return deduped
    }
}
