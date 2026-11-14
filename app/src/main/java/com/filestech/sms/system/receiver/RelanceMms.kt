package com.filestech.sms.system.receiver

import android.content.Intent
import android.net.Uri
import android.telephony.SubscriptionManager
import com.filestech.sms.data.mms.MmsDownloader
import java.security.MessageDigest

/**
 * 2026-11-14 (courriel d'un utilisateur en 1.30.0, téléphone sans services Google) — **le nouvel essai de
 * téléchargement d'un MMS reçu.**
 *
 * En Wi-Fi, ses MMS échouaient par intermittence avec le code 3, `SmsManager.MMS_ERROR_UNABLE_CONNECT_MMS` ;
 * couper le Wi-Fi puis demander à l'expéditeur de renvoyer faisait passer le message. Le service MMS d'Android
 * réclame un réseau CELLULAIRE doté de la capacité MMS (AOSP `MmsNetworkManager`, lu le 2026-11-14) ; quand il
 * ne l'obtient pas, `MmsRequest.execute` rend ce code SANS autre essai. L'application abandonnait alors le
 * message, alors qu'elle avait encore de quoi le redemander au MMSC — l'adresse de téléchargement, l'identifiant
 * de transaction, l'expéditeur, la SIM — et que le MMSC garde un message jusqu'à son expiration. AOSP Messaging
 * le redemande ; l'application aussi, désormais.
 *
 * # Deux chemins
 * - **automatique** : [ESSAIS_AUTOMATIQUES] essais, après [DELAIS_MS], pour les seuls échecs passagers
 *   ([echecPassager]) ;
 * - **manuel** : le bouton « Réessayer » de la notification et de la fenêtre d'explication, pour tout échec.
 *
 * # Rien sur le disque
 * Les paramètres voyagent dans des `PendingIntent` (alarme, action de notification), que le système tient en
 * mémoire — comme celui du résultat que [MmsDownloader] lui confie déjà. Ni l'adresse du MMSC, qui peut porter
 * un jeton, ni le numéro de l'expéditeur ne sont écrits en clair. Le prix : un redémarrage efface la reprise,
 * comme il efface la notification qui la porte.
 *
 * # La notification est la condition
 * Un essai automatique ne part que si la notification d'échec de CE message est encore affichée
 * (`MmsFailureNotifier.relanceAffichee`). L'effacement d'urgence annule toutes les notifications
 * (`PanicService.nukeEverything`) : une alarme restée armée ne peut donc pas recréer un message dans une base
 * qu'on vient de vider. Balayer la notification arrête de même les essais. Une notification jamais postée —
 * permission refusée, correspondant du coffre, session leurre — n'en arme aucun : rien ne change pour eux.
 */
object RelanceMms {

    /** Essais automatiques après le premier échec. Le bouton « Réessayer », lui, ne s'épuise pas. */
    const val ESSAIS_AUTOMATIQUES: Int = 3

    /**
     * Délai avant chaque essai automatique. Un essai peut lui-même attendre le réseau MMS jusqu'à trente
     * minutes (`MmsNetworkManager`, délai par défaut d'Android récent) : la fenêtre couvre ainsi quelques
     * heures. Et un essai EN ATTENTE aboutit dès que le réseau MMS monte — par exemple quand l'utilisateur
     * coupe le Wi-Fi.
     */
    private val DELAIS_MS: LongArray = longArrayOf(2 * MINUTE_MS, 10 * MINUTE_MS, 30 * MINUTE_MS)

    sealed interface Decision {
        /** Un essai automatique dans [delaiMs], qui sera la tentative [tentative]. */
        data class Automatique(val delaiMs: Long, val tentative: Int) : Decision

        /** Plus d'essai automatique : seul le bouton « Réessayer » reste. */
        data object Manuelle : Decision
    }

    /**
     * Que faire après l'échec [code] (`SmsManager.MMS_ERROR_*`) de la tentative [tentative] (0 = le
     * téléchargement d'origine) ?
     */
    fun apresEchec(code: Int, statutHttp: Int, tentative: Int): Decision {
        if (!echecPassager(code, statutHttp)) return Decision.Manuelle
        val rang = tentative.coerceAtLeast(0)
        if (rang >= ESSAIS_AUTOMATIQUES) return Decision.Manuelle
        return Decision.Automatique(DELAIS_MS[rang], rang + 1)
    }

    /**
     * Un échec qu'un nouvel essai, plus tard, peut lever : réseau MMS injoignable, données coupées ou absentes,
     * entrée/sortie, erreur imprévue du service, ou une erreur HTTP qui n'est pas un refus du MMSC (4xx, sauf
     * 408 et 429). Restent au seul bouton : APN invalide, configuration, abonnement invalide ou inactif, MMS
     * désactivés par l'opérateur, et tout code que le SDK de compilation ne déclare pas (13, « trop gros pour le
     * lien satellite », n'apparaît qu'en 36.1).
     */
    fun echecPassager(code: Int, statutHttp: Int): Boolean = when (code) {
        MMS_ERROR_HTTP_FAILURE -> statutHttp !in HTTP_REFUS || statutHttp in HTTP_PASSAGERS
        in CODES_PASSAGERS -> true
        else -> false
    }

    /** La tentative portée par [intent], bornée : l'extra vient de nos propres `PendingIntent`, mais rien n'y oblige. */
    fun tentativeDepuis(intent: Intent): Int =
        intent.getIntExtra(MmsDownloader.EXTRA_TENTATIVE, 0).coerceIn(0, ESSAIS_AUTOMATIQUES)

    /**
     * Ce qu'il faut pour redemander un MMS au MMSC : exactement ce que [MmsDownloader.download] reçoit.
     *
     * @property adresse l'adresse à télécharger, déjà complétée (`MmsDownloader.adresseATelecharger`).
     * @property transactionIdOctets l'identifiant en octets, pour la réponse au MMSC après le retrait.
     */
    class Demande private constructor(
        val adresse: String,
        val transactionId: String?,
        val expediteur: String?,
        val subId: Int?,
        val transactionIdOctets: ByteArray?,
        val tentative: Int,
    ) {
        /**
         * L'identité du message : elle nomme sa notification (étiquette) et ses `PendingIntent` (URI). Empreinte
         * SHA-256, comme la clé des PDU gardés : l'adresse du MMSC n'apparaît en clair nulle part.
         */
        val etiquette: String = empreinte(transactionId, adresse, subId)

        /** L'URI qui distingue les `PendingIntent` de ce message de ceux d'un autre (les extras n'y comptent pas). */
        val identite: Uri get() = Uri.parse("smstech://mms-relance/$etiquette")

        fun avecTentative(tentative: Int): Demande =
            Demande(adresse, transactionId, expediteur, subId, transactionIdOctets, tentative)

        /** Pose la demande dans [intent] : identité et extras. */
        fun dans(intent: Intent): Intent = intent.apply {
            data = identite
            putExtra(EXTRA_ADRESSE, adresse)
            putExtra(EXTRA_TRANSACTION_ID, transactionId)
            putExtra(EXTRA_EXPEDITEUR, expediteur)
            putExtra(EXTRA_SUB_ID, subId ?: SubscriptionManager.INVALID_SUBSCRIPTION_ID)
            putExtra(EXTRA_TRANSACTION_ID_OCTETS, transactionIdOctets)
            putExtra(EXTRA_RANG, tentative)
        }

        companion object {
            /**
             * La demande, ou `null` si [adresse] n'est pas `http(s)` — la règle de [MmsDownloader.download], qui
             * refuserait de toute façon. Une SIM invalide devient `null`, comme dans `MmsDownloadedReceiver`.
             */
            fun creer(
                adresse: String?,
                transactionId: String?,
                expediteur: String?,
                subId: Int?,
                transactionIdOctets: ByteArray?,
                tentative: Int,
            ): Demande? {
                if (adresse.isNullOrBlank() || !MmsDownloader.adresseHttp(adresse)) return null
                return Demande(
                    adresse = adresse,
                    transactionId = transactionId,
                    expediteur = expediteur,
                    subId = subId?.takeIf { it != SubscriptionManager.INVALID_SUBSCRIPTION_ID },
                    transactionIdOctets = transactionIdOctets?.takeIf { it.size <= OCTETS_TRANSACTION_MAX },
                    tentative = tentative.coerceIn(0, ESSAIS_AUTOMATIQUES),
                )
            }

            /** La demande posée par [dans], ou `null`. */
            fun depuis(intent: Intent): Demande? = creer(
                adresse = intent.getStringExtra(EXTRA_ADRESSE),
                transactionId = intent.getStringExtra(EXTRA_TRANSACTION_ID),
                expediteur = intent.getStringExtra(EXTRA_EXPEDITEUR),
                subId = intent.getIntExtra(EXTRA_SUB_ID, SubscriptionManager.INVALID_SUBSCRIPTION_ID),
                transactionIdOctets = intent.getByteArrayExtra(EXTRA_TRANSACTION_ID_OCTETS),
                tentative = intent.getIntExtra(EXTRA_RANG, 0),
            )

            /** Chaque champ précédé de sa longueur, comme `PdusEnAttente.cle` : aucun séparateur ne se confond. */
            private fun empreinte(transactionId: String?, adresse: String, subId: Int?): String {
                val brut = listOf("relance", transactionId.orEmpty(), adresse, subId?.toString().orEmpty())
                    .joinToString(separator = "|") { champ -> "${champ.length}:$champ" }
                return MessageDigest.getInstance("SHA-256")
                    .digest(brut.toByteArray(Charsets.UTF_8))
                    .joinToString(separator = "") { octet -> "%02x".format(octet) }
            }
        }
    }

    // Les valeurs de `SmsManager.MMS_ERROR_*`, écrites ici pour ne pas dépendre du niveau d'API qui les déclare ;
    // `RelanceMmsTest` vérifie qu'elles sont celles du SDK.
    internal const val MMS_ERROR_UNSPECIFIED = 1
    internal const val MMS_ERROR_UNABLE_CONNECT_MMS = 3
    internal const val MMS_ERROR_HTTP_FAILURE = 4
    internal const val MMS_ERROR_IO_ERROR = 5
    internal const val MMS_ERROR_RETRY = 6
    internal const val MMS_ERROR_NO_DATA_NETWORK = 8
    internal const val MMS_ERROR_DATA_DISABLED = 11

    private val CODES_PASSAGERS: Set<Int> = setOf(
        MMS_ERROR_UNSPECIFIED,
        MMS_ERROR_UNABLE_CONNECT_MMS,
        MMS_ERROR_IO_ERROR,
        MMS_ERROR_RETRY,
        MMS_ERROR_NO_DATA_NETWORK,
        MMS_ERROR_DATA_DISABLED,
    )

    private val HTTP_REFUS: IntRange = 400..499

    /** Délai dépassé, trop de requêtes : des refus qui invitent à revenir. */
    private val HTTP_PASSAGERS: Set<Int> = setOf(408, 429)

    /** Un identifiant de transaction tient en quelques dizaines d'octets ; au-delà, on ne le garde pas. */
    private const val OCTETS_TRANSACTION_MAX = 256

    private const val MINUTE_MS: Long = 60_000L

    private const val EXTRA_ADRESSE = "com.filestech.sms.extra.RELANCE_ADRESSE"
    private const val EXTRA_TRANSACTION_ID = "com.filestech.sms.extra.RELANCE_TRANSACTION_ID"
    private const val EXTRA_EXPEDITEUR = "com.filestech.sms.extra.RELANCE_EXPEDITEUR"
    private const val EXTRA_SUB_ID = "com.filestech.sms.extra.RELANCE_SUB_ID"
    private const val EXTRA_TRANSACTION_ID_OCTETS = "com.filestech.sms.extra.RELANCE_TRANSACTION_ID_OCTETS"
    private const val EXTRA_RANG = "com.filestech.sms.extra.RELANCE_RANG"
}
