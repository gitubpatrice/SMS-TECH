package com.filestech.sms.domain.location

/**
 * Port domaine : résolution de la position courante pour le mode urgence.
 *
 * [com.filestech.sms.domain.usecase.TriggerEmergencyUseCase] n'a besoin que d'un couple lat/lng
 * ([GeoLocation]) pour composer l'URL de carte du SMS d'urgence. L'implémentation
 * [com.filestech.sms.data.location.LocationResolver] enveloppe `LocationManager` (Android) — gère
 * permission / timeout / fallback `lastKnown` — et projette le `android.location.Location` obtenu
 * en [GeoLocation], de sorte que `domain/` reste sans import Android.
 */
interface LocationProvider {

    /**
     * Renvoie la position courante, ou `null` si indisponible (permission `ACCESS_FINE_LOCATION`
     * refusée, aucun provider activé, ou aucun fix dans le délai).
     */
    suspend fun resolveLocation(): GeoLocation?
}
