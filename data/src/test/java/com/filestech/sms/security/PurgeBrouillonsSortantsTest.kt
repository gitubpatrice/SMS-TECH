package com.filestech.sms.security

import com.google.common.truth.Truth.assertThat
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * v1.28.12 (audit B1) — **la purge des brouillons sortants détruisait une pièce jointe que
 * l'utilisateur était en train de composer.**
 *
 * `AutoLockObserver` vidait `cache/media_outgoing` et `cache/voice_mms` EN BLOC à chaque passage en
 * arrière-plan, sur une affirmation fausse : « ce dossier ne contient QUE des brouillons
 * abandonnés ». `ThreadViewModel` y met en attente la photo choisie ou le clip enregistré, et en
 * garde les `File` dans `pendingAttachments` / `VoiceState.Reviewing`. La purge tournait **même
 * sans aucun verrou configuré** — `forceLock()` est alors un no-op — après `autoLockDelay`, dont le
 * défaut est UNE MINUTE.
 *
 * Le test mesure les deux faces, sans quoi il ne pourrait pas échouer :
 *  - sans verrou, un brouillon frais SURVIT et un brouillon abandonné part ;
 *  - verrou mordu, le brouillon frais part quand même — l'intention F13 / S-P2-3 est intacte.
 */
class PurgeBrouillonsSortantsTest {

    @Test
    fun `sans verrou, le brouillon frais survit et l'abandonne part`(@TempDir dir: File) {
        val maintenant = 1_800_000_000_000L
        val frais = fichier(dir, "frais.jpg", maintenant - 60_000L)
        val abandonne = fichier(dir, "abandonne.jpg", maintenant - DRAFT_MAX_AGE_MS - 1L)

        val supprimes = purgerBrouillonsSortants(dir, verrouEngage = false, nowMs = maintenant)

        assertThat(supprimes).isEqualTo(1)
        assertThat(frais.exists()).isTrue()
        assertThat(abandonne.exists()).isFalse()
    }

    @Test
    fun `verrou mordu, tout part y compris le brouillon frais`(@TempDir dir: File) {
        val maintenant = 1_800_000_000_000L
        val frais = fichier(dir, "frais.jpg", maintenant - 60_000L)
        val abandonne = fichier(dir, "abandonne.jpg", maintenant - DRAFT_MAX_AGE_MS - 1L)

        val supprimes = purgerBrouillonsSortants(dir, verrouEngage = true, nowMs = maintenant)

        assertThat(supprimes).isEqualTo(2)
        assertThat(frais.exists()).isFalse()
        assertThat(abandonne.exists()).isFalse()
    }

    /**
     * La frontière est fermée du bon côté : un fichier qui a exactement l'âge du seuil n'est pas
     * encore abandonné. Sans ce cas, un `<=` glissé à la place du `<` passerait inaperçu.
     */
    @Test
    fun `le fichier qui a exactement l'age du seuil survit`(@TempDir dir: File) {
        val maintenant = 1_800_000_000_000L
        val pile = fichier(dir, "pile.jpg", maintenant - DRAFT_MAX_AGE_MS)

        val supprimes = purgerBrouillonsSortants(dir, verrouEngage = false, nowMs = maintenant)

        assertThat(supprimes).isEqualTo(0)
        assertThat(pile.exists()).isTrue()
    }

    /**
     * Le prédécesseur appelait `deleteRecursively()` sur le dossier entier ; l'audit P1-5 (v1.2.0)
     * avait justement corrigé un balayage qui ne descendait pas. On vérifie que la purge par âge
     * emporte bien un sous-dossier, et n'est donc pas une régression de P1-5.
     */
    @Test
    fun `un sous-dossier abandonne est emporte en entier`(@TempDir dir: File) {
        val maintenant = 1_800_000_000_000L
        val sous = File(dir, "reencodage").apply { mkdirs() }
        fichier(sous, "part.tmp", maintenant - DRAFT_MAX_AGE_MS - 1L)
        assertThat(sous.setLastModified(maintenant - DRAFT_MAX_AGE_MS - 1L)).isTrue()

        val supprimes = purgerBrouillonsSortants(dir, verrouEngage = false, nowMs = maintenant)

        assertThat(supprimes).isEqualTo(1)
        assertThat(sous.exists()).isFalse()
    }

    @Test
    fun `un dossier absent ne leve pas`(@TempDir dir: File) {
        val absent = File(dir, "jamais-cree")

        assertThat(purgerBrouillonsSortants(absent, verrouEngage = true, nowMs = 1L)).isEqualTo(0)
        assertThat(purgerBrouillonsSortants(absent, verrouEngage = false, nowMs = 1L)).isEqualTo(0)
    }

    // ──────────── Le câblage : « le verrou a-t-il mordu ? » ────────────

    /**
     * Le premier contrôle négatif a montré que l'inversion de cette lecture passait VERTE :
     * la décision était juste, mais personne ne vérifiait qu'on la lisait dans le bon sens.
     * Cinq états, cinq réponses — et `isOpenForUi` appelle l'implémentation réelle, comme dans
     * [VaultGuardsTest] : la dupliquer ici rendrait le test complaisant.
     */
    @Test
    fun `seuls Locked et LockedOut comptent comme un verrou qui a mordu`() {
        assertThat(observateur(AppLockManager.LockState.Locked).verrouAMordu()).isTrue()
        assertThat(observateur(AppLockManager.LockState.LockedOut(until = 1L)).verrouAMordu()).isTrue()
        assertThat(observateur(AppLockManager.LockState.Disabled).verrouAMordu()).isFalse()
        assertThat(observateur(AppLockManager.LockState.Unlocked).verrouAMordu()).isFalse()
        assertThat(observateur(AppLockManager.LockState.PanicDecoy).verrouAMordu()).isFalse()
    }

    private fun observateur(etat: AppLockManager.LockState): AutoLockObserver {
        val appLock = mockk<AppLockManager>().also { m ->
            every { m.state } returns MutableStateFlow(etat)
            every { m.isOpenForUi(any()) } answers { callOriginal() }
        }
        return AutoLockObserver(
            context = mockk(relaxed = true),
            appLock = appLock,
            vaultLazy = mockk(relaxed = true),
            settings = mockk(relaxed = true),
            scope = mockk(relaxed = true),
        )
    }

    /**
     * `setLastModified` peut échouer en silence selon le système de fichiers. On l'affirme, sinon
     * l'horodatage réel serait « maintenant » et tous les cas d'âge seraient verts sans rien mesurer.
     */
    private fun fichier(dir: File, nom: String, modifieA: Long): File =
        File(dir, nom).apply {
            writeBytes(byteArrayOf(1, 2, 3))
            assertThat(setLastModified(modifieA)).isTrue()
        }
}
