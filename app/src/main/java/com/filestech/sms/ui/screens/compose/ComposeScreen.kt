package com.filestech.sms.ui.screens.compose

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.PersonAdd
import androidx.compose.material.icons.outlined.PersonOff
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.filestech.sms.R
import com.filestech.sms.domain.model.PhoneAddress
import com.filestech.sms.system.settings.ouvrirLaFicheDeLApplication
import com.filestech.sms.ui.components.Avatar
import com.filestech.sms.ui.components.ContactIntents
import com.filestech.sms.ui.components.SmsTechSnackbarHost
import com.filestech.sms.ui.components.showError
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ComposeScreen(
    initialAddress: String?,
    /** v1.28.3 — mode groupe : chaque ligne ajoute une puce, rien n'ouvre de fil avant « Continuer ». */
    groupe: Boolean = false,
    onBack: () -> Unit,
    onConversationCreated: (Long) -> Unit,
    viewModel: ComposeViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val filtered by viewModel.filtered.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbarHost = remember { SnackbarHostState() }
    // 2026-11-14 — gabarits lus dans la composition (sensibles à la configuration), mis en forme à l'annonce,
    // comme les bandeaux d'envoi partiel de `ThreadScreen`.
    val ajouteFmt = stringResource(R.string.compose_recipient_added)
    val ajouteBloqueFmt = stringResource(R.string.compose_recipient_added_blocked)
    val dejaFmt = stringResource(R.string.compose_recipient_already)
    val bandeau = remember { BandeauCourant() }
    LaunchedEffect(Unit) {
        viewModel.events.collect { e ->
            when (e) {
                is ComposeViewModel.Event.ConversationCreated -> onConversationCreated(e.id)
                // 2026-11-14 (Patrice) — chaque ajout est confirmé. Le bandeau précédent est remplacé, pas empilé :
                // on ajoute souvent plusieurs contacts d'affilée.
                is ComposeViewModel.Event.DestinataireAjoute -> bandeau.annoncer(
                    snackbarHost,
                    (if (e.bloque) ajouteBloqueFmt else ajouteFmt).format(e.nom, e.total),
                    scope,
                )
                is ComposeViewModel.Event.DejaChoisi -> bandeau.annoncer(snackbarHost, dejaFmt.format(e.nom), scope)
            }
        }
    }
    // v1.28.13 — relit les contacts au retour au premier plan tant que l'accès manque : on revient
    // le plus souvent de la fiche Android où l'on vient de l'accorder.
    val cycleDeVie = LocalLifecycleOwner.current.lifecycle
    val accesRefuse by rememberUpdatedState(state.contactsRefused)
    DisposableEffect(cycleDeVie) {
        val observateur = LifecycleEventObserver { _, evenement ->
            if (evenement == Lifecycle.Event.ON_RESUME && accesRefuse) viewModel.chargerContacts()
        }
        cycleDeVie.addObserver(observateur)
        onDispose { cycleDeVie.removeObserver(observateur) }
    }

    Scaffold(
        snackbarHost = { SmsTechSnackbarHost(snackbarHost) },
        topBar = {
            TopAppBar(
                title = {
                    Text(stringResource(if (groupe) R.string.action_new_group else R.string.action_new_message))
                },
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
        // v1.28.3 (audit global, X-07 — mesuré sur le S9) — la liste RÉSERVE la place du clavier.
        //
        // Sans `imePadding`, la colonne gardait la hauteur de l'écran entier et le clavier en
        // recouvrait le bas ; quand les résultats tenaient dans cette hauteur, rien ne défilait,
        // et les contacts sous le clavier — le plus souvent celui qu'on cherchait, en bas de la
        // liste courte — étaient inatteignables. Le fil et la feuille de transfert le faisaient
        // déjà ; cet écran, non.
        //
        // 2026-11-14 (signalé par Patrice, mesuré sur le S9) — `consumeWindowInsets(padding)` : l'écart du `Scaffold`
        // contient déjà la barre de navigation, et l'encart du clavier la contient aussi (il se mesure depuis le bas
        // de l'écran). Sans cette consommation, la barre était comptée deux fois : la liste s'arrêtait à y = 1056 au
        // lieu de 1200 (bord du clavier), 144 px vides, et le contact cherché restait à moitié caché. Le fil fait de
        // même avec `navigationBarsPadding()` avant `imePadding()`.
        Column(modifier = Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding).imePadding()) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(stringResource(R.string.compose_to_label), style = MaterialTheme.typography.titleSmall)
            }
            if (state.recipients.isNotEmpty()) {
                PucesDestinataires(
                    destinataires = state.recipients,
                    onRetirer = viewModel::removeRecipient,
                    bloques = state.bloques,
                )
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
                // v1.28.13 (MR F-Droid !38458) — sans `READ_CONTACTS`, la liste est vide parce
                // qu'on n'a pas pu la lire, pas parce qu'il n'y a personne : l'écran le dit, et
                // mène à la fiche Android de l'application. La fiche plutôt que la boîte de
                // dialogue système : après deux refus, Android ne l'affiche plus, et la ligne ne
                // ferait alors RIEN. La liste est relue au retour, cf. l'observateur ci-dessus.
                if (state.contactsRefused) {
                    item(key = "contacts-refused") {
                        ListItem(
                            headlineContent = { Text(stringResource(R.string.compose_contacts_refused_title)) },
                            supportingContent = { Text(stringResource(R.string.compose_contacts_refused_desc)) },
                            leadingContent = { Icon(Icons.Outlined.PersonOff, contentDescription = null) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { ouvrirLaFicheDeLApplication(context) },
                        )
                        HorizontalDivider()
                    }
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
                                    if (state.recipients.isEmpty() && !groupe) R.string.compose_use_this_number
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
                                    viewModel.addRecipient(number, contact.displayName)
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
                                viewModel.pickRecipient(number, contact.displayName)
                                viewModel.setQuery("")
                            },
                    )
                }
            }
        }
    }
}

/**
 * Les destinataires choisis, une puce chacun ; toucher une puce la retire.
 *
 * 2026-11-14 (signalé par Patrice, création d'un groupe) — **les puces passent à la ligne.** Elles vivaient dans
 * une `Row`, qui ne passe jamais à la ligne : la ligne pleine, la puce suivante recevait la largeur restante, et
 * son numéro se repliait caractère par caractère, à la verticale ; la quatrième recevait une largeur nulle. Le
 * contact était pourtant AJOUTÉ (aucune limite dans `ComposeViewModel`) : invisible, impossible à retirer, il
 * serait parti dans le groupe. Désormais `FlowRow`, une ligne de texte par puce, une hauteur bornée qui défile
 * au-delà de [HAUTEUR_MAX_PUCES], et la dernière puce ajoutée amenée en vue.
 *
 * @param bloques 2026-11-14 — les destinataires de la liste de blocage (par [PhoneAddress.normalized]) : leur puce
 *   le dit. Un des 56 contacts de Patrice y figurait sans que rien ne le montre.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun PucesDestinataires(
    destinataires: List<PhoneAddress>,
    onRetirer: (PhoneAddress) -> Unit,
    modifier: Modifier = Modifier,
    bloques: Set<String> = emptySet(),
) {
    val defilement = rememberScrollState()
    // Seulement quand la zone GRANDIT : une puce ajoutée, ou une marque « bloqué » qui allonge la dernière (elle
    // arrive après l'ajout). Retirer une puce ne fait pas défiler : l'utilisateur garde sa place (relectures Claude
    // et GPT du 2026-11-14).
    // Partir de zéro : la première composition compte comme une croissance, et la dernière puce est amenée en vue
    // (mesuré sur émulateur API 34 : sans cela, six puces dépassaient la hauteur et la dernière restait cachée).
    val tailles = remember { intArrayOf(0, 0) }
    // Mesuré sur le S9 le 2026-11-14 : clavier ouvert, trois rangées de puces ne laissaient presque plus de place à la
    // liste, et la ligne « Ajouter au groupe » du numéro tapé passait sous le clavier. Pendant la saisie, la zone se
    // réduit à une rangée, qui défile ; clavier fermé, elle reprend sa hauteur.
    val clavierOuvert = WindowInsets.isImeVisible
    LaunchedEffect(destinataires.size, bloques.size, clavierOuvert) {
        val grandit = destinataires.size > tailles[0] || bloques.size > tailles[1]
        tailles[0] = destinataires.size
        tailles[1] = bloques.size
        // La zone rétrécit à l'ouverture du clavier : la dernière puce est ramenée en vue, elle aussi.
        if (!grandit && !clavierOuvert) return@LaunchedEffect
        // Une image d'attente : la hauteur à faire défiler n'est connue qu'après la mise en page de la puce ajoutée.
        withFrameNanos { }
        defilement.animateScrollTo(defilement.maxValue)
    }
    FlowRow(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(max = if (clavierOuvert) HAUTEUR_PUCES_CLAVIER_OUVERT else HAUTEUR_MAX_PUCES)
            .verticalScroll(defilement)
            .padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        destinataires.forEach { addr ->
            val libelle = if (addr.normalized in bloques) {
                stringResource(R.string.compose_recipient_blocked, addr.raw)
            } else {
                addr.raw
            }
            AssistChip(
                onClick = { onRetirer(addr) },
                label = { Text(libelle, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                trailingIcon = { Icon(Icons.Outlined.Close, contentDescription = null, modifier = Modifier.size(16.dp)) },
            )
        }
    }
}

/** Trois rangées de puces environ ; au-delà, la zone défile et la liste des contacts garde sa place. */
private val HAUTEUR_MAX_PUCES = 152.dp

/** Une rangée de puces (cible tactile de 48 dp et sa marge) quand le clavier est ouvert, cf. [PucesDestinataires]. */
private val HAUTEUR_PUCES_CLAVIER_OUVERT = 56.dp

/**
 * 2026-11-14 — un bandeau court, qui REMPLACE le précédent : `showSnackbar` met les messages en file, et dix contacts
 * ajoutés d'affilée défileraient l'un après l'autre longtemps après le dernier geste. `dismiss()` ne vidait que le
 * bandeau affiché, pas la file (relectures GPT et Claude) : on annule la coroutine du précédent, affiché ou en
 * attente, ce qui le retire.
 */
private class BandeauCourant {
    private var job: kotlinx.coroutines.Job? = null

    fun annoncer(hote: SnackbarHostState, texte: String, scope: kotlinx.coroutines.CoroutineScope) {
        job?.cancel()
        job = scope.launch { hote.showSnackbar(texte, duration = androidx.compose.material3.SnackbarDuration.Short) }
    }
}
