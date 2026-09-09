package com.filestech.sms.data.sms

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/**
 * v1.28.3 (F23) — **deux tentatives d'un même message partageaient une seule adresse de retour.**
 *
 * Un `PendingIntent` s'identifie par son action, son composant, ses `data` et son `requestCode` —
 * `filterEquals` **ignore les extras**. Une relance réutilisant le même id Room, la tentative 2
 * retrouvait donc l'objet de la tentative 1, `FLAG_UPDATE_CURRENT` se contentant d'en réécrire
 * les extras. Rien, dans l'accusé qui revenait, ne disait de quelle tentative il provenait.
 *
 * La conséquence était durable et du mauvais côté : la relance rétrograde la ligne en `PENDING`,
 * si bien qu'un `FAILED` tardif de la tentative précédente y écrivait `3`, sommet de l'échelle
 * monotone, que le succès réel de la nouvelle tentative ne pouvait **plus jamais** promouvoir.
 * Bulle rouge définitive sur un message bel et bien reçu.
 *
 * Les trois séparations doivent tenir **ensemble** : par tentative (le correctif), par partie
 * (audit F36) et par message. En vérifier une seule laisserait passer un remède qui casse les
 * deux autres — c'est exactement ce que F19 a montré sur les notifications, où le correctif d'un
 * audit avait remplacé une collision par une autre.
 */
class SmsTrackingRequestCodeTest {

    private val sent = SmsSenderImpl.ACTION_SMS_SENT
    private val delivered = SmsSenderImpl.ACTION_SMS_DELIVERED

    @Test
    fun `deux tentatives du meme message ne partagent plus leur code`() {
        val t0 = smsTrackingRequestCode(sent, localId = 42L, partIndex = 0, attempt = 0)
        val t1 = smsTrackingRequestCode(sent, localId = 42L, partIndex = 0, attempt = 1)

        assertThat(t0).isNotEqualTo(t1)
    }

    /**
     * Généralisation : le défaut ne portait pas sur la seule paire (0, 1). Une relance peut
     * suivre une relance, et rien ne borne le nombre de tentatives d'un message.
     */
    @Test
    fun `aucune paire de tentatives ne se confond sur les vingt premieres`() {
        val codes = (0..19).map { smsTrackingRequestCode(sent, 42L, partIndex = 0, attempt = it) }

        assertThat(codes.toSet()).hasSize(codes.size)
    }

    /** Contrôle : la séparation par partie posée par l'audit F36 ne doit pas tomber. */
    @Test
    fun `les parties d un meme envoi restent distinctes`() {
        val codes = (0..9).map { smsTrackingRequestCode(sent, 42L, partIndex = it, attempt = 3) }

        assertThat(codes.toSet()).hasSize(codes.size)
    }

    /**
     * Contrôle : la séparation par message aussi, y compris au-delà de `Int.MAX_VALUE` — c'est
     * le cas que F36 visait, le `requestCode` étant un `Int`.
     */
    @Test
    fun `les messages restent distincts meme au dela de Int MAX_VALUE`() {
        val ids = listOf(1L, 2L, 1_000L, Int.MAX_VALUE.toLong(), Int.MAX_VALUE + 1L, 9_000_000_000L)

        val codes = ids.map { smsTrackingRequestCode(sent, it, partIndex = 0, attempt = 0) }

        assertThat(codes.toSet()).hasSize(codes.size)
    }

    /** Contrôle : accusé d'envoi et accusé de réception ne se confondent pas non plus. */
    @Test
    fun `les deux actions de suivi restent distinctes`() {
        assertThat(smsTrackingRequestCode(sent, 42L, 0, 2))
            .isNotEqualTo(smsTrackingRequestCode(delivered, 42L, 0, 2))
    }

    /**
     * Contrôle POSITIF, sans lequel les précédents ne prouveraient rien : un code qui serait
     * aléatoire les passerait tous, et le `PendingIntent` d'une tentative en cours deviendrait
     * introuvable à chaque reconstruction. Le code doit être **stable**.
     */
    @Test
    fun `le code est stable pour un meme quadruplet`() {
        assertThat(smsTrackingRequestCode(sent, 42L, 1, 2))
            .isEqualTo(smsTrackingRequestCode(sent, 42L, 1, 2))
    }

    /** `PendingIntent.getBroadcast` refuse un `requestCode` négatif sur certaines pistes OEM. */
    @Test
    fun `le code n est jamais negatif`() {
        for (id in longArrayOf(0L, 1L, Long.MAX_VALUE, 9_000_000_000L)) {
            for (attempt in 0..5) {
                assertThat(smsTrackingRequestCode(sent, id, 0, attempt)).isAtLeast(0)
            }
        }
    }
}
