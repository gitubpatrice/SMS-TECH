package com.filestech.sms.data.repository

import androidx.room.withTransaction
import com.filestech.sms.data.local.db.AppDatabase
import com.filestech.sms.data.local.db.dao.ConversationDao
import com.filestech.sms.data.local.db.dao.MessageDao
import com.filestech.sms.data.sms.SystemCopyEraser
import com.filestech.sms.domain.repository.VaultPurgeResult
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * v1.28.1 (revue externe GitLab !38458, 3e passe) — la suppression d'une conversation, **et sa
 * copie dans le fournisseur du systeme**.
 *
 * # Pourquoi ce code a quitte `ConversationRepositoryImpl`
 *
 * Il y etait `private`, entoure de onze dependances dont neuf ne le concernent pas. Le tester
 * demandait de construire tout le repository ; personne ne l'a fait, et le meme chemin a laisse
 * passer trois defauts en trois versions. Ici, trois dependances suffisent, dont une interface —
 * un test peut donner un [SystemCopyEraser] qui refuse toujours et verifier ce qu'il advient de
 * la conversation, sans appareil et sans role SMS.
 *
 * Le comportement est celui du repository, a l'identique. Cette classe ne decide rien de plus :
 * elle rend seulement visible ce qui etait cache.
 */
@Singleton
class ConversationEraser @Inject constructor(
    private val database: AppDatabase,
    private val conversationDao: ConversationDao,
    private val messageDao: MessageDao,
    private val systemCopy: SystemCopyEraser,
) {

    private companion object {
        /**
         * Une purge de retention peut porter sur des dizaines de milliers de lignes. On les lit
         * par paquets, jamais d'un bloc : le corps des messages y passerait en entier.
         */
        const val TAILLE_DE_PAGE = 200
    }

    /**
     * Supprime la conversation [id] localement ET la copie systeme de chacun de ses messages.
     * Rend `true` si la copie systeme est demontrablement partie pour TOUS ses messages.
     *
     * La propagation precede le `DELETE` local : sans elle, une reimportation (rafraichissement
     * manuel, retour du role SMS, restauration d'usine) ressusciterait chaque message et la
     * conversation reapparaitrait de nulle part.
     *
     * v1.27.11 (revue externe GitLab !38458, constat 2) — la fonction **rend compte**. Elle
     * gobait l'echec : le `runCatching` etait sans `onFailure`, et l'effacement systeme jetait le
     * nombre de lignes. `ConversationRepository.delete` n'a rien a en faire — l'utilisateur qui
     * efface un fil voit la ligne disparaitre, c'est ce qu'il a demande — mais [purgeVault] en a
     * besoin : la, une copie systeme laissee derriere finit par revenir.
     *
     * v1.28.1 (meme revue, 3e passe, constat reproduit sur emulateur) — **rendre compte ne
     * suffisait pas** : la ligne Room partait quand meme, y compris quand la copie systeme
     * restait. Le premier essai de la porte « PIN oublie » refusait donc correctement de retirer
     * le PIN, mais avait deja efface la conversation ; au second essai `idsInVault()` etait vide,
     * [VaultPurgeResult] valait `0/0/0`, et `isComplete` etait vrai **par vacuite**. Le PIN
     * partait, la copie systeme survivait, et la resynchronisation suivante la ressuscitait en
     * clair. Exactement l'etat que la v1.27.11 pretendait empecher.
     *
     * [preserveOnSystemFailure] est le correctif, et il n'a pas besoin d'un journal de purge
     * persistant : **la ligne du coffre EST le journal**. Conservee, elle est relue par
     * `idsInVault()` au prochain essai comme apres un redemarrage, elle compte dans `remaining`,
     * et la suppression systeme est retentee. Une ligne deja partie du fournisseur se declare
     * absente : l'operation est idempotente.
     *
     * Le drapeau reste a `false` pour la suppression ordinaire et il doit le rester : qui efface
     * un fil a demande qu'il disparaisse, et lui laisser une ligne qu'il croyait supprimee serait
     * un mensonge dans l'autre sens.
     */
    suspend fun erase(id: Long, preserveOnSystemFailure: Boolean = false): Boolean {
        var systemCopyGone = true
        runCatching {
            val balayes = messageDao.findByConversation(id)
            for (m in balayes) if (!systemCopy.erase(m)) systemCopyGone = false
            // v1.28.1 (relecture externe GPT, point 4) — un message ARRIVE pendant le balayage
            // n'a jamais ete presente au fournisseur. Sans ce controle, la conversation partait
            // avec lui, sa copie systeme restait, et la purge se declarait complete : la fuite
            // que corrige [preserveOnSystemFailure], par une autre porte. La reprise s'en charge
            // au prochain essai, ou le message sera dans la liste des le depart.
            val connus = balayes.mapTo(HashSet(balayes.size)) { it.id }
            if (messageDao.findByConversation(id).any { it.id !in connus }) {
                systemCopyGone = false
                Timber.w("delete: message arrived during sweep of conversation %d", id)
            }
        }.onFailure {
            systemCopyGone = false
            Timber.w(it, "delete: system-provider sweep failed for conversation %d", id)
        }
        if (systemCopyGone || !preserveOnSystemFailure) {
            conversationDao.delete(id)
        } else {
            Timber.w("delete: conversation %d kept locally, its system copy survives", id)
        }
        return systemCopyGone
    }

    /**
     * v1.28.1 — la purge de RETENTION, copie systeme comprise.
     *
     * # Le defaut, trouve en audit de coherence le 2026-09-08
     *
     * `delete`, `deleteMessage` et `deleteAllInVault` propageaient au fournisseur du systeme.
     * La retention, **non** : elle faisait un `DELETE` SQL et s'arretait la. Deux consequences,
     * aucune documentee nulle part — ni dans le code, ni dans le KDoc de l'interface :
     *
     *  1. les messages que l'utilisateur croyait effaces restaient dans `content://sms`, lisibles
     *     par toute application ayant `READ_SMS`. La retention est un reglage de CONFIDENTIALITE ;
     *     ne pas propager la vidait de son sens ;
     *  2. le bouton « Resynchroniser », qui remet le curseur d'import a zero, les ramenait tous.
     *
     * # La recette etait ecrite DEUX fois
     *
     * `ConversationRepositoryImpl.purgeHistoryNow` et le cycle mensuel de `TelephonySyncWorker`
     * portaient chacun leur transaction, leur `purgeOlderThan` et leur rafraichissement d'apercus
     * — le commentaire du worker disait meme « comme le jumeau ». Corriger l'un sans l'autre
     * aurait laisse la purge automatique, celle qui tourne sans que personne ne regarde, avec
     * l'ancien comportement. Les deux appellent desormais ceci.
     *
     * # Le contrat, et pourquoi il differe de [purgeVault]
     *
     * La ligne locale part **quoi qu'il arrive**, comme pour une suppression ordinaire. Le coffre
     * fait l'inverse, et la difference n'est pas un oubli : la seule regle qui vaille est
     * *« la ligne locale ne survit a un echec de propagation que si une decision de SECURITE
     * depend de cette propagation »*. Retirer le PIN du coffre est une telle decision — on exige
     * donc la preuve. Une purge de retention n'en leve aucune protection : conserver la ligne y
     * enfermerait l'utilisateur dans un historique qu'il a demande a voir disparaitre, sans rien
     * proteger de plus.
     *
     * Ce qui n'a pas pu partir du fournisseur est journalise, pas tu.
     *
     * @return le nombre de lignes effacees en base, ce que l'interface promet depuis la v1.3.0.
     */
    suspend fun purgeHistory(cutoff: Long): Int {
        var survivants = 0
        var apresId = 0L
        while (true) {
            val page = messageDao.findMirroredOlderThan(cutoff, apresId, TAILLE_DE_PAGE)
            if (page.isEmpty()) break
            for (m in page) if (!systemCopy.erase(m)) survivants++
            apresId = page.last().id
        }
        if (survivants > 0) {
            Timber.w("purgeHistory: %d system copies survived the sweep", survivants)
        }
        // v1.26.1 (audit H9) — les deux ecritures sont ATOMIQUES. Sans transaction, il suffisait
        // que le processus meure entre le DELETE et le rafraichissement pour que
        // `conversations.last_message_preview` conserve LE CORPS EN CLAIR du message purge, et
        // rien ne reparait cet etat : l'apercu perime etait PERMANENT.
        return database.withTransaction {
            val n = messageDao.purgeOlderThan(cutoff)
            if (n > 0) {
                // v1.3.3 (audit G1) — une conversation videe garderait sinon son apercu en clair.
                messageDao.refreshAllConversationPreviewsAfterPurge()
            }
            n
        }
    }

    /**
     * v1.27.10 — voir `ConversationRepository.deleteAllInVault`.
     *
     * Boucle sur [erase] plutot qu'un `DELETE` de masse : chaque conversation doit d'abord
     * disparaitre du fournisseur du systeme, sinon la resynchronisation suivante la ressuscite
     * hors du coffre. Une conversation qui echoue n'interrompt pas les autres — un coffre
     * partiellement purge vaut mieux qu'un coffre intact dont l'utilisateur croit qu'il est vide.
     *
     * v1.27.11 (meme revue, constat 2) — ce qui a echoue est desormais COMPTE, et le coffre est
     * RELU apres la boucle. L'appelant decide alors s'il retire le PIN ; il ne le retire plus sur
     * un simple nombre de succes. Voir [VaultPurgeResult].
     */
    suspend fun purgeVault(): VaultPurgeResult {
        var deleted = 0
        var failed = 0
        for (id in conversationDao.idsInVault()) {
            runCatching { erase(id, preserveOnSystemFailure = true) }
                .onSuccess { systemCopyGone -> if (systemCopyGone) deleted++ else failed++ }
                .onFailure {
                    failed++
                    Timber.w(it, "deleteAllInVault: conversation %d not deleted", id)
                }
        }
        // Relu APRES la boucle, et non deduit d'elle : une conversation deplacee dans le coffre
        // pendant la purge n'apparait dans aucun des deux compteurs ci-dessus.
        return VaultPurgeResult(
            deleted = deleted,
            failed = failed,
            remaining = conversationDao.idsInVault().size,
        )
    }
}
