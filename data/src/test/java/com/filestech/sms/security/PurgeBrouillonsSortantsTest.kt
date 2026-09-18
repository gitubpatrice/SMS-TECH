package com.filestech.sms.security

import com.filestech.sms.domain.settings.LockMode
import com.google.common.truth.Truth.assertThat
import io.mockk.mockk
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

        val supprimes = purgerBrouillonsSortants(dir, verrouArme = false, nowMs = maintenant)

        assertThat(supprimes).isEqualTo(1)
        assertThat(frais.exists()).isTrue()
        assertThat(abandonne.exists()).isFalse()
    }

    @Test
    fun `verrou mordu, tout part y compris le brouillon frais`(@TempDir dir: File) {
        val maintenant = 1_800_000_000_000L
        val frais = fichier(dir, "frais.jpg", maintenant - 60_000L)
        val abandonne = fichier(dir, "abandonne.jpg", maintenant - DRAFT_MAX_AGE_MS - 1L)

        val supprimes = purgerBrouillonsSortants(dir, verrouArme = true, nowMs = maintenant)

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

        val supprimes = purgerBrouillonsSortants(dir, verrouArme = false, nowMs = maintenant)

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

        val supprimes = purgerBrouillonsSortants(dir, verrouArme = false, nowMs = maintenant)

        assertThat(supprimes).isEqualTo(1)
        assertThat(sous.exists()).isFalse()
    }

    @Test
    fun `un dossier absent ne leve pas`(@TempDir dir: File) {
        val absent = File(dir, "jamais-cree")

        assertThat(purgerBrouillonsSortants(absent, verrouArme = true, nowMs = 1L)).isEqualTo(0)
        assertThat(purgerBrouillonsSortants(absent, verrouArme = false, nowMs = 1L)).isEqualTo(0)
    }

    // ──────────── Le câblage : « un verrou est-il armé ? » ────────────

    /**
     * Le premier contrôle négatif a montré que l'inversion de cette lecture passait VERTE :
     * la décision était juste, mais personne ne vérifiait qu'on la lisait dans le bon sens.
     *
     * v1.28.12 ter — le prédicat ne lit plus l'ÉTAT du verrou mais le RÉGLAGE, et les deux
     * relectures externes ont dit pourquoi : en « prochain lancement seulement » l'état reste
     * ouvert alors que le verrou mordra, et à froid il vaut `Locked` alors qu'il n'y a peut-être
     * aucun verrou. **Le test énumère les QUATRE modes** : un mode ajouté demain ne compilera
     * pas ici sans qu'on ait tranché son cas.
     */
    @Test
    fun `tout mode de verrouillage sauf OFF compte comme un verrou arme`() {
        val observateur = observateur()
        for (mode in LockMode.entries) {
            assertThat(observateur.verrouArme(mode)).isEqualTo(mode != LockMode.OFF)
        }
        // Le test ci-dessus serait vert sur une enumeration vide : on compte.
        assertThat(LockMode.entries).hasSize(4)
        assertThat(observateur.verrouArme(LockMode.OFF)).isFalse()
        assertThat(observateur.verrouArme(LockMode.PIN)).isTrue()
    }

    private fun observateur(): AutoLockObserver = AutoLockObserver(
        context = mockk(relaxed = true),
        appLock = mockk(relaxed = true),
        vaultLazy = mockk(relaxed = true),
        settings = mockk(relaxed = true),
        scope = mockk(relaxed = true),
    )

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
