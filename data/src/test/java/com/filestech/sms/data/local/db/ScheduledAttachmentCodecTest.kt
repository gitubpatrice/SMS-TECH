package com.filestech.sms.data.local.db

import com.filestech.sms.domain.usecase.SendMediaMmsUseCase.AttachmentPayload
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test
import java.io.File

/**
 * v1.26.0 — ce codec porte les pièces jointes d'un envoi **différé** : ce qu'il perd est perdu
 * pour de bon, et l'utilisateur ne s'en apercevra qu'à l'échéance, quand le MMS partira amputé.
 *
 * La colonne `attachments_json` existait depuis la création de la table sans jamais être écrite —
 * personne n'avait donc jamais éprouvé ce format.
 */
class ScheduledAttachmentCodecTest {

    private fun payload(path: String, mime: String = "image/jpeg") =
        AttachmentPayload(file = File(path), mimeType = mime)

    @Test
    fun `un aller-retour preserve chemin type et metriques`() {
        val original = listOf(
            AttachmentPayload(
                file = File("/data/user/0/pkg/files/mms_attachments/out-1.jpg"),
                mimeType = "image/jpeg",
                width = 1024,
                height = 768,
            ),
            AttachmentPayload(
                file = File("/data/user/0/pkg/files/mms_attachments/out-2.amr"),
                mimeType = "audio/amr",
                durationMs = 4200L,
            ),
        )

        val decoded = ScheduledAttachmentCodec.decode(ScheduledAttachmentCodec.encode(original))

        assertThat(decoded).hasSize(2)
        // `encode` ecrit `absolutePath` : on compare donc a la meme forme. Sur Android les deux
        // coincident (les chemins y sont deja absolus) ; sur JVM Windows, `absolutePath` prefixe
        // la lettre de lecteur, ce qui ferait echouer une comparaison sur `path`.
        assertThat(decoded[0].file).isEqualTo(File(original[0].file.absolutePath))
        assertThat(decoded[0].mimeType).isEqualTo("image/jpeg")
        assertThat(decoded[0].width).isEqualTo(1024)
        assertThat(decoded[0].height).isEqualTo(768)
        assertThat(decoded[0].durationMs).isNull()
        assertThat(decoded[1].durationMs).isEqualTo(4200L)
        assertThat(decoded[1].width).isNull()
    }

    @Test
    fun `un chemin contenant le separateur survit`() {
        // C'est la raison d'etre de l'ordre des champs : le chemin est place en DERNIER et le
        // decoupage est borne, donc un `|` dans le nom de fichier ne casse rien. Avec le chemin
        // en premier, cette piece jointe aurait ete tronquee — donc introuvable a l'echeance.
        val original = listOf(payload("/data/user/0/pkg/files/mms_attachments/wei|rd name.jpg"))

        val decoded = ScheduledAttachmentCodec.decode(ScheduledAttachmentCodec.encode(original))

        assertThat(decoded).hasSize(1)
        assertThat(decoded[0].file).isEqualTo(File(original[0].file.absolutePath))
    }

    @Test
    fun `une liste vide ne produit aucune colonne`() {
        // `null` et non chaine vide : la colonne doit rester nulle pour qu'un envoi texte reste
        // indiscernable d'avant, et que `decode` le rende bien sans piece jointe.
        assertThat(ScheduledAttachmentCodec.encode(emptyList())).isNull()
        assertThat(ScheduledAttachmentCodec.decode(null)).isEmpty()
        assertThat(ScheduledAttachmentCodec.decode("")).isEmpty()
        assertThat(ScheduledAttachmentCodec.decode("   ")).isEmpty()
    }

    @Test
    fun `une ligne malformee est ignoree sans emporter les autres`() {
        // Tolerance deliberee : une ligne illisible ne doit pas rendre tout l'envoi illisible.
        // 4 separateurs = 5 champs. La ligne du milieu n'en a aucun : elle est rejetee.
        val raw = "image/jpeg||||/a/b.jpg\nligne-cassee\nimage/png||||/c/d.png"

        val decoded = ScheduledAttachmentCodec.decode(raw)

        // Les deux lignes valides survivent, celle du milieu est ecartee : une ligne illisible ne
        // doit emporter ni celle d'avant ni celle d'apres.
        assertThat(decoded).hasSize(2)
        assertThat(decoded.map { it.file }).containsExactly(File("/a/b.jpg"), File("/c/d.png")).inOrder()
    }

    @Test
    fun `une ligne sans chemin est ignoree`() {
        assertThat(ScheduledAttachmentCodec.decode("image/jpeg|1|2|3|")).isEmpty()
        assertThat(ScheduledAttachmentCodec.decode("image/jpeg|1|2|3|   ")).isEmpty()
    }

    @Test
    fun `un type absent retombe sur un type generique plutot que vide`() {
        // Un mime vide ferait rejeter la piece jointe par la couche MMS : mieux vaut un type
        // generique, que le transporteur saura au moins acheminer.
        val decoded = ScheduledAttachmentCodec.decode("||||/a/b.bin")

        assertThat(decoded).hasSize(1)
        assertThat(decoded[0].mimeType).isEqualTo("application/octet-stream")
    }

    @Test
    fun `le separateur est neutralise dans le type`() {
        // Le type vient de l'exterieur (selecteur systeme) : s'il contenait un `|`, il decalerait
        // tous les champs et le chemin deviendrait introuvable.
        val encoded = ScheduledAttachmentCodec.encode(listOf(payload("/a/b.jpg", mime = "image|jpeg")))

        val decoded = ScheduledAttachmentCodec.decode(encoded)

        assertThat(decoded).hasSize(1)
        assertThat(decoded[0].file).isEqualTo(File(File("/a/b.jpg").absolutePath))
        assertThat(decoded[0].mimeType).doesNotContain("|")
    }
}
