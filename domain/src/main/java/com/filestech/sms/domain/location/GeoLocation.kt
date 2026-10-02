package com.filestech.sms.domain.location

import java.util.Locale
import kotlin.math.ceil

/**
 * Position géographique minimale, côté domaine. Projetée depuis `android.location.Location` par
 * l'implémentation du port [LocationProvider] pour garder `domain/` sans import Android.
 *
 * @property precisionMetres rayon de précision annoncé par le système (68 % de confiance), ou
 *   `null` s'il n'en donne pas. v1.28.13 — porté jusqu'ici pour que le SMS d'urgence puisse DIRE
 *   qu'une position est approximative, cf. [lienCarte].
 */
data class GeoLocation(
    val latitude: Double,
    val longitude: Double,
    val precisionMetres: Float? = null,
)

/**
 * Le lien de carte du SMS d'urgence : `https://maps.google.com/?q=LAT,LON`, suivi de
 * `(+/-N km)` quand la position est connue à plus d'un kilomètre près.
 *
 * v1.28.13 — **une position approximative est envoyée, et elle est dite approximative.**
 *
 * Quand l'utilisateur n'accorde que la localisation « approximative », Android décale la position
 * d'environ deux kilomètres. Le lien, lui, garde cinq décimales et a l'air précis au mètre : sans
 * mention, les proches chercheraient au mauvais endroit — la variante « transmise mais fausse »
 * déjà écartée pour les positions périmées (v1.26.1, audit H11). La marge s'écrit en ASCII, sans
 * `±` ni `~` : `±` sort de l'alphabet GSM-7 et ferait passer tout le SMS en UCS-2, et `+/-` se lit
 * dans toutes les langues de l'application.
 *
 * `Locale.ROOT` est OBLIGATOIRE (v1.27.2, audit externe) : en français, `%.5f` écrit une virgule
 * décimale et Maps ne sait plus lire `?q=48,85341,2,34880`.
 */
fun GeoLocation.lienCarte(): String {
    val lien = String.format(Locale.ROOT, "https://maps.google.com/?q=%.5f,%.5f", latitude, longitude)
    val marge = precisionMetres?.takeIf { it > SEUIL_APPROXIMATIF_METRES } ?: return lien
    return "$lien (+/-${ceil(marge / METRES_PAR_KM).toInt()} km)"
}

/** Au-delà d'un kilomètre, la rue n'est plus désignée : on le dit. */
private const val SEUIL_APPROXIMATIF_METRES = 1_000f
private const val METRES_PAR_KM = 1_000f
