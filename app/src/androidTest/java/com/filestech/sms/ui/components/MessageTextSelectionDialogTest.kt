package com.filestech.sms.ui.components

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.ComposeFoundationFlags
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalTextToolbar
import androidx.compose.ui.platform.TextToolbar
import androidx.compose.ui.platform.TextToolbarStatus
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.test.platform.app.InstrumentationRegistry
import com.filestech.sms.ui.security.EXTRA_IS_SENSITIVE
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * v1.28.6 — la sélection libre d'un extrait copie **par le menu système**, donc hors de
 * [com.filestech.sms.ui.security.copyToClipboardSensitive]. Ce test prouve deux choses :
 *
 *  1. un appui long sélectionne un extrait du corps et le menu système se présente avec une
 *     action « copier » ;
 *  2. cette copie est marquée **sensible** (invariant N4), c'est-à-dire que `SelectionContainer`
 *     passe bien par `LocalClipboard`, que l'enveloppe [com.filestech.sms.ui.security
 *     .SensitiveClipboard] intercepte. Sans l'enveloppe, la marque est absente et le test rougit
 *     (contrôle négatif fait le 2026-09-12).
 *
 * La barre d'outils système est remplacée par une factice qui capture l'action « copier » : on ne
 * peut pas taper sur le vrai menu flottant depuis un test Compose. Le presse-papiers, lui, est le
 * VRAI service système : c'est sa description qu'on lit.
 */
class MessageTextSelectionDialogTest {

    @get:Rule val compose = createComposeRule()

    private val context: Context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val clipboard: ClipboardManager
        get() = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager

    private class FakeToolbar : TextToolbar {
        var onCopy: (() -> Unit)? = null
        override var status: TextToolbarStatus = TextToolbarStatus.Hidden
        override fun showMenu(
            rect: Rect,
            onCopyRequested: (() -> Unit)?,
            onPasteRequested: (() -> Unit)?,
            onCutRequested: (() -> Unit)?,
            onSelectAllRequested: (() -> Unit)?,
        ) {
            onCopy = onCopyRequested
            status = TextToolbarStatus.Shown
        }
        override fun hide() { status = TextToolbarStatus.Hidden }
    }

    private companion object {
        const val BODY = "Votre code de connexion est 481 516 et il expire dans dix minutes."
        const val WAIT_MS = 5_000L
    }

    private var nouveauMenuInitial = true

    @Before
    fun videLePressePapiers() {
        compose.runOnUiThread { clipboard.setPrimaryClip(ClipData.newPlainText("vide", "")) }
        // Foundation 1.11 affiche la barre via son nouveau menu contextuel (drapeau vrai par
        // défaut), qui ne passe plus par `LocalTextToolbar` : la factice ne verrait rien. Le
        // drapeau ne change que l'AFFICHAGE de la barre ; la copie, elle, suit le même chemin
        // (`SelectionManager.copy` → `LocalClipboard`), qui est ce que ce test mesure.
        nouveauMenuInitial = ComposeFoundationFlags.isNewContextMenuEnabled
        ComposeFoundationFlags.isNewContextMenuEnabled = false
    }

    @After
    fun restaureLeDrapeau() {
        ComposeFoundationFlags.isNewContextMenuEnabled = nouveauMenuInitial
    }

    @Test
    fun unAppuiLongSelectionneUnExtraitEtLaCopieEstMarqueeSensible() {
        val toolbar = FakeToolbar()
        compose.setContent {
            CompositionLocalProvider(LocalTextToolbar provides toolbar) {
                MessageTextSelectionContent(BODY)
            }
        }

        compose.onNodeWithTag(MESSAGE_TEXT_SELECTION_TAG).performTouchInput { longClick(center) }
        compose.waitUntil(WAIT_MS) { toolbar.onCopy != null }

        compose.runOnUiThread { toolbar.onCopy!!.invoke() }
        compose.waitUntil(WAIT_MS) {
            clipboard.primaryClip?.getItemAt(0)?.text?.isNotBlank() == true
        }

        val clip = clipboard.primaryClip!!
        val copie = clip.getItemAt(0).text.toString()
        assertWithMessage("l'extrait copié doit venir du corps, pas du corps entier")
            .that(copie).isNotEqualTo(BODY)
        assertThat(BODY).contains(copie)
        assertWithMessage("copie via le menu système non marquée sensible (N4)")
            .that(clip.description.extras?.getBoolean(EXTRA_IS_SENSITIVE, false))
            .isTrue()
    }
}
