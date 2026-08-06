package com.filestech.sms.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.filestech.sms.core.result.Outcome
import com.filestech.sms.domain.model.PhoneAddress
import com.filestech.sms.domain.repository.ConversationRepository
import com.filestech.sms.security.AppLockManager
import com.filestech.sms.system.notifications.NotificationIntentToken
import com.filestech.sms.system.notifications.PendingNavHolder
import com.filestech.sms.system.notifications.SafetyCallAckHolder
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
     * v1.27.4 — exposé pour que [AppRoot] confirme un geste Safety call fait depuis une
     * notification. Ces gestes réussissaient **en silence** : le tap acquittait l'alerte et
     * ouvrait la liste des SMS sans un mot, indiscernable d'une notification ratée. Motif du
     * jumeau asymétrique sur un couple déjà recousu une fois — voir [SafetyCallAckHolder].
     */
    val safetyCallAck: SafetyCallAckHolder,
    /**
     * v1.26.1 (audit H2) — authentifie l'intent d'ouverture de conversation avant toute
     * navigation. Voir [isNotificationIntentAuthentic].
     */
    private val notificationIntentToken: NotificationIntentToken,
    /**
     * v1.14.8 (bug fix "Message" depuis Phone app) — utilisé par [AppRoot] pour résoudre
     * une adresse téléphone reçue via deep-link `sms:`/`smsto:` → trouver/créer la
     * conversation correspondante puis naviguer directement vers [ThreadScreen]. Sans ce
     * resolver, le user atterrissait sur la liste de conversations au lieu du thread.
     */
    // v1.24.0 SEC-CRIT — `Lazy` : ce ViewModel est instancié sur le main thread pendant la
    // composition, et `ConversationRepository` tire `AppDatabase`, donc la réparation zéro-clé.
    // L'unique usage est déjà dans `viewModelScope.launch`.
    private val conversationRepoLazy: dagger.Lazy<ConversationRepository>,
    private val databaseRepairState: com.filestech.sms.data.local.db.DatabaseRepairState,
) : ViewModel() {

    /**
     * `true` once the one-shot database repair has settled.
     *
     * [AppRoot] withholds the whole navigation graph until then. The splash screen only delays
     * *drawing* — it does not stop composition, so without this gate the screen ViewModels would
     * be built on the main thread and provision `AppDatabase` there, which is exactly the work the
     * repair makes expensive.
     */
    val databaseReady: kotlinx.coroutines.flow.StateFlow<Boolean> = databaseRepairState.settled

    /** Non-null when the database could not be opened at all — drives the recovery screen. */
    val databaseFailure: kotlinx.coroutines.flow.StateFlow<Throwable?> = databaseRepairState.failure

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
            val res = conversationRepoLazy.get().findOrCreate(listOf(addr))
            val id = if (res is Outcome.Success) res.value.id else null
            onResolved(id)
        }
    }

    /**
     * v1.26.1 (audit H2) — vrai si l'intent d'ouverture de conversation vient bien de NOS
     * notifications.
     *
     * `MainActivity` est exposé (rôle SMS) : une application tierce peut lui envoyer
     * `ACTION_OPEN_CONVERSATION` avec un identifiant arbitraire. Le secret est persisté et
     * partagé par toutes nos notifications — voir
     * [com.filestech.sms.system.notifications.NotificationIntentToken] pour le détail du modèle
     * et pourquoi il n'est ni mono-usage ni roté.
     */
    suspend fun isNotificationIntentAuthentic(token: Long): Boolean {
        // v1.26.1 (audit SEC-B1) — FAIL-CLOSED, et surtout : ne jamais laisser une lecture de
        // magasin remonter jusqu'au `LaunchedEffect` de navigation.
        //
        // `secStore` n'a pas de `corruptionHandler` : une corruption fait lever `edit`/`data`.
        // Avant ce correctif, ce chemin ne faisait aucune E/S ; il en fait maintenant, donc un
        // magasin corrompu ferait crasher `AppRoot` au moindre tap sur une notification. On
        // refuse la navigation plutôt que de propager — même motif que celui posé dans
        // `AutoLockObserver`, y compris la relance de l'annulation.
        val authentic = try {
            notificationIntentToken.matches(token)
        } catch (ce: kotlin.coroutines.cancellation.CancellationException) {
            throw ce
        } catch (t: Throwable) {
            Timber.w(t, "AppRootViewModel: nav-token read failed — rejecting intent")
            false
        }
        if (!authentic) {
            Timber.w("AppRootViewModel: rejected unauthenticated navigation intent")
        }
        return authentic
    }
}
