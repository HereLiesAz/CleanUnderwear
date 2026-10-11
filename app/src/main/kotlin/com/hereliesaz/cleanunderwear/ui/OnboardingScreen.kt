package com.hereliesaz.cleanunderwear.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.hereliesaz.aznavrail.*
import com.hereliesaz.aznavrail.model.AzButtonShape
import kotlinx.coroutines.launch

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun OnboardingScreen(onComplete: () -> Unit) {
    val pagerState = rememberPagerState(pageCount = { 4 })
    val scope = rememberCoroutineScope()

    Column(modifier = Modifier.fillMaxSize()) {
        HorizontalPager(
            state = pagerState,
            modifier = Modifier.weight(1f)
        ) { page ->
            OnboardingPage(page)
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(24.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextButton(onClick = onComplete) {
                Text("Skip")
            }
            
            AzButton(
                text = if (pagerState.currentPage == 3) "Get Started" else "Next",
                onClick = {
                    if (pagerState.currentPage < 3) {
                        scope.launch { pagerState.animateScrollToPage(pagerState.currentPage + 1) }
                    } else {
                        onComplete()
                    }
                },
                shape = AzButtonShape.RECTANGLE
            )
        }
    }
}

@Composable
fun OnboardingPage(page: Int) {
    val (title, description) = when (page) {
        0 -> "Your Contacts" to "We bring in contacts from your phone and the accounts you connect, and fill in missing details, so you can keep up with the people you know."
        1 -> "Daily Check" to "Every morning at 9 AM, the app looks your contacts up in public arrest, inmate and obituary records and tells you if something changes."
        2 -> "Match Evidence" to "If a status change is detected, we provide you with the exact snippet of text as evidence, allowing you to verify the match manually."
        3 -> "Your Data" to "Your contact list stays on your device: no account, no cloud. To run lookups, contact names (and sometimes a city or area code) are sent to the public-records and lookup sites being searched."
        else -> "" to ""
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.headlineLarge,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = description,
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
