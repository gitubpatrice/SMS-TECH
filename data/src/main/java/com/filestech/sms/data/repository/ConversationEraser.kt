package com.filestech.sms.data.repository

import androidx.room.withTransaction
import com.filestech.sms.data.local.db.AppDatabase
import com.filestech.sms.data.local.db.ScheduledAttachmentCodec
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
 * passer trois defauts en trois versions. Ici les dependances sont peu nombreuses et toutes
 * concernees, dont deux interfaces — un test peut donner un [SystemCopyEraser] qui refuse
 * toujours et verifier ce qu'il advient de la conversation, sans appareil et sans role SMS.
 *
 * v1.28.3 — elles sont passees de trois a sept, et le KDoc disait encore « trois ». La hausse
 * est assumee : la suppression d'une conversation a un cycle de vie plus large qu'on ne le
 * croyait, et c'est precisement ce que la relecture externe a montre (F03, F04). Ce qui restait
 * dehors ne disparaissait pas — les envois programmes et les fichiers de pieces jointes lui
 * survivaient.
 */
@Singleton
class ConversationEraser @Inject constructor(
    private val database: AppDatabase,
    private val conversationDao: ConversationDao,
    private val messageDao: MessageDao,
    private val systemCopy: SystemCopyEraser,
    // v1.28.3 (F03) — les envois programmés d'une conversation doivent partir avec elle. Sans
    // cela, une purge du coffre laissait un envoi futur porteur du contenu qu'elle prétendait
    // détruire. `conversation_id` n'étant pas une clé étrangère, aucune cascade ne s'en chargeait.
    private val scheduledDao: com.filestech.sms.data.local.db.dao.ScheduledMessageDao,
    private val scheduler: com.filestech.sms.domain.scheduler.ScheduledMessageScheduler,
    // v1.28.3 (F04) — les lignes `attachments` partaient en cascade, jamais leurs FICHIERS.
    private val attachmentDao: com.filestech.sms.data.local.db.dao.AttachmentDao,
    @dagger.hilt.android.qualifiers.ApplicationContext
    private val context: android.content.Context,
    // v1.28.4 (F13) — la purge lève une barrière : on n'entre pas au coffre pendant qu'on le vide.
    private val barriere: com.filestech.sms.security.VaultPurgeBarrier,
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
    suspend fun erase(id: Long, preserveOnSystemFailure: Boolean = false): Issue {
        var systemCopyGone = true
        var connus: Set<Long> = emptySet()
        runCatching {
            val balayes = messageDao.findByConversation(id)
            for (m in balayes) if (!systemCopy.erase(m)) systemCopyGone = false
            connus = balayes.mapTo(HashSet(balayes.size)) { it.id }
        }.onFailure {
            systemCopyGone = false
            Timber.w(it, "delete: system-provider sweep failed for conversation %d", id)
        }
        if (!systemCopyGone && preserveOnSystemFailure) {
            Timber.w("delete: conversation %d kept locally, its system copy survives", id)
            return Issue(systemCopyGone = false, localeComplete = true)
        }

        // v1.28.4 (relecture externe, R01/R02) — les dépendants RENDENT COMPTE. Le travail
        // WorkManager et les fichiers sont traités HORS transaction : idempotents, retentables,
        // et l'on ne tient pas un verrou SQLite sur de l'entrée-sortie. Un seul échec et, pour
        // le coffre, le parent est CONSERVÉ : c'est lui qui permettra de retrouver l'orphelin au
        // prochain essai — supprimé, plus rien n'y mènerait, et la purge se dirait complète.
        val echecs = annulerLesTravauxProgrammes(id) + supprimerFichiersPossedes(id)
        if (echecs > 0 && preserveOnSystemFailure) {
            Timber.w("delete: conversation %d kept locally, %d dependant(s) not cleaned", id, echecs)
            return Issue(systemCopyGone, localeComplete = false)
        }

        // v1.28.4 (relecture externe, R03) — la FIN est atomique : relecture et suppression dans
        // UNE transaction d'écriture. SQLite n'a qu'un écrivain à la fois : un import ou une
        // réception qui commet pendant la purge attend notre verrou, et ne peut plus se glisser
        // entre la relecture et la suppression pour être emporté par la cascade avec sa copie
        // système intacte. Un message arrivé AVANT est vu par la relecture et garde le parent.
        val arriveTard = database.withTransaction {
            if (preserveOnSystemFailure && messageDao.findByConversation(id).any { it.id !in connus }) {
                true
            } else {
                for (envoi in scheduledDao.findForConversation(id)) scheduledDao.delete(envoi.id)
                conversationDao.delete(id)
                false
            }
        }
        if (arriveTard) {
            Timber.w("delete: message arrived during sweep of conversation %d", id)
            return Issue(systemCopyGone = false, localeComplete = true)
        }
        return Issue(systemCopyGone, localeComplete = true)
    }

    /**
     * v1.28.4 — ce qu'un effacement laisse derrière lui, dit séparément : la copie système
     * ([systemCopyGone]) et les dépendants locaux — envois programmés, fichiers
     * ([localeComplete]). Un parent conservé pour l'un ou l'autre motif compte dans `remaining`.
     */
    data class Issue(val systemCopyGone: Boolean, val localeComplete: Boolean)

    /**
     * v1.28.3 (F03) — annule et efface les envois programmés d'une conversation qui disparaît.
     *
     * `scheduled_messages.conversation_id` n'est pas une clé étrangère — c'est délibéré, un envoi
     * programmé pouvant survivre à une fusion de doublons — mais rien n'exigeait en retour que la
     * conversation parente survive. Une purge du coffre laissait donc partir, plus tard, un
     * message porteur du contenu qu'elle venait d'annoncer détruit, PIN retiré.
     *
     * Appliqué aussi à la suppression ordinaire, et non au seul coffre : un envoi programmé vers
     * un fil que l'utilisateur a effacé n'a pas plus de raison de partir. Poser le garde sur le
     * seul chemin qui l'a motivé est précisément ce qui a produit la moitié des défauts de cette
     * relecture.
     *
     * L'annulation `WorkManager` passe avant l'effacement de la ligne, comme dans
     * `CancelScheduledMessageUseCase` : l'ordre inverse laisserait un worker se réveiller sur une
     * ligne absente. Chaque envoi est isolé — un échec ne doit pas empêcher les suivants ni la
     * suppression de la conversation.
     */
    private suspend fun annulerLesTravauxProgrammes(conversationId: Long): Int {
        // v1.28.4 (R01) — une énumération qui échoue n'est PAS une liste vide : c'est un échec.
        val programmes = runCatching { scheduledDao.findForConversation(conversationId) }
            .onFailure { Timber.w(it, "delete: lecture des envois programmes de %d echouee", conversationId) }
            .getOrNull() ?: return 1
        var echecs = 0
        for (envoi in programmes) {
            runCatching {
                scheduler.cancel(envoi.id)
                for (piece in ScheduledAttachmentCodec.decode(envoi.attachmentsJson)) {
                    // Un `delete()` qui rend `false` n'est pas une exception — mais c'est un échec.
                    if (piece.file.exists() && !piece.file.delete()) echecs++
                }
            }.onFailure {
                echecs++
                Timber.w(it, "delete: envoi programme %d non annule", envoi.id)
            }
        }
        // La ligne elle-même part dans la transaction finale d'[erase], avec le parent.
        if (programmes.isNotEmpty()) {
            Timber.i(
                "delete: %d envoi(s) programme(s) annule(s) avec la conversation %d (%d echec(s))",
                programmes.size,
                conversationId,
                echecs,
            )
        }
        return echecs
    }

    /**
     * v1.28.3 (F04) — efface les FICHIERS des pièces jointes, que la cascade Room laissait
     * derrière elle.
     *
     * `AttachmentEntity` est en `ForeignKey.CASCADE` : ses lignes partaient bien, mais
     * `local_uri` désigne un fichier de `filesDir`, et plus rien ne le référençait ensuite. Le
     * contenu d'une conversation purgée du coffre — images, audio — restait donc en clair sur
     * l'appareil jusqu'à une désinstallation ou un effacement d'urgence. `AutoLockObserver`
     * documente d'ailleurs que ce dossier n'est volontairement pas purgé par le verrouillage,
     * en renvoyant au nettoyage d'urgence : personne n'avait vu que la purge du coffre, elle,
     * aurait dû s'en charger.
     *
     * Deux garde-fous :
     *
     *  - les `content://` sont ignorés. Ce sont les parties du fournisseur système d'un MMS
     *    importé, pas nos fichiers ; leur sort relève de [SystemCopyEraser].
     *  - un chemin hors du bac à sable de l'application est refusé et journalisé. Une sauvegarde
     *    restaurée d'un AUTRE appareil peut porter des `local_uri` étrangers — c'est le défaut
     *    exact que la v1.27.11 a fermé sur `telephony_uri`, et un chemin de suppression ne doit
     *    pas le rouvrir sous une autre forme.
     */
    private suspend fun supprimerFichiersPossedes(conversationId: Long): Int {
        // v1.28.4 (R02) — une énumération qui échoue n'est PAS une liste vide : c'est un échec.
        val pieces = runCatching { attachmentDao.findForConversation(conversationId) }
            .onFailure { Timber.w(it, "delete: lecture des pieces jointes de %d echouee", conversationId) }
            .getOrNull() ?: return 1
        val racines = listOfNotNull(context.filesDir, context.cacheDir)
            .map { it.canonicalPath + java.io.File.separator }
        var echecs = 0
        for (piece in pieces) {
            if (piece.localUri.startsWith("content://")) continue
            runCatching {
                val fichier = java.io.File(piece.localUri)
                val chemin = fichier.canonicalPath
                if (racines.none { chemin.startsWith(it) }) {
                    // Refusé à dessein, pas un échec : ce n'est pas notre fichier.
                    Timber.w("delete: piece jointe hors du bac a sable ignoree (conversation %d)", conversationId)
                    return@runCatching
                }
                // Un `delete()` qui rend `false` n'est pas une exception — mais c'est un échec, et
                // il compte : le parent reste, la référence au fichier avec lui.
                if (fichier.exists() && !fichier.delete()) {
                    echecs++
                    Timber.w("delete: piece jointe non effacee (conversation %d)", conversationId)
                }
            }.onFailure {
                echecs++
                Timber.w(it, "delete: effacement de piece jointe echoue")
            }
        }
        return echecs
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
     *
     * v1.28.2 — [force] est la SORTIE ASSUMEE, et elle n'existe que parce que le refus prudent
     * pouvait devenir une impasse definitive. Une liaison durablement fausse — un
     * `telephony_uri` restaure d'un autre telephone — fait echouer le garde d'identite a chaque
     * essai, a l'identique : l'utilisateur qui a oublie son PIN n'avait alors plus d'issue.
     *
     * Sous [force], la ligne locale part meme si sa copie systeme resiste. Ce n'est PAS un
     * assouplissement du garde : la copie systeme n'est toujours pas supprimee sans preuve
     * d'identite — on refuse toujours de toucher au message d'autrui. Ce qui change est
     * l'arbitrage LOCAL, et il appartient a l'utilisateur, qui l'a explicitement demande apres
     * qu'on lui a dit ce qui subsisterait. Le resultat continue de rendre `failed` : l'appelant
     * doit le lui montrer, pas le taire.
     */
    suspend fun purgeVault(force: Boolean = false): VaultPurgeResult = barriere.pendant {
        var deleted = 0
        var systemResidue = 0
        var localFailures = 0
        for (id in conversationDao.idsInVault()) {
            // v1.28.3 (F09) — les deux natures d'echec sont desormais comptees separement.
            // `erase` attrape lui-meme tout ce qui touche au fournisseur du systeme : ce qui
            // remonte jusqu'ici ne peut donc venir que de `conversationDao.delete`, c'est-a-dire
            // d'un echec LOCAL. La distinction n'est pas cosmetique — elle decide si la sortie
            // forcee a le droit de retirer le PIN. Cf. [VaultPurgeResult].
            runCatching { erase(id, preserveOnSystemFailure = !force) }
                .onSuccess { issue ->
                    when {
                        // v1.28.4 (R01/R02) — un dépendant resté derrière est un échec LOCAL, le
                        // parent est conservé et compte dans `remaining` : jamais « complet ».
                        !issue.localeComplete -> localFailures++
                        issue.systemCopyGone -> deleted++
                        else -> systemResidue++
                    }
                }
                .onFailure {
                    localFailures++
                    Timber.w(it, "deleteAllInVault: conversation %d not deleted", id)
                }
        }
        // Relu APRES la boucle, et non deduit d'elle : une conversation deplacee dans le coffre
        // pendant la purge n'apparait dans aucun des compteurs ci-dessus.
        VaultPurgeResult(
            deleted = deleted,
            systemResidue = systemResidue,
            localFailures = localFailures,
            remaining = conversationDao.idsInVault().size,
        )
    }
}
