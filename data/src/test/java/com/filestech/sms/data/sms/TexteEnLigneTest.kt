package com.filestech.sms.data.sms

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/**
 * v1.28.9 (audit de cohérence, C2) — **ce que l'import prend pour du texte, et ce qu'il garde en pièce jointe.**
 *
 * Le chemin complet — un MMS réel dans `content://mms`, relu par l'import — a son test instrumenté,
 * `MmsImportVcardTest`. Ici, la règle seule, sur les types qu'un téléphone écrit vraiment.
 */
class TexteEnLigneTest {

    @Test
    fun `la legende et le HTML sont du texte, parametres et casse compris`() {
        for (type in listOf("text/plain", "TEXT/PLAIN", "text/plain; charset=utf-8", "text/html")) {
            assertThat(TelephonyReader.estTexteEnLigne(type)).isTrue()
        }
    }

    @Test
    fun `une carte de visite ou un agenda sont des pieces jointes`() {
        for (type in listOf("text/x-vcard", "text/vcard", "text/x-vCalendar", "text/plainx", "image/jpeg")) {
            assertThat(TelephonyReader.estTexteEnLigne(type)).isFalse()
        }
    }
}
