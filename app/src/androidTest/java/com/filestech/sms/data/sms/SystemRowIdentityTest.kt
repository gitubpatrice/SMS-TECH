package com.filestech.sms.data.sms

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.provider.Telephony
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.filestech.sms.data.local.db.entity.MessageEntity
import com.filestech.sms.domain.model.MessageDirection
import com.filestech.sms.domain.model.MessageStatus
import com.filestech.sms.domain.model.MessageType
import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * v1.28.1 (revue externe GitLab !38458, 3e passe, constat 2) — **la date ne prouve pas
 * l'identite**, et la demonstration se fait sur un VRAI fournisseur.
 *
 * # Ce que le testeur reproche a la v1.27.11
 *
 * `matchesSystemRow` ne lisait que `date`, a une minute pres. Or une minute est la duree
 * ORDINAIRE d'un echange : deux messages d'une meme conversation y tombent constamment. Le garde
 * cense empecher de supprimer la ligne d'autrui laissait donc passer le cas le plus courant.
 *
 * # Pourquoi ce test ne peut pas etre unitaire
 *
 * Il mesure ce que `content://sms` expose reellement — `body` et `type` sur l'URI d'UNE ligne. Un
 * faux fournisseur affirmerait ce qu'on veut bien lui faire dire ; c'est exactement ainsi qu'on a
 * cru la suppression systeme fonctionnelle pendant des mois.
 *
 * # Prerequis, tentes et non interroges
 *
 * Ecrire dans `content://sms` exige le role SMS. La precondition **tente l'ecriture** au lieu de
 * lire un reglage : mesure du 2026-09-07, `getDefaultSmsPackage` ment sur le S9 comme sur le S24,
 * et quatre tests avaient ete comptes verts sans jamais s'executer. Sur un emulateur, accorder le
 * role avant la campagne :
 *
 * ```
 * adb shell cmd role add-role-holder android.app.role.SMS com.filestech.sms.debug
 * adb shell pm grant com.filestech.sms.debug android.permission.READ_SMS
 * ```
 */
@RunWith(AndroidJUnit4::class)
class SystemRowIdentityTest {

    private val context: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    private val resolver get() = context.contentResolver

    private val eraser by lazy { TelephonySystemCopyEraser(context) }

    private val ecrites = mutableListOf<Uri>()

    /** Voir [MmsRowIdentityTest.tearDown] : le nettoyage se verifie, il ne se suppose pas. */
    @After
    fun tearDown() {
        val survivants = ecrites.filter { uri ->
            runCatching { resolver.delete(uri, null, null) }
            ligneSystemePresente(uri)
        }
        assertThat(survivants).isEmpty()
    }

    /**
     * Le cas que le garde de la v1.27.11 laissait passer : **meme date, corps different**. La
     * ligne systeme appartient a un autre message — typiquement restaure depuis un autre
     * telephone, dont il a garde le `telephony_uri` — et elle ne doit pas etre touchee.
     */
    @Test
    fun uneLigneDeMemeDateMaisDeCorpsDifferent_nEstPasSupprimee() {
        val date = System.currentTimeMillis()
        val uri = insertProbeOrSkip(date, "corps de quelqu'un d'autre")

        val partie = eraser.erase(message(uri, "le NOTRE, ecrit la meme seconde", date))

        assertThat(partie).isFalse()
        assertThat(ligneSystemePresente(uri)).isTrue()
    }

    /**
     * Controle positif, sans lequel le precedent ne prouverait rien : un garde qui refuserait
     * TOUJOURS le satisferait aussi. Meme date, meme corps, meme sens : la ligne part.
     */
    @Test
    fun uneLigneIdentique_estBienSupprimee() {
        val date = System.currentTimeMillis()
        val corps = "message identique des deux cotes"
        val uri = insertProbeOrSkip(date, corps)

        val partie = eraser.erase(message(uri, corps, date))

        assertThat(partie).isTrue()
        assertThat(ligneSystemePresente(uri)).isFalse()
    }

    /**
     * Le sens seul suffit a distinguer, corps egal : un recu et un envoye portant le meme texte a
     * la meme seconde ne sont pas le meme message. C'est aussi la raison pour laquelle on compare
     * le SENS et non le type brut — cf. [smsTypeToDirection] : la pile fait passer un envoi par
     * `QUEUED` puis `SENT` sans nous prevenir, et comparer le type brut refuserait de supprimer
     * un message que l'on vient d'ecrire.
     */
    @Test
    fun uneLigneDeSensOppose_nEstPasSupprimee() {
        val date = System.currentTimeMillis()
        val corps = "meme texte, sens oppose"
        val uri = insertProbeOrSkip(date, corps) // insere en INBOX ⇒ INCOMING

        val partie = eraser.erase(message(uri, corps, date, MessageDirection.OUTGOING))

        assertThat(partie).isFalse()
        assertThat(ligneSystemePresente(uri)).isTrue()
    }

    /**
     * Autorite inexistante : la lecture leve, l'effacement ne jette pas et ne ment pas.
     *
     * ⚠ **Ce test ne prouve PAS la politique appliquee a `UNKNOWN`**, et il faut le dire :
     * mesure du 2026-09-08, il reste vert avec l'ANCIENNE politique remise en place, parce que la
     * suppression echouerait de toute facon sous une autorite inconnue. Les deux regles produisent
     * ici le meme effet observable. La politique elle-meme est prouvee par
     * `SystemRowMatchPolicyTest`, cote JVM, ou une inversion echoue immediatement.
     *
     * Ce qu'il prouve, et qui vaut d'etre fige : un URI que le systeme ne sait pas resoudre ne
     * fait pas remonter d'exception et ne rend jamais « c'est parti ».
     */
    @Test
    fun uneAutoriteInexistante_neJettePasEtNeSeDeclarePasPartie() {
        val partie = eraser.erase(
            message(Uri.parse("content://com.filestech.sms.autorite.absente/42"), "peu importe", 1L),
        )

        assertThat(partie).isFalse()
    }

    /** Un message jamais miroite dans le systeme n'a rien a y faire disparaitre. */
    @Test
    fun unMessageSansLiaisonSysteme_estConsidereCommeDejaParti() {
        val entity = message(Uri.parse("content://sms/1"), "corps", 1L).copy(telephonyUri = null)

        assertThat(eraser.erase(entity)).isTrue()
    }

    /**
     * v1.28.1 (relecture externe GPT, point 5) — **le meme texte a deux destinataires dans la
     * meme minute**. C'est le cas qui a fait rentrer l'adresse dans la comparaison : « OK »
     * envoye a deux personnes passe la date, le corps et le sens, et seule l'adresse les separe.
     */
    @Test
    fun uneLigneDeMemeCorpsMaisDAdresseDifferente_nEstPasSupprimee() {
        val date = System.currentTimeMillis()
        val corps = "OK"
        val uri = insertProbeOrSkip(date, corps)

        val autreDestinataire = message(uri, corps, date).copy(address = "+33699999999")
        val partie = eraser.erase(autreDestinataire)

        assertThat(partie).isFalse()
        assertThat(ligneSystemePresente(uri)).isTrue()
    }

    /**
     * Controle negatif de la comparaison d'adresse : elle passe par [blockKey] et non par
     * l'egalite de chaine, sinon un fournisseur rendant `+33...` la ou Room garde `06...`
     * refuserait a tort — et un refus a tort bloque la porte « PIN oublie ».
     */
    @Test
    fun uneMemeAdresseSousUneAutreForme_nEmpechePasLaSuppression() {
        val date = System.currentTimeMillis()
        val corps = "meme numero, autre notation"
        val uri = insertProbeOrSkip(date, corps)

        // ADRESSE vaut "+33600000042" cote systeme ; Room porte ici la forme nationale.
        val formeNationale = message(uri, corps, date).copy(address = "06 00 00 00 42")
        val partie = eraser.erase(formeNationale)

        assertThat(partie).isTrue()
        assertThat(ligneSystemePresente(uri)).isFalse()
    }

    /** Voir le KDoc de classe : la precondition se TENTE, elle ne s'interroge pas. */
    private fun insertProbeOrSkip(date: Long, body: String): Uri {
        val cv = ContentValues().apply {
            put(Telephony.Sms.ADDRESS, ADRESSE)
            put(Telephony.Sms.BODY, body)
            put(Telephony.Sms.DATE, date)
            put(Telephony.Sms.DATE_SENT, date)
            put(Telephony.Sms.READ, 1)
            put(Telephony.Sms.SEEN, 1)
            put(Telephony.Sms.TYPE, Telephony.Sms.MESSAGE_TYPE_INBOX)
        }
        val tentative = runCatching { resolver.insert(Telephony.Sms.Inbox.CONTENT_URI, cv) }
        // Le motif du refus est REPORTE. Un « ignore » muet est la facon dont quatre tests ont
        // ete comptes verts le 2026-09-07 sans jamais s'executer : qui lit le rapport doit
        // pouvoir distinguer « pas le role SMS » de « pas de telephonie sur cet emulateur ».
        val inserted = tentative.getOrNull()
        assumeTrue(
            "ecriture dans content://sms refusee — " +
                (tentative.exceptionOrNull()?.toString() ?: "insert a rendu null, sans exception"),
            inserted != null,
        )
        val canonique = Uri.parse("content://sms/${inserted!!.lastPathSegment}")
        ecrites += canonique
        // Lire est aussi indispensable qu'ecrire : `insert` peut aboutir pendant que READ_SMS
        // manque, et les trois premiers tests lisent. Une precondition qui n'en couvre qu'une
        // moitie laisse passer un echec d'environnement deguise en echec de produit.
        val lisible = runCatching {
            resolver.query(canonique, arrayOf(Telephony.Sms.BODY), null, null, null)
                .use { it != null && it.moveToFirst() }
        }.getOrDefault(false)
        assumeTrue("lecture de content://sms refusee — READ_SMS n'est pas accorde", lisible)
        return canonique
    }

    private fun ligneSystemePresente(uri: Uri): Boolean =
        resolver.query(uri, arrayOf(Telephony.Sms._ID), null, null, null)
            .use { it != null && it.moveToFirst() }

    private fun message(
        uri: Uri,
        body: String,
        date: Long,
        direction: MessageDirection = MessageDirection.INCOMING,
    ) = MessageEntity(
        conversationId = 1L,
        telephonyUri = uri.toString(),
        address = ADRESSE,
        body = body,
        type = MessageType.SMS,
        direction = direction,
        date = date,
        dateSent = date,
        read = true,
        starred = false,
        status = if (direction == MessageDirection.INCOMING) {
            MessageStatus.RECEIVED
        } else {
            MessageStatus.SENT
        },
        errorCode = null,
        subId = null,
        scheduledAt = null,
        attachmentsCount = 0,
    )

    private companion object {
        const val ADRESSE = "+33600000042"
    }
}
