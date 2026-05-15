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
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun add(number: String) = viewModelScope.launch { repo.block(number) }
    fun remove(rawNumber: String) = viewModelScope.launch { repo.unblock(rawNumber) }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BlockedNumbersScreen(onBack: () -> Unit, viewModel: BlockedNumbersViewModel = hiltViewModel()) {
    val rows by viewModel.state.collectAsStateWithLifecycle()
    var showDialog by remember { mutableStateOf(false) }
    var newNumber by remember { mutableStateOf("") }

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
            ExtendedFloatingActionButton(
                text = { Text(stringResource(R.string.blocked_add)) },
                icon = { Icon(Icons.Outlined.Add, contentDescription = null) },
                onClick = { showDialog = true },
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
                            IconButton(onClick = { viewModel.remove(item.rawNumber) }) {
                                Icon(Icons.Outlined.Delete, contentDescription = stringResource(R.string.action_unblock))
                            }
                        },
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                }
            }
        }
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
