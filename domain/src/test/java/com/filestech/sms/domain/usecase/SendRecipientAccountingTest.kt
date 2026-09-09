package com.filestech.sms.domain.usecase

import com.filestech.sms.core.result.AppError
import com.filestech.sms.core.result.Outcome
import com.filestech.sms.domain.model.MessageStatus
import com.filestech.sms.domain.model.PhoneAddress
import com.filestech.sms.domain.model.SendErrorCode
import com.filestech.sms.domain.settings.AppSettings
import com.filestech.sms.domain.settings.AppSettingsSource
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test

/**
 * v1.28.3 (F21) — **un destinataire écarté ne laissait aucune trace, et l'envoi partiel passait
 * pour un envoi réussi.**
 *
 * La boucle d'envoi sautait un destinataire bloqué par un `continue` muet : ni ligne, ni message,
 * ni compte. On tapait « Envoyer », et le message disparaissait — sans erreur, sans bulle, sans
 * rien. Et la valeur de retour, la seule liste des identifiants remis à la pile, était traitée
 * comme un succès dès qu'elle n'était pas vide : un envoi à trois personnes dont deux échouaient
 * rendait exactement la même chose qu'un envoi parfait.
 *
 * SMS n'ayant pas de vrai groupe, un envoi multiple se scinde en autant de fils individuels : les
 * bulles rouges existaient bien, mais chacune dans la conversation de son destinataire, là où
 * l'expéditeur n'allait pas regarder. Le composeur, lui, effaçait le brouillon et se taisait.
 */
class SendRecipientAccountingTest {

    private companion object {
        const val ALICE = "+33611111111"
        const val BOB = "+33622222222"
        const val CAROL = "+33633333333"
        const val BODY = "On se voit demain ?"
    }

    private class DefaultSettings : AppSettingsSource {
        private val value = AppSettings()
        override val flow: Flow<AppSettings> = flowOf(value)
        override val state: StateFlow<AppSettings> = MutableStateFlow(value)
        override suspend fun hydratedOrNull(): AppSettings = value
        override suspend fun update(transform: (AppSettings) -> AppSettings) = Unit
    }

    private fun send(
        recipients: List<String>,
        blocked: Set<String> = emptySet(),
        sender: RecordingSender = RecordingSender(),
        mirror: NoopMirror = NoopMirror(),
        recorder: RecordingRecorder = RecordingRecorder(),
    ): Outcome<com.filestech.sms.domain.model.SendReport> = runBlocking {
        sendSmsUseCase(
            settings = DefaultSettings(),
            sender = sender,
            recorder = recorder,
            mirror = mirror,
            blocked = BlockedList(blocked),
        ).invoke(recipients.map { PhoneAddress.of(it) }, BODY)
    }

    // ───────────────────── Le destinataire bloqué laisse une trace ─────────────────────

    @Test
    fun `un destinataire bloque produit une ligne locale en echec`() {
        val mirror = NoopMirror()

        send(listOf(ALICE), blocked = setOf(ALICE), mirror = mirror)

        assertThat(mirror.lignes.map { it.adresse }).containsExactly(ALICE)
        assertThat(mirror.statuts).hasSize(1)
        assertThat(mirror.statuts.single().second).isEqualTo(MessageStatus.FAILED)
        assertThat(mirror.statuts.single().third).isEqualTo(SendErrorCode.RECIPIENT_BLOCKED)
    }

    /**
     * Rien ne part sur le réseau, donc **rien ne doit être écrit chez le fournisseur système** :
     * une ligne « envoyée » y serait visible de toutes les autres applications SMS de l'appareil,
     * et prétendrait un envoi qui n'a jamais eu lieu. La ligne locale, elle, est nécessaire — sans
     * elle l'utilisateur ne sait pas qu'il a écrit à quelqu'un qu'il avait bloqué.
     */
    @Test
    fun `un destinataire bloque n ecrit rien chez le fournisseur systeme`() {
        val recorder = RecordingRecorder()
        val sender = RecordingSender()

        send(listOf(ALICE), blocked = setOf(ALICE), sender = sender, recorder = recorder)

        assertThat(recorder.adressesEcrites).isEmpty()
        assertThat(sender.callCount).isEqualTo(0)
    }

    // ───────────────────── L'envoi partiel se distingue de l'envoi complet ─────────────

    @Test
    fun `le rapport distingue ce qui est parti de ce qui a ete bloque`() {
        val out = send(listOf(ALICE, BOB, CAROL), blocked = setOf(BOB))

        val rapport = (out as Outcome.Success).value
        assertThat(rapport.dispatched).hasSize(2)
        assertThat(rapport.blocked.map { it.raw }).containsExactly(BOB)
        assertThat(rapport.failed).isEmpty()
        assertThat(rapport.isComplete).isFalse()
        assertThat(rapport.refusedCount).isEqualTo(1)
    }

    /**
     * Contrôle POSITIF indispensable : sans lui, un correctif qui déclarerait TOUT envoi partiel
     * passerait le test précédent, et le composeur crierait à l'échec après chaque message.
     */
    @Test
    fun `un envoi qui atteint tout le monde est complet`() {
        val out = send(listOf(ALICE, BOB))

        val rapport = (out as Outcome.Success).value
        assertThat(rapport.dispatched).hasSize(2)
        assertThat(rapport.isComplete).isTrue()
        assertThat(rapport.refusedCount).isEqualTo(0)
    }

    /** Un refus de la pile téléphonie est compté à part d'un blocage : les causes diffèrent. */
    @Test
    fun `un refus de la pile est compte comme echec et non comme blocage`() {
        val out = send(
            listOf(ALICE, BOB),
            blocked = setOf(BOB),
            sender = RecordingSender(outcome = radioFailure()),
        )

        // ALICE a été remise à la pile, qui a refusé ; BOB n'a jamais été tentée.
        assertThat(out).isInstanceOf(Outcome.Failure::class.java)
        assertThat((out as Outcome.Failure).error).isInstanceOf(AppError.Telephony::class.java)
    }

    // ───────────────────── L'échec total dit pourquoi ─────────────────────

    /**
     * Le cœur du volet « sans trace » : quand tous les destinataires sont bloqués, l'échec
     * rendu était `AppError.Telephony("no message dispatched")`. L'utilisateur lisait un
     * problème de réseau là où il n'y avait qu'une règle qu'il avait lui-même posée, et
     * attendait donc que « ça repasse ».
     */
    @Test
    fun `tous les destinataires bloques rend une erreur de blocage et non de telephonie`() {
        val out = send(listOf(ALICE, BOB), blocked = setOf(ALICE, BOB))

        assertThat(out).isInstanceOf(Outcome.Failure::class.java)
        assertThat((out as Outcome.Failure).error).isEqualTo(AppError.RecipientBlocked)
    }

    /** Et l'inverse reste vrai : une panne de la pile n'est pas un blocage. */
    @Test
    fun `un echec total de la pile reste une erreur de telephonie`() {
        val out = send(listOf(ALICE), sender = RecordingSender(outcome = radioFailure()))

        assertThat((out as Outcome.Failure).error).isInstanceOf(AppError.Telephony::class.java)
    }
}
