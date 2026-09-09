package com.filestech.sms.system.notifications

import androidx.core.app.NotificationCompat
import com.filestech.sms.core.ext.blockKey
import com.filestech.sms.core.ext.stripMmsAddressSuffix
import com.filestech.sms.data.local.datastore.SettingsRepository
import com.filestech.sms.data.local.db.dao.ConversationDao
import com.filestech.sms.domain.model.PhoneAddress
import com.filestech.sms.domain.settings.PreviewMode
import com.filestech.sms.security.AppLockManager
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * v1.28.3 — **ce qu'une notification a le droit de dire d'un correspondant.**
 *
 * # Pourquoi cette classe existe
 *
 * La règle vivait en trois exemplaires : [IncomingMessageNotifier] la portait depuis des
 * versions, [MmsFailureNotifier] ne la portait pas du tout — c'est le finding F08 de la
 * relecture externe — et le notificateur d'échec d'ENVOI, qui n'existait pas encore, allait en
 * demander un troisième.
 *
 * Or c'est exactement le motif dominant de cette relecture : *une règle écrite, mais posée sur un
 * seul des chemins qui en avaient besoin*. Il s'est reproduit trois fois pendant les corrections
 * elles-mêmes. Écrire une quatrième copie de cette politique-ci, sur un chemin qui expose
 * l'identité d'un correspondant sur un écran verrouillé, serait le reproduire une quatrième — en
 * connaissance de cause.
 *
 * # La règle, dans l'ordre où elle compte
 *
 * 1. **Session leurre** → [Verdict.TAIRE]. Une notification nommant un correspondant du coffre
 *    trahirait devant l'agresseur ce que le mode leurre existe précisément pour cacher.
 * 2. **Conversation du coffre** → [Verdict.TAIRE]. Le rapprochement se fait par clé numérique et
 *    non par égalité de chaîne : une adresse arrive sous des formes qui varient d'un chemin à
 *    l'autre, et une comparaison stricte rendrait la garde inopérante là où elle sert.
 * 3. **Aperçus masqués** ([PreviewMode]) → [Verdict.ANONYMISER]. Le motif de l'événement reste
 *    visible — c'est l'information utile, et elle ne désigne personne.
 * 4. Sinon → [Verdict.NOMMER].
 *
 * Et depuis l'audit global du 2026-09-09, deux compléments que le premier jet n'avait pas :
 *
 * - **Quand l'appelant connaît la conversation** à laquelle la ligne est rattachée, c'est elle
 *   qui fait foi (A-01). Le rapprochement par adresse ne regarde que les conversations 1-à-1 ;
 *   une ligne rattachée à un GROUPE du coffre lui échapperait.
 * - **Ce que la notification laisse paraître d'elle-même** sur l'écran verrouillé (D-02) — cf.
 *   [ecranVerrouille]. Le premier jet unifiait QUI l'on nomme, pas SI l'événement paraît, et
 *   les deux notificateurs d'échec laissaient le défaut du framework, PRIVÉ, là où le jumeau
 *   entrant suit le réglage d'aperçu depuis SEC-01.
 *
 * # Le repli, et pourquoi il va dans ce sens
 *
 * Toute lecture qui échoue fait **taire** la notification, jamais l'inverse. C'est la règle posée
 * par l'audit H16 sur les notifications entrantes : le coût du repli sûr est une notification
 * manquée, celui du repli permissif est le nom d'un correspondant protégé sur un écran
 * verrouillé. Les deux ne se comparent pas.
 */
@Singleton
class CorrespondentVisibilityPolicy @Inject constructor(
    private val settings: SettingsRepository,
    private val appLock: AppLockManager,
    private val conversationDao: ConversationDao,
) {

    /** Ce qu'il advient de l'identité du correspondant dans la notification. */
    enum class Verdict {
        /** L'adresse peut être affichée. */
        NOMMER,

        /** Un libellé générique remplace l'adresse ; l'événement reste annoncé. */
        ANONYMISER,

        /** Rien n'est posté du tout. */
        TAIRE,
    }

    /** Ce qu'une notification laisse paraître d'ELLE-MÊME sur l'écran verrouillé. */
    enum class EcranVerrouille {
        /** Tout est visible — [PreviewMode.ALWAYS]. */
        PUBLIC,

        /** Le système masque le contenu tant que l'appareil est verrouillé — [PreviewMode.WHEN_UNLOCKED]. */
        PRIVE,

        /** Rien ne paraît, pas même l'existence de l'événement — [PreviewMode.NEVER]. */
        SECRET,
    }

    /**
     * @param adresse adresse brute du correspondant, telle qu'elle vient du PDU ou de Room.
     *   `null` ou vide vaut « inconnu » : il n'y a alors rien à protéger, mais rien à nommer non
     *   plus — le verdict est [Verdict.ANONYMISER], sauf session leurre.
     * @param conversationId v1.28.3 (audit global A-01) — la conversation à laquelle la ligne
     *   est RATTACHÉE, quand l'appelant la connaît. Elle fait foi avant l'adresse : le
     *   rapprochement par adresse ne voit que les 1-à-1, et une ligne rattachée à un groupe du
     *   coffre lui échapperait. Aujourd'hui les lignes sortantes vivent dans des 1-à-1 (X-01),
     *   donc l'adresse suffit ; le jour où elles seront rattachées au groupe d'où l'on a
     *   envoyé, ce paramètre est ce qui empêchera cette classe de mentir.
     */
    suspend fun verdictPour(adresse: String?, conversationId: Long? = null): Verdict {
        val leurre = appLock.state.value is AppLockManager.LockState.PanicDecoy
        if (leurre || (conversationId != null && conversationDansLeCoffre(conversationId))) return Verdict.TAIRE

        val brute = adresse?.takeIf { it.isNotBlank() } ?: return Verdict.ANONYMISER

        val dansLeCoffre = runCatching {
            val cle = brute.stripMmsAddressSuffix().blockKey()
            conversationDao.snapshotVaultOneToOne().any { conv ->
                PhoneAddress.list(conv.addressesCsv)
                    .firstOrNull()
                    ?.raw
                    ?.stripMmsAddressSuffix()
                    ?.blockKey() == cle
            }
        }.getOrElse {
            Timber.w(it, "Politique de notification : lecture coffre impossible — on se tait")
            true
        }
        if (dansLeCoffre) return Verdict.TAIRE

        return if (modeApercu() != PreviewMode.ALWAYS) Verdict.ANONYMISER else Verdict.NOMMER
    }

    /**
     * v1.28.3 (audit global D-02) — ce que la notification laisse paraître d'elle-même sur
     * l'écran verrouillé, d'après le réglage d'aperçu. Réglages illisibles → [EcranVerrouille.SECRET],
     * le repli va dans le même sens que tout le reste de cette classe.
     */
    suspend fun ecranVerrouille(): EcranVerrouille = modeApercu().ecranVerrouille()

    private suspend fun conversationDansLeCoffre(id: Long): Boolean = runCatching {
        conversationDao.findById(id)?.inVault == true
    }.getOrElse {
        Timber.w(it, "Politique de notification : conversation %d illisible — on se tait", id)
        true
    }

    /** Réglages absents ou illisibles → [PreviewMode.NEVER], le plus prudent. */
    private suspend fun modeApercu(): PreviewMode = runCatching {
        settings.hydratedOrNull()?.notifications?.previewMode ?: PreviewMode.NEVER
    }.getOrDefault(PreviewMode.NEVER)
}

/**
 * Une seule table pour les trois notificateurs. `IncomingMessageNotifier` la portait seul
 * depuis SEC-01 ; c'est ici qu'elle vit désormais.
 */
fun PreviewMode.ecranVerrouille(): CorrespondentVisibilityPolicy.EcranVerrouille = when (this) {
    PreviewMode.ALWAYS -> CorrespondentVisibilityPolicy.EcranVerrouille.PUBLIC
    PreviewMode.WHEN_UNLOCKED -> CorrespondentVisibilityPolicy.EcranVerrouille.PRIVE
    PreviewMode.NEVER -> CorrespondentVisibilityPolicy.EcranVerrouille.SECRET
}

/** Traduction vers la constante `NotificationCompat`, au seul endroit où l'on en a besoin. */
fun CorrespondentVisibilityPolicy.EcranVerrouille.versNotificationCompat(): Int = when (this) {
    CorrespondentVisibilityPolicy.EcranVerrouille.PUBLIC -> NotificationCompat.VISIBILITY_PUBLIC
    CorrespondentVisibilityPolicy.EcranVerrouille.PRIVE -> NotificationCompat.VISIBILITY_PRIVATE
    CorrespondentVisibilityPolicy.EcranVerrouille.SECRET -> NotificationCompat.VISIBILITY_SECRET
}
