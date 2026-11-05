package com.filestech.sms.ui.screens.media

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.filestech.sms.domain.model.Attachment
import com.filestech.sms.domain.repository.ConversationRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

/**
 * Issue #59 — les photos et vidéos d'une conversation, envoyées et reçues, en lecture seule.
 *
 * On y entre par le menu du fil, jamais d'ailleurs : une galerie de toutes les conversations mêlerait
 * celles du Coffre aux autres.
 */
@HiltViewModel
class MediaGalleryViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    repo: ConversationRepository,
) : ViewModel() {

    private val conversationId: Long = checkNotNull(savedStateHandle["conversationId"])

    /** `null` tant que la première lecture n'a pas répondu : l'écran ne dit pas « aucune photo » avant de savoir. */
    val media: StateFlow<List<Attachment>?> = repo.observeMedia(conversationId)
        .stateIn<List<Attachment>?>(viewModelScope, SharingStarted.WhileSubscribed(5_000L), null)

    /** Le Coffre s'est refermé : l'écran sort, comme le fil, au lieu de prétendre qu'il n'y a rien. */
    val vaultHidden: StateFlow<Boolean> = repo.observeVaultHidden(conversationId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000L), false)
}
