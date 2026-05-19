package com.filestech.sms.ui.components

import android.content.Intent
import android.net.Uri
import android.provider.ContactsContract
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.PersonAdd
import androidx.compose.material.icons.outlined.Phone
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import com.filestech.sms.R
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * v1.3.11 (F4) — actions sheet triggered when the user taps a phone number detected inside
 * a message body. Mirrors the Apple Messages / Google Messages pattern but rendered as an
 * [AlertDialog] for cohesion with SMS Tech's existing confirm flows.
 *
 * Three actions:
 *   - **Appeler** → `Intent.ACTION_DIAL` with a `tel:` URI. We intentionally use `ACTION_DIAL`
 *     (no runtime permission, pre-fills the dialer; user still has to tap "Call") rather
 *     than `ACTION_CALL` which would require the `CALL_PHONE` permission — unnecessary
 *     friction for an SMS app.
 *   - **Copier** → push the digits to the system clipboard + surface a snackbar via [onSnack].
 *   - **Ajouter aux contacts** → `ContactsContract.Intents.Insert.ACTION` pre-filled with the
 *     phone number; the system Contacts app picks up from there (no permission needed).
 *
 * Robustness:
 *   - Each `startActivity` is wrapped in `runCatching` so a stripped ROM without a default
 *     dialer / contacts app does not crash the thread. The failure surfaces as a snackbar.
 *   - The phone number is rendered verbatim (no normalisation) so the user sees what was
 *     written in the message; the `tel:` URI uses the raw value — Android's dialer accepts
 *     any free-form input.
 */
@Composable
fun PhoneActionsDialog(
    number: String,
    onDismiss: () -> Unit,
    onSnack: (String) -> Unit,
) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(number, style = MaterialTheme.typography.titleMedium) },
        text = { Text(stringResource(R.string.phone_action_subtitle)) },
        confirmButton = {
            // Material3 stacks the action list inside `confirmButton`. We pick a Column so
            // the three actions wrap vertically — readable on every width without truncation.
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                PhoneActionRow(
                    icon = Icons.Outlined.Phone,
                    labelRes = R.string.phone_action_call,
                    onClick = {
                        runCatching {
                            context.startActivity(
                                Intent(Intent.ACTION_DIAL).apply {
                                    data = Uri.parse("tel:$number")
                                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                },
                            )
                        }.onFailure {
                            Timber.w(it, "ACTION_DIAL failed for %s", number)
                            scope.launch { onSnack(context.getString(R.string.phone_action_no_dialer)) }
                        }
                        onDismiss()
                    },
                )
                PhoneActionRow(
                    icon = Icons.Outlined.ContentCopy,
                    labelRes = R.string.action_copy,
                    onClick = {
                        clipboard.setText(AnnotatedString(number))
                        scope.launch { onSnack(context.getString(R.string.toast_copied)) }
                        onDismiss()
                    },
                )
                PhoneActionRow(
                    icon = Icons.Outlined.PersonAdd,
                    labelRes = R.string.phone_action_add_contact,
                    onClick = {
                        runCatching {
                            context.startActivity(
                                Intent(ContactsContract.Intents.Insert.ACTION).apply {
                                    type = ContactsContract.RawContacts.CONTENT_TYPE
                                    putExtra(ContactsContract.Intents.Insert.PHONE, number)
                                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                },
                            )
                        }.onFailure {
                            Timber.w(it, "Contacts Insert intent failed for %s", number)
                            scope.launch { onSnack(context.getString(R.string.phone_action_no_contacts)) }
                        }
                        onDismiss()
                    },
                )
            }
        },
        // v1.4.0 — no explicit "Annuler" button: tap-outside the dialog and the Android
        // back gesture both already invoke [onDismissRequest] (= [onDismiss]). A separate
        // "Annuler" entry would (a) clutter the three-action stack, (b) sit visually
        // orphaned because Material3's `dismissButton` slot lays out below the
        // `confirmButton` block. Standard pattern across iMessage / Google Messages.
    )
}

@Composable
private fun PhoneActionRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    labelRes: Int,
    onClick: () -> Unit,
) {
    TextButton(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(icon, contentDescription = null)
            Text(
                text = stringResource(labelRes),
                modifier = Modifier.padding(start = 12.dp),
            )
        }
    }
}
