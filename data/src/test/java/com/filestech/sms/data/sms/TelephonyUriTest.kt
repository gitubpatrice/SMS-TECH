package com.filestech.sms.data.sms

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/**
 * v1.27.11 — la regle de [canonicalTelephonyUri], verifiee sans appareil.
 *
 * Elle est appliquee a **trois** endroits — le chemin d'ecriture, le chemin de suppression et la
 * migration `7 → 8` — donc une divergence entre eux recreerait exactement le defaut qu'elle
 * corrige. Elle vit pour cette raison dans une fonction unique, sur des chaines et non sur
 * `android.net.Uri` : c'est ce qui la rend verifiable ici, en JVM ordinaire.
 *
 * Les mesures qui la motivent, faites le 2026-09-07 : `ContentResolver.insert` rend
 * `content://sms/sent/9164` sur un Galaxy S9 sous Android 10, et `content://sms/9164` sur un
 * Galaxy S24 sous Android 16.
 */
class TelephonyUriTest {

    @Test
    fun `la forme avec dossier perd son dossier`() {
        assertThat(canonicalTelephonyUri("content://sms/sent/9164"))
            .isEqualTo("content://sms/9164")
        assertThat(canonicalTelephonyUri("content://sms/inbox/42"))
            .isEqualTo("content://sms/42")
        assertThat(canonicalTelephonyUri("content://sms/draft/7"))
            .isEqualTo("content://sms/7")
        assertThat(canonicalTelephonyUri("content://mms/outbox/3"))
            .isEqualTo("content://mms/3")
    }

    @Test
    fun `la forme deja canonique traverse inchangee`() {
        // Le cas d'Android 16, et celui de tout ce que l'import construit.
        assertThat(canonicalTelephonyUri("content://sms/9164")).isEqualTo("content://sms/9164")
        assertThat(canonicalTelephonyUri("content://mms/42")).isEqualTo("content://mms/42")
    }

    @Test
    fun `la fonction est idempotente`() {
        // Elle s'applique a l'ecriture ET a la lecture : la reappliquer ne doit rien changer,
        // sans quoi une ligne migree se deformerait a chaque passage.
        val once = canonicalTelephonyUri("content://sms/sent/9164")
        assertThat(canonicalTelephonyUri(once)).isEqualTo(once)
    }

    @Test
    fun `ce qui n'est pas reconnu traverse intact`() {
        // Deformer un URI qu'on ne sait pas interpreter serait pire que le laisser passer : on
        // perdrait le lien vers la ligne systeme sans rien gagner.
        assertThat(canonicalTelephonyUri("content://mms/part/5"))
            .isEqualTo("content://mms/5") // dossier + id numerique : la regle s'applique
        assertThat(canonicalTelephonyUri("content://sms/sent/abc"))
            .isEqualTo("content://sms/sent/abc") // id non numerique
        assertThat(canonicalTelephonyUri("content://sms"))
            .isEqualTo("content://sms") // pas d'identifiant
        assertThat(canonicalTelephonyUri("")).isEqualTo("")
        assertThat(canonicalTelephonyUri("nimporte quoi")).isEqualTo("nimporte quoi")
        assertThat(canonicalTelephonyUri("content://sms/12/34"))
            .isEqualTo("content://sms/12/34") // deux segments numeriques : pas un dossier
    }

    // ── 2026-11-10 (issue #87) — [uriDeLigneCreee] : une écriture refusée n'est pas une ligne ──

    @Test
    fun `une ligne creee rend sa forme canonique`() {
        // Le cas ordinaire : la ligne existe, on garde son adresse — sous la forme canonique.
        assertThat(uriDeLigneCreee("content://sms/sent/9164")).isEqualTo("content://sms/9164")
        assertThat(uriDeLigneCreee("content://sms/inbox/1")).isEqualTo("content://sms/1")
        assertThat(uriDeLigneCreee("content://sms/42")).isEqualTo("content://sms/42")
        assertThat(uriDeLigneCreee("content://sms/10")).isEqualTo("content://sms/10") // finit par 0, n'est pas 0
    }

    @Test
    fun `un refus du fournisseur ne donne aucune adresse`() {
        // `ContentProvider.rejectInsert` : l'URI demandé suivi de `/0`.
        assertThat(uriDeLigneCreee("content://sms/sent/0")).isNull()
        assertThat(uriDeLigneCreee("content://sms/inbox/0")).isNull()
        assertThat(uriDeLigneCreee("content://sms/0")).isNull()
        assertThat(uriDeLigneCreee(null)).isNull()
        assertThat(uriDeLigneCreee("")).isNull()
    }

    @Test
    fun `un dernier segment non numerique traverse`() {
        // Même prudence que la forme canonique : on ne jette pas ce qu'on ne sait pas lire.
        assertThat(uriDeLigneCreee("content://sms/sent/abc")).isEqualTo("content://sms/sent/abc")
    }
}
