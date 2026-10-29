package com.hereliesaz.cleanunderwear.ui

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import com.hereliesaz.cleanunderwear.R
import kotlinx.coroutines.launch

private data class Page(@androidx.annotation.StringRes val title: Int, @androidx.annotation.StringRes val body: Int)

private val PAGES = listOf(
    Page(R.string.onboarding_contacts_title, R.string.onboarding_contacts_body),
    Page(R.string.onboarding_daily_title, R.string.onboarding_daily_body),
    Page(R.string.onboarding_wrong_title, R.string.onboarding_wrong_body),
    Page(R.string.onboarding_sad_title, R.string.onboarding_sad_body),
    Page(R.string.onboarding_data_title, R.string.onboarding_data_body)
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

    Column(modifier = Modifier.fillMaxSize().systemBarsPadding().displayCutoutPadding().contentWidth()) {
        HorizontalPager(state = pagerState, modifier = Modifier.weight(1f)) { page ->
            // Scrolls, so a page still reads whole at a large font size.
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(androidx.compose.foundation.rememberScrollState())
                    .padding(Spacing.xl),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Text(stringResource(PAGES[page].title), style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
                Spacer(modifier = Modifier.height(Spacing.md))
                Text(
                    stringResource(PAGES[page].body),
                    style = MaterialTheme.typography.bodyLarge,
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        Column(modifier = Modifier.fillMaxWidth().padding(Spacing.lg), verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            AppCaption(
                stringResource(R.string.onboarding_page_of, pagerState.currentPage + 1, PAGES.size),
                Modifier.align(Alignment.CenterHorizontally)
            )
            if (last) {
                AppCaption(stringResource(R.string.onboarding_permissions_note))
                AppAction(stringResource(R.string.onboarding_continue), {
                    val wanted = buildList {
                        add(Manifest.permission.READ_CONTACTS)
                        add(Manifest.permission.WRITE_CONTACTS)
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) add(Manifest.permission.POST_NOTIFICATIONS)
                    }
                    permissions.launch(wanted.toTypedArray())
                })
            } else {
                // No Skip: the pages on wrong matches and on sad news are the ones not to miss.
                AppAction(stringResource(R.string.onboarding_next), { scope.launch { pagerState.animateScrollToPage(pagerState.currentPage + 1) } })
            }
        }
    }
}
