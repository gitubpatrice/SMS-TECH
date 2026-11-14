package com.filestech.sms.ui.screens.compose

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.filestech.sms.core.ext.foldForSearch
import com.filestech.sms.core.ext.normalizePhone
import com.filestech.sms.core.ext.oneShotEvents
import com.filestech.sms.core.result.Outcome
import com.filestech.sms.core.result.runCatchingCancellable
import com.filestech.sms.di.IoDispatcher
import com.filestech.sms.domain.model.Contact
import com.filestech.sms.domain.model.PhoneAddress
import com.filestech.sms.domain.repository.BlockedNumberRepository
import com.filestech.sms.domain.repository.ContactRepository
import com.filestech.sms.domain.repository.ConversationRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.updateAndGet
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import javax.inject.Inject

@HiltViewModel
class ComposeViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val contactRepo: ContactRepository,
    private val conversationRepo: ConversationRepository,
    @IoDispatcher private val io: CoroutineDispatcher,
    /** 2026-11-14 — pour marquer « bloqué » la puce d'un destinataire de la liste de blocage. */
    private val blockedRepo: BlockedNumberRepository,
) : ViewModel() {

    // v1.6.1 (audit QUAL-17) — @Stable pour Compose recomposition skipping.
    @androidx.compose.runtime.Stable
    data class UiState(
        val results: List<Contact> = emptyList(),
        val recipients: List<PhoneAddress> = emptyList(),
        val initialLoaded: Boolean = false,
        /** v1.28.13 — `READ_CONTACTS` refusée : la liste est vide parce qu'on n'a pas pu la lire. */
        val contactsRefused: Boolean = false,
        /**
         * 2026-11-14 (groupe de 56 sur le S24 de Patrice) — les destinataires choisis qui figurent dans la liste de
         * blocage (par [PhoneAddress.normalized]). Un de ses contacts y était, importé de la liste d'Android, sans
         * que rien ne le montre : sa puce le dit désormais. Le fil, lui, prévient avant d'envoyer.
         */
        val bloques: Set<String> = emptySet(),
    )

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    /**
     * v1.27.9 — **le texte saisi dans le champ de recherche est un état Compose (snapshot),
     * plus un champ de [UiState].**
     *
     * `OutlinedTextField(value = …, onValueChange = …)` exige que la valeur remontée par
     * `onValueChange` lui revienne **dans la même recomposition**. Elle transitait ici par
     * `MutableStateFlow` → `collectAsStateWithLifecycle`, qui la rend au mieux une frame plus
     * tard (dispatch `AndroidUiDispatcher`), et bien davantage quand la frappe déclenche en
     * plus le re-filtrage de la liste de contacts. Le champ recevait alors une valeur périmée
     * — **et la position de curseur qui l'accompagne** : lettres réinsérées dans le désordre
     * (« christine » → « hchi… ») et touche « effacer » qui ne faisait que reculer le curseur
     * sans rien supprimer. Un `mutableStateOf` est lu par le champ dans la frame courante.
     */
    var searchInput by mutableStateOf("")
        private set

    val events = oneShotEvents<Event>()

    sealed interface Event {
        data class ConversationCreated(val id: Long) : Event

        /**
         * 2026-11-14 (Patrice : « chaque fois que j'en ajoute un, aucun message ne le confirme ») — un destinataire
         * vient d'être ajouté : son [nom] (ou son numéro), le [total] des destinataires, et s'il est [bloque].
         */
        data class DestinataireAjoute(val nom: String, val total: Int, val bloque: Boolean) : Event

        /** 2026-11-14 — ce destinataire était déjà choisi : rien n'a changé, et on le dit. */
        data class DejaChoisi(val nom: String) : Event
    }

    /**
     * v1.28.3 — mode groupe (« Nouveau groupe » dans la liste) : le raccourci « toucher = ouvrir le
     * fil » est désactivé, chaque ligne ajoute une puce, « Continuer » crée la conversation.
     */
    val modeGroupe: Boolean = savedStateHandle.get<Boolean>("groupe") ?: false

    init {
        savedStateHandle.get<String>("initialAddress")?.let { addr ->
            _state.update { it.copy(recipients = listOf(PhoneAddress.of(addr))) }
        }
        chargerContacts()
    }

    /**
     * v1.28.13 (MR F-Droid !38458, test de by-architect sur Android 16) — **la liste se charge sans
     * la permission Contacts, et l'écran dit pourquoi elle est vide.**
     *
     * `ContactsReader.listAll` lève `SecurityException` quand `READ_CONTACTS` est refusée, et rien
     * ne la rattrapait dans `viewModelScope` : ouvrir « Nouveau message » faisait planter l'app. Le
     * protocole de test de F-Droid refuse exprès les permissions optionnelles ; nos tests, et le
     * premier testeur, les accordaient toutes.
     *
     * ⚠️ Le refus se rattrape ICI, pas dans `ContactRepositoryImpl` : `IncomingBlockPolicy` a besoin
     * de l'exception pour distinguer « je n'ai pas pu regarder » de « expéditeur inconnu ». L'avaler
     * dans le dépôt couperait toute réception à qui bloque les inconnus sans accorder les contacts.
     *
     * Rappelée par l'écran à chaque retour au premier plan tant que l'accès manque : Android tue le
     * processus quand on RETIRE une permission, pas quand on l'accorde.
     */
    fun chargerContacts() {
        viewModelScope.launch {
            val lecture = runCatchingCancellable { contactRepo.listAll() }
            lecture.exceptionOrNull()?.let { Timber.w(it, "ComposeViewModel: contacts illisibles") }
            _state.update {
                it.copy(
                    results = lecture.getOrDefault(emptyList()),
                    initialLoaded = true,
                    contactsRefused = lecture.exceptionOrNull() is SecurityException,
                )
            }
        }
    }

    fun setQuery(q: String) {
        searchInput = q
    }

    /** @param nom 2026-11-14 — le nom du contact, pour le dire à l'ajout ; à défaut, le numéro. */
    fun addRecipient(rawNumber: String, nom: String? = null) {
        ajouter(PhoneAddress.of(rawNumber), nom)
    }

    fun removeRecipient(addr: PhoneAddress) {
        _state.update { it.copy(recipients = it.recipients - addr, bloques = it.bloques - addr.normalized) }
    }

    /**
     * 2026-11-14 — **ajoute [addr], et le dit.** Signalé par Patrice en créant un groupe de 56 : aucun ajout n'était
     * confirmé, et la quatrième puce, de largeur nulle, ne se voyait pas — il ne savait pas si son geste avait pris.
     * L'écran annonce désormais le nom, le total, et un numéro de la liste de blocage (sa puce le marque aussi :
     * même test que l'envoi, `BlockedNumberRepository.isBlocked` ; une lecture impossible ne marque rien, le fil
     * prévient de toute façon avant un MMS de groupe). Un destinataire déjà choisi n'est pas ajouté deux fois, et
     * on le dit plutôt que de ne rien faire.
     *
     * @return `true` si [addr] a été ajouté.
     */
    private fun ajouter(addr: PhoneAddress, nom: String?): Boolean {
        if (addr.normalized.isEmpty()) return false
        val libelle = nom?.takeIf { it.isNotBlank() } ?: addr.raw
        if (_state.value.recipients.any { it.normalized == addr.normalized }) {
            events.tryEmit(Event.DejaChoisi(libelle))
            return false
        }
        _state.update { it.copy(recipients = it.recipients + addr) }
        viewModelScope.launch {
            val bloque = runCatchingCancellable { withContext(io) { blockedRepo.isBlocked(addr.raw) } }
                .onFailure { Timber.w(it, "Compose: liste de blocage illisible") }
                .getOrDefault(false)
            // Toujours choisi ? Retiré pendant la lecture, il n'y a plus de puce à marquer ni d'ajout à annoncer.
            val apres = _state.updateAndGet { s ->
                val choisi = s.recipients.any { it.normalized == addr.normalized }
                if (choisi && bloque) s.copy(bloques = s.bloques + addr.normalized) else s
            }
            if (apres.recipients.any { it.normalized == addr.normalized }) {
                events.tryEmit(Event.DestinataireAjoute(libelle, apres.recipients.size, bloque))
            }
        }
        return true
    }

    /**
     * v1.3.11 (F2) — "tap-to-pick" UX: when the picker is empty (the common single-recipient
     * case), tapping a contact / free-entry row should immediately open the thread instead of
     * requiring an extra "Continuer" tap. If the user has already started building a group
     * (chips already present), the tap only appends a new chip and the explicit "Continuer"
     * button stays as the validation step — preserving the multi-recipient flow.
     *
     * Returns `true` when the tap triggered the navigation (caller can use this to dismiss
     * the keyboard / clear the query field), `false` when it just appended a chip.
     */
    fun pickRecipient(rawNumber: String, nom: String? = null): Boolean {
        val addr = PhoneAddress.of(rawNumber)
        if (addr.normalized.isEmpty()) return false
        if (_state.value.recipients.isEmpty() && !modeGroupe) {
            // Le raccourci : la conversation s'ouvre, l'ouverture tient lieu de confirmation.
            _state.update { it.copy(recipients = it.recipients + addr) }
            createConversation()
            return true
        }
        // 2026-11-14 — sinon une puce de plus, annoncée (cf. [ajouter]).
        ajouter(addr, nom)
        return false
    }

    fun createConversation() {
        viewModelScope.launch {
            val rec = _state.value.recipients
            if (rec.isEmpty()) return@launch
            val res = conversationRepo.findOrCreate(rec)
            if (res is Outcome.Success) events.tryEmit(Event.ConversationCreated(res.value.id))
        }
    }

    /**
     * Contact « pré-indexé » pour la recherche : le nom est **replié une seule fois**
     * (casse + accents retirés via [foldForSearch]) et les numéros normalisés une seule
     * fois, au moment où la liste de contacts change — et non à chaque frappe. Le filtrage
     * par requête ne fait alors que des `contains` sur ces chaînes déjà calculées.
     */
    private data class IndexedContact(
        val contact: Contact,
        val foldedName: String,
        val phoneDigits: List<String>,
    )

    /**
     * Résultats filtrés exposés en [StateFlow] dérivé (optimal) plutôt qu'en fonction
     * rappelée à chaque recomposition : le repli des contacts n'a lieu qu'au changement
     * de la liste source ([distinctUntilChanged]), et le filtrage qu'au changement de
     * requête. Recherche insensible à la **casse ET aux accents** (« maite » trouve
     * « Maïté »), et par numéro si l'utilisateur tape des chiffres.
     */
    val filtered: StateFlow<List<Contact>> = combine(
        // `snapshotFlow` fait le pont état Compose → Flow : le champ lit [searchInput] dans la frame
        // courante, le filtrage se déclenche derrière, sur IO, sans jamais réécrire le champ.
        snapshotFlow { searchInput.trim() }.distinctUntilChanged(),
        _state.map { it.results }.distinctUntilChanged().map { list ->
            list.map { c ->
                IndexedContact(
                    contact = c,
                    foldedName = c.displayName?.foldForSearch().orEmpty(),
                    phoneDigits = c.phones.map { it.normalized },
                )
            }
        },
    ) { q, indexed ->
        if (q.isEmpty()) return@combine indexed.map { it.contact }
        val needle = q.foldForSearch()
        // v1.2.10 : `normalizePhone()` d'un texte pur (ex. "Patrice") rend "" et
        // `contains("")` matcherait TOUT → on ne tente le match numéro que si la requête
        // contient réellement des chiffres (`n.isNotBlank()`).
        val n = q.normalizePhone()
        indexed.mapNotNull { idx ->
            val match = idx.foldedName.contains(needle) ||
                (n.isNotBlank() && idx.phoneDigits.any { it.contains(n) })
            if (match) idx.contact else null
        }
    }
        // Le repli NFD (`foldForSearch`) de tous les contacts au chargement de la liste ne doit
        // pas s'exécuter sur le Main : `combine{}.stateIn` tourne sur Main.immediate par défaut.
        // `flowOn(io)` déplace le fold + le filtrage sur IO — parité avec ConversationsViewModel.
        .flowOn(io)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000L), emptyList())

    companion object {
        /**
         * v1.28.3 (audit global, X-06 — mesuré sur le S9) — **une saisie n'est proposée comme
         * destinataire que si elle se compose.**
         *
         * La ligne de saisie libre offrait n'importe quel texte comme adresse : taper « Pat »
         * puis l'ajouter envoyait un SMS au destinataire « Pat », que la pile refusait avec
         * `RESULT_ERROR_NULL_PDU`, bulle rouge et notification d'échec à la clé — pour un nom
         * qui figurait dans les contacts juste au-dessus. `PhoneAddress.of` garde à dessein les
         * adresses alphanumériques (« Free », « INFO ») : elles servent en RÉCEPTION, jamais en
         * destination. Chiffres, `+`, espaces, parenthèses, tirets et points ; au moins deux
         * chiffres pour couvrir les numéros courts.
         */
        fun estComposable(saisie: String): Boolean {
            val propre = saisie.trim()
            if (propre.count { it.isDigit() } < 2) return false
            return propre.all { it.isDigit() || it in "+ ()-." }
        }
    }
}
