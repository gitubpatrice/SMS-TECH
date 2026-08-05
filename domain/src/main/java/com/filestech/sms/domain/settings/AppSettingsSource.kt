package com.filestech.sms.domain.settings

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/**
 * Port domaine : lecture (réactive + snapshot) et mise à jour transactionnelle des préférences
 * utilisateur ([AppSettings]).
 *
 * Les use-cases qui dépendent des réglages (envoi, urgence, safety-call, « je vais bien ») en
 * dépendent au lieu de la couche data. L'implémentation
 * [com.filestech.sms.data.local.datastore.SettingsRepository] gère la sérialisation DataStore ;
 * [AppSettings] et toute sa hiérarchie sont des types purs vivant dans `domain/`.
 */
interface AppSettingsSource {

    /** Flux réactif des réglages (ré-émis à chaque écriture DataStore). */
    val flow: Flow<AppSettings>

    /**
     * Dernier snapshot des réglages, lecture zéro-I/O (`state.value`).
     *
     * ⚠️ **Tant que le processus n'a pas hydraté ce flux, il rend les valeurs PAR DÉFAUT** — pas
     * les réglages de l'utilisateur. C'est le cas exact d'un receveur ou d'un worker réveillé sur
     * un processus qui vient de naître. N'utiliser que sur un chemin où l'on sait le processus
     * déjà chaud (interface au premier plan) ; partout ailleurs, préférer [hydratedOrNull].
     */
    val state: StateFlow<AppSettings>

    /**
     * v1.27.2 — instantané **réellement lu depuis le stockage**, ou `null` s'il reste illisible.
     *
     * Rend [state] immédiatement quand le processus l'a déjà hydraté (cas courant, coût nul), et
     * ne paie une lecture suspendue que sur un processus démarré à froid. À utiliser sur tout
     * chemin déclenché par le système — SMS entrant, worker, receveur — où [state] rendrait
     * silencieusement les défauts.
     *
     * Le `null` est volontairement distinct des défauts : il laisse l'appelant choisir le sens
     * dans lequel son repli échoue, au lieu de lui servir une configuration plausible mais fausse.
     */
    suspend fun hydratedOrNull(): AppSettings?

    /** Applique [transform] de façon atomique et persiste le résultat. */
    suspend fun update(transform: (AppSettings) -> AppSettings)
}
