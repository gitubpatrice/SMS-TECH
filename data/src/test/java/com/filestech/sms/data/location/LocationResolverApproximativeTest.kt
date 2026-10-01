package com.filestech.sms.data.location

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import com.google.common.truth.Truth.assertThat
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

/**
 * v1.28.13 — **la position approximative accordée donne une position, avec sa marge.**
 *
 * Mesuré sur émulateur API 34 : à « Approximative » dans la boîte de dialogue d'Android 12+, seule
 * `ACCESS_COARSE_LOCATION` est accordée. Le résolveur ne testait que `ACCESS_FINE_LOCATION` et
 * rendait `null` : le SMS d'urgence partait « (position non disponible) » alors que l'utilisateur
 * avait accepté de donner sa position. Et même ce test passé, la demande GPS lève
 * `SecurityException` sans la position exacte, dans le même `try` que la demande réseau, qui
 * n'était donc jamais faite.
 *
 * Les stubs `android.jar` rendent 0 pour `Process.myPid()` (`isReturnDefaultValues`) : on répond à
 * `Context.checkPermission`, ce qu'appelle `ContextCompat.checkSelfPermission`.
 */
class LocationResolverApproximativeTest {

    private fun contexte(fine: Boolean, coarse: Boolean, lm: LocationManager): Context = mockk {
        every { checkPermission(Manifest.permission.ACCESS_FINE_LOCATION, any(), any()) } returns
            if (fine) PackageManager.PERMISSION_GRANTED else PackageManager.PERMISSION_DENIED
        every { checkPermission(Manifest.permission.ACCESS_COARSE_LOCATION, any(), any()) } returns
            if (coarse) PackageManager.PERMISSION_GRANTED else PackageManager.PERMISSION_DENIED
        every { getSystemService(Context.LOCATION_SERVICE) } returns lm
    }

    /** Position réseau toute fraîche, décalée comme Android le fait pour une position approximative. */
    private fun positionReseau(): Location = mockk {
        every { latitude } returns 48.85661
        every { longitude } returns 2.35222
        every { time } returns System.currentTimeMillis()
        every { hasAccuracy() } returns true
        every { accuracy } returns 2_000f
        every { provider } returns LocationManager.NETWORK_PROVIDER
    }

    private fun gestionnaire(): LocationManager = mockk {
        every { getLastKnownLocation(LocationManager.NETWORK_PROVIDER) } returns positionReseau()
        // Ce que fait Android sans la position exacte.
        every { getLastKnownLocation(LocationManager.GPS_PROVIDER) } throws
            SecurityException("\"gps\" location provider requires ACCESS_FINE_LOCATION permission.")
    }

    @Test
    fun `approximative seule - la position reseau est rendue, avec sa precision`() = runTest {
        val lm = gestionnaire()
        val resolveur = LocationResolver(contexte(fine = false, coarse = true, lm = lm))

        val position = resolveur.resolveLocation()

        assertThat(position).isNotNull()
        assertThat(position!!.latitude).isEqualTo(48.85661)
        assertThat(position.precisionMetres).isEqualTo(2_000f)
        // Le GPS n'est pas interrogé : il exige la position exacte.
        verify(exactly = 0) { lm.getLastKnownLocation(LocationManager.GPS_PROVIDER) }
    }

    @Test
    fun `aucune permission - aucune position, et rien n'est interroge`() = runTest {
        val lm = gestionnaire()
        val resolveur = LocationResolver(contexte(fine = false, coarse = false, lm = lm))

        assertThat(resolveur.resolveLocation()).isNull()
        verify(exactly = 0) { lm.getLastKnownLocation(any()) }
    }
}
