package com.filestech.sms.data.sync

import com.filestech.sms.core.result.runCatchingCancellable
import com.filestech.sms.data.local.datastore.SettingsRepository
import com.filestech.sms.data.repository.ConversationMirror
import com.filestech.sms.data.sms.PhoneIdentity
import com.filestech.sms.data.sms.TelephonyReader
import com.filestech.sms.domain.mms.DestinatairesMms
import kotlinx.coroutines.flow.first
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 2026-11-06 (courriel d'un utilisateur Visible/Verizon, Galaxy A54) — **les fils de groupe du fournisseur
 * Android**, et la passe qui range dans leur conversation de groupe ceux que l'import avait éclatés.
 *
 * Constaté : dès que SMS Tech devenait l'application par défaut, chaque conversation de groupe MMS devenait une
 * conversation 1-à-1 avec un seul de ses membres ; l'application d'origine les montrait toujours en groupe. Cause :
 * l'import ouvrait la conversation d'un fil sur la seule adresse de sa première ligne, et la rapprochait de la
 * 1-à-1 existante de cette personne. Le fil du groupe se mêlait à ses échanges privés.
 *
 * - [lire] : `thread_id` → membres, pour les seuls fils de groupe. L'import s'en sert pour ouvrir la bonne
 *   conversation (`ConversationMirror.adressesDuFil`).
 * - [regrouper] : la réparation des installations déjà importées. L'import ne tourne qu'une fois
 *   (`mmsImportCompleted`), ses erreurs ne se seraient donc jamais corrigées seules. Passe unique, marquée
 *   `AdvancedSettings.groupThreadsRegroupedV1300` par l'appelant quand elle a abouti.
 */
@Singleton
class FilsDeGroupe @Inject constructor(
    private val telephonyReader: TelephonyReader,
    private val mirror: ConversationMirror,
    private val settings: SettingsRepository,
    private val phoneIdentity: PhoneIdentity,
) {

    /**
     * Les fils de groupe du fournisseur : `thread_id` → membres (au moins deux, « Mon numéro » retiré, cf.
     * [DestinatairesMms.membresDuFil]). `null` si le fournisseur n'a pas pu être lu : l'appelant importe alors
     * comme avant, et la réparation attend le passage suivant.
     *
     * Sans « Mon numéro », rien n'est retiré, et c'est voulu (relecture Claude du 2026-11-06, qui proposait d'écarter
     * les fils à deux membres) : le fournisseur tient les fils tels que l'application d'origine les affichait, et une
     * réponse y partait aux mêmes destinataires. Un fil {moi, Alice}, s'il existe, y était déjà une conversation à
     * part ; le reproduire ne change rien à ce que l'utilisateur voyait. Le retirer exige de savoir qui il est.
     */
    suspend fun lire(): Map<Long, List<String>>? = runCatchingCancellable {
        val fils = telephonyReader.lireFilsDuFournisseur() ?: return null
        val soi = settings.flow.first().sending.userMsisdn?.takeIf { it.isNotBlank() }
        val cle = phoneIdentity.snapshot()::key
        val groupes = buildMap {
            for ((fil, destinataires) in fils) {
                DestinatairesMms.membresDuFil(destinataires, soi, cle)?.let { put(fil, it) }
            }
        }
        // Des nombres seulement : la preuve que le fournisseur de ce téléphone se lit, même sans aucun groupe.
        Timber.i("Fils du fournisseur : %d, dont %d de groupe", fils.size, groupes.size)
        groupes
    }.onFailure { Timber.w(it, "Fils du fournisseur illisibles") }.getOrNull()

    /**
     * Range chaque fil de [filsDeGroupe] dans la conversation de son groupe (`ConversationMirror.regrouperFil`).
     *
     * Chaque fil a sa transaction, et l'échec d'un fil n'arrête pas les autres (relecture Claude du 2026-11-06) :
     * sinon un seul fil en échec durable empêchait de poser le marqueur, et chaque synchronisation relisait tout le
     * fournisseur pour échouer au même endroit. Un fil en échec reste comme avant la 1.30.0 ; le journal le compte, et
     * « Resynchroniser depuis le téléphone » rejoue la passe (`SettingsViewModel.forceResyncFromTelephony`).
     *
     * @return `true` si la passe a abouti — le marqueur peut être posé ; `false` si les messages des fils n'ont pas
     *   pu être lus : rien n'a bougé, la passe suivante recommencera.
     */
    suspend fun regrouper(filsDeGroupe: Map<Long, List<String>>): Boolean {
        if (filsDeGroupe.isEmpty()) return true
        val messages = runCatchingCancellable { telephonyReader.lireMessagesDesFils(filsDeGroupe.keys) }
            .onFailure { Timber.w(it, "Messages des fils de groupe illisibles") }
            .getOrNull() ?: return false
        var deplaces = 0
        var enEchec = 0
        for ((fil, uris) in messages) {
            val membres = filsDeGroupe[fil] ?: continue
            runCatchingCancellable { mirror.regrouperFil(fil, membres, uris) }
                .onSuccess { deplaces += it }
                .onFailure {
                    enEchec++
                    Timber.w(it, "Fil de groupe non regroupe")
                }
        }
        // Sans numéro : des nombres seulement.
        Timber.i(
            "Fils de groupe : %d fil(s), %d message(s) range(s) dans leur groupe, %d fil(s) en echec",
            filsDeGroupe.size,
            deplaces,
            enEchec,
        )
        return true
    }
}
