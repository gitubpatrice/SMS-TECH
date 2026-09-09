package com.filestech.sms.system.notifications

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/**
 * v1.28.3 (F19) — **le correctif de l'audit F38 collisionnait à son tour.**
 *
 * F38 avait remplacé `msgId.toInt().or(1)`, qui confondait un message sur deux, par
 * `hash or BASE_TAG`. Le remède reproduisait le défaut sous une autre forme : `or 0x10000` force
 * le bit 16, donc `1` et `65537` rendaient tous deux `65537`.
 *
 * L'enjeu n'est pas l'affichage — la notification porte un tag de conversation qui borne la
 * collision — mais le fait que cet identifiant sert de `requestCode` aux `PendingIntent` des
 * actions. `PendingIntent.filterEquals` ignorant les extras, deux actions partageant leur
 * `requestCode` sont le MÊME `PendingIntent`, et `FLAG_UPDATE_CURRENT` réécrit les extras du
 * premier. `NotificationActionReceiver` y lit l'adresse du destinataire : une réponse tapée
 * depuis l'ancienne notification pouvait partir à quelqu'un d'autre.
 *
 * Le second volet du correctif — un `data` distinct par action, qui fait porter l'identité par
 * `filterEquals` lui-même plutôt que par le `requestCode` — n'est pas testable ici : il vit dans
 * la construction des `Intent`, qui demande un `Context`. Il est vérifié par lecture.
 */
class NotificationIdTest {

    /** La collision exacte citée par la relecture externe. */
    @Test
    fun `les identifiants 1 et 65537 ne se confondent plus`() {
        assertThat(notificationIdFor(1L)).isNotEqualTo(notificationIdFor(65_537L))
    }

    /**
     * Généralisation : le défaut ne portait pas sur une paire particulière mais sur TOUTE paire
     * `(n, n xor 0x10000)`. Le vérifier sur une seule paire laisserait croire à un cas isolé.
     */
    @Test
    fun `aucune paire separee par le bit 16 ne se confond`() {
        for (n in longArrayOf(1L, 2L, 7L, 100L, 4_242L, 99_999L)) {
            assertThat(notificationIdFor(n)).isNotEqualTo(notificationIdFor(n xor 0x10000L))
        }
    }

    /**
     * Contrôle sur le domaine réel. Les identifiants Room sont séquentiels et commencent à 1 :
     * sur toute une plage d'usage, aucun doublon ne doit apparaître.
     */
    @Test
    fun `aucun doublon sur les premiers identifiants Room`() {
        val ids = (1L..200_000L).map(::notificationIdFor)
        assertThat(ids.toSet()).hasSize(ids.size)
    }

    /**
     * La raison d'être du `or` d'origine : un identifiant de notification nul serait écarté par
     * le système. Elle reste tenue — mais pour le seul cas qui la justifie, au lieu d'amputer
     * tous les autres.
     */
    @Test
    fun `aucun identifiant n est nul`() {
        assertThat(notificationIdFor(0L)).isEqualTo(NOTIFICATION_ID_FALLBACK)
        assertThat(notificationIdFor(0L)).isNotEqualTo(0)
        for (n in longArrayOf(1L, 65_536L, 65_537L, Long.MAX_VALUE)) {
            assertThat(notificationIdFor(n)).isNotEqualTo(0)
        }
    }

    /** Stable : deux appels pour le même message rendent le même identifiant. */
    @Test
    fun `l identifiant est stable`() {
        assertThat(notificationIdFor(4_242L)).isEqualTo(notificationIdFor(4_242L))
    }
}
