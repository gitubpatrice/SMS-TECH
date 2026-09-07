package com.filestech.sms.data.repository

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.provider.Telephony
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * v1.27.11 — fige, sur un VRAI fournisseur systeme, les hypotheses sur lesquelles repose
 * `ConversationRepositoryImpl.deleteFromTelephonyProvider` apres la revue externe GitLab !38458.
 *
 * # Pourquoi ce test existe
 *
 * Le correctif des constats 2 et 3 lit desormais le nombre de lignes rendu par `delete` et
 * interroge le fournisseur avant d'effacer. Aucun test unitaire ne peut l'atteindre : il faut un
 * `ContentResolver` reel. Si l'une de ces hypotheses est fausse, la suppression cesse de se
 * propager au systeme et les messages **ressuscitent a la resynchronisation suivante, sans
 * erreur visible** — le pire mode de panne pour une application SMS.
 *
 * # Ce que la premiere execution a trouve (S9, Android 10, 2026-09-07)
 *
 * Elle a echoue, et pas sur le correctif : `resolver.insert(Telephony.Sms.Sent.CONTENT_URI, …)`
 * rend `content://sms/sent/<id>`, forme que le fournisseur **refuse en suppression**. Or c'est
 * elle que `TelephonyReader` enregistre pour tout message que SMS Tech ecrit lui-meme. La
 * suppression systeme de ces messages echouait donc **depuis toujours**, l'exception etant
 * absorbee sans un mot. Le defaut etait anterieur au correctif ; c'est le correctif, en cessant
 * de jeter le resultat, qui l'a rendu visible.
 *
 * D'ou [uneLigneInsereeParLApplication_neSEffacePasSousLaFormeRendueParInsert], qui n'existe pas
 * pour verifier notre code mais pour **retenir la raison** de la normalisation : si un jour la
 * plateforme accepte les deux formes, ce test le dira, et pas avant.
 *
 * # Prerequis
 *
 * Ecrire dans `content://sms` exige le role d'application SMS par defaut. Sans lui les tests
 * sont **ignores** plutot que verts : un test qui passe parce qu'il n'a rien pu faire est pire
 * que pas de test du tout.
 */
@RunWith(AndroidJUnit4::class)
class SystemProviderContractTest {

    private val context: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    private val resolver get() = context.contentResolver

    /**
     * Precondition **fonctionnelle** : on tente l'ecriture au lieu d'interroger un signal.
     *
     * La premiere version demandait `Telephony.Sms.getDefaultSmsPackage(context)`. Mesure du
     * 2026-09-07 : sur le Galaxy S24 sous Android 16, `settings get secure
     * sms_default_application` rend `null` alors que `dumpsys role` designe bien cette
     * application — et sur le S9 sous Android 10 il rend une TROISIEME valeur, celle de
     * Samsung Messages. Le signal ment, dans les deux sens et sur les deux appareils.
     *
     * Les quatre tests etaient donc **ignores en silence** tout en etant comptes « OK » : un
     * test vert sur un chemin mort, exactement ce qu'un test est cense empecher. Tenter
     * l'insertion ne peut pas mentir — soit la ligne est ecrite, soit elle ne l'est pas.
     */
    private fun probeOrSkip(date: Long = System.currentTimeMillis()): Uri {
        val inserted = runCatching { insertProbeSms(date) }.getOrNull()
        assumeTrue(
            "ecriture dans content://sms refusee — cette application n'a pas le role SMS",
            inserted != null,
        )
        // L'ecriture ne suffit pas : mesure du 2026-09-07 sur le S9, `insert` peut aboutir
        // pendant que la LECTURE est refusee faute de READ_SMS accorde. Les quatre tests lisent
        // tous, donc la precondition doit couvrir les deux ou elle laisse passer un echec
        // d'environnement deguise en echec de produit.
        val lisible = runCatching { readDateColumn(canonical(inserted!!)) }.isSuccess
        if (!lisible) runCatching { resolver.delete(canonical(inserted!!), null, null) }
        assumeTrue("lecture de content://sms refusee — READ_SMS n'est pas accorde", lisible)
        return inserted!!
    }

    /** Meme tolerance que le correctif, pour mesurer ce qu'il mesure. */
    private val toleranceMs = 60_000L

    @Test
    fun laFormeCanoniqueEstToujoursSupprimable_quelleQueSoitCelleRendueParInsert() {
        val inserted = probeOrSkip()
        val canonical = canonical(inserted)
        try {
            // **Le comportement depend de la version d'Android** — mesure du 2026-09-07 :
            //   - Galaxy S9, Android 10  : `insert` rend `content://sms/sent/9164` ;
            //   - Galaxy S24, Android 16 : `insert` rend deja `content://sms/9164`.
            // La branche ci-dessous ne s'execute donc que sur les plateformes concernees par le
            // defaut, et c'est elle qui retient sa raison d'etre : sur celles-la, la forme
            // rendue par `insert` est REFUSEE en suppression, alors meme que c'est celle que
            // `TelephonyReader` enregistre dans Room.
            if (inserted != canonical) {
                val refused = runCatching { resolver.delete(inserted, null, null) }
                assertThat(refused.isFailure).isTrue()
                assertThat(refused.exceptionOrNull())
                    .isInstanceOf(IllegalArgumentException::class.java)
            }

            // L'invariant, lui, vaut partout — c'est celui sur lequel la normalisation s'appuie.
            assertThat(resolver.delete(canonical, null, null)).isEqualTo(1)
        } finally {
            runCatching { resolver.delete(canonical, null, null) }
        }
    }

    @Test
    fun laDateDUnSmsEstLisibleEtEnMillisecondes() {
        val date = System.currentTimeMillis()
        val inserted = probeOrSkip(date)
        try {
            val read = readDateColumn(canonical(inserted))
            assertThat(read).isNotNull()
            // L'ecart doit etre NUL, pas seulement petit : on relit ce qu'on vient d'ecrire. Si
            // le fournisseur rendait des secondes ici, l'ecart vaudrait ~1,7e12 ms.
            assertThat(read).isEqualTo(date)
            assertThat(kotlin.math.abs(read!! - date)).isAtMost(toleranceMs)
        } finally {
            runCatching { resolver.delete(canonical(inserted), null, null) }
        }
    }

    @Test
    fun laSuppressionRendLeNombreDeLignes() {
        val inserted = probeOrSkip()

        // Valeur que le correctif lit desormais, la ou elle etait jetee : c'est elle qui
        // distingue un refus du fournisseur d'une suppression reussie.
        val deleted = resolver.delete(canonical(inserted), null, null)

        assertThat(deleted).isEqualTo(1)
    }

    @Test
    fun uneLigneAbsenteRendUnCurseurVideEtNonNull() {
        val inserted = probeOrSkip()
        resolver.delete(canonical(inserted), null, null)

        // Branche ABSENT du correctif : la copie systeme est deja partie, il n'y a rien a
        // supprimer et c'est un SUCCES. Si le fournisseur rendait `null` ici, le correctif
        // tomberait en UNKNOWN et le compte rendu de purge deviendrait faux.
        resolver.query(canonical(inserted), arrayOf("date"), null, null, null).use { cursor ->
            assertThat(cursor).isNotNull()
            assertThat(cursor!!.moveToFirst()).isFalse()
        }
    }

    @Test
    fun laDateDUnMmsEstEnSecondes() {
        // Aucune ecriture : on lit une ligne MMS reelle deja presente sur l'appareil.
        val probe = firstExistingMms()
        assumeTrue("aucun MMS sur cet appareil", probe != null)
        val (uri, rawDate) = probe!!
        val now = System.currentTimeMillis()

        // Un horodatage plausible en SECONDES est de l'ordre de 1,7e9 ; en millisecondes, de
        // 1,7e12. Mille les separe, donc le depistage est sans ambiguite — et c'est exactement
        // la conversion que fait le correctif.
        assertThat(rawDate).isLessThan(now / 100L)
        assertThat(rawDate * 1000L).isLessThan(now + toleranceMs)
        assertThat(rawDate * 1000L).isGreaterThan(now - 20L * 365 * 24 * 3600 * 1000)
        assertThat(uri.toString()).startsWith("content://mms")
    }

    // ─────────────────────────────────── outillage ───────────────────────────────────

    /** Meme regle que `ConversationRepositoryImpl.canonicalTelephonyUri`. */
    private fun canonical(uri: Uri): Uri =
        Uri.parse("${uri.scheme}://${uri.authority}/${uri.lastPathSegment}")

    private fun insertProbeSms(date: Long): Uri? {
        val cv = ContentValues().apply {
            put(Telephony.Sms.ADDRESS, "+33000000000")
            put(Telephony.Sms.BODY, "SMS Tech — sonde de test v1.27.11, a supprimer")
            put(Telephony.Sms.DATE, date)
            put(Telephony.Sms.DATE_SENT, date)
            put(Telephony.Sms.READ, 1)
            put(Telephony.Sms.SEEN, 1)
            put(Telephony.Sms.TYPE, Telephony.Sms.MESSAGE_TYPE_SENT)
        }
        return resolver.insert(Telephony.Sms.Sent.CONTENT_URI, cv)
    }

    private fun readDateColumn(uri: Uri): Long? =
        resolver.query(uri, arrayOf("date"), null, null, null).use { cursor ->
            if (cursor != null && cursor.moveToFirst()) cursor.getLong(0) else null
        }

    private fun firstExistingMms(): Pair<Uri, Long>? =
        resolver.query(
            Uri.parse("content://mms"),
            arrayOf("_id", "date"),
            null,
            null,
            "date DESC LIMIT 1",
        ).use { cursor ->
            if (cursor != null && cursor.moveToFirst()) {
                Uri.parse("content://mms/${cursor.getLong(0)}") to cursor.getLong(1)
            } else {
                null
            }
        }
}
