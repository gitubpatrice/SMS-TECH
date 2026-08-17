package com.filestech.sms.ui.screens.blocked

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.filestech.sms.R
import com.filestech.sms.domain.model.BlockedNumber
import com.filestech.sms.domain.repository.BlockedNumberRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class BlockedNumbersViewModel @Inject constructor(
    private val repo: BlockedNumberRepository,
) : ViewModel() {
    val state: StateFlow<List<BlockedNumber>> = repo.observe()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000L), emptyList())

    fun add(number: String) = viewModelScope.launch { repo.block(number) }
    fun remove(rawNumber: String) = viewModelScope.launch { repo.unblock(rawNumber) }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BlockedNumbersScreen(onBack: () -> Unit, viewModel: BlockedNumbersViewModel = hiltViewModel()) {
    val rows by viewModel.state.collectAsStateWithLifecycle()
    var showDialog by remember { mutableStateOf(false) }
    var newNumber by remember { mutableStateOf("") }

    /** v1.25.3 (audit M21) — numéro en attente de confirmation de déblocage. */
    var confirmUnblockFor by remember { mutableStateOf<String?>(null) }

    // Lu une fois : le bouton flottant s'en sert DEUX fois — comme nom accessible et comme libellé
    // visible. Deux appels diraient la même chose, mais laisseraient croire que les deux valeurs
    // peuvent diverger, alors que c'est précisément ce qu'il ne faut pas. Voir le bouton plus bas.
    val ajouterUnNumero = stringResource(R.string.blocked_add)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.blocked_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = stringResource(R.string.action_back))
                    }
                },
            )
        },
        floatingActionButton = {
            // 🔴🔴 **Le nom accessible est posé sur le BOUTON. Sans lui, ce bouton était MUET.**
            //
            // `ExtendedFloatingActionButton` de material3 1.4.0 (BOM Compose 2026.06.00) enveloppe
            // son emplacement `text` dans un `clearAndSetSemantics` : le libellé est **dessiné** et
            // **absent de l'arbre de sémantique fusionné**, celui que lit un lecteur d'écran.
            //
            // Mesuré sur le S9 le 2026-08-17, sur la **1.27.3 publiée** : ce nœud portait
            // `clickable=true`, `text=""`, `content-desc=""`, et `NAF="true"` — la marque que
            // `uiautomator` pose lui-même sur un nœud cliquable sans nom. TalkBack annonçait donc
            // « bouton », sans dire lequel, sur le seul moyen d'ajouter un numéro à la liste.
            //
            // ⚠️ `contentDescription = null` sur l'icône reste correct et le demeure : une icône
            // décorative ne se nomme pas, et la nommer ici ajouterait une **seconde** source de
            // libellé — donc une annonce en double le jour où material3 cesse d'effacer le slot, et
            // un arrêt de focus parasite si son `mergeDescendants` change. La propriété appartient à
            // l'**action**, pas au pictogramme.
            //
            // Trouvé en portant Notes Tech, qui écrivait exactement le même code.
            ExtendedFloatingActionButton(
                text = { Text(ajouterUnNumero) },
                icon = { Icon(Icons.Outlined.Add, contentDescription = null) },
                onClick = { showDialog = true },
                modifier = Modifier.semantics { contentDescription = ajouterUnNumero },
            )
        },
    ) { padding ->
        if (rows.isEmpty()) {
            Column(modifier = Modifier.fillMaxSize().padding(padding).padding(24.dp), verticalArrangement = Arrangement.Center) {
                Text(text = stringResource(R.string.blocked_empty), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            LazyColumn(modifier = Modifier.fillMaxSize().padding(padding)) {
                items(rows, key = { it.id }) { item ->
                    ListItem(
                        headlineContent = { Text(item.rawNumber) },
                        supportingContent = item.label?.let { { Text(it) } },
                        trailingContent = {
                            // v1.25.3 (audit M21) — passe par une confirmation : un tap
                            // accidentel remettait en circulation un numéro indésirable, sans
                            // annulation possible ni trace de ce qui venait de se produire.
                            IconButton(onClick = { confirmUnblockFor = item.rawNumber }) {
                                Icon(Icons.Outlined.Delete, contentDescription = stringResource(R.string.action_unblock))
                            }
                        },
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                }
            }
        }
    }

    confirmUnblockFor?.let { number ->
        AlertDialog(
            onDismissRequest = { confirmUnblockFor = null },
            title = { Text(stringResource(R.string.blocked_unblock_confirm_title)) },
            text = { Text(stringResource(R.string.blocked_unblock_confirm_body, number)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmUnblockFor = null
                    viewModel.remove(number)
                }) { Text(stringResource(R.string.action_unblock)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmUnblockFor = null }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }

    if (showDialog) {
        AlertDialog(
            onDismissRequest = { showDialog = false; newNumber = "" },
            title = { Text(stringResource(R.string.blocked_add)) },
            text = {
                OutlinedTextField(value = newNumber, onValueChange = { newNumber = it }, singleLine = true, modifier = Modifier.fillMaxWidth())
            },
            confirmButton = {
                TextButton(onClick = { viewModel.add(newNumber); showDialog = false; newNumber = "" }, enabled = newNumber.isNotBlank()) {
                    Text(stringResource(R.string.action_block))
                }
            },
            dismissButton = { TextButton(onClick = { showDialog = false; newNumber = "" }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
}
