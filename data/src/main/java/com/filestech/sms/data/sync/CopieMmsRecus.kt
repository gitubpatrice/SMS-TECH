package com.filestech.sms.data.sync

import com.filestech.sms.core.result.runCatchingCancellable
import com.filestech.sms.data.local.db.SQLITE_HOST_PARAM_LIMIT
import com.filestech.sms.data.local.db.dao.AttachmentDao
import com.filestech.sms.data.local.db.dao.ConversationDao
import com.filestech.sms.data.local.db.dao.MessageDao
import com.filestech.sms.data.local.db.entity.MessageEntity
import com.filestech.sms.data.mms.MmsSystemWriteback
import com.filestech.sms.data.mms.PdusEnAttente
import com.filestech.sms.data.sms.SystemCopyEraser
import com.filestech.sms.domain.mms.DestinatairesMms
import com.filestech.sms.domain.model.PhoneAddress
import timber.log.Timber
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 2026-11-19 (issue #98, plusieurs profils) — **recopie dans `content://mms` les MMS reçus ici**, relie chacun à sa
 * copie (`telephony_uri`), et retire du stockage partagé ce qui ne doit plus y être.
 *
 * # Pourquoi
 *
 * Un SMS reçu est écrit dans le stockage d'Android avant même notre base (`SmsDeliverReceiver`). Un MMS reçu ne
 * l'était jamais : il ne vivait que dans la base chiffrée de SMS Tech. Or Android ne remet les messages entrants
 * qu'au profil principal, et les autres profils du téléphone ne voient que ce stockage partagé : sur GrapheneOS,
 * la réponse à un MMS arrivait dans le profil principal et jamais dans le secondaire (issue #98).
 *
 * # Un passage, sous le verrou de la synchronisation
 *
 * Le message est d'abord écrit chez nous (sa clé de transaction le protège d'un doublon), puis recopié ici, SOUS le
 * verrou de la synchronisation (`TelephonySyncManager.copierMmsRecus`, et en tête de chaque passe) : aucun import de
 * CE profil ne lit une copie avant son lien. Les autres profils ne partagent pas ce verrou ; ils ne la lisent pas
 * pour autant avant qu'elle soit complète, car elle naît dans la boîte d'envoi ([MmsSystemWriteback.insertInbox]).
 *
 * 1. **[verifierLeJournal]** — chaque copie est notée dès que sa ligne existe ([JournalCopiesMms]). Au passage
 *    suivant, une copie à laquelle plus aucun message d'ici n'est relié est effacée : message supprimé PENDANT sa
 *    copie (l'effaceur avait lu le message avant le lien), ou passage interrompu. Sans quoi l'import la ferait
 *    revenir, ici et dans les autres profils (relectures Claude et GPT du 2026-11-19). Ce seul contrôle couvre tous
 *    les chemins de suppression — message, conversation, purge, panique — sans toucher à l'effaceur.
 * 2. **[balayerLeCoffre]** — une conversation mise au Coffre APRÈS la copie de ses MMS : leurs copies partent du
 *    stockage partagé (relectures des trois modèles). Les profils qui les ont déjà importées les gardent : il n'y a
 *    pas de réconciliation des MMS d'un profil à l'autre, et c'est écrit dans `SECURITY.md`.
 * 3. **La copie** des MMS reçus complets.
 *
 * # Ce qui n'est PAS recopié
 *
 * - **Le Coffre.** Ses photos apparaîtraient en clair dans les autres profils. Les SMS du Coffre, eux, ont une copie
 *   système (limite N2 de `SECURITY.md`) ; on ne l'étend pas aux MMS.
 * - **Un message incomplet** : PDU encore gardé (la reprise le complétera) ou pièce absente du disque. Une copie
 *   amputée serait reliée, donc jamais refaite.
 * - **L'historique** d'avant cette version ([copier] `depuis`), ni rien de plus vieux que [FENETRE_MS] : recopier d'un
 *   coup des centaines de MMS privés dans le stockage partagé n'est pas un effet de bord acceptable d'une mise à jour.
 *
 * Une conversation SORTIE du Coffre redevient ordinaire : ses MMS reçus des [FENETRE_MS] derniers jours sont recopiés
 * au passage suivant, comme ceux de n'importe quelle conversation. C'est voulu.
 */
@Singleton
class CopieMmsRecus @Inject constructor(
    private val messageDao: MessageDao,
    private val attachmentDao: AttachmentDao,
    private val conversationDao: ConversationDao,
    private val ecrivain: MmsSystemWriteback,
    private val pdus: PdusEnAttente,
    private val journal: JournalCopiesMms,
    private val effaceur: SystemCopyEraser,
) {

    /**
     * @param depuis début de la copie (`AdvancedSettings.copieMmsRecusDepuis`), en ms.
     * @return le nombre de messages reliés à une copie par ce passage.
     */
    suspend fun copier(depuis: Long, maintenant: Long = horloge()): Int {
        verifierLeJournal(maintenant)
        ecrivain.purgerCopiesEnPlan(maintenant - ABANDON_EN_PLAN_MS)
        balayerLeCoffre()
        val borne = maxOf(depuis, maintenant - FENETRE_MS)
        var relies = 0
        for (message in messageDao.mmsRecusSansCopieSysteme(borne, LOT)) {
            if (copierUn(message)) relies++
        }
        if (relies > 0) Timber.i("Copie MMS recus : %d message(s) relie(s) a leur copie systeme", relies)
        return relies
    }

    /** Ce qu'il faut pour copier un message : ses fichiers (et leur type), les adresses de sa conversation. */
    private class Pret(val fichiers: List<Pair<File, String>>, val adressesDeLaConversation: String)

    /**
     * [Pret], ou `null` si le message n'est pas (encore) à copier : PDU encore gardé (la reprise le complétera),
     * pièce absente du disque, conversation disparue ou au Coffre. La conversation est relue ICI : elle a pu entrer au
     * Coffre depuis la requête ; une entrée APRÈS cette lecture est rattrapée par [balayerLeCoffre].
     */
    private suspend fun pretACopier(message: MessageEntity): Pret? {
        message.mmsTransactionKey?.takeUnless { pdus.existePourCles(listOf(it)) } ?: return null
        // Le type sans ses paramètres (`image/jpeg; name=…`) : la garde de l'écrivain refuse les paramètres, et le
        // message serait retenté à chaque passage sans jamais être copié (relecture Claude, 2e vague).
        val fichiers = attachmentDao.findForMessage(message.id)
            .map { File(it.localUri) to it.mimeType.substringBefore(';').trim() }
        if (fichiers.any { (fichier, _) -> !fichier.isFile }) {
            Timber.i("Copie MMS recu %d : piece absente du disque, pas de copie", message.id)
            return null
        }
        val conversation = conversationDao.findById(message.conversationId)?.takeUnless { it.inVault } ?: return null
        return Pret(fichiers, conversation.addressesCsv)
    }

    /** `true` si [message] est désormais relié à une copie. */
    private suspend fun copierUn(message: MessageEntity): Boolean {
        val pret = pretACopier(message) ?: return false
        val id = ecrivain.insertInbox(
            expediteur = message.address,
            destinataires = DestinatairesMms.decoder(message.mmsRecipients),
            membresDuFil = filDeLaCopie(message.address, pret.adressesDeLaConversation),
            dateMs = message.date,
            lu = message.read,
            subId = message.subId,
            textBody = message.body,
            fichiers = pret.fichiers,
            // L'heure de l'ÉCRITURE, pas celle du début du passage : un passage long ferait sinon vérifier une copie
            // avant que le délai de [verifierLeJournal] ne soit réellement écoulé (relecture Claude, 2e vague).
            creee = { journal.noter(IdDate(it, horloge())) },
        ) ?: return false
        val uri = "$MMS_URI_PREFIX$id"
        // Une annulation remonte (elle n'est pas « message supprimé ») ; la copie non reliée reste au journal.
        val lie = runCatchingCancellable { messageDao.lierCopieSysteme(message.id, uri) }
            .onFailure { Timber.w(it, "Copie MMS recu %d : lien refuse", message.id) }
            .getOrDefault(0)
        if (lie == 0) {
            // Message supprimé pendant la copie : la laisser le ferait revenir, ici par l'import et dans les autres
            // profils. Si cet effacement échoue, le journal la retrouvera.
            ecrivain.effacerCopieRecue(id)
            return false
        }
        // Conversation entrée au Coffre pendant la copie (relecture GPT, 2e vague) : la copie part tout de suite, sans
        // attendre le prochain [balayerLeCoffre].
        if (conversationDao.findById(message.conversationId)?.inVault == true) {
            retirerCopie(message.copy(telephonyUri = uri))
        }
        return true
    }

    /**
     * 2026-11-19 (issue #98, contrôle de cohérence) — **effacement de panique** : toutes les copies encore notées au
     * journal sont effacées du fournisseur, reliées ou non. L'effacement de panique vient d'effacer les messages (les
     * copies reliées sont déjà parties par l'effaceur) et va remettre les réglages à zéro, journal compris : une copie
     * orpheline de la dernière minute ne serait plus jamais retrouvée. Rend le nombre de copies que le fournisseur n'a
     * pas pu traiter.
     */
    suspend fun effacerLeJournal(): Int {
        val traitees = HashSet<Long>()
        val entrees = journal.lire()
        for (entree in entrees) {
            if (ecrivain.effacerCopieRecue(entree.id) != null) traitees += entree.id
        }
        journal.retirer(traitees)
        return entrees.size - traitees.size
    }

    /**
     * Les copies notées il y a plus de [DELAI_VERIFICATION_MS] : reliée à un message d'ici, elle sort du journal ;
     * sinon elle est effacée du fournisseur (copie de MMS reçu seulement, cf. [MmsSystemWriteback.effacerCopieRecue])
     * et sort du journal. Une suppression que le fournisseur n'a pas traitée reste notée, pour le passage suivant.
     *
     * Le délai laisse finir une suppression commencée juste avant le lien : vérifiée trop tôt, la copie serait vue
     * reliée, sortie du journal, puis laissée derrière lui par l'effaceur.
     */
    private suspend fun verifierLeJournal(maintenant: Long) {
        val murs = journal.lire().filter { maintenant - it.ms >= DELAI_VERIFICATION_MS }
        if (murs.isEmpty()) return
        val reliees = murs.map { "$MMS_URI_PREFIX${it.id}" }.chunked(SQLITE_HOST_PARAM_LIMIT)
            .flatMap { messageDao.telephonyUrisParmi(it) }
            .toSet()
        val traitees = HashSet<Long>()
        for (entree in murs) {
            if ("$MMS_URI_PREFIX${entree.id}" in reliees) {
                traitees += entree.id
            } else if (ecrivain.effacerCopieRecue(entree.id) != null) {
                Timber.i("Copie MMS %d sans message ici : effacee du fournisseur", entree.id)
                traitees += entree.id
            }
        }
        journal.retirer(traitees)
    }

    /**
     * Les MMS reçus recopiés puis mis au Coffre : leur copie est effacée, par l'effaceur ordinaire (qui vérifie
     * l'identité de la ligne), puis le lien retiré. Une copie qui résiste garde son lien, et le passage suivant
     * réessaie.
     */
    private suspend fun balayerLeCoffre() {
        for (message in messageDao.mmsRecusCopiesAuCoffre(LOT)) retirerCopie(message)
    }

    /** Efface la copie système d'un message relié, par l'effaceur ordinaire, puis retire le lien. */
    private suspend fun retirerCopie(message: MessageEntity) {
        val uri = message.telephonyUri ?: return
        if (effaceur.erase(message)) {
            messageDao.delierCopieSysteme(message.id, uri)
            Timber.i("Copie MMS du message %d retiree du fournisseur : conversation au Coffre", message.id)
        }
    }

    /**
     * Le fil Android de la copie : celui du GROUPE quand le message est rangé dans un groupe ici (ses membres, nous
     * exclus) ; sinon celui de l'expéditeur, sous l'adresse que le réseau a donnée — comme un SMS reçu de lui
     * (`insertInboxSms`), et non sous la forme rangée dans la conversation (`06…` contre `+33…`). C'est la règle
     * d'AOSP (`PduPersister`) : MMS de groupe coupé, le fil est celui de l'expéditeur seul.
     */
    internal fun filDeLaCopie(expediteur: String, adressesDeLaConversation: String): List<String> {
        val membres = PhoneAddress.list(adressesDeLaConversation).map { it.raw }
        return if (membres.size >= 2) membres else listOf(expediteur)
    }

    /** L'heure ; remplaçable par les tests. */
    internal var horloge: () -> Long = System::currentTimeMillis

    internal companion object {
        /** Une copie restée en plan plus longtemps est abandonnée, cf. [MmsSystemWriteback.purgerCopiesEnPlan]. */
        const val ABANDON_EN_PLAN_MS: Long = 60L * 60L * 1_000L

        /** Les messages plus vieux ne sont plus recopiés : rôle SMS perdu une semaine, par exemple. */
        const val FENETRE_MS: Long = 7L * 24L * 60L * 60L * 1_000L

        /** Par passage et par étape. Les plus récents d'abord ; le passage suivant reprend la suite. */
        const val LOT: Int = 200

        /** Cf. [verifierLeJournal]. */
        const val DELAI_VERIFICATION_MS: Long = 60L * 1_000L

        const val MMS_URI_PREFIX = "content://mms/"
    }
}
