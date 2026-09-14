package com.filestech.sms.system.receiver

import com.filestech.sms.core.result.runCatchingCancellable
import com.filestech.sms.data.local.db.dao.MessageDao
import com.filestech.sms.data.mms.ContenuMmsRecu
import com.filestech.sms.data.mms.IntegrationMmsRecu
import com.filestech.sms.data.mms.IntegrationMmsRecu.Resultat
import com.filestech.sms.data.mms.LecteurRetrieveConf
import com.filestech.sms.data.sms.PhoneIdentity
import com.filestech.sms.domain.mms.GroupMmsMembers
import com.filestech.sms.domain.model.PhoneAddress
import com.filestech.sms.domain.settings.AppSettingsSource
import com.filestech.sms.pdu.RetrieveConf
import com.filestech.sms.system.notifications.IncomingMessageNotifier
import com.filestech.sms.system.notifications.MmsFailureNotifier
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * v1.28.9 (F17, septième note d'Andrew sur la MR !38458) — **le traitement d'un MMS reçu, écrit une fois**,
 * pour le receveur qui vient de le télécharger comme pour la reprise qui rouvre un PDU gardé.
 *
 * Il vivait en ligne dans `MmsDownloadedReceiver`. La reprise en a besoin à l'identique — mêmes règles
 * d'écartement, même reconstitution de groupe, même écriture, mêmes notifications — et deux copies auraient
 * divergé : c'est le motif de défaut le plus fréquent de ce dépôt.
 *
 * # L'issue
 *
 * [Issue.garderLePdu] : le PDU porte encore ce que la base n'a pas — un média non écrit —, il reste la seule
 * copie et la reprise le rouvrira. [Issue.consigner] : un message de cette transaction a été écrit, par ce
 * passage ou par un précédent ; un autre exemplaire du même MMS n'a plus rien à écrire. C'est vrai aussi d'un
 * message supprimé depuis ([Resultat.Disparu]) : son exemplaire suivant ne doit pas le ressusciter.
 *
 * Une exception remonte : l'appelant garde le PDU.
 */
@Singleton
class TraitementMmsRecu @Inject constructor(
    private val blockPolicy: IncomingBlockPolicy,
    private val settings: AppSettingsSource,
    private val phoneIdentity: PhoneIdentity,
    private val integration: IntegrationMmsRecu,
    private val messageDao: MessageDao,
    private val notifier: IncomingMessageNotifier,
    private val failureNotifier: MmsFailureNotifier,
) {

    data class Issue(val garderLePdu: Boolean, val consigner: Boolean)

    /**
     * @param cle la clé de transaction lue dans le nom du PDU, ou `null` : rien ne reconnaîtra alors un
     *   message déjà écrit.
     * @param indiceExpediteur l'expéditeur annoncé par la notification WAP-Push ; `null` à la reprise.
     * @param pduPresent lu dans la transaction d'écriture : `false` quand le PDU est parti avec son message
     *   pendant le traitement, cf. `ConversationMirror.inscrireMmsRecu`.
     */
    suspend fun traiter(
        conf: RetrieveConf,
        cle: String?,
        subId: Int?,
        indiceExpediteur: String?,
        pduPresent: () -> Boolean,
    ): Issue {
        val contenu = LecteurRetrieveConf.lire(conf, indiceExpediteur, System.currentTimeMillis())
        // v1.26.1 (audit H5) — garde miroir de [MmsWapPushReceiver], posée ici parce que le WAP-Push ne porte
        // pas toujours l'expéditeur. ⚠️ AVANT toute écriture sur le disque : écrit puis abandonné, le média
        // d'un expéditeur bloqué restait dans `filesDir/mms_attachments/`, en clair, sans ligne ni purge pour
        // y mener. v1.27.2 — repli ouvert commun aux trois receveurs (`isBlockedFailOpen`), sans avaler
        // l'annulation. v1.28.3 (F26) — couvre aussi « bloquer les numéros inconnus ».
        if (blockPolicy.doitEcarter(contenu.expediteur)) {
            // Rejet DÉLIBÉRÉ, sur un `true` franc : le message ne doit pas être conservé. Une ERREUR de
            // consultation rend `false` et n'arrive jamais ici.
            Timber.i("Dropping downloaded MMS from blocked sender")
            return Issue(garderLePdu = false, consigner = false)
        }
        return when (val resultat = integration.integrer(contenu, subId, membresDuGroupe(contenu), cle, pduPresent)) {
            is Resultat.Ecrit -> {
                notifierArrivee(contenu, resultat.messageId)
                Issue(garderLePdu = false, consigner = true)
            }
            // v1.28.3 (F15) — LE MÉDIA EXISTAIT ET N'A PAS PU ÊTRE ÉCRIT (disque plein, renommage refusé). La
            // ligne part quand même — la légende et la trace valent mieux que rien —, mais le PDU, seule copie
            // du média, est GARDÉ, et l'échec est DIT.
            is Resultat.EcritIncomplet -> {
                signalerMediaNonEcrit(contenu)
                notifierArrivee(contenu, resultat.messageId)
                Issue(garderLePdu = true, consigner = true)
            }
            is Resultat.ToujoursIncomplet -> Issue(garderLePdu = true, consigner = true)
            is Resultat.DejaComplet, is Resultat.Complete -> Issue(garderLePdu = false, consigner = true)
            Resultat.Disparu -> Issue(garderLePdu = false, consigner = true)
        }
    }

    /**
     * v1.28.4 — MMS de groupe : l'en-tête du PDU porte tous les destinataires, nous compris. Réglage actif et
     * « Mon numéro » connu, la conversation est celle du groupe (retrouvée ou créée) ; sinon, conversation
     * ordinaire, comme avant.
     */
    private suspend fun membresDuGroupe(contenu: ContenuMmsRecu): List<PhoneAddress>? {
        val envoi = runCatchingCancellable { settings.hydratedOrNull() }.getOrNull()?.sending
        val membres = if (envoi?.groupMms == true) {
            val identite = phoneIdentity.snapshot()
            GroupMmsMembers.of(
                from = contenu.expediteur,
                to = contenu.destinataires,
                cc = contenu.copies,
                self = envoi.userMsisdn,
                identityKey = identite::key,
            )
        } else {
            null
        }
        // Mesure sans numéro : ce que le PDU porte, et ce qu'on en a décidé.
        Timber.i(
            "MMS groupe: reglage=%s monNumero=%s to=%d cc=%d -> membres=%s",
            envoi?.groupMms,
            !envoi?.userMsisdn.isNullOrBlank(),
            contenu.destinataires.size,
            contenu.copies.size,
            membres?.size,
        )
        return membres
    }

    /**
     * Le message est en base : rien de ce qui suit n'est de la persistance, et un échec de notification ne
     * doit pas changer le sort du PDU. Symétrique de `SmsDeliverReceiver` : la ligne relue donne la
     * conversation, pour que la notification puisse être retirée par étiquette à l'ouverture du fil.
     */
    private suspend fun notifierArrivee(contenu: ContenuMmsRecu, messageId: Long) {
        runCatchingCancellable {
            val conversationId = messageDao.findById(messageId)?.conversationId
            if (conversationId == null) {
                Timber.w("MMS: message %d introuvable apres ecriture", messageId)
            } else {
                notifier.notifyIncoming(
                    address = contenu.expediteur,
                    body = contenu.libelleApercu,
                    messageId = messageId,
                    conversationId = conversationId,
                )
            }
        }.onFailure { Timber.w(it, "MMS: notification d'arrivee non postee") }
    }

    private suspend fun signalerMediaNonEcrit(contenu: ContenuMmsRecu) {
        runCatchingCancellable {
            failureNotifier.notifyFailure(MmsFailureNotifier.Reason.DOWNLOAD_FAILED, senderAddress = contenu.expediteur)
        }.onFailure { Timber.w(it, "MMS: notification d'echec non postee") }
    }
}
