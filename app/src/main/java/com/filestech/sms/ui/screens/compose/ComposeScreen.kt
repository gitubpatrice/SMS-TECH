package com.filestech.sms.ui.screens.compose

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.PersonAdd
import androidx.compose.material3.AssistChip
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.filestech.sms.R
import com.filestech.sms.ui.components.Avatar
import com.filestech.sms.ui.components.ContactIntents
import com.filestech.sms.ui.components.SmsTechSnackbarHost
import com.filestech.sms.ui.components.showError
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ComposeScreen(
    initialAddress: String?,
    onBack: () -> Unit,
    onConversationCreated: (Long) -> Unit,
    viewModel: ComposeViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val filtered by viewModel.filtered.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbarHost = remember { SnackbarHostState() }
    LaunchedEffect(Unit) {
        viewModel.events.collect { e ->
            when (e) {
                is ComposeViewModel.Event.ConversationCreated -> onConversationCreated(e.id)
            }
        }
    }

    Scaffold(
        snackbarHost = { SmsTechSnackbarHost(snackbarHost) },
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.action_new_message)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = stringResource(R.string.action_back))
                    }
                },
                actions = {
                    TextButton(
                        onClick = viewModel::createConversation,
                        enabled = state.recipients.isNotEmpty(),
                    ) {
                        Text(stringResource(R.string.action_continue))
                    }
                },
            )
        },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(stringResource(R.string.compose_to_label), style = MaterialTheme.typography.titleSmall)
            }
            if (state.recipients.isNotEmpty()) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    state.recipients.forEach { addr ->
                        AssistChip(
                            onClick = { viewModel.removeRecipient(addr) },
                            label = { Text(addr.raw) },
                            trailingIcon = { Icon(Icons.Outlined.Close, contentDescription = null, modifier = Modifier.size(16.dp)) },
                        )
                    }
                }
                Spacer(Modifier.size(8.dp))
            }
            OutlinedTextField(
                // v1.27.9 — lecture directe de l'état Compose du ViewModel : la valeur saisie
                // doit revenir au champ dans la MÊME recomposition (cf. [ComposeViewModel.searchInput]).
                value = viewModel.searchInput,
                onValueChange = viewModel::setQuery,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                placeholder = { Text(stringResource(R.string.compose_search_contact)) },
                singleLine = true,
            )
            Spacer(Modifier.size(8.dp))
            HorizontalDivider()
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                // Entrée persistante en tête de liste : ouvre l'éditeur de contacts du
                // système sur un formulaire vierge (choix produit : toujours vierge,
                // indépendamment de la recherche saisie). Placée au-dessus des résultats
                // pour rester atteignable même quand la liste est longue.
                item(key = "create-contact") {
                    ListItem(
                        headlineContent = { Text(stringResource(R.string.action_create_contact)) },
                        leadingContent = {
                            Icon(Icons.Outlined.PersonAdd, contentDescription = null)
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                if (!ContactIntents.createContact(context)) {
                                    scope.launch {
                                        snackbarHost.showError(
                                            context.getString(R.string.phone_action_no_contacts),
                                        )
                                    }
                                }
                            },
                    )
                    HorizontalDivider()
                }
                // Free-entry row: lets the user pick a raw number (or anything they typed) when
                // it doesn't match any saved contact. Audit Q-BUG-1: previously the modifier ran
                // `.let { mod -> mod.also { /* clickable */ } }` — a no-op that left the row
                // visually clickable-looking but inert, breaking the whole new-conversation flow.
                // v1.27.9 — la saisie est capturée UNE fois : la ligne affiche, teste et
                // sélectionne la même valeur. À relire `viewModel.searchInput` dans le lambda
                // `.clickable`, un tap arrivé après une frappe aurait envoyé un numéro autre
                // que celui affiché sur la ligne.
                val input = viewModel.searchInput
                // X-06 — un nom tapé n'est pas une adresse : la ligne n'apparaît que pour un
                // numéro composable, cf. [ComposeViewModel.estComposable].
                if (ComposeViewModel.estComposable(input) && filtered.none { c -> c.firstPhone?.raw == input }) {
                    item {
                        ListItem(
                            headlineContent = { Text(input) },
                            // v1.3.11 (F2) — show "Use this number" / "Add to group" rather
                            // than the misleading "Continuer" subtitle: when the picker is
                            // empty the tap opens the thread directly; otherwise it appends
                            // to the recipients chips (group flow). The [pickRecipient] call
                            // returns true in the first case so we know not to leave a stale
                            // query around the moment we navigate away.
                            supportingContent = {
                                Text(stringResource(
                                    if (state.recipients.isEmpty()) R.string.compose_use_this_number
                                    else R.string.compose_add_to_group
                                ))
                            },
                            leadingContent = { Avatar(label = input) },
                            // v1.28.3 (audit global, X-05 — mesure sur le S9) — le bouton qui
                            // rend le groupe ATTEIGNABLE. Depuis le raccourci « tap-to-pick »
                            // (v1.3.11), toucher une ligne sur une liste vide ouvre le fil :
                            // « Ajouter au groupe » ne pouvait donc jamais apparaitre, et creer
                            // un groupe dans l'application etait impossible — le libelle
                            // existait, le chemin etait mort. Ce bouton pose une puce sans
                            // naviguer ; la ligne garde son raccourci.
                            trailingContent = {
                                IconButton(
                                    onClick = {
                                        viewModel.addRecipient(input)
                                        viewModel.setQuery("")
                                    },
                                ) {
                                    Icon(
                                        Icons.Outlined.PersonAdd,
                                        contentDescription = stringResource(R.string.compose_add_to_group),
                                    )
                                }
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 4.dp)
                                .clickable {
                                    viewModel.pickRecipient(input)
                                    viewModel.setQuery("")
                                },
                        )
                    }
                }
                items(filtered, key = { it.id ?: it.hashCode().toLong() }) { contact ->
                    val number = contact.firstPhone?.raw.orEmpty()
                    ListItem(
                        headlineContent = { Text(contact.displayName ?: number) },
                        supportingContent = { Text(number) },
                        leadingContent = { Avatar(label = contact.displayName ?: number) },
                        // X-05 — meme bouton que sur la saisie libre : une puce, pas de navigation.
                        trailingContent = {
                            IconButton(
                                onClick = {
                                    viewModel.addRecipient(number)
                                    viewModel.setQuery("")
                                },
                                enabled = number.isNotBlank(),
                            ) {
                                Icon(
                                    Icons.Outlined.PersonAdd,
                                    contentDescription = stringResource(R.string.compose_add_to_group),
                                )
                            }
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(enabled = number.isNotBlank()) {
                                viewModel.pickRecipient(number)
                                viewModel.setQuery("")
                            },
                    )
                }
            }
        }
    }
}
