package com.filestech.sms.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.filestech.sms.core.result.Outcome
import com.filestech.sms.domain.model.PhoneAddress
import com.filestech.sms.domain.repository.ConversationRepository
import com.filestech.sms.security.AppLockManager
import com.filestech.sms.system.notifications.PendingNavHolder
import com.filestech.sms.system.share.IncomingShareHolder
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

@HiltViewModel
class AppRootViewModel @Inject constructor(
    val appLock: AppLockManager,
    /**
     * v1.4.1 — exposed so [AppRoot] can observe `pending` and route the user to the
     * [com.filestech.sms.ui.screens.compose.ComposeScreen] picker whenever a share-target
     * payload arrives (`ACTION_SEND` from the Gallery, the browser, etc.). Previously the
     * payload silently sat in the holder until the user happened to open a thread, which
     * felt like the share had been dropped on the floor.
     */
    val incomingShare: IncomingShareHolder,
    /**
     * v1.8.0 (bug 4 fix) — exposed so [AppRoot] can observe `pending` and navigate
     * to [com.filestech.sms.ui.navigation.Thread] whenever the user taps an incoming
     * message notification. Previously [com.filestech.sms.MainActivity.handleSharedIntent]
     * received the `OPEN_CONVERSATION` action but no handler navigated anywhere — the
     * app would simply open on the conversations list, leaving the user wondering why
     * the tap "did nothing".
     */
    val pendingNav: PendingNavHolder,
    /**
     * v1.14.8 (bug fix "Message" depuis Phone app) — utilisé par [AppRoot] pour résoudre
     * une adresse téléphone reçue via deep-link `sms:`/`smsto:` → trouver/créer la
     * conversation correspondante puis naviguer directement vers [ThreadScreen]. Sans ce
     * resolver, le user atterrissait sur la liste de conversations au lieu du thread.
     */
    private val conversationRepo: ConversationRepository,
) : ViewModel() {

    /**
     * v1.14.8 — Résout une adresse téléphone vers un conversationId Room (existant ou créé).
     * Appelle [ConversationRepository.findOrCreate]. Retourne `null` si l'adresse n'est pas
     * valide ou si la création/lookup échoue (cas extrême : DB indisponible).
     *
     * Lancé dans `viewModelScope` pour respecter le cycle de vie de l'activité — un kill
     * mi-route ne laisse pas de coroutine zombie.
     */
    fun resolveSendToAddress(rawAddress: String, onResolved: (Long?) -> Unit) {
        viewModelScope.launch {
            val addr = PhoneAddress.of(rawAddress)
            if (addr.normalized.isBlank()) {
                Timber.w("AppRootViewModel: rejected blank address from sms: deep-link")
                onResolved(null)
                return@launch
            }
            val res = conversationRepo.findOrCreate(listOf(addr))
            val id = if (res is Outcome.Success) res.value.id else null
            onResolved(id)
        }
    }
}
