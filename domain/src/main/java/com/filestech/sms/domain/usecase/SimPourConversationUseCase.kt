package com.filestech.sms.domain.usecase

import com.filestech.sms.di.IoDispatcher
import com.filestech.sms.domain.repository.ConversationRepository
import com.filestech.sms.domain.sender.SimsActives
import com.filestech.sms.domain.settings.AppSettings
import com.filestech.sms.domain.settings.AppSettingsSource
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.withContext
import javax.inject.Inject

/**
 * 2026-11-10 (choix de la SIM d'envoi, v1.30.1) — la SIM par laquelle répondre à une conversation depuis
 * HORS du fil : la réponse rapide d'une notification. Un message reçu sur la SIM 2 doit recevoir sa réponse
 * par la SIM 2, comme dans le fil.
 *
 * Le fil, lui, a déjà ses messages et la liste des SIM : il applique [ChoixDeSim] directement. Les deux
 * passent par la même règle et la même lecture de l'historique ([ChoixDeSim.historique]).
 */
class SimPourConversationUseCase @Inject constructor(
    private val sims: SimsActives,
    private val repo: ConversationRepository,
    private val settings: AppSettingsSource,
    @IoDispatcher private val io: CoroutineDispatcher,
) {

    /** `null` — Android choisit — quand il y a moins de deux SIM actives. */
    suspend fun pourConversation(conversationId: Long?): Int? {
        val (actives, simAndroid) = withContext(io) { sims.lister() to sims.simSmsDAndroid() }
        if (actives.size < 2) return null
        val messages = conversationId
            ?.let { repo.observeMessagesWindow(it, flowOf(HISTORIQUE)).firstOrNull()?.messages }
            .orEmpty()
        return ChoixDeSim.pourConversation(
            actives = actives,
            historique = ChoixDeSim.historique(messages),
            reglage = (settings.hydratedOrNull() ?: AppSettings()).sending.defaultSubId,
            simAndroid = simAndroid,
        )
    }

    private companion object {
        /** Assez pour trouver un message porteur de SIM, sans charger un long fil. */
        const val HISTORIQUE = 50
    }
}
