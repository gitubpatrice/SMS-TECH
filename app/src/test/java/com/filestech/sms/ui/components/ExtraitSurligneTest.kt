package com.filestech.sms.ui.components

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontWeight
import com.filestech.sms.domain.model.MessageSearchHit
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/**
 * v1.28.8 (issue #17) — l'extrait marqué par l'index devient un texte mis en évidence, sans que
 * les marqueurs n'apparaissent jamais à l'écran.
 */
class ExtraitSurligneTest {

    private val gras = SpanStyle(fontWeight = FontWeight.Bold)
    private val d = MessageSearchHit.MARK_START
    private val f = MessageSearchHit.MARK_END

    private fun AnnotatedString.passages() = spanStyles.map { text.substring(it.start, it.end) }

    @Test
    fun `les marqueurs ne sont jamais rendus et encadrent la mise en evidence`() {
        val r = extraitSurligne("Rendez-vous au ${d}café$f demain", gras)

        assertThat(r.text).isEqualTo("Rendez-vous au café demain")
        assertThat(r.passages()).containsExactly("café")
    }

    @Test
    fun `plusieurs passages sont mis en evidence separement`() {
        val r = extraitSurligne("${d}bon${f}jour et ${d}bon${f}soir", gras)

        assertThat(r.text).isEqualTo("bonjour et bonsoir")
        assertThat(r.passages()).containsExactly("bon", "bon").inOrder()
    }

    @Test
    fun `un marqueur orphelin ne casse rien`() {
        // L'extrait a coupé le début du premier passage et la fin du dernier.
        val r = extraitSurligne("…fé$f demain ${d}sui", gras)

        assertThat(r.text).isEqualTo("…fé demain sui")
        assertThat(r.passages()).containsExactly("sui")
    }

    @Test
    fun `sans marqueur le texte est intact et rien n est mis en evidence`() {
        val r = extraitSurligne("Aucun passage", gras)

        assertThat(r.text).isEqualTo("Aucun passage")
        assertThat(r.spanStyles).isEmpty()
    }
}
