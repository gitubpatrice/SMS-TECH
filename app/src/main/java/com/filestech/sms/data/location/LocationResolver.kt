package com.filestech.sms.data.location

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import com.filestech.sms.domain.location.GeoLocation
import com.filestech.sms.domain.location.LocationProvider
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import timber.log.Timber
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume

/**
 * v1.10.0 — Résolveur de position pour le Mode urgence.
 *
 * **Volontairement minimaliste** — un seul fix, pas de tracking continu,
 * pas de Google Play Services (le bundle `FusedLocationProviderClient`
 * exigerait Google Play Services qui n'existe pas sur les builds F-Droid
 * FLOSS).
 *
 * **Stratégie en cascade** :
 *  1. Si la permission `ACCESS_FINE_LOCATION` n'est PAS accordée → return null.
 *  2. Si un `lastKnownLocation` (GPS ou NETWORK) frais (< [FRESH_THRESHOLD_MS])
 *     existe → on le retourne directement (pas de fix réseau, pas d'attente).
 *  3. Sinon, on `requestSingleUpdate`-équivalent sur GPS + NETWORK en
 *     parallèle, on prend le PREMIER fix qui arrive ([timeoutMs] ms max).
 *  4. Au timeout : on retourne le `lastKnownLocation` même s'il est rance,
 *     ou null si rien n'est connu.
 *
 * **Précision** : pas de filtrage sur `accuracy` — un fix imprécis vaut
 * mieux que pas de fix dans un contexte d'urgence. Le destinataire peut
 * toujours comprendre "près de cette zone" même avec 1km de marge.
 *
 * **Pas d'effet de bord** : les listeners sont systématiquement retirés
 * en `invokeOnCancellation` + dans le callback `onLocationChanged`.
 * Pas de leak possible si le coroutine est annulé.
 */
@Singleton
class LocationResolver @Inject constructor(
    @ApplicationContext private val context: Context,
) : LocationProvider {

    /**
     * Projette le [Location] Android résolu en [GeoLocation] domaine — le seul contrat exposé aux
     * use-cases. Le timeout de résolution reste un détail d'implémentation.
     */
    override suspend fun resolveLocation(): GeoLocation? =
        getCurrentLocation()?.let { GeoLocation(latitude = it.latitude, longitude = it.longitude) }

    /**
     * Tente de récupérer la position actuelle. Retourne null si :
     *  - permission `ACCESS_FINE_LOCATION` non accordée
     *  - aucun provider activé (GPS off ET network off)
     *  - aucun fix dans le timeout ET pas de `lastKnown` rance
     */
    suspend fun getCurrentLocation(
        timeoutMs: Long = DEFAULT_TIMEOUT_MS,
    ): Location? {
        if (!hasLocationPermission()) {
            Timber.d("LocationResolver: ACCESS_FINE_LOCATION not granted, returning null")
            return null
        }
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
        if (lm == null) {
            Timber.w("LocationResolver: LocationManager unavailable")
            return null
        }

        val freshLast = bestFreshLastKnown(lm)
        if (freshLast != null) {
            Timber.d(
                "LocationResolver: using fresh lastKnown (provider=%s, ageMs=%d)",
                freshLast.provider,
                System.currentTimeMillis() - freshLast.time,
            )
            return freshLast
        }

        val gpsEnabled = runCatching { lm.isProviderEnabled(LocationManager.GPS_PROVIDER) }
            .getOrDefault(false)
        val networkEnabled = runCatching { lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER) }
            .getOrDefault(false)
        if (!gpsEnabled && !networkEnabled) {
            Timber.d("LocationResolver: no enabled provider, returning stale lastKnown if any")
            return bestLastKnown(lm)
        }

        val newFix = withTimeoutOrNull(timeoutMs) {
            awaitFirstFix(lm, gpsEnabled, networkEnabled)
        }
        if (newFix != null) {
            Timber.d("LocationResolver: got fresh fix (provider=%s)", newFix.provider)
            return newFix
        }
        // Timeout — best-effort fallback sur le lastKnown même rance.
        val stale = bestLastKnown(lm)
        if (stale != null) {
            Timber.d(
                "LocationResolver: timeout, falling back to stale lastKnown (provider=%s, ageMs=%d)",
                stale.provider,
                System.currentTimeMillis() - stale.time,
            )
        } else {
            Timber.d("LocationResolver: timeout, no lastKnown available")
        }
        return stale
    }

    fun hasLocationPermission(): Boolean =
        ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_FINE_LOCATION,
        ) == PackageManager.PERMISSION_GRANTED

    private fun bestFreshLastKnown(lm: LocationManager): Location? {
        val now = System.currentTimeMillis()
        return bestLastKnown(lm)?.takeIf { now - it.time <= FRESH_THRESHOLD_MS }
    }

    private fun bestLastKnown(lm: LocationManager): Location? {
        val candidates = listOfNotNull(
            safeLastKnown(lm, LocationManager.GPS_PROVIDER),
            safeLastKnown(lm, LocationManager.NETWORK_PROVIDER),
        )
        // Le plus récent et le plus précis (priorité au plus récent).
        return candidates.maxByOrNull { it.time }
    }

    private fun safeLastKnown(lm: LocationManager, provider: String): Location? =
        try {
            @Suppress("MissingPermission") // Vérifié par hasLocationPermission().
            lm.getLastKnownLocation(provider)
        } catch (t: SecurityException) {
            // Permission révoquée entre le check et l'appel — défense en profondeur.
            Timber.w(t, "LocationResolver: lastKnown SecurityException on %s", provider)
            null
        } catch (t: IllegalArgumentException) {
            Timber.w(t, "LocationResolver: lastKnown unknown provider %s", provider)
            null
        }

    @Suppress("MissingPermission") // Garde-fou en amont via hasLocationPermission().
    private suspend fun awaitFirstFix(
        lm: LocationManager,
        gpsEnabled: Boolean,
        networkEnabled: Boolean,
    ): Location? = suspendCancellableCoroutine { cont ->
        val handler = Handler(Looper.getMainLooper())
        val listenerHolder = arrayOfNulls<LocationListener>(1)
        // v1.10.0 audit P1 — garde contre double `resume`. GPS et NETWORK
        // peuvent rendre quasi-simultanément ; sans ce flag le 2e callback
        // appelle resume() sur une coroutine déjà terminée → IllegalStateException
        // attrapée par runCatching côté caller mais perte du fix valide.
        val resumed = AtomicBoolean(false)

        fun cleanup() {
            val l = listenerHolder[0]
            if (l != null) {
                runCatching { lm.removeUpdates(l) }
                listenerHolder[0] = null
            }
        }

        val listener = object : LocationListener {
            override fun onLocationChanged(location: Location) {
                if (!resumed.compareAndSet(false, true)) return
                cleanup()
                if (cont.isActive) cont.resume(location)
            }

            // Méthodes vides nécessaires pour la compat API < 30 (LocationListener
            // a vu sa surface réduite en API 30+, mais les anciens devices
            // requièrent ces overrides — sinon AbstractMethodError au runtime).
            @Deprecated("Required for API < 30 compat")
            override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {
            }

            override fun onProviderEnabled(provider: String) {}
            override fun onProviderDisabled(provider: String) {}
        }
        listenerHolder[0] = listener

        cont.invokeOnCancellation {
            cleanup()
        }

        try {
            if (gpsEnabled) {
                lm.requestLocationUpdates(
                    LocationManager.GPS_PROVIDER,
                    0L, 0f, listener, handler.looper,
                )
            }
            if (networkEnabled) {
                lm.requestLocationUpdates(
                    LocationManager.NETWORK_PROVIDER,
                    0L, 0f, listener, handler.looper,
                )
            }
        } catch (t: SecurityException) {
            Timber.w(t, "LocationResolver: requestLocationUpdates SecurityException")
            // v1.10.0 audit SEC-6 — cleanup TOUJOURS, indépendamment du resume.
            // Si un fix GPS est arrivé entre `requestLocationUpdates(GPS)` et
            // l'échec sur NETWORK, `resumed` est déjà true mais le listener
            // GPS reste enregistré sans le cleanup — leak batterie résiduel.
            cleanup()
            if (resumed.compareAndSet(false, true)) {
                if (cont.isActive) cont.resume(null)
            }
        }
    }

    companion object {
        /** Timeout par défaut pour un fix neuf (8 secondes). */
        const val DEFAULT_TIMEOUT_MS: Long = 8_000L

        /**
         * Âge max d'un `lastKnown` considéré "frais" — au-dessus on tente
         * un nouveau fix avant de retomber sur le lastKnown stale.
         */
        const val FRESH_THRESHOLD_MS: Long = 5 * 60 * 1000L
    }
}
