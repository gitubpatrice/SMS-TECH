package com.filestech.sms.system.receiver

import android.content.Intent
import android.telephony.SmsManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.filestech.sms.data.mms.MmsDownloader
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * 2026-11-14 — la politique de reprise d'un MMS reçu dont le téléchargement a échoué.
 *
 * Courriel d'un utilisateur : en Wi-Fi, des MMS échouaient avec le code 3 (`MMS_ERROR_UNABLE_CONNECT_MMS`), et
 * l'application les abandonnait. Ces tests tiennent la décision (quel échec se retente seul, combien de fois),
 * et l'aller-retour de la demande dans un intent : une demande qui perdrait un champ redemanderait un autre
 * message, ou aucun.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [33])
class RelanceMmsTest {

    private fun demande(
        adresse: String? = "http://mmsc.example/abc?tx=1",
        transactionId: String? = "T123",
        subId: Int? = 2,
        tentative: Int = 0,
    ) = RelanceMms.Demande.creer(
        adresse = adresse,
        transactionId = transactionId,
        expediteur = "+15551234567/TYPE=PLMN",
        subId = subId,
        transactionIdOctets = byteArrayOf(0x54, 0x31),
        tentative = tentative,
    )

    /** Les codes recopiés doivent rester ceux du SDK, sinon la décision porterait sur d'autres échecs. */
    @Test
    fun `les codes recopies sont ceux de SmsManager`() {
        assertThat(RelanceMms.MMS_ERROR_UNSPECIFIED).isEqualTo(SmsManager.MMS_ERROR_UNSPECIFIED)
        assertThat(RelanceMms.MMS_ERROR_UNABLE_CONNECT_MMS).isEqualTo(SmsManager.MMS_ERROR_UNABLE_CONNECT_MMS)
        assertThat(RelanceMms.MMS_ERROR_HTTP_FAILURE).isEqualTo(SmsManager.MMS_ERROR_HTTP_FAILURE)
        assertThat(RelanceMms.MMS_ERROR_IO_ERROR).isEqualTo(SmsManager.MMS_ERROR_IO_ERROR)
        assertThat(RelanceMms.MMS_ERROR_RETRY).isEqualTo(SmsManager.MMS_ERROR_RETRY)
        assertThat(RelanceMms.MMS_ERROR_NO_DATA_NETWORK).isEqualTo(SmsManager.MMS_ERROR_NO_DATA_NETWORK)
        assertThat(RelanceMms.MMS_ERROR_DATA_DISABLED).isEqualTo(SmsManager.MMS_ERROR_DATA_DISABLED)
    }

    /** Le cas du courriel : code 3, trois essais automatiques espacés, puis le bouton seul. */
    @Test
    fun `reseau MMS injoignable - trois essais automatiques puis le bouton`() {
        val code = SmsManager.MMS_ERROR_UNABLE_CONNECT_MMS

        assertThat(RelanceMms.apresEchec(code, 0, 0)).isEqualTo(RelanceMms.Decision.Automatique(120_000L, 1))
        assertThat(RelanceMms.apresEchec(code, 0, 1)).isEqualTo(RelanceMms.Decision.Automatique(600_000L, 2))
        assertThat(RelanceMms.apresEchec(code, 0, 2)).isEqualTo(RelanceMms.Decision.Automatique(1_800_000L, 3))
        assertThat(RelanceMms.apresEchec(code, 0, 3)).isEqualTo(RelanceMms.Decision.Manuelle)
        // Une tentative hors bornes (extra forgé, ou futur appelant) ne déborde pas du tableau des délais.
        assertThat(RelanceMms.apresEchec(code, 0, 99)).isEqualTo(RelanceMms.Decision.Manuelle)
        assertThat(RelanceMms.apresEchec(code, 0, -5)).isEqualTo(RelanceMms.Decision.Automatique(120_000L, 1))
    }

    @Test
    fun `echecs passagers - reseau, donnees, entree-sortie`() {
        listOf(
            SmsManager.MMS_ERROR_UNSPECIFIED,
            SmsManager.MMS_ERROR_IO_ERROR,
            SmsManager.MMS_ERROR_RETRY,
            SmsManager.MMS_ERROR_NO_DATA_NETWORK,
            SmsManager.MMS_ERROR_DATA_DISABLED,
            SmsManager.MMS_ERROR_UNABLE_CONNECT_MMS,
        ).forEach { code -> assertThat(RelanceMms.echecPassager(code, 0)).isTrue() }
    }

    /** Contrôle négatif : ce qu'un nouvel essai ne lèvera pas reste au seul bouton. */
    @Test
    fun `echecs durables - APN, configuration, abonnement, operateur, code inconnu`() {
        listOf(
            SmsManager.MMS_ERROR_INVALID_APN,
            SmsManager.MMS_ERROR_CONFIGURATION_ERROR,
            SmsManager.MMS_ERROR_INVALID_SUBSCRIPTION_ID,
            SmsManager.MMS_ERROR_INACTIVE_SUBSCRIPTION,
            SmsManager.MMS_ERROR_MMS_DISABLED_BY_CARRIER,
            0,
            13,
            99,
        ).forEach { code ->
            assertThat(RelanceMms.echecPassager(code, 0)).isFalse()
            assertThat(RelanceMms.apresEchec(code, 0, 0)).isEqualTo(RelanceMms.Decision.Manuelle)
        }
    }

    @Test
    fun `erreur HTTP - un refus 4xx reste au bouton, le reste se retente`() {
        val http = SmsManager.MMS_ERROR_HTTP_FAILURE

        assertThat(RelanceMms.echecPassager(http, 0)).isTrue()
        assertThat(RelanceMms.echecPassager(http, 500)).isTrue()
        assertThat(RelanceMms.echecPassager(http, 503)).isTrue()
        assertThat(RelanceMms.echecPassager(http, 408)).isTrue()
        assertThat(RelanceMms.echecPassager(http, 429)).isTrue()
        assertThat(RelanceMms.echecPassager(http, 404)).isFalse()
        assertThat(RelanceMms.echecPassager(http, 403)).isFalse()
        assertThat(RelanceMms.echecPassager(http, 400)).isFalse()
    }

    /** Une demande qui perdrait un champ redemanderait un autre message, ou rien. */
    @Test
    fun `aller-retour dans un intent - tous les champs, la meme etiquette`() {
        val origine = requireNotNull(demande(tentative = 2))
        val relue = requireNotNull(RelanceMms.Demande.depuis(origine.dans(Intent("x"))))

        assertThat(relue.adresse).isEqualTo(origine.adresse)
        assertThat(relue.transactionId).isEqualTo("T123")
        assertThat(relue.expediteur).isEqualTo("+15551234567/TYPE=PLMN")
        assertThat(relue.subId).isEqualTo(2)
        assertThat(relue.transactionIdOctets).isEqualTo(byteArrayOf(0x54, 0x31))
        assertThat(relue.tentative).isEqualTo(2)
        assertThat(relue.etiquette).isEqualTo(origine.etiquette)
        assertThat(relue.identite).isEqualTo(origine.identite)
    }

    @Test
    fun `sans SIM ni identifiant - l'aller-retour garde null`() {
        val origine = requireNotNull(demande(transactionId = null, subId = null))
        val relue = requireNotNull(RelanceMms.Demande.depuis(origine.dans(Intent("x"))))

        assertThat(relue.subId).isNull()
        assertThat(relue.transactionId).isNull()
        assertThat(relue.etiquette).isEqualTo(origine.etiquette)
    }

    /** La SIM « invalide » d'Android vaut absence, comme dans `MmsDownloadedReceiver` : même message, même étiquette. */
    @Test
    fun `SIM invalide - traitee comme absente`() {
        val invalide = requireNotNull(demande(subId = -1))

        assertThat(invalide.subId).isNull()
        assertThat(invalide.etiquette).isEqualTo(requireNotNull(demande(subId = null)).etiquette)
    }

    /** Deux messages distincts ne partagent ni notification ni `PendingIntent`. */
    @Test
    fun `deux messages - deux etiquettes, deux identites`() {
        val a = requireNotNull(demande(transactionId = "A"))
        val b = requireNotNull(demande(transactionId = "B"))
        val autreSim = requireNotNull(demande(transactionId = "A", subId = 3))

        assertThat(a.etiquette).isNotEqualTo(b.etiquette)
        assertThat(a.etiquette).isNotEqualTo(autreSim.etiquette)
        assertThat(a.identite).isNotEqualTo(b.identite)
        // La tentative ne change pas l'identité : l'essai suivant remplace l'alarme et la notification du message.
        assertThat(a.avecTentative(3).etiquette).isEqualTo(a.etiquette)
    }

    /** L'adresse du MMSC peut porter un jeton : elle n'apparaît ni dans l'étiquette ni dans l'URI. */
    @Test
    fun `l'adresse du MMSC n'apparait pas en clair`() {
        val d = requireNotNull(demande(adresse = "http://mmsc.example/jeton-secret"))

        assertThat(d.etiquette).matches("[0-9a-f]{64}")
        assertThat(d.identite.toString()).doesNotContain("jeton-secret")
        assertThat(d.identite.toString()).doesNotContain("mmsc.example")
    }

    /** Contrôle négatif : la règle de `MmsDownloader` — http(s) seulement. */
    @Test
    fun `adresse absente ou hors http - pas de demande`() {
        assertThat(demande(adresse = null)).isNull()
        assertThat(demande(adresse = "")).isNull()
        assertThat(demande(adresse = "file:///data/data/x")).isNull()
        assertThat(demande(adresse = "content://x/y")).isNull()
        assertThat(RelanceMms.Demande.depuis(Intent("x"))).isNull()
        assertThat(demande(adresse = "HTTPS://mmsc.example/a")).isNotNull()
    }

    @Test
    fun `tentative bornee - dans la demande et dans le resultat du telechargement`() {
        assertThat(requireNotNull(demande(tentative = 42)).tentative).isEqualTo(RelanceMms.ESSAIS_AUTOMATIQUES)
        assertThat(requireNotNull(demande(tentative = -3)).tentative).isEqualTo(0)

        val resultat = Intent("x")
        assertThat(RelanceMms.tentativeDepuis(resultat)).isEqualTo(0)
        resultat.putExtra(MmsDownloader.EXTRA_TENTATIVE, 2)
        assertThat(RelanceMms.tentativeDepuis(resultat)).isEqualTo(2)
        resultat.putExtra(MmsDownloader.EXTRA_TENTATIVE, 1_000)
        assertThat(RelanceMms.tentativeDepuis(resultat)).isEqualTo(RelanceMms.ESSAIS_AUTOMATIQUES)
    }

    /**
     * Relecture GPT du 2026-11-14 : un identifiant trop long était retiré EN SILENCE, et la réponse au MMSC partait
     * sans lui. La demande est désormais refusée ; à la borne, il est gardé entier.
     */
    @Test
    fun `identifiant de transaction demesure - pas de demande, plutot qu'un identifiant perdu`() {
        fun avec(octets: Int) = RelanceMms.Demande.creer(
            adresse = "http://mmsc.example/a",
            transactionId = "T",
            expediteur = null,
            subId = null,
            transactionIdOctets = ByteArray(octets) { 7 },
            tentative = 0,
        )

        assertThat(avec(RelanceMms.OCTETS_TRANSACTION_MAX + 1)).isNull()
        val aLaBorne = avec(RelanceMms.OCTETS_TRANSACTION_MAX)?.transactionIdOctets
        assertThat(aLaBorne).hasLength(RelanceMms.OCTETS_TRANSACTION_MAX)
    }

    /** L'heure du téléchargement d'origine suit la demande d'essai en essai : c'est elle que l'effacement compare. */
    @Test
    fun `l'heure de lancement fait l'aller-retour, et suit les essais suivants`() {
        val origine = requireNotNull(
            RelanceMms.Demande.creer("http://mmsc.example/a", "T", null, null, null, 0, lanceeA = 123_456L),
        )
        val relue = requireNotNull(RelanceMms.Demande.depuis(origine.dans(Intent("x"))))

        assertThat(relue.lanceeA).isEqualTo(123_456L)
        assertThat(relue.avecTentative(2).lanceeA).isEqualTo(123_456L)
    }

    /** L'étiquette qu'un intent porte vers `MainActivity`, exportée : seule la forme d'une vraie étiquette passe. */
    @Test
    fun `forme de l'etiquette - 64 hexadecimaux minuscules, rien d'autre`() {
        val vraie = requireNotNull(demande()).etiquette

        assertThat(RelanceMms.etiquetteValide(vraie)).isTrue()
        assertThat(RelanceMms.etiquetteValide(null)).isFalse()
        assertThat(RelanceMms.etiquetteValide("")).isFalse()
        assertThat(RelanceMms.etiquetteValide(vraie.uppercase())).isFalse()
        assertThat(RelanceMms.etiquetteValide(vraie.dropLast(1))).isFalse()
        assertThat(RelanceMms.etiquetteValide(vraie + "0")).isFalse()
        assertThat(RelanceMms.etiquetteValide("../" + vraie.drop(3))).isFalse()
    }
}
