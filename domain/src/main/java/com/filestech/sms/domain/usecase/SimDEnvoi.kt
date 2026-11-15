package com.filestech.sms.domain.usecase

import com.filestech.sms.di.IoDispatcher
import com.filestech.sms.domain.sender.SimsActives
import com.filestech.sms.domain.settings.AppSettings
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import javax.inject.Inject

/**
 * 2026-11-10 (choix de la SIM d'envoi, v1.30.1) — la SIM qui part réellement, pour chaque voie d'envoi.
 *
 * Remplace la ligne `subId ?: s.sending.defaultSubId`, recopiée à l'identique dans [SendSmsUseCase],
 * [SendMediaMmsUseCase] et [SendVoiceMmsUseCase], et y ajoute la validation : le réglage « SIM d'envoi
 * par défaut » existe désormais à l'écran, et une SIM retirée après coup ferait échouer chaque envoi qui
 * ne précise pas de SIM — le SMS d'urgence compris. Cf. [ChoixDeSim.valider].
 *
 * La liste des SIM est lue sur [io] : le fil envoie depuis `viewModelScope`, donc depuis le thread
 * principal, et cette lecture est un appel binder qui peut geler sur certains Samsung.
 */
class SimDEnvoi @Inject constructor(
    private val sims: SimsActives,
    @IoDispatcher private val io: CoroutineDispatcher,
) {

    /** La SIM d'un envoi : [demande], sinon le réglage de [reglages] ; validée. `null` = la SIM SMS d'Android. */
    suspend fun pourEnvoi(demande: Int?, reglages: AppSettings): Int? =
        valider(demande ?: reglages.sending.defaultSubId)

    /** [subId] s'il désigne une SIM active (ou si la liste est inconnue), sinon `null`. Sans lecture pour `null`. */
    suspend fun valider(subId: Int?): Int? {
        if (subId == null) return null
        return ChoixDeSim.valider(subId, withContext(io) { sims.lister() })
    }
}
