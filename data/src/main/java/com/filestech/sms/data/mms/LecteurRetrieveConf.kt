package com.filestech.sms.data.mms

import com.filestech.sms.core.ext.stripInvisibleChars
import com.filestech.sms.core.ext.stripMmsAddressSuffix
import com.filestech.sms.pdu.CharacterSets
import com.filestech.sms.pdu.EncodedStringValue
import com.filestech.sms.pdu.PduBody
import com.filestech.sms.pdu.PduPart
import com.filestech.sms.pdu.RetrieveConf

/**
 * v1.28.9 (F17) — ce qu'un `RetrieveConf` porte d'utile, lu UNE fois.
 *
 * Cette lecture vivait dans `MmsDownloadedReceiver`, en fonctions privées. La reprise d'un PDU gardé en a
 * besoin à l'identique : deux copies auraient divergé — c'est le motif de défaut le plus fréquent de ce
 * dépôt —, et la règle de complétude de la reprise dépend de la liste EXACTE des parties retenues.
 */
data class ContenuMmsRecu(
    /** Adresse nettoyée : sans le suffixe de passerelle `/TYPE=PLMN`, sans caractère invisible. */
    val expediteur: String,
    /** Millisecondes. */
    val date: Long,
    val sujet: String?,
    /** Le texte saisi par l'expéditeur, stocké tel quel dans `messages.body`. */
    val legende: String?,
    /** Les parties porteuses de contenu, dans l'ordre du PDU. */
    val parties: List<PartieMms>,
    val destinataires: List<String>,
    val copies: List<String>,
) {
    /**
     * La ligne de la liste des conversations et le texte de la notification : la légende, sinon le sujet,
     * sinon un libellé tiré du type de la première partie — l'utilisateur voit toujours quelque chose.
     */
    val libelleApercu: String
        get() = legende ?: sujet ?: LecteurRetrieveConf.libelleParDefaut(parties.firstOrNull()?.mime)
}

/** Une partie média d'un MMS reçu. Pas une `data class` : l'égalité d'un tableau d'octets est l'identité. */
class PartieMms(val octets: ByteArray, val mime: String)

object LecteurRetrieveConf {

    /** La légende et la mise en page ne sont pas des pièces jointes. */
    private val HORS_PIECES = setOf("text/plain", "application/smil")

    /** La date d'un PDU est en secondes. */
    private const val MS_PAR_SECONDE = 1000L

    /**
     * @param indiceExpediteur l'expéditeur annoncé par la notification WAP-Push, quand le `RetrieveConf`
     *   n'a pas de `From:` ; `null` à la reprise, qui ne l'a plus.
     * @param maintenant l'heure retenue quand le PDU ne porte pas de date.
     */
    fun lire(conf: RetrieveConf, indiceExpediteur: String?, maintenant: Long): ContenuMmsRecu {
        val corps = conf.body
        return ContenuMmsRecu(
            // v1.6.1 (audit SEC-08) — Bidi/RLO/ZWSP retirés de l'expéditeur, du sujet et de la légende
            // avant qu'ils n'atteignent la notification : le PDU est une entrée externe.
            // v1.26.1 (audit H5) — l'indice de repli est nettoyé LUI AUSSI : il arrive brut du WAP-Push,
            // avec le suffixe `/TYPE=PLMN`, précisément quand le `RetrieveConf` n'a pas de `From:`.
            expediteur = (conf.from?.string ?: indiceExpediteur ?: "")
                .stripMmsAddressSuffix()
                .stripInvisibleChars(),
            date = if (conf.date > 0) conf.date * MS_PAR_SECONDE else maintenant,
            sujet = conf.subject?.string?.stripInvisibleChars()?.takeIf { it.isNotBlank() },
            legende = corps?.let { premiereLegende(it) }?.stripInvisibleChars(),
            parties = corps?.let { partiesMedia(it) }.orEmpty(),
            destinataires = adresses(conf.to),
            copies = adresses(conf.cc),
        )
    }

    /**
     * v1.28.3 (F16) — **toutes** les parties porteuses de contenu, et non plus la première.
     *
     * La fonction s'arrêtait à la première partie utile : d'un MMS à plusieurs photos une seule restait,
     * et le PDU — seule copie — était supprimé ensuite. Et `text/x-vcard` était écarté comme du texte,
     * alors que c'est une pièce jointe : un MMS ne portant qu'une carte de visite donnait une bulle vide.
     *
     * Seuls `text/plain` (la légende, lue par [premiereLegende]) et `application/smil` (la mise en page)
     * restent écartés. v1.28.9 — cette liste fait foi pour la complétude d'un message repris.
     */
    internal fun partiesMedia(body: PduBody): List<PartieMms> = parties(body).mapNotNull { partie ->
        val mime = decoderMime(partie.contentType)?.takeIf { it !in HORS_PIECES }
        val donnees = partie.data?.takeIf { it.isNotEmpty() }
        if (mime != null && donnees != null) PartieMms(donnees, mime) else null
    }

    /**
     * Le texte de la première partie `text/plain` non vide, s'il y en a une.
     *
     * Le jeu de caractères « quelconque » du WAP (MIBenum 0, rendu `*` par [CharacterSets]) n'est pas un
     * jeu Java valide : `charset("*")` lèverait et la légende disparaîtrait. Faute de jeu utilisable, on
     * lit en UTF-8, ce que produit tout téléphone Android récent.
     */
    internal fun premiereLegende(body: PduBody): String? = parties(body)
        .filter { decoderMime(it.contentType) == "text/plain" }
        .firstNotNullOfOrNull { partie ->
            val donnees = partie.data
            if (donnees == null || donnees.isEmpty()) {
                null
            } else {
                runCatching { String(donnees, jeuDeCaracteres(partie.charset)) }
                    .getOrNull()
                    ?.trim()
                    ?.takeIf { it.isNotEmpty() }
            }
        }

    /**
     * Le type d'une partie, sans le NUL final des chaînes WAP (`"text/plain "` ≠ `"text/plain"`) ni
     * ses paramètres (`; charset=…`), en minuscules.
     */
    internal fun decoderMime(octets: ByteArray?): String? {
        if (octets == null || octets.isEmpty()) return null
        val fin = octets.indexOf(0.toByte()).let { if (it < 0) octets.size else it }
        if (fin == 0) return null
        return String(octets, 0, fin).substringBefore(';').trim().lowercase().takeIf { it.isNotEmpty() }
    }

    /** Le libellé d'aperçu quand il n'y a ni légende ni sujet, sur le modèle des messageries courantes. */
    fun libelleParDefaut(mime: String?): String {
        val m = mime?.lowercase() ?: return "[MMS]"
        return when {
            m.startsWith("audio/") -> "🎤"
            m.startsWith("image/") -> "🖼️"
            m.startsWith("video/") -> "🎞️"
            else -> "📎"
        }
    }

    /** Les parties du corps, dans l'ordre du PDU. */
    private fun parties(body: PduBody): List<PduPart> = (0 until body.partsNum).mapNotNull { body.getPart(it) }

    private fun adresses(valeurs: Array<out EncodedStringValue>?): List<String> =
        valeurs?.map { it.string.stripMmsAddressSuffix().stripInvisibleChars() }.orEmpty()

    private fun jeuDeCaracteres(mibEnum: Int): java.nio.charset.Charset {
        val nom = runCatching { CharacterSets.getMimeName(mibEnum) }.getOrNull()
        if (nom.isNullOrEmpty() || nom == "*") return Charsets.UTF_8
        return runCatching { charset(nom) }.getOrDefault(Charsets.UTF_8)
    }
}
