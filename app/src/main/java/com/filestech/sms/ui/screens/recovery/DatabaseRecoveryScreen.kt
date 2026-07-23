package com.filestech.sms.ui.screens.recovery

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.filestech.sms.R

/**
 * Shown instead of the navigation graph when the database cannot be provisioned at all.
 *
 * Without it the app crash-loops with no explanation. On an application whose users chose it for
 * its vault and panic mode, "it does not start" reads as "I lost everything" — so the screen states
 * plainly what happened, that the messages are still on the device, and what to do next.
 *
 * Deliberately offers **no destructive action**. Wiping is available from the app's own settings
 * once it starts, and offering a one-tap "erase everything" to someone who has just been told their
 * app is broken is how data gets destroyed by panic rather than by choice.
 */
@Composable
fun DatabaseRecoveryScreen(cause: Throwable) {
    Surface(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
            horizontalAlignment = Alignment.Start,
        ) {
            Text(
                text = stringResource(R.string.db_recovery_title),
                style = MaterialTheme.typography.headlineSmall,
            )
            Text(
                text = stringResource(R.string.db_recovery_body),
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                text = stringResource(R.string.db_recovery_hint),
                style = MaterialTheme.typography.bodyMedium,
            )
            // The exception message carries no message content, no phone number and no key — only
            // the database file name and byte counts (see LegacyZeroKeyRekey.Failure). Surfacing it
            // is what makes a bug report actionable.
            Text(
                text = cause.message.orEmpty(),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
