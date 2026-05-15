package com.filestech.sms.ui.screens.onboarding

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.filestech.sms.R
import kotlinx.coroutines.launch

@Composable
fun OnboardingScreen(onFinished: () -> Unit) {
    val pager = rememberPagerState(pageCount = { 4 })
    val scope = rememberCoroutineScope()

    Column(modifier = Modifier.fillMaxSize().padding(24.dp)) {
        HorizontalPager(state = pager, modifier = Modifier.weight(1f)) { page ->
            val (title, body) = when (page) {
                0 -> stringResource(R.string.onboarding_welcome_title) to stringResource(R.string.onboarding_welcome_body)
                1 -> stringResource(R.string.onboarding_permissions_title) to stringResource(R.string.onboarding_permissions_body)
                2 -> stringResource(R.string.onboarding_default_title) to stringResource(R.string.onboarding_default_body)
                else -> stringResource(R.string.onboarding_language_title) to stringResource(R.string.onboarding_language_body)
            }
            Column(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(text = title, style = MaterialTheme.typography.headlineMedium)
                Spacer(Modifier.size(16.dp))
                Text(
                    text = body,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Row(
            // Audit Q-BUG-2: previously `fillMaxSize()` here stole the entire remaining vertical
            // space from the pager above (which lives inside a `Column` weight chain) — the
            // pager content was getting clipped or compressed depending on the device height.
            // The footer row only needs full width.
            modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = onFinished) { Text(stringResource(R.string.action_continue)) }
            Button(
                onClick = {
                    if (pager.currentPage == pager.pageCount - 1) onFinished()
                    else scope.launch { pager.animateScrollToPage(pager.currentPage + 1) }
                },
            ) {
                Text(if (pager.currentPage == pager.pageCount - 1) stringResource(R.string.onboarding_done) else stringResource(R.string.action_continue))
            }
        }
    }
}
