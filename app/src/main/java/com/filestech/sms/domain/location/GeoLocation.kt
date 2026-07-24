package com.filestech.sms.domain.location

/**
 * Position géographique minimale, côté domaine. Projetée depuis `android.location.Location` par
 * l'implémentation du port [LocationProvider] pour garder `domain/` sans import Android.
 */
data class GeoLocation(
    val latitude: Double,
    val longitude: Double,
)
