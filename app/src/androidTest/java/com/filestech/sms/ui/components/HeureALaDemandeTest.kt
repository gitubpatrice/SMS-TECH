package com.filestech.sms.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.platform.app.InstrumentationRegistry
import com.filestech.sms.R
import com.filestech.sms.data.voice.VoicePlaybackController
import com.filestech.sms.domain.model.Attachment
import com.filestech.sms.domain.model.Message
import com.filestech.sms.ui.screens.thread.HeuresRevelees
import com.filestech.sms.ui.screens.thread.actionHeure
import com.filestech.sms.ui.screens.thread.ligneHeureDOffice
import com.filestech.sms.ui.screens.thread.surToucherBulle
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test

/**
 * 2026-11-12 (issue #87) — **l'heure et l'état d'un message, à la demande** : un toucher sur une bulle de texte, ou
 * l'entrée du menu ⋮ des TROIS bulles (texte, vocal, photo), chacune la recevant par son propre câblage.
 *
 * Chaque montage reprend le câblage de `ThreadMessageItem` — mêmes fonctions [ligneHeureDOffice], [actionHeure] et
 * [surToucherBulle], état hissé de la même façon — avec un message AU MILIEU d'une série d'envois : sa ligne ne
 * s'affiche pas d'office, c'est exactement le cas de l'issue.
 */
class HeureALaDemandeTest {

    @get:Rule val compose = createComposeRule()

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    private fun etat(res: Int) = "• " + context.getString(res)

    /** Le nombre de lignes « heure • [res] » affichées. */
    private fun lignes(res: Int) = compose.onAllNodesWithText(etat(res), substring = true).fetchSemanticsNodes().size

    /**
     * La bulle, ses voisines d'une série d'envois, et l'état « révélée » tenu comme dans `ThreadScreen` : un
     * [HeuresRevelees] au-dessus de l'élément, lu pendant la composition — c'est sa lecture qui doit relancer la
     * composition à chaque bascule.
     */
    @Composable
    private fun Monte(msg: Message, relancer: () -> Unit = {}, bulle: @Composable (Boolean, ActionHeure?, () -> Unit) -> Unit) {
        val heures = remember { HeuresRevelees() }
        val revelee = heures.estRevelee(msg.id)
        val dOffice = ligneHeureDOffice(msg, voisin(msg, -MINUTE), voisin(msg, +MINUTE))
        val action = remember(dOffice, revelee) { actionHeure(dOffice, revelee) { heures.basculer(msg.id) } }
        bulle(dOffice || revelee, action) { surToucherBulle(msg, action, relancer) }
    }

    private fun voisin(msg: Message, decalage: Long) = msg.copy(id = msg.id + decalage, date = msg.date + decalage)

    /**
     * Une bulle de texte montée comme en production : `ThreadMessageItem` lui passe `onSelectText` dès que le corps
     * n'est pas vide, donc le toucher passe par `combinedClickable`. [avecSelection] à `false` couvre l'autre branche,
     * `clickable`, celle d'une bulle sans texte à sélectionner.
     */
    @Composable
    private fun BulleTexte(status: Message.Status, avecSelection: Boolean = true, relancer: () -> Unit = {}) {
        val msg = texte(status)
        Monte(msg, relancer) { ligne, action, toucher ->
            MessageBubble(
                message = msg,
                showTimestamp = ligne,
                onTap = toucher,
                onSelectText = if (avecSelection) { {} } else null,
                actionHeure = action,
            )
        }
    }

    @Test
    fun unToucherAfficheLHeureEtLEtatPuisLesMasque() {
        compose.setContent { BulleTexte(Message.Status.DELIVERED) }
        assertThat(lignes(R.string.thread_status_delivered)).isEqualTo(0)

        compose.onNodeWithText(BODY).performClick()
        compose.waitForIdle()
        assertThat(lignes(R.string.thread_status_delivered)).isEqualTo(1)

        compose.onNodeWithText(BODY).performClick()
        compose.waitForIdle()
        assertThat(lignes(R.string.thread_status_delivered)).isEqualTo(0)
    }

    @Test
    fun leToucherPorteUnNomPourTalkBack() = nomPourTalkBack(avecSelection = true)

    @Test
    fun leToucherPorteUnNomPourTalkBack_bulleSansSelection() = nomPourTalkBack(avecSelection = false)

    private fun nomPourTalkBack(avecSelection: Boolean) {
        compose.setContent { BulleTexte(Message.Status.DELIVERED, avecSelection) }
        assertThat(nomDuToucher()).isEqualTo(context.getString(R.string.action_show_time))

        compose.onNodeWithText(BODY).performClick()
        compose.waitForIdle()
        assertThat(nomDuToucher()).isEqualTo(context.getString(R.string.action_hide_time))
    }

    @Test
    fun unEchecRelanceAuToucherEtSonEtatResteAffiche() {
        var relances = 0
        compose.setContent { BulleTexte(Message.Status.FAILED, relancer = { relances++ }) }
        val echec = R.string.thread_status_failed
        assertThat(lignes(echec)).isEqualTo(1)
        // Pas de nom « afficher l'heure » sur un toucher qui relance.
        assertThat(nomDuToucher()).isNull()

        compose.onNodeWithText(BODY).performClick()
        compose.waitForIdle()

        assertThat(relances).isEqualTo(1)
        assertThat(lignes(echec)).isEqualTo(1)
    }

    @Test
    fun leMenuAfficheLHeure_bulleDeTexte() {
        compose.setContent { BulleTexte(Message.Status.DELIVERED) }
        afficherParLeMenu()
        assertThat(lignes(R.string.thread_status_delivered)).isEqualTo(1)
    }

    @Test
    fun leMenuAfficheLHeure_messageVocal() {
        val vocal = mms(Attachment(10L, 7L, "audio/mp4", "vocal.m4a", 1_000L, "/inexistant/vocal.m4a", null, null, 3_000L))
        compose.setContent {
            Monte(vocal) { ligne, action, _ ->
                AudioMessageBubble(
                    message = vocal,
                    audio = vocal.attachments.single(),
                    playbackProvider = { VoicePlaybackController.PlaybackState() },
                    onTogglePlay = {},
                    onSeekTo = {},
                    showTimestamp = ligne,
                    actionHeure = action,
                )
            }
        }
        assertThat(lignes(R.string.thread_status_sent)).isEqualTo(0)
        afficherParLeMenu()
        assertThat(lignes(R.string.thread_status_sent)).isEqualTo(1)
    }

    @Test
    fun leMenuAfficheLHeure_photo() {
        val photo = mms(Attachment(11L, 7L, "image/jpeg", "photo.jpg", 1_000L, "/inexistant/photo.jpg", 10, 10, null))
        compose.setContent {
            Monte(photo) { ligne, action, _ ->
                MediaAttachmentBubble(
                    message = photo,
                    attachment = photo.attachments.single(),
                    onOuvrirPieceJointe = {},
                    showTimestamp = ligne,
                    onDelete = {},
                    onReply = {},
                    onReact = null,
                    onRemoveReaction = {},
                    actionHeure = action,
                )
            }
        }
        assertThat(lignes(R.string.thread_status_sent)).isEqualTo(0)
        afficherParLeMenu()
        assertThat(lignes(R.string.thread_status_sent)).isEqualTo(1)
    }

    private fun afficherParLeMenu() {
        compose.onNodeWithContentDescription(context.getString(R.string.action_message_actions)).performClick()
        compose.onNodeWithText(context.getString(R.string.action_show_time)).performClick()
        compose.waitForIdle()
    }

    private fun nomDuToucher(): String? =
        compose.onNodeWithText(BODY).fetchSemanticsNode().config.getOrNull(SemanticsActions.OnClick)?.label

    private companion object {
        const val BODY = "Test 2"
        const val MINUTE = 60_000L
        const val DATE = 1_794_393_840_000L

        fun texte(status: Message.Status) = Message(
            id = 7L,
            conversationId = 1L,
            address = "+33612345678",
            body = BODY,
            type = Message.Type.SMS,
            direction = Message.Direction.OUTGOING,
            date = DATE,
            dateSent = null,
            read = true,
            starred = false,
            status = status,
            errorCode = null,
            attachmentsCount = 0,
            subId = null,
            scheduledAt = null,
        )

        fun mms(piece: Attachment) = texte(Message.Status.SENT).copy(
            body = "",
            type = Message.Type.MMS,
            attachmentsCount = 1,
            attachments = listOf(piece),
        )
    }
}
