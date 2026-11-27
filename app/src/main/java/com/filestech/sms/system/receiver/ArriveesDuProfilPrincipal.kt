package com.filestech.sms.system.receiver

import android.content.Context
import android.os.Build
import android.os.UserManager
import com.filestech.sms.core.result.runCatchingCancellable
import com.filestech.sms.data.local.datastore.SettingsRepository
import com.filestech.sms.data.local.db.dao.AttachmentDao
import com.filestech.sms.data.local.db.dao.MessageDao
import com.filestech.sms.data.local.db.entity.MessageEntity
import com.filestech.sms.data.mms.LecteurRetrieveConf
import com.filestech.sms.data.sms.DefaultSmsAppManager
import com.filestech.sms.data.sync.TelephonySyncManager
import com.filestech.sms.domain.model.MessageType
import com.filestech.sms.system.notifications.IncomingMessageNotifier
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 2026-11-27 (issue #98) — **dans un profil SECONDAIRE, notifier les messages que le profil principal a reçus.**
 *
 * Android ne remet les SMS et MMS entrants qu'à l'application SMS du profil principal (`SMS_DELIVER`,
 * `WAP_PUSH_DELIVER`). Les autres profils ne voient que le stockage partagé, où celle-ci les écrit, et SMS Tech ne les
 * y lisait qu'à sa synchronisation suivante, sans notification (GrapheneOS, issue #98). Deux signaux les atteignent
 * pourtant, et déclenchent une passe (`ArriveesDuProfilPrincipalWorker`) :
 *
 * - **SMS** : `SMS_RECEIVED` va à tous les utilisateurs en marche, après le `SMS_DELIVER` du principal, donc après
 *   son écriture dans le stockage (AOSP `InboundSmsHandler`, mesuré sur émulateurs Android 14 et 16 à deux
 *   utilisateurs), avec une exemption temporaire du Doze ([SmsRecuAuProfilPrincipalReceiver]).
 * - **MMS** : aucune diffusion. `MMS_DOWNLOADED` est envoyé à chaque utilisateur, mais il n'est pas exempté des
 *   limites des diffusions implicites (`framework-sysconfig.xml`) : un récepteur déclaré au manifeste ne le reçoit
 *   pas. C'est le stockage qui prévient — chaque écriture y est notifiée à TOUS les utilisateurs
 *   (`MmsProvider.notifyChange`, `UserHandle.USER_ALL`), et une tâche déclenchée par un changement de
 *   `content://mms-sms/` se réveille quand le principal y recopie un MMS reçu.
 *
 * Une passe synchronise, puis notifie les messages REÇUS NON LUS que la synchronisation a apportés, par le même
 * notificateur que le profil principal (Coffre, sourdine, aperçu masqué, verrou, réglages des notifications) et
 * avec la même politique d'écartement (liste noire, numéros inconnus). Rien dans le profil principal, qui reçoit
 * les messages lui-même ([aEcouter]).
 *
 * # Ce qui a déjà été regardé
 *
 * Un repère PERSISTANT (`AdvancedSettings.arriveesNotifieesJusqua`) : le plus grand identifiant de message déjà
 * regardé. Un relevé au début de chaque passe ne suffirait pas : le SMS démarre le processus, dont la
 * synchronisation de démarrage peut importer le message avant la passe qui doit le notifier. La première passe
 * (repère jamais posé) ne regarde que les [PREMIERE_FENETRE_MS] dernières minutes : une mise à jour ne notifie pas
 * l'historique non lu. [FENETRE_MS] et [LIMITE] bornent une passe qui importerait d'un coup de vieux messages (une
 * sauvegarde restaurée par une autre application).
 */
@Singleton
class ArriveesDuProfilPrincipal @Inject constructor(
    private val synchro: TelephonySyncManager,
    private val messageDao: MessageDao,
    private val attachmentDao: AttachmentDao,
    private val notifier: IncomingMessageNotifier,
    private val ecartement: IncomingBlockPolicy,
    private val settings: SettingsRepository,
) {

    /** Deux passes (un SMS et un MMS rapprochés) ne lisent pas le même repère : la seconde attend la première. */
    private val verrou = Mutex()

    /** @return le nombre de notifications posées. */
    suspend fun importerEtNotifier(raison: String): Int = verrou.withLock {
        passeDesArrivees(
            repere = settings.flow.first().advanced.arriveesNotifieesJusqua,
            maintenant = System.currentTimeMillis(),
            synchroniser = { synchro.synchroniserMaintenant(raison) },
            plusGrandId = messageDao::plusGrandId,
            candidats = messageDao::recusNonLusEntre,
            aEcarter = ecartement::doitEcarter,
            notifier = { message ->
                notifier.notifyIncoming(
                    address = message.address,
                    body = corpsDeLaNotification(message),
                    messageId = message.id,
                    conversationId = message.conversationId,
                )
            },
            poserRepere = { repere ->
                settings.update { s -> s.copy(advanced = s.advanced.copy(arriveesNotifieesJusqua = repere)) }
            },
        )
    }

    private suspend fun corpsDeLaNotification(message: MessageEntity): String =
        corpsDeNotification(message.type, message.body) {
            attachmentDao.findForMessage(message.id).firstOrNull()?.mimeType
        }

    companion object {
        /** La première passe, repère jamais posé : ce qui est arrivé ces dernières minutes, pas l'historique. */
        const val PREMIERE_FENETRE_MS: Long = 10L * 60L * 1_000L

        /** Un message plus ancien que ceci n'est pas notifié, même tout juste importé. */
        const val FENETRE_MS: Long = 24L * 60L * 60L * 1_000L

        /** Notifications posées au plus par une passe. */
        const val LIMITE: Int = 20

        /**
         * Un profil où écouter : pas le profil principal (il reçoit lui-même), pas un profil de travail (Android ne
         * lui écrit pas les MMS et peut lui remettre les SMS lui-même), et SMS Tech y est l'application SMS par défaut
         * — sinon c'est l'autre application qui notifie.
         *
         * Le rôle par [DefaultSmsAppManager] : dans un utilisateur secondaire, `Telephony.Sms.getDefaultSmsPackage`
         * rend `null` alors que SMS Tech y détient le rôle (mesuré sur émulateur Android 16).
         */
        fun aEcouter(context: Context): Boolean {
            val utilisateurs = context.getSystemService(UserManager::class.java) ?: return false
            if (utilisateurs.isSystemUser) return false
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && utilisateurs.isManagedProfile) return false
            return runCatching { DefaultSmsAppManager(context.applicationContext).isDefault() }.getOrDefault(false)
        }
    }
}

/**
 * Le texte d'une notification d'arrivée : celui du message ; pour un MMS sans texte, le libellé que pose le profil
 * principal (`ContenuMmsRecu.libelleApercu`, d'après le type de la première pièce, lue seulement dans ce cas).
 */
internal suspend fun corpsDeNotification(
    type: MessageType,
    texte: String,
    premiereMime: suspend () -> String?,
): String =
    if (type != MessageType.MMS || texte.isNotBlank()) texte else LecteurRetrieveConf.libelleParDefaut(premiereMime())

/**
 * La passe, sans Android (`PasseDesArriveesTest`). Synchronise, puis notifie les reçus non lus d'identifiant dans
 * `]repère, plafond]` — le plafond est relevé APRÈS la synchronisation et devient le nouveau repère, si bien qu'un
 * message importé ensuite par une autre passe reste au-dessus. Un repère au-delà du plafond (base recréée) compte
 * comme jamais posé. Une notification qui échoue n'arrête pas les autres.
 *
 * @return le nombre de notifications posées.
 */
@Suppress("LongParameterList")
internal suspend fun passeDesArrivees(
    repere: Long,
    maintenant: Long,
    synchroniser: suspend () -> Unit,
    plusGrandId: suspend () -> Long,
    candidats: suspend (apres: Long, jusqua: Long, depuis: Long, limite: Int) -> List<MessageEntity>,
    aEcarter: suspend (String) -> Boolean,
    notifier: suspend (MessageEntity) -> Unit,
    poserRepere: suspend (Long) -> Unit,
): Int {
    synchroniser()
    val plafond = plusGrandId()
    val pose = repere in 0L..plafond
    val apres = if (pose) repere else 0L
    val fenetre = if (pose) ArriveesDuProfilPrincipal.FENETRE_MS else ArriveesDuProfilPrincipal.PREMIERE_FENETRE_MS
    val depuis = maintenant - fenetre
    var notifiees = 0
    if (plafond > apres) {
        for (message in candidats(apres, plafond, depuis, ArriveesDuProfilPrincipal.LIMITE).sortedBy { it.id }) {
            if (aEcarter(message.address)) continue
            runCatchingCancellable { notifier(message) }
                .onSuccess { notifiees++ }
                .onFailure { Timber.w(it, "Arrivee au profil principal : notification non posee") }
        }
    }
    if (plafond != repere) poserRepere(plafond)
    return notifiees
}
