package com.filestech.sms.data.mms

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import timber.log.Timber
import java.io.File
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

/**
 * v1.28.9 (F17, septième note d'Andrew sur la MR !38458) — **les PDU MMS entrants gardés pour une
 * reprise** : leur clé, la forme de leur nom, et leur effacement avec le message qu'ils portent.
 *
 * # Pourquoi une clé, et pourquoi dans le nom
 *
 * Aucun MMS entrant n'est écrit dans `content://mms` : le PDU téléchargé est la SEULE copie du message.
 * Quand son traitement échoue, `MmsDownloadedReceiver` le garde ; une reprise doit alors savoir si le
 * message a déjà été écrit — sinon elle le dupliquerait, ou, s'il a été supprimé entre-temps, le
 * ressusciterait. La clé est calculée par [MmsDownloader] AVANT le téléchargement, à partir de ce que
 * la notification WAP-Push lui a donné, et écrite dans le NOM du fichier : le receveur et la reprise la
 * lisent au même endroit, et la base la garde sur le message (`messages.mms_transaction_key`).
 *
 * # Ce qui entre dans la clé
 *
 * Le `transactionId` de la `NotificationInd` (en-tête obligatoire) ne suffit pas seul : il n'est unique
 * que pour un MMSC, et un téléphone à deux SIM parle à deux MMSC. L'adresse de téléchargement et la SIM
 * y entrent donc aussi (relecture GPT 5.2 de la conception, 2026-09-14). Les deux exemplaires d'une
 * même notification répétée par l'opérateur portent les trois à l'identique : même clé.
 *
 * Empreinte SHA-256 complète, 64 caractères hexadécimaux : ni l'adresse du MMSC — qui peut porter un
 * jeton — ni l'identifiant opérateur n'apparaissent en clair dans un nom de fichier ou en base.
 */
@Singleton
class PdusEnAttente @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    /** Le dossier où [MmsDownloader] fait écrire les PDU, et où ils restent quand on les garde. */
    val dossier: File get() = File(context.cacheDir, MmsDownloader.MMS_IN_DIR)

    /**
     * Efface les PDU gardés dont la clé figure dans [cles].
     *
     * @return le nombre d'échecs : fichier que `delete()` refuse, ou dossier présent mais illisible —
     *   ne rien voir n'est pas ne rien trouver. Un PDU qui résiste pourrait ressusciter son message à la
     *   reprise : l'appelant en décide selon son contrat.
     */
    fun effacerPourCles(cles: Collection<String>): Int {
        if (cles.isEmpty()) return 0
        val dossier = dossier
        if (!dossier.exists()) return 0
        val fichiers = dossier.listFiles() ?: return 1.also {
            Timber.w("pdu en attente : dossier illisible, %d cle(s) non traitee(s)", cles.size)
        }
        val voulues = cles.toHashSet()
        var echecs = 0
        for (fichier in fichiers.filter { lireNom(it.name)?.cle in voulues }) {
            if (!fichier.delete() && fichier.exists()) {
                echecs++
                Timber.w("pdu en attente : fichier non efface")
            }
        }
        return echecs
    }

    /**
     * Reste-t-il un PDU que la reprise peut rouvrir : non vide, et porteur d'une clé ? Sans clé, rien ne
     * reconnaîtrait un message déjà écrit ; vide, il n'y a rien à lire. Un dossier absent ne réclame rien.
     *
     * v1.28.9 (relecture GPT 5.2 du code F17, constat 7) — un dossier présent mais ILLISIBLE réclame une
     * passe : ne rien voir n'est pas ne rien trouver, et ce filet sert justement quand quelque chose a mal
     * tourné. Une passe de trop ne coûte qu'un réveil.
     */
    fun aReprendre(): Boolean {
        val dossier = dossier
        val fichiers = dossier.listFiles() ?: return dossier.exists()
        return fichiers.any { it.isFile && it.length() > 0L && lireNom(it.name)?.cle != null }
    }

    /** Ce que le nom d'un PDU dit de lui. [cle] est `null` pour un fichier écrit avant la 1.28.9. */
    data class Nom(val cle: String?, val subId: Int?)

    companion object {
        /**
         * v1.28.3 (F27) — plafond de lecture d'un PDU entrant, pour le receveur comme pour la reprise.
         *
         * Genereux au regard du reel : les MMSC francais plafonnent l'utile a 300 Ko, et l'en-tete plus
         * l'encodage n'en ajoutent qu'une fraction. Quatre megaoctets laissent donc passer tout MMS
         * legitime, y compris venu d'un operateur plus permissif, tout en bornant ce qu'un fichier
         * aberrant peut faire allouer. v1.28.9 — déplacé ici depuis `MmsDownloadedReceiver` : la reprise
         * lit les mêmes fichiers, sous la même borne.
         */
        const val PLAFOND_OCTETS: Long = 4L * 1024 * 1024

        /** Préfixe et extension des PDU entrants, partagés avec [MmsDownloader]. */
        private const val PREFIXE = "in-"
        private const val EXTENSION = ".pdu"

        /**
         * Forme stricte, ancrée : `in-<horodatage>-<8 hex>[-k<64 hex>][-s<subId>].pdu`. Une seule
         * fonction la lit, et une seule l'écrit ([nom]) : un nom mal lu serait un fichier « sans clé »,
         * donc jamais repris. Le `subId` est un `Int` : dix chiffres et un signe (relecture GPT 5.2 du code
         * F17, constat 5 — neuf chiffres faisaient perdre sa clé à un nom pourtant bien formé).
         */
        private val FORME = Regex("""^in-\d+-[0-9a-f]{8}(?:-k([0-9a-f]{64}))?(?:-s(-?\d{1,10}))?\.pdu$""")

        /**
         * La clé d'un MMS, ou `null` sans `transactionId` — il n'y a alors rien pour reconnaître le
         * message à la reprise, et le PDU ne sera pas repris.
         */
        fun cle(transactionId: String?, contentLocation: String, subId: Int?): String? {
            if (transactionId.isNullOrBlank()) return null
            // v1.28.9 (audit data-room du 2026-09-14, DR4) — chaque champ est précédé de sa longueur. Le
            // `transactionId` et l'adresse viennent de la notification WAP-Push, donc de l'extérieur, et
            // peuvent contenir le séparateur : « A|http://x » suivi de « y », et « A » suivi de « http://x|y »,
            // donnaient la même chaîne, donc la même clé — le second MMS passait pour un doublon du premier.
            val brut = listOf(transactionId, contentLocation, subId?.toString().orEmpty())
                .joinToString(separator = "|") { champ -> "${champ.length}:$champ" }
            return MessageDigest.getInstance("SHA-256")
                .digest(brut.toByteArray(Charsets.UTF_8))
                .joinToString(separator = "") { octet -> "%02x".format(octet) }
        }

        /** Le nom d'un PDU à télécharger. [aleatoire] : 8 caractères hexadécimaux. */
        fun nom(horodatage: Long, aleatoire: String, cle: String?, subId: Int?): String = buildString {
            append(PREFIXE).append(horodatage).append('-').append(aleatoire)
            if (cle != null) append("-k").append(cle)
            if (subId != null) append("-s").append(subId)
            append(EXTENSION)
        }

        /** `null` si [nom] n'est pas celui d'un PDU entrant. */
        fun lireNom(nom: String): Nom? = FORME.matchEntire(nom)?.let { trouve ->
            Nom(
                cle = trouve.groupValues[1].ifEmpty { null },
                subId = trouve.groupValues[2].ifEmpty { null }?.toIntOrNull(),
            )
        }
    }
}
