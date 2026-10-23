package com.hereliesaz.cleanunderwear.ui

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import kotlinx.coroutines.launch

private data class Page(val title: String, val body: String)

private val PAGES = listOf(
    Page(
        "Your contacts",
        "The app reads the people saved on your phone, and can import friends from other accounts if you ask it to."
    ),
    Page(
        "A daily check",
        "Once a day, when your phone is online and its battery isn't low, each contact's name is looked up in public " +
            "jail, prison, court and obituary records, and in the news. You're told if something turns up."
    ),
    Page(
        "Matches can be wrong",
        "Many people share a name. A record that resembles someone you know is often someone else. The app shows you " +
            "why it thinks a record fits, and nothing is ever recorded about a contact until you confirm it."
    ),
    Page(
        "This can bring sad news",
        "You may learn that someone has died or been arrested. Alerts about either can be turned off in Settings; " +
            "matches still wait for you in the app."
    ),
    Page(
        "Your data",
        "Your contact list stays on your phone. To check someone, their name is sent to the records and news sites " +
            "being searched; looking up missing details sends their name, number or email to a people-search site. " +
            "A status you confirm is written into that contact's note, which syncs with your contacts account."
    )
)

/**
 * First run: what the app does, what can go wrong, what leaves the phone. Contacts and
 * notification access are asked for at the end, after the explanation, and the app works
 * (with an empty list) if either is refused.
 */
@Composable
fun OnboardingScreen(onComplete: () -> Unit) {
    val pagerState = rememberPagerState(pageCount = { PAGES.size })
    val scope = rememberCoroutineScope()
    val permissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { onComplete() }
    val last = pagerState.currentPage == PAGES.lastIndex

    Column(modifier = Modifier.fillMaxSize().systemBarsPadding()) {
        HorizontalPager(state = pagerState, modifier = Modifier.weight(1f)) { page ->
            Column(
                modifier = Modifier.fillMaxSize().padding(Spacing.xl),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Text(PAGES[page].title, style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
                Spacer(modifier = Modifier.height(Spacing.md))
                Text(
                    PAGES[page].body,
                    style = MaterialTheme.typography.bodyLarge,
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        Column(modifier = Modifier.fillMaxWidth().padding(Spacing.lg), verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            if (last) {
                AppCaption("Next, Android asks to let the app read and update your contacts, and to send you alerts.")
                AppAction("Continue", {
                    val wanted = buildList {
                        add(Manifest.permission.READ_CONTACTS)
                        add(Manifest.permission.WRITE_CONTACTS)
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) add(Manifest.permission.POST_NOTIFICATIONS)
                    }
                    permissions.launch(wanted.toTypedArray())
                })
            } else {
                AppAction("Next", { scope.launch { pagerState.animateScrollToPage(pagerState.currentPage + 1) } })
                AppTextAction("Skip", { scope.launch { pagerState.animateScrollToPage(PAGES.lastIndex) } })
            }
        }
    }
}
