package com.filestech.sms.system.share

import android.net.Uri
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import javax.inject.Inject
import javax.inject.Singleton

/**
 * v1.3.3 bug #4 — étape d'attente entre l'arrivée d'un contenu à écrire et la conversation où il doit l'être.
 * [MainActivity] y pose un partage venu d'une autre application (`ACTION_SEND`) ou le texte d'un lien `sms:` ; le
 * fil d'origine d'un transfert y pose le message transféré ; la conversation visée le reprend
 * ([consumePour]) et le met dans sa saisie.
 *
 * # Chaque dépôt a une CIBLE (2026-11-20)
 *
 * Le relais ne savait pas d'où venait ce qu'il portait. [com.filestech.sms.ui.AppRoot] traitait donc tout dépôt comme
 * un partage externe, et sa garde SEC-03 (v1.4.1 : un partage arrivé pendant qu'on est dans un fil est effacé, pour
 * qu'il ne tombe pas dans la mauvaise conversation) effaçait AUSSI les transferts, lancés justement depuis un fil :
 * « Transférer » ouvrait la conversation choisie, le champ de saisie restait vide (courriel du 2026-11-20, « same
 * problem on 1.30.1 »). Même sort pour le texte d'un lien `sms:` arrivé pendant qu'un fil était ouvert.
 *
 * Désormais ([Cible]) :
 * - un partage externe est [Cible.AChoisir] : AppRoot ouvre « Nouveau message », et lui seul passe par SEC-03 ;
 * - un transfert vers une conversation existante, et le texte d'un lien `sms:` dont la conversation est résolue,
 *   sont [Cible.Conversation] ;
 * - un transfert vers un nouveau contact (ou le texte d'un lien `sms:` qui passe par « Nouveau message ») est
 *   [Cible.EnAttente] de la conversation que « Nouveau message » va créer.
 *
 * Seule la conversation CIBLÉE reprend un dépôt ([consumePour]), à son ouverture ; un dépôt « à choisir » ou « en
 * attente » est reciblé ([cibler]) quand « Nouveau message » crée ou retrouve sa conversation, et effacé si cet écran
 * se ferme sans ([abandonnerNonCible]). Un partage ou un transfert abandonné ne peut donc plus finir dans une autre
 * conversation — ce que SEC-03 ne garantissait qu'à l'arrivée. Le verrouillage efface les transferts en route
 * ([abandonnerTransferts]), la session leurre efface tout.
 *
 * Singleton du processus, en mémoire : un processus tué annule le dépôt (éphémère, l'utilisateur peut recommencer).
 */
@Singleton
class IncomingShareHolder @Inject constructor() {

    /** 2026-11-20 — la conversation à qui revient un dépôt, cf. le KDoc de la classe. */
    sealed interface Cible {
        /** Partage venu d'une autre application : l'utilisateur choisit la conversation dans « Nouveau message ». */
        data object AChoisir : Cible

        /** Conversation pas encore connue : transfert vers un nouveau contact, lien `sms:` en cours de résolution. */
        data object EnAttente : Cible

        data class Conversation(val id: Long) : Cible
    }

    /**
     * Un dépôt : une ou plusieurs URI, un type MIME indicatif, un texte optionnel, et sa [cible].
     *
     * v1.3.3 G2 audit fix — [postedAt] (epoch ms du dépôt) borne sa vie ([isExpired]) : partage oublié en arrière-plan,
     * tap sur une notification qui l'aurait collé à la mauvaise conversation.
     */
    data class Pending(
        val uris: List<Uri>,
        val mimeType: String?,
        val text: String?,
        val postedAt: Long = System.currentTimeMillis(),
        val cible: Cible = Cible.AChoisir,
    ) {
        val isEmpty: Boolean get() = uris.isEmpty() && text.isNullOrBlank()

        /**
         * Un dépôt vit [PENDING_TTL_MS] — un partage à choisir comme un dépôt destiné à une conversation, qui s'ouvre
         * aussitôt. Seul un dépôt [Cible.EnAttente] vit [TTL_EN_ATTENTE_MS] : taper ou chercher un nouveau contact
         * dépasse souvent la minute. Le reciblage ([cibler]) repart de zéro.
         */
        fun isExpired(now: Long = System.currentTimeMillis()): Boolean =
            now - postedAt > if (cible == Cible.EnAttente) TTL_EN_ATTENTE_MS else PENDING_TTL_MS
    }

    private val _pending = MutableStateFlow<Pending?>(null)
    val pending: StateFlow<Pending?> = _pending.asStateFlow()

    /** Pose un dépôt. Écrase silencieusement le précédent (rare). */
    fun set(pending: Pending) {
        if (pending.isEmpty) return
        _pending.value = pending
    }

    /**
     * 2026-11-20 — reprend le dépôt s'il revient à [conversationId], et seulement alors : `null` sinon, sans y toucher
     * (il attend sa conversation, ou son reciblage). Un dépôt expiré est effacé et n'est pas rendu.
     */
    fun consumePour(conversationId: Long, now: Long = System.currentTimeMillis()): Pending? {
        // Compare-and-set, et non `update` : le résultat ne doit venir que de la transition qui a RÉUSSI (relectures
        // Claude et GPT — une variable écrite dans le lambda rejouable de `update` pouvait rendre un dépôt déjà repris).
        while (true) {
            val courant = _pending.value ?: return null
            if (courant.isExpired(now)) {
                _pending.compareAndSet(courant, null)
                return null
            }
            if (courant.cible != Cible.Conversation(conversationId)) return null
            if (_pending.compareAndSet(courant, null)) return courant
        }
    }

    /**
     * 2026-11-20 — la conversation d'un dépôt « à choisir » ou « en attente » est connue : « Nouveau message » l'a
     * créée ou retrouvée. Un dépôt déjà destiné à une conversation n'est pas touché ; un dépôt expiré est effacé, et
     * non ranimé (relecture Claude : un partage expiré pendant le verrouillage serait reparti vers la conversation du
     * message suivant). Le délai repart de zéro : la conversation s'ouvre aussitôt.
     */
    fun cibler(conversationId: Long, now: Long = System.currentTimeMillis()) {
        _pending.update { courant ->
            when {
                courant == null || courant.isExpired(now) -> null
                courant.cible is Cible.Conversation -> courant
                else -> courant.copy(cible = Cible.Conversation(conversationId), postedAt = now)
            }
        }
    }

    /**
     * 2026-11-20 — « Nouveau message », ouvert à [ouvertA], se ferme : un dépôt qui l'attendait (partage à choisir,
     * transfert vers un nouveau contact) et qu'il n'a pas reciblé est abandonné, et effacé. Sans quoi le prochain
     * « Nouveau message » le recevrait, pour un tout autre destinataire (relectures Claude et GPT). Un dépôt posé APRÈS
     * son ouverture appartient à un écran plus récent : il n'est pas touché.
     */
    fun abandonnerNonCible(ouvertA: Long) {
        _pending.update { courant ->
            if (courant != null && courant.cible !is Cible.Conversation && courant.postedAt <= ouvertA) null else courant
        }
    }

    /**
     * 2026-11-20 — l'application se verrouille : un transfert ou un texte en route vers une conversation est effacé
     * (relecture GPT). Un partage venu d'une autre application attend le déverrouillage, comme avant (SEC-03, délai).
     */
    fun abandonnerTransferts() {
        _pending.update { courant -> if (courant != null && courant.cible != Cible.AChoisir) null else courant }
    }

    /** 2026-11-20 — dépose le texte d'un lien `sms:` pour [cible] ; rien sans texte. Cf. `PendingNavHolder.Pending.smsBody`. */
    fun deposerTexte(texte: String?, cible: Cible) {
        if (texte.isNullOrBlank()) return
        set(Pending(uris = emptyList(), mimeType = null, text = texte, cible = cible))
    }

    /** 2026-11-20 — l'état du verrou ou de la session leurre change, cf. [abandonnerTransferts]. */
    fun surVerrouOuLeurre(verrouille: Boolean, leurre: Boolean) {
        if (leurre) clear() else if (verrouille) abandonnerTransferts()
    }

    /** Efface sans lire (l'utilisateur annule, ou un intent qui n'est pas un partage arrive). */
    fun clear() {
        _pending.value = null
    }

    companion object {
        /**
         * Durée de vie d'un partage « à choisir ». 60 s : une hésitation normale (chooser → écran verrouillé →
         * déverrouillage → choix) sans qu'un partage oublié la veille ne rouvre « Nouveau message » aujourd'hui.
         */
        const val PENDING_TTL_MS: Long = 60_000L

        /** 2026-11-20 — durée de vie d'un dépôt [Cible.EnAttente] de sa conversation, cf. [Pending.isExpired]. */
        const val TTL_EN_ATTENTE_MS: Long = 10L * 60_000L

        /**
         * 2026-11-20 — la saisie après l'arrivée de [texte] : lui seul si elle est vide, sinon AJOUTÉ à la suite, sur
         * une nouvelle ligne. Il n'était écrit que dans une saisie vide : un transfert vers une conversation où un
         * message était commencé disparaissait sans rien dire.
         */
        fun saisieAvec(saisie: String, texte: String): String =
            if (saisie.isBlank()) texte else saisie.trimEnd() + "\n" + texte
    }
}
