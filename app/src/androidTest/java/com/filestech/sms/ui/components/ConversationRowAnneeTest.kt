package com.filestech.sms.ui.components

import android.util.Log
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import com.filestech.sms.domain.model.Conversation
import com.filestech.sms.domain.model.PhoneAddress
import com.filestech.sms.domain.settings.TextScale
import com.filestech.sms.ui.theme.LocalMessageTextFactor
import com.filestech.sms.ui.theme.factor
import com.filestech.sms.ui.util.ChatFormatters
import com.filestech.sms.ui.util.relativeRowLabel
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import java.util.Calendar

/**
 * 2026-11-27 (suggestion d'un utilisateur, par courriel) — la date d'une conversation d'une année antérieure porte
 * son année dans la liste (« 27 mars 2024 »). Plus longue que « 27 mars », elle doit tenir sur UNE ligne, dans la
 * ligne, à côté d'un nom long, à la taille de police d'Android et au réglage de taille du texte de l'app, jusqu'au
 * plus grand des deux : c'est le nom, qui a le poids et l'ellipse, qui cède la place.
 */
class ConversationRowAnneeTest {

    @get:Rule val compose = createComposeRule()

    private val contexte = InstrumentationRegistry.getInstrumentation().targetContext

    /** Le 27 mars d'il y a deux ans, à midi. */
    private val ilYADeuxAns: Long = Calendar.getInstance().apply {
        add(Calendar.YEAR, -2)
        set(Calendar.MONTH, Calendar.MARCH)
        set(Calendar.DAY_OF_MONTH, 27)
        set(Calendar.HOUR_OF_DAY, 12)
    }.timeInMillis

    private val nomLong = "Jean-Baptiste de La Fontaine-Martin"

    private fun conversation(date: Long) = Conversation(
        id = 1L,
        threadId = null,
        addresses = listOf(PhoneAddress.of("+33612345678")),
        displayName = nomLong,
        lastMessageAt = date,
        lastMessagePreview = "À bientôt",
        unreadCount = 0,
        pinned = false,
        archived = false,
        muted = false,
        inVault = false,
        draft = null,
    )

    private fun afficher(policeAndroid: Float, tailleDuTexte: Float) {
        compose.setContent {
            val densite = LocalDensity.current
            CompositionLocalProvider(
                LocalDensity provides Density(densite.density, fontScale = policeAndroid),
                LocalMessageTextFactor provides tailleDuTexte,
            ) {
                Box(Modifier.width(LARGEUR_TELEPHONE_DP.dp)) {
                    ConversationRow(conversation = conversation(ilYADeuxAns), onClick = {})
                }
            }
        }
    }

    private fun verifier(cas: String) {
        val locale = contexte.resources.configuration.locales[0]
        val attendu = ChatFormatters(locale).relativeRowLabel(ilYADeuxAns)
        val annee = Calendar.getInstance().apply { timeInMillis = ilYADeuxAns }.get(Calendar.YEAR).toString()
        assertThat(attendu).contains(annee)

        // L'arbre NON fusionné : la ligne est cliquable et fusionne ses textes en un seul nœud, dont la mise en page
        // lue serait celle du nom (tronqué, donc en débordement) et les bornes celles de toute la ligne.
        val date = compose.onNodeWithText(attendu, useUnmergedTree = true).assertIsDisplayed().fetchSemanticsNode()
        val lignes = mutableListOf<TextLayoutResult>()
        date.config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action?.invoke(lignes)
        val racine = compose.onRoot().fetchSemanticsNode().boundsInRoot
        val nom = compose.onNodeWithText(nomLong, useUnmergedTree = true).assertIsDisplayed()
            .fetchSemanticsNode().boundsInRoot
        Log.i(
            "ConversationRowAnnee",
            "$cas : date « $attendu » largeur ${date.boundsInRoot.width} px, ${lignes.firstOrNull()?.lineCount} " +
                "ligne(s) ; nom largeur ${nom.width} px ; ligne ${racine.width} px",
        )
        // Une seule ligne, qui montre la date jusqu'au dernier caractère, sans ellipse. Pas `hasVisualOverflow` :
        // Compose y compare la largeur du texte (172 px à police normale) à celle de sa mise en page (la contrainte,
        // 608 px), et le dit vrai pour tout texte plus court que sa place (mesuré sur Android 16).
        assertThat(lignes).hasSize(1)
        val miseEnPage = lignes.single()
        assertThat(miseEnPage.lineCount).isEqualTo(1)
        assertThat(miseEnPage.getLineEnd(0, visibleEnd = true)).isEqualTo(attendu.length)
        assertThat(miseEnPage.isLineEllipsized(0)).isFalse()
        assertThat(date.boundsInRoot.right).isAtMost(racine.right)
        assertThat(nom.width).isGreaterThan(0f)
    }

    @Test
    fun policeNormale() {
        afficher(policeAndroid = 1f, tailleDuTexte = 1f)
        verifier("police 1, texte 1")
    }

    @Test
    fun policeAndroidDoublee() {
        afficher(policeAndroid = 2f, tailleDuTexte = 1f)
        verifier("police 2, texte 1")
    }

    @Test
    fun policeAndroidDoublee_etTexteLePlusGrand() {
        val plusGrand = TextScale.entries.maxOf { it.factor() }
        afficher(policeAndroid = 2f, tailleDuTexte = plusGrand)
        verifier("police 2, texte $plusGrand")
    }

    private companion object {
        /** Un petit téléphone en portrait. */
        const val LARGEUR_TELEPHONE_DP = 320
    }
}
