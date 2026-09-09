package com.filestech.sms.system.notifications

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

    /**
     * @param adresse adresse brute du correspondant, telle qu'elle vient du PDU ou de Room.
     *   `null` ou vide vaut « inconnu » : il n'y a alors rien à protéger, mais rien à nommer non
     *   plus — le verdict est [Verdict.ANONYMISER], sauf session leurre.
     */
    suspend fun verdictPour(adresse: String?): Verdict {
        if (appLock.state.value is AppLockManager.LockState.PanicDecoy) return Verdict.TAIRE

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

        val apercusMasques = runCatching {
            settings.hydratedOrNull()
                ?.notifications
                ?.previewMode
                ?.let { it != PreviewMode.ALWAYS }
                ?: true
        }.getOrDefault(true)
        return if (apercusMasques) Verdict.ANONYMISER else Verdict.NOMMER
    }
}
