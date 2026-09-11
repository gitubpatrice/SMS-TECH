package com.filestech.sms.ui.screens.settings

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.filestech.sms.core.crypto.wipe
import com.filestech.sms.data.blocking.BlockedNumbersImporter
import com.filestech.sms.data.local.datastore.SettingsRepository
import com.filestech.sms.data.sms.DefaultSmsAppManager
import com.filestech.sms.domain.repository.ConversationRepository
import com.filestech.sms.domain.settings.AppSettings
import com.filestech.sms.security.AppLockManager
import com.filestech.sms.security.PanicService
import com.filestech.sms.security.PinVerdict
import com.filestech.sms.system.scheduler.TelephonySyncWorker
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val settings: SettingsRepository,
    // v1.27.2 (audit Codex, C-06) — seul porteur du pipeline complet SMS + MMS.
    private val telephonySyncManager: com.filestech.sms.data.sync.TelephonySyncManager,
    val defaultAppManager: DefaultSmsAppManager,
    private val panic: PanicService,
    private val appLock: AppLockManager,
    private val blockedImporter: BlockedNumbersImporter,
    private val conversationRepo: ConversationRepository,
    private val vaultPin: com.filestech.sms.security.VaultPinManager,
    @ApplicationContext private val context: Context,
) : ViewModel() {

    /** One-shot UI events (snackbar après purge bloqués + after history cleanup). Buffered so a rapid tap pair is OK. */
    private val _events = Channel<Event>(capacity = Channel.BUFFERED)
    val events: Flow<Event> = _events.receiveAsFlow()

    sealed interface Event {
        data class BlockedPurged(val count: Int) : Event
        /** v1.3.0 — résultat du nettoyage manuel de l'historique (bouton "Effacer maintenant"). */
        data class HistoryPurged(val count: Int) : Event
        /** v1.3.0 — re-sync from content://sms a été enquêtée. Le snack confirme à l'utilisateur. */
        data object ResyncRequested : Event

        /** v1.26.0 — code panique enregistré. */
        data object PanicCodeSet : Event

        /** v1.26.0 — code panique retiré. */
        data object PanicCodeCleared : Event

        /**
         * v1.28.3 — la purge du coffre a échoué **localement**, et la sortie forcée ne
         * s'appliquera pas.
         *
         * Distinct de [VaultPurgeStuck], qui ouvre la sortie assumée : celle-ci ne vaut que
         * pour un résidu SYSTÈME, où plus rien n'est protégé sur l'appareil et où le PIN qu'on
         * retirerait ne garde donc plus rien. Un échec LOCAL est l'inverse — la conversation est
         * toujours là, chiffrée, dans le coffre — et retirer le PIN l'ouvrirait en grand.
         *
         * Sans cet événement, ce cas retombait dans [VaultPurgeStuck], qui rouvre le dialogue
         * « Vider quand même ? » ne menant qu'à `force = true`, refusé à son tour : une BOUCLE,
         * avec un texte affirmant que ce qui reste est « dans le stockage SMS du téléphone », ce
         * qui est faux ici. Le refus est le bon, c'est son absence d'explication qui ne l'était
         * pas — un garde sans issue doit au moins dire pourquoi, et ce qu'il reste à faire.
         */
        data class VaultPurgeStuckLocal(val deleted: Int, val left: Int) : Event

        /**
         * v1.28.3 (F07) — refus d'abaisser le verrouillage : la biométrie est actuellement le
         * SEUL second facteur du Coffre, et il n'est pas vide.
         *
         * L'abaisser laisserait le Coffre ouvert à quiconque tient le téléphone déverrouillé.
         * Même règle que le refus d'ouvrir posé en v1.27.2 quand la biométrie devient
         * indisponible : le second facteur du Coffre doit rester un secret DISTINCT, et il se
         * configure juste au-dessus, dans cet écran.
         */
        data object LockDowngradeRefusedVault : Event

        /**
         * v1.28.5 (sixième note d'Andrew, point 4) — refus d'abaisser le verrouillage parce que
         * l'état du Coffre n'a pas pu être lu. Le garde échoue fermé ; il le dit pour qu'on
         * réessaie plutôt que de conclure à une application cassée.
         */
        data object LockDowngradeUnverifiable : Event

        /**
         * v1.26.0 — refus : le code proposé est le PIN principal, ou aucun PIN n'est configuré.
         * Les deux enfermeraient l'utilisateur en mode leurre sans issue.
         */
        data class PanicCodeRejected(val outcome: AppLockManager.PanicCodeOutcome) : Event

        /**
         * v1.26.1 (audit C3) — refus symétrique du précédent : le PIN proposé est le code
         * panique déjà enregistré. L'accepter enfermerait l'utilisateur en mode leurre sans
         * issue, le code panique étant évalué avant le PIN.
         */
        data object PinRejectedSameAsPanicCode : Event

        /**
         * v1.27.10 — le coffre a ete vide par la porte de sortie « PIN oublie ».
         * [count] conversations supprimees, message compris. Le PIN est retire.
         */
        data class VaultPurged(val count: Int) : Event

        /**
         * v1.27.11 (revue externe GitLab !38458, constat 2) — la purge n'a PAS abouti, donc le
         * PIN du coffre **reste en place**. [deleted] conversations sont parties, [left] ne le
         * sont pas : soit leur copie dans le fournisseur du systeme a survecu — elle reviendrait
         * a la resynchronisation suivante, hors du coffre — soit elles sont encore la.
         *
         * Cause la plus courante, et la seule que l'utilisateur puisse corriger : SMS Tech n'est
         * plus l'application SMS par defaut, donc le systeme lui refuse la suppression.
         */
        data class VaultPurgeIncomplete(val deleted: Int, val left: Int) : Event

        /**
         * v1.28.2 — la purge a echoue **une seconde fois**. Le PIN reste en place, mais on
         * propose desormais la sortie assumee : vider quand meme, en sachant que [left]
         * copie(s) systeme subsisteront.
         *
         * Le second echec, et pas le premier : une panne passagere — role SMS momentanement
         * perdu — se leve par un simple nouvel essai, et offrir l'option degradee tout de suite
         * pousserait a detruire plus que necessaire. Un echec qui se REPETE, lui, ne se levera
         * pas : c'est en general une liaison restauree d'un autre telephone, que rien dans
         * l'application ne peut reparer.
         */
        data class VaultPurgeStuck(val deleted: Int, val left: Int) : Event

        /**
         * v1.28.2 — l'utilisateur a choisi la sortie assumee. Le coffre est vide et le PIN
         * retire, mais [left] copie(s) systeme ont resiste et **restent sur le telephone**.
         * Cela se dit, cela ne se tait pas : c'est la contrepartie qu'il a acceptee.
         */
        data class VaultPurgedWithResidue(val deleted: Int, val left: Int) : Event
    }

    val state: StateFlow<AppSettings> = settings.flow.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000L),
        AppSettings(),
    )

    /**
     * v1.10.0 audit SEC-1 — exposé pour que [SettingsScreen] puisse masquer
     * la section Mode urgence en session [AppLockManager.LockState.PanicDecoy].
     * Un agresseur en decoy ne doit pas voir qu'un mode urgence existe
     * (l'illusion "app SMS ordinaire" doit tenir).
     */
    val isPanicDecoy: StateFlow<Boolean> = appLock.state
        .map { it is AppLockManager.LockState.PanicDecoy }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000L), false)

    /**
     * v1.10.0 perf P2 — temps restant avant déclenchement du Safety call (en ms).
     * Recomputé à chaque tick 60s (granularité suffisante pour un compteur d'heures
     * affiché en h/j) OU à chaque changement de [state] (reset "Je vais bien",
     * modification timeout, désactivation…). Évite l'appel à
     * `System.currentTimeMillis()` à chaque recomposition de [SettingsScreen].
     *
     * Valeur sentinelle [REMAINING_NOT_ARMED] quand le deadman est désactivé ou
     * pas encore initialisé — l'UI ne lit cette flow que dans la branche `armed`,
     * mais la sentinelle évite toute lecture stale entre deux ticks.
     */
    val safetyCallRemainingMs: StateFlow<Long> = combine(
        state,
        flow {
            while (true) {
                emit(Unit)
                delay(60_000L)
            }
        },
    ) { snapshot, _ ->
        val cfg = snapshot.security.safetyCall
        // v1.10.0 SEC-11 — affichage cohérent avec [SafetyCallConfig.isExpired] :
        // si la mono clock n'est pas posée (config v1.9.0 héritée), on traite
        // comme "non armé" — l'UI ne fait pas miroiter un compte à rebours qui
        // ne déclencherait pas.
        if (!cfg.enabled || cfg.lastActivityAt == 0L || cfg.monotonicLastActivityAt == 0L) {
            REMAINING_NOT_ARMED
        } else {
            (cfg.lastActivityAt + cfg.timeoutMs) - System.currentTimeMillis()
        }
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000L),
        REMAINING_NOT_ARMED,
    )

    companion object {
        /** Sentinelle : Safety call inactif (désactivé ou non initialisé). */
        const val REMAINING_NOT_ARMED: Long = Long.MIN_VALUE
    }

    fun update(transform: (AppSettings) -> AppSettings) = viewModelScope.launch { settings.update(transform) }

    /**
     * v1.27.11 (revue externe GitLab !38458, constat 1) — la reinitialisation des preferences
     * **preserve desormais le bloc [AppSettings.security]**.
     *
     * Elle ecrivait `AppSettings()` nu. Les defauts de ce constructeur sont `lockMode = OFF` et
     * `vaultPinEnabled = false`, or les empreintes des deux PIN ne vivent PAS dans les reglages
     * mais dans le magasin securise. Reinitialiser ne les effacait donc pas : il cessait
     * simplement de les consulter. Le coffre restait plein — l'operation ne touche a aucune
     * conversation — et s'ouvrait sans rien demander, `VaultViewModel.entryGate` ne posant son
     * `pinRequired` que sur le flag ; au demarrage suivant,
     * [com.filestech.sms.security.AppLockManager.resolveInitialState] ne lit que `lockMode` et
     * concluait `Disabled`.
     *
     * Le chemin le plus couteux etait le mode leurre : le bouton vit dans la section « Avance »
     * de [SettingsScreen], hors du garde `!isPanicDecoy` qui protege les sections Sauvegarde,
     * Safety call et Mode urgence. Un agresseur ayant obtenu le code panique sous contrainte
     * sortait donc du leurre en trois tapes, avec les donnees intactes — c'est-a-dire tout ce
     * que le deni plausible promet d'empecher.
     *
     * Preserver le bloc plutot que masquer le bouton : masquer est une enumeration d'ecrans, qui
     * ne dit rien du prochain point d'entree. Retirer une protection reste une operation de la
     * couche securite, et elle exige le secret en place ([disableVaultPin], [clearLock]) ; ce
     * n'est pas l'effet de bord d'un bouton de confort.
     */
    fun resetAll() = viewModelScope.launch {
        settings.update { AppSettings(security = it.security) }
    }

    fun nukeData() = viewModelScope.launch { panic.nukeEverything() }

    /**
     * Sets the user's PIN/passphrase via [AppLockManager.setPin]. The `CharArray` is wiped
     * inside the manager so the secret never lingers on the JVM heap. Caller (UI) must hand
     * over a fresh `CharArray` — we never accept `String` to avoid the implicit intern table.
     */
    fun setPin(pin: CharArray) = viewModelScope.launch {
        // v1.26.1 (audit C3) — le refus « PIN identique au code panique » doit être VISIBLE :
        // un échec muet laisserait l'utilisateur croire son nouveau PIN posé alors qu'il ne
        // l'est pas. Voir [AppLockManager.setPin] pour la raison du refus.
        when (appLock.setPin(pin)) {
            AppLockManager.SetPinOutcome.Ok -> Unit
            AppLockManager.SetPinOutcome.SameAsPanicCode ->
                _events.send(Event.PinRejectedSameAsPanicCode)
        }
    }

    /** Disables the lock entirely (back to [com.filestech.sms.domain.settings.LockMode.OFF]). */
    fun clearLock() = viewModelScope.launch {
        // v1.26.0 — `clearPin` retire aussi le code panique : sans PIN principal, celui-ci
        // deviendrait le seul secret connu et ouvrirait l'application en leurre définitif. On
        // reflète donc l'état ici, sans relire le magasin (on sait ce qui vient de s'y passer).
        // v1.28.3 (F07) — le refus remonte : cf. [AppLockManager.refusDAbaissement].
        signalerRefus(appLock.clearPin())
        // v1.26.1 (audit C1) — on RELIT l'état au lieu de le supposer : `clearPin()` refuse
        // désormais en session leurre, donc « on sait ce qui vient de s'y passer » n'est plus
        // vrai sur tous les chemins. Relire coûte une lecture DataStore et ne peut pas dériver.
        _panicCodeSet.value = appLock.isPanicCodeSet()
    }

    /**
     * v1.26.0 — vrai si un code panique est enregistré.
     *
     * Le secret ne vit pas dans `AppSettings` (il est haché dans le magasin sécurisé, comme le
     * PIN), donc son état ne transite pas par le flux des réglages : on le relit à la demande.
     */
    private val _panicCodeSet = kotlinx.coroutines.flow.MutableStateFlow(false)
    val panicCodeSet: StateFlow<Boolean> = _panicCodeSet

    fun refreshPanicCodeSet() = viewModelScope.launch {
        _panicCodeSet.value = appLock.isPanicCodeSet()
    }

    /** v1.26.0 — voir [AppLockManager.setPanicCode] pour les deux refus et leur raison. */
    fun setPanicCode(code: CharArray) = viewModelScope.launch {
        when (val outcome = appLock.setPanicCode(code)) {
            AppLockManager.PanicCodeOutcome.Ok -> {
                _panicCodeSet.value = true
                _events.send(Event.PanicCodeSet)
            }
            else -> _events.send(Event.PanicCodeRejected(outcome))
        }
    }

    fun clearPanicCode() = viewModelScope.launch {
        appLock.clearPanicCode()
        _panicCodeSet.value = false
        _events.send(Event.PanicCodeCleared)
    }

    /**
     * Forces the blocked-conversation purge synchronously and reports the count via [events].
     * v1.25.3 — c'est désormais le **seul** déclencheur de cette purge. Elle s'enchaînait
     * auparavant à `BlockedNumbersImporter.importFromSystem()`, donc au démarrage de l'app et à
     * chaque synchronisation : bloquer un numéro effaçait la conversation en tâche de fond,
     * définitivement et sans avertissement. Ici l'utilisateur la demande, et l'écran le prévient
     * (`settings_purge_blocked_confirm_body` annonce l'irréversibilité et le fournisseur système).
     */
    fun purgeBlockedConversations() = viewModelScope.launch {
        val count = runCatching { blockedImporter.purgeMatchingConversations() }.getOrDefault(0)
        _events.send(Event.BlockedPurged(count))
    }

    /**
     * Switches to biometric unlock **on top of an existing PIN**. Caller (UI) must ensure a PIN
     * is configured first — returns `false` if not (in which case the UI should keep the picker
     * open and route the user through PIN setup).
     */
    suspend fun enableBiometricOverPin(): Boolean = appLock.enableBiometric()

    /** Reverts to PIN-only mode. */
    fun disableBiometric() = viewModelScope.launch { signalerRefus(appLock.disableBiometric()) }

    /** v1.28.3 (F07) — un refus silencieux ferait recommencer l'utilisateur indéfiniment. */
    private suspend fun signalerRefus(outcome: AppLockManager.LockDowngradeOutcome) {
        when (outcome) {
            AppLockManager.LockDowngradeOutcome.VaultWouldLoseItsFactor ->
                _events.send(Event.LockDowngradeRefusedVault)
            // v1.28.5 — un refus faute de lecture se dit aussi, sinon l'utilisateur recommence.
            AppLockManager.LockDowngradeOutcome.VaultStateUnknown ->
                _events.send(Event.LockDowngradeUnverifiable)
            AppLockManager.LockDowngradeOutcome.Ok,
            AppLockManager.LockDowngradeOutcome.PanicDecoy,
            -> Unit
        }
    }

    /**
     * v1.3.0 — compte combien de messages seraient effacés par un nettoyage manuel à la
     * profondeur [olderThanDays]. Suspend pour que le dialog "Effacer maintenant" puisse
     * afficher le total avant confirmation et permettre à l'utilisateur d'annuler si le
     * volume est inattendu (sécurité). Retourne 0 si la sélection est désactivée ou si
     * aucun message ne correspond.
     */
    suspend fun countHistoryToPurge(olderThanDays: Int?): Int {
        val days = olderThanDays ?: return 0
        if (days <= 0) return 0
        return runCatching { conversationRepo.countMessagesToPurge(days) }.getOrDefault(0)
    }

    /**
     * v1.3.0 — déclenche un nettoyage manuel immédiat à la profondeur [olderThanDays] et
     * émet un [Event.HistoryPurged] avec le nombre de rows effacées. Ne touche pas au
     * `lastAutoPurgeAt` (le cycle mensuel auto reste indépendant). No-op si désactivé.
     */
    fun purgeHistoryNow(olderThanDays: Int?) = viewModelScope.launch {
        val days = olderThanDays ?: return@launch
        if (days <= 0) return@launch
        val count = runCatching { conversationRepo.purgeHistoryNow(days) }.getOrDefault(0)
        _events.send(Event.HistoryPurged(count))
    }

    /**
     * v1.3.0 — force une resynchronisation complète depuis `content://sms` (le system
     * provider Android). Reset le curseur `lastSyncedSmsId = 0` puis enqueue un OneTime
     * worker pour relancer immédiatement le scan complet. L'index UNIQUE `telephony_uri`
     * + `OnConflictStrategy.IGNORE` garantissent l'absence de doublons : seuls les
     * messages absents de Room sont réinsérés. Utilisé pour récupérer un historique
     * purgé par erreur (auto-purge ou nettoyage manuel) tant que les rows sont encore
     * dans le system provider.
     */
    fun forceResyncFromTelephony() = viewModelScope.launch {
        // 🔴 v1.27.2 (audit Codex du 2026-08-05, C-06) — CE BOUTON NE RESYNCHRONISAIT PAS LES MMS.
        //
        // Il ne remettait que le curseur SMS a zero, puis enfilait `TelephonySyncWorker` — qui ne
        // lit aucun MMS et REAVANCE le curseur. Quand `TelephonySyncManager` reprenait la main,
        // `isFirstRun` etait redevenu faux, `hasAnyMms` etait vrai, et le marqueur de completion
        // MMS aussi : l'import MMS restait saute. Le dialogue annonce pourtant « fournisseur
        // SMS/MMS », et c'est le geste presente comme reparant un historique incomplet.
        //
        // Les DEUX marqueurs sont donc invalides dans la meme ecriture, et c'est le manager —
        // seul porteur du pipeline complet SMS + MMS — qui est sollicite.
        settings.update {
            it.copy(
                advanced = it.advanced.copy(
                    lastSyncedSmsId = 0L,
                    mmsImportCompleted = false,
                ),
            )
        }
        telephonySyncManager.requestSync("resynchronisation manuelle")
        TelephonySyncWorker.enqueueOneShot(context)
        _events.send(Event.ResyncRequested)
    }

    /**
     * v1.13.0 — pose ou remplace le PIN/pass coffre. Les tableaux sont wipés par
     * [com.filestech.sms.security.VaultPinManager]. Le flag `vaultPinEnabled`
     * est posé `true` côté manager après hash réussi.
     *
     * v1.27.10 (revue externe GitLab !38458) — [current] est **obligatoire des lors qu'un PIN
     * est deja en place** : le remplacer sans le connaitre annulait tout l'interet du second
     * facteur. `null` n'est admis que pour la toute premiere configuration, et le manager le
     * verifie de son cote — cette fonction n'est pas la seule garde.
     *
     * `suspend` et non `launch` : l'appelant est un dialogue qui doit RESTER ouvert et afficher
     * le verdict en cas de refus.
     */
    suspend fun submitVaultPin(current: CharArray?, newPin: CharArray): PinVerdict =
        // Le `!isVaultPinConfigured()` traite l'incoherence documentee « flag ON, hash absent »
        // (restauration partielle, cf. [VaultPinManager.isVaultPinConfigured]) : il n'y a alors
        // aucun secret a prouver, et exiger un PIN qui n'existe pas enfermerait l'utilisateur
        // dehors de son propre coffre. Ce n'est pas un assouplissement du garde — sans hash, il
        // n'y a rien a garder.
        if (current == null || !vaultPin.isVaultPinConfigured()) {
            current?.wipe()
            if (vaultPin.configureVaultPin(newPin)) PinVerdict.Ok else PinVerdict.Invalid
        } else {
            vaultPin.changeVaultPin(current, newPin)
        }

    /**
     * v1.13.0 — retire le PIN/pass coffre + flip le flag à `false`.
     *
     * v1.27.10 — exige desormais le PIN en place. Le contenu du coffre n'est PAS touche : il
     * redevient simplement atteignable derriere le seul verrou d'application.
     */
    suspend fun disableVaultPin(current: CharArray): PinVerdict =
        if (!vaultPin.isVaultPinConfigured()) {
            // Meme incoherence que dans [submitVaultPin] : pas de hash, donc rien a prouver.
            current.wipe()
            vaultPin.forgetVaultPin()
            PinVerdict.Ok
        } else {
            vaultPin.disableVaultPin(current)
        }

    /** v1.27.10 — millisecondes de temporisation restantes, pour le compte a rebours. */
    suspend fun vaultLockoutRemainingMs(): Long = vaultPin.vaultLockoutRemainingMs()

    /**
     * v1.27.10 — porte de sortie « PIN du coffre oublie ». **Detruit le contenu du coffre**,
     * puis retire le PIN.
     *
     * L'ordre n'est pas negociable : purger d'abord, ouvrir ensuite. L'inverse laisserait, entre
     * les deux ecritures, un coffre en clair et encore plein — precisement l'etat que le
     * porteur du seul PIN d'application cherchait a obtenir.
     *
     * v1.27.11 (revue externe GitLab !38458, constat 2) — l'ordre ne suffisait pas : le PIN
     * partait **quel que soit** le sort de la purge, y compris quand elle n'avait rien efface du
     * tout. Purger d'abord ne protege que si l'on regarde ensuite ce que la purge a fait. Le PIN
     * n'est donc retire que sur un coffre demontrablement vide, cf. [VaultPurgeResult].
     *
     * Le refus doit se VOIR : c'est une porte de sortie, et un echec muet y laisserait
     * l'utilisateur croire son coffre ouvert alors qu'il reste ferme — ou l'inverse.
     */
    fun forgetVaultPinAndPurge(force: Boolean = false) = viewModelScope.launch {
        val dejaEchoue = settings.flow.first().security.vaultPurgeFailedOnce
        // v1.28.5 (sixieme note d'Andrew, point 2) — le PIN est retire SOUS la barriere de purge,
        // pas apres le retour. Entre les deux, la barriere retombait et une conversation pouvait
        // entrer au coffre apres la relecture de `remaining` : le PIN partait sur un coffre qui
        // venait de se remplir. La decision est prise ici, une seule fois, et les evenements
        // ci-dessous decoulent de ce qui a ete FAIT, pas d'une seconde evaluation des memes
        // conditions — le jumeau asymetrique, encore lui.
        //
        // v1.28.2 — sortie assumee : l'utilisateur a demande qu'on vide quand meme, apres
        // qu'on lui a dit ce qui subsisterait. Ce qui subsiste lui est redit ici.
        //
        // v1.28.3 (F09) — la sortie n'est plus accordee sur le seul fait que `force` a ete
        // demande, mais sur la NATURE de ce qui subsiste. `residuSystemeSeul` veut dire que
        // plus rien n'est protege sur cet appareil : le PIN qu'on retire ne garde plus rien.
        // Un echec de suppression LOCALE tombe dans la branche `VaultPurgeStuckLocal`
        // ci-dessous, qui ne retire pas le PIN — sans quoi on ouvrirait un coffre encore
        // plein, alors que le texte de consentement promet a l'utilisateur que ce qui reste
        // est « dans le stockage SMS du telephone ».
        var pinRetire = false
        val purge = conversationRepo.deleteAllInVault(force) { resultat ->
            if (resultat.isComplete || (force && resultat.residuSystemeSeul)) {
                vaultPin.forgetVaultPin()
                pinRetire = true
            }
        }
        val reste = purge.reste
        when {
            pinRetire && purge.isComplete -> {
                oublierLEchecPasse()
                _events.send(Event.VaultPurged(purge.deleted))
            }
            pinRetire -> {
                oublierLEchecPasse()
                _events.send(Event.VaultPurgedWithResidue(purge.deleted, reste))
            }
            // v1.28.3 (audit du 2026-09-09) — l'echec LOCAL n'a pas de sortie forcee, et il
            // faut le DIRE.
            //
            // La branche `force && residuSystemeSeul` ci-dessus ne l'accorde qu'a un residu
            // systeme. Un echec local retombait donc dans `dejaEchoue`, qui rouvre le dialogue
            // « Vider quand meme ? » ne menant qu'a `force = true` — refuse a son tour. Une
            // boucle, sous un texte affirmant que ce qui reste est « dans le stockage SMS du
            // telephone », faux dans ce cas : la conversation est toujours dans le coffre.
            //
            // Le refus reste le bon — retirer le PIN ouvrirait un coffre encore plein. Ce qui
            // manquait est l'explication, sans laquelle un garde devient une impasse.
            purge.localFailures > 0 ->
                _events.send(Event.VaultPurgeStuckLocal(purge.deleted, reste))
            // Second echec : celui-la ne se levera pas tout seul, on ouvre la sortie.
            dejaEchoue -> _events.send(Event.VaultPurgeStuck(purge.deleted, reste))
            else -> {
                settings.update {
                    it.copy(security = it.security.copy(vaultPurgeFailedOnce = true))
                }
                _events.send(Event.VaultPurgeIncomplete(purge.deleted, reste))
            }
        }
    }

    /**
     * Le drapeau ne doit survivre ni a une purge reussie ni a une sortie assumee : dans les deux
     * cas le coffre est ouvert, et le laisser poserait la sortie degradee d'emblee au prochain
     * PIN de coffre que l'utilisateur configurerait.
     */
    private suspend fun oublierLEchecPasse() {
        settings.update { it.copy(security = it.security.copy(vaultPurgeFailedOnce = false)) }
    }
}
