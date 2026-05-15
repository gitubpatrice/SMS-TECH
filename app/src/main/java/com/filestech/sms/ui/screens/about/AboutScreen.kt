package com.filestech.sms.ui.screens.about

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.BugReport
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.Gavel
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.PrivacyTip
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.filestech.sms.BuildConfig
import com.filestech.sms.R

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AboutScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    var tapCount by remember { mutableStateOf(0) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.about_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = stringResource(R.string.action_back))
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Image(
                painter = painterResource(id = R.drawable.sms_tech_icon),
                contentDescription = null,
                modifier = Modifier.size(96.dp).clip(RoundedCornerShape(24.dp)),
            )
            Spacer(Modifier.size(12.dp))
            Text(text = stringResource(R.string.app_name), style = MaterialTheme.typography.headlineSmall)
            Text(
                text = stringResource(R.string.about_version, BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.clickable { tapCount++ },
            )
            if (tapCount >= 7) {
                Spacer(Modifier.size(4.dp))
                Text(text = "✨ Merci à toutes celles et ceux qui prennent soin de leur vie privée. ✨", style = MaterialTheme.typography.labelSmall)
            }
            Spacer(Modifier.size(8.dp))
            Text(
                text = stringResource(R.string.about_tagline_short),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(Modifier.size(24.dp))
            ValuesSection()

            Spacer(Modifier.size(16.dp))
            SecurityCard()

            Spacer(Modifier.size(16.dp))
            Text(stringResource(R.string.about_permissions_title), style = MaterialTheme.typography.titleMedium, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.size(8.dp))
            PermissionLine("SEND_SMS / RECEIVE_SMS / READ_SMS / WRITE_SMS", "Required for any SMS app.")
            PermissionLine("RECEIVE_MMS / RECEIVE_WAP_PUSH", "Receive incoming MMS.")
            PermissionLine("READ_CONTACTS", "Show contact names instead of bare numbers.")
            PermissionLine("READ_PHONE_STATE / READ_PHONE_NUMBERS", "Multi-SIM support (sending from the correct SIM).")
            PermissionLine("POST_NOTIFICATIONS", "Show new-message notifications.")
            PermissionLine("USE_BIOMETRIC", "Optional biometric unlock.")
            PermissionLine("SCHEDULE_EXACT_ALARM", "Send scheduled messages at the exact time.")
            PermissionLine("INTERNET", "MMS transport via your carrier MMSC. No analytics.")
            PermissionLine("RECORD_AUDIO", "Record audio messages attached to outgoing MMS.")
            PermissionLine("FOREGROUND_SERVICE", "Long-running migration / backup.")

            Spacer(Modifier.size(16.dp))
            HorizontalDivider()
            Spacer(Modifier.size(8.dp))
            ListItem(
                headlineContent = { Text(stringResource(R.string.about_source_code)) },
                leadingContent = { Icon(Icons.Outlined.Code, contentDescription = null) },
                trailingContent = { Icon(Icons.Outlined.Public, contentDescription = null) },
                modifier = Modifier.clickable {
                    // Audit U4: never crash if the user has no browser installed.
                    safeStartActivity(context, Intent(Intent.ACTION_VIEW, Uri.parse(REPO_URL)))
                },
            )
            ListItem(
                headlineContent = { Text(stringResource(R.string.about_report_issue)) },
                leadingContent = { Icon(Icons.Outlined.BugReport, contentDescription = null) },
                modifier = Modifier.clickable {
                    val intent = Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:contact@files-tech.com")).apply {
                        putExtra(Intent.EXTRA_SUBJECT, "SMS Tech ${BuildConfig.VERSION_NAME}")
                    }
                    safeStartActivity(context, intent)
                },
            )
            ListItem(
                headlineContent = { Text(stringResource(R.string.about_website)) },
                supportingContent = { Text(WEBSITE_URL, style = MaterialTheme.typography.bodySmall) },
                leadingContent = { Icon(Icons.Outlined.Language, contentDescription = null) },
                modifier = Modifier.clickable {
                    safeStartActivity(context, Intent(Intent.ACTION_VIEW, Uri.parse(WEBSITE_URL)))
                },
            )
            ListItem(
                headlineContent = { Text(stringResource(R.string.about_license)) },
                supportingContent = { Text("Apache License 2.0", style = MaterialTheme.typography.bodySmall) },
                leadingContent = { Icon(Icons.Outlined.Gavel, contentDescription = null) },
                modifier = Modifier.clickable {
                    safeStartActivity(context, Intent(Intent.ACTION_VIEW, Uri.parse(LICENSE_URL)))
                },
            )
            ListItem(
                headlineContent = { Text(stringResource(R.string.about_privacy)) },
                leadingContent = { Icon(Icons.Outlined.PrivacyTip, contentDescription = null) },
                modifier = Modifier.clickable {
                    safeStartActivity(context, Intent(Intent.ACTION_VIEW, Uri.parse(PRIVACY_URL)))
                },
            )

            Spacer(Modifier.size(16.dp))
            Text(stringResource(R.string.about_credits_title), style = MaterialTheme.typography.titleMedium, modifier = Modifier.fillMaxWidth())
            Text(
                text = stringResource(R.string.about_credits_body, "Patrice Haltaya"),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(Modifier.size(12.dp))
            Text(stringResource(R.string.about_legal_title), style = MaterialTheme.typography.titleMedium, modifier = Modifier.fillMaxWidth())
            Text(
                text = stringResource(R.string.about_legal_body, "2026", "Patrice Haltaya"),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.size(24.dp))
        }
    }
}

@Composable
private fun ValuesSection() {
    Column {
        Text(text = stringResource(R.string.about_values_title), style = MaterialTheme.typography.titleMedium, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.size(8.dp))
        ValueRow(stringResource(R.string.about_value_private), stringResource(R.string.about_value_private_body))
        ValueRow(stringResource(R.string.about_value_quiet), stringResource(R.string.about_value_quiet_body))
        ValueRow(stringResource(R.string.about_value_local), stringResource(R.string.about_value_local_body))
        ValueRow(stringResource(R.string.about_value_open), stringResource(R.string.about_value_open_body))
    }
}

@Composable
private fun ValueRow(title: String, body: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Icon(Icons.Outlined.Lock, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.size(8.dp))
        Column {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun SecurityCard() {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(stringResource(R.string.about_security_title), style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.size(4.dp))
            Text(
                text = stringResource(R.string.about_security_body),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun PermissionLine(name: String, why: String) {
    ListItem(
        headlineContent = { Text(name, style = MaterialTheme.typography.bodyMedium) },
        supportingContent = { Text(why, style = MaterialTheme.typography.bodySmall) },
        modifier = Modifier.fillMaxWidth(),
    )
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))
}

/** Audit U4: never crash if no Activity is registered for the intent. */
private fun safeStartActivity(context: android.content.Context, intent: android.content.Intent) {
    runCatching { context.startActivity(intent) }
        .onFailure { timber.log.Timber.w(it, "safeStartActivity: no handler for %s", intent.action) }
}

private const val REPO_URL = "https://github.com/gitubpatrice/sms_tech"
private const val WEBSITE_URL = "https://files-tech.com"
private const val LICENSE_URL = "https://www.apache.org/licenses/LICENSE-2.0"
private const val PRIVACY_URL = "https://github.com/gitubpatrice/sms_tech/blob/main/PRIVACY.md"
