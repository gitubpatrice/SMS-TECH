package com.filestech.sms.data.backup

import com.filestech.sms.data.local.db.entity.MessageEntity
import com.filestech.sms.domain.model.MessageDirection
import com.filestech.sms.domain.model.MessageStatus
import com.filestech.sms.domain.model.MessageType
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/**
 * v1.27.11 — verrouille le constat 3 de la revue externe GitLab !38458.
 *
 * **Le defaut** : la restauration remappait les identifiants Room et laissait passer
 * `telephonyUri`, `mmsSystemId` et `subId`, qui designent des lignes du telephone SOURCE. Sur un
 * autre appareil, `content://sms/42` est un autre message — d'ou deux consequences, l'une
 * silencieuse et l'autre destructrice :
 *
 *  - a la restauration, l'index `UNIQUE(telephony_uri)` et le `OnConflictStrategy.IGNORE`
 *    faisaient entrer en collision deux messages sans rapport. Celui de la sauvegarde etait
 *    ecarte sans un mot, expediteur et contenu differents compris ;
 *  - a la suppression, cet URI partait tel quel au fournisseur du systeme de la destination, qui
 *    pouvait effacer la ligne de quelqu'un d'autre.
 *
 * La garde est posee sur [BackupService.toLocalRow] et non sur le corps de `readSmsbk` : c'est
 * la decision elle-meme, et elle se verifie ici sans Room, sans SQLCipher et sans appareil.
 */
class RestoreBindingsTest {

    private val fromSourcePhone = MessageEntity(
        id = 4_242L,
        conversationId = 7L,
        telephonyUri = "content://sms/42",
        address = "+33612345678",
        body = "Message du telephone source",
        type = MessageType.SMS,
        direction = MessageDirection.INCOMING,
        date = 1_700_000_000_000L,
        dateSent = 1_700_000_000_000L,
        read = true,
        starred = true,
        status = MessageStatus.SENT,
        errorCode = null,
        subId = 3,
        scheduledAt = null,
        attachmentsCount = 2,
        replyToMessageId = 4_241L,
        mmsSystemId = 99L,
    )

    @Test
    fun `a backup from another device does not bring its provider and SIM bindings`() {
        val restored = BackupService.toLocalRow(fromSourcePhone, conversationId = 12L, sameDevice = false)

        assertThat(restored.telephonyUri).isNull()
        assertThat(restored.mmsSystemId).isNull()
        assertThat(restored.subId).isNull()
    }

    @Test
    fun `a backup from this device keeps them`() {
        // Controle positif, et il porte : couper TOUJOURS passerait le test precedent tout en
        // faisant re-importer chaque message en double a la resynchronisation suivante — la
        // reinstallation puis restauration sur le meme telephone est le cas d'usage courant.
        val restored = BackupService.toLocalRow(fromSourcePhone, conversationId = 12L, sameDevice = true)

        assertThat(restored.telephonyUri).isEqualTo("content://sms/42")
        assertThat(restored.mmsSystemId).isEqualTo(99L)
        assertThat(restored.subId).isEqualTo(3)
    }

    @Test
    fun `the message itself survives the crossing`() {
        // Ce que couper les liaisons ne doit PAS emporter : sans cette assertion, une
        // implementation qui viderait la ligne entiere passerait le premier test.
        val restored = BackupService.toLocalRow(fromSourcePhone, conversationId = 12L, sameDevice = false)

        assertThat(restored.body).isEqualTo("Message du telephone source")
        assertThat(restored.address).isEqualTo("+33612345678")
        assertThat(restored.date).isEqualTo(1_700_000_000_000L)
        assertThat(restored.direction).isEqualTo(MessageDirection.INCOMING)
        assertThat(restored.starred).isTrue()
    }

    @Test
    fun `the local identifiers are remapped as before`() {
        // Invariants anterieurs (v1.15.2 SECU-M4 et v1.26.1 M7), figes ici parce que la
        // v1.27.11 les a deplaces dans une fonction : un deplacement ne doit rien perdre.
        val restored = BackupService.toLocalRow(fromSourcePhone, conversationId = 12L, sameDevice = true)

        assertThat(restored.id).isEqualTo(0L)
        assertThat(restored.conversationId).isEqualTo(12L)
        assertThat(restored.replyToMessageId).isNull()
        assertThat(restored.attachmentsCount).isEqualTo(0)
    }
}
