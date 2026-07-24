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

    /** Dernier snapshot des réglages, lecture zéro-I/O (`state.value`). */
    val state: StateFlow<AppSettings>

    /** Applique [transform] de façon atomique et persiste le résultat. */
    suspend fun update(transform: (AppSettings) -> AppSettings)
}
