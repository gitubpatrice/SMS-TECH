package com.filestech.sms.data.mms

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.telephony.SmsManager
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager
import androidx.core.content.FileProvider
import com.filestech.sms.core.result.AppError
import com.filestech.sms.core.result.Outcome
import dagger.hilt.android.qualifiers.ApplicationContext
import timber.log.Timber
import java.io.File
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Triggers the OS-managed retrieval of an incoming MMS body from the MMSC.
 *
 * Input: the `contentLocation` URL carried by the [com.filestech.sms.pdu.NotificationInd]
 * that arrived via WAP_PUSH. We create an empty cache file, hand its [FileProvider] Uri to
 * [SmsManager.downloadMultimediaMessage], and Android takes care of the rest (APN routing,
 * HTTP GET, writing the [com.filestech.sms.pdu.RetrieveConf] PDU into our file). The
 * result is delivered to [com.filestech.sms.system.receiver.MmsDownloadedReceiver].
 */
@Singleton
class MmsDownloader @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    /**
     * @param transactionIdOctets 2026-11-06 — le `transactionId` de la notification, en OCTETS : la
     *   réponse au MMSC ([MmsSender.repondreAuMmsc]) doit le rendre à l'identique, ce que la chaîne
     *   [transactionId] (décodée en UTF-8, et qui fait la clé du PDU) ne garantit pas.
     */
    fun download(
        contentLocation: String,
        transactionId: String?,
        senderAddress: String?,
        subId: Int? = null,
        transactionIdOctets: ByteArray? = null,
    ): Outcome<Unit> {
        if (contentLocation.isBlank()) return Outcome.Failure(AppError.Validation("empty contentLocation"))
        // v1.3.10 (SEC-05) — defense in depth: reject any contentLocation whose scheme is
        // not `http(s)://`. The WAP push receiver is OS-protected (BROADCAST_WAP_PUSH), and
        // every production AOSP telephony stack rejects non-HTTP schemes downstream, but
        // some legacy OEM stacks were observed honouring `file://` / `content://` URIs —
        // we'd rather fail the download loudly than let `SmsManager` chase an arbitrary URI.
        if (!adresseHttp(contentLocation)) {
            val scheme = contentLocation.substringBefore("://", missingDelimiterValue = "").lowercase()
            Timber.w("Rejected MMS contentLocation with non-HTTP scheme: %s", scheme)
            return Outcome.Failure(AppError.Validation("contentLocation scheme not allowed: $scheme"))
        }

        val dir = File(context.cacheDir, MMS_IN_DIR).apply { mkdirs() }
        // v1.28.9 (F17) — le nom porte la clé de transaction et la SIM : si le traitement échoue et que le
        // PDU est gardé, la reprise reconnaîtra le message déjà écrit au lieu de le dupliquer, cf.
        // [PdusEnAttente]. Seul ce téléchargeur écrit la clé ; le receveur et la reprise la LISENT ici.
        val pduFile = File(
            dir,
            PdusEnAttente.nom(
                horodatage = System.currentTimeMillis(),
                aleatoire = UUID.randomUUID().toString().take(8),
                cle = PdusEnAttente.cle(transactionId, contentLocation, subId),
                subId = subId,
            ),
        )
        try {
            // Create the file empty; SmsManager writes the downloaded PDU into it.
            pduFile.createNewFile()
        } catch (t: Throwable) {
            Timber.w(t, "Cannot create MMS PDU destination file")
            return Outcome.Failure(AppError.Storage(t))
        }

        val pduUri = try {
            FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", pduFile)
        } catch (t: Throwable) {
            pduFile.delete()
            return Outcome.Failure(AppError.Storage(t))
        }

        // Audit P0-2 (v1.2.0): Intent **explicit** via `setClass`. The previous `setPackage`-only
        // implicit form relied on the static receiver having an `<intent-filter>` for the custom
        // action, which it doesn't — Android 14+ silently drops these broadcasts. Without delivery,
        // the receiver never runs → the downloaded PDU file (raw audio + sender headers) stays
        // forever in `cache/mms_incoming/`, leaking plaintext on adb-pull / forensics.
        val intent = Intent(ACTION_MMS_DOWNLOADED)
            .setClassName(context, "com.filestech.sms.system.receiver.MmsDownloadedReceiver")
            .apply {
                putExtra(EXTRA_PDU_FILE, pduFile.absolutePath)
                putExtra(EXTRA_TRANSACTION_ID, transactionId)
                putExtra(EXTRA_SENDER, senderAddress)
                // v1.22.0 — propage la SIM d'arrivée jusqu'au write-back Room
                // ([MmsDownloadedReceiver]). `null` (mono-SIM ou ROM sans extra) est encodé
                // comme INVALID_SUBSCRIPTION_ID et retraduit en `null` côté réception.
                putExtra(EXTRA_SUBSCRIPTION_ID, subId ?: SubscriptionManager.INVALID_SUBSCRIPTION_ID)
                // 2026-11-06 — de quoi répondre au MMSC une fois le message traité (m-notifyresp.ind).
                // L'intent est explicite, vers un receveur non exporté ; l'adresse ne va pas au journal.
                putExtra(EXTRA_CONTENT_LOCATION, contentLocation)
                putExtra(EXTRA_NOTIFICATION_TRANSACTION_ID, transactionIdOctets)
            }
        val reqCode = (pduFile.absolutePath.hashCode() and 0x7FFFFFFF)
        val pi = PendingIntent.getBroadcast(
            context, reqCode, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        return try {
            subscriptionAwareManager(subId).downloadMultimediaMessage(
                context, contentLocation, pduUri, null, pi,
            )
            Outcome.Success(Unit)
        } catch (t: Throwable) {
            Timber.w(t, "downloadMultimediaMessage failed")
            pduFile.delete()
            Outcome.Failure(AppError.Telephony("downloadMultimediaMessage failed", t))
        }
    }

    /**
     * 2026-11-06 (courriel d'un utilisateur Visible/Verizon) — l'adresse à télécharger, complétée de
     * l'identifiant de transaction quand l'opérateur l'attend ([AdresseDeTelechargement]). Le service MMS
     * d'Android ne le fait pas : c'est à l'application, comme dans AOSP Messaging. La même adresse sert au
     * téléchargement, à la clé du PDU gardé et à la réponse au MMSC.
     */
    fun adresseATelecharger(contentLocation: String, transactionId: String?, subId: Int?): String {
        val parLaConfiguration = runCatching {
            subscriptionAwareManager(subId).carrierConfigValues
                .getBoolean(SmsManager.MMS_CONFIG_APPEND_TRANSACTION_ID, false)
        }.getOrDefault(false)
        val ajouter = parLaConfiguration || AdresseDeTelechargement.operateurAttendLIdentifiant(operateurSim(subId))
        val adresse = AdresseDeTelechargement.completer(contentLocation, transactionId, ajouter)
        // Sans l'adresse : elle peut porter un jeton de session (cf. audit SEC-06 du receveur).
        Timber.i(
            "MMS: identifiant de transaction ajoute=%b (configuration=%b, operateur=%b)",
            adresse != contentLocation,
            parLaConfiguration,
            ajouter && !parLaConfiguration,
        )
        return adresse
    }

    /** MCC+MNC de la SIM de [subId] (`310004`…), ou `null` ; aucune permission requise. */
    private fun operateurSim(subId: Int?): String? = runCatching {
        val base = context.getSystemService(TelephonyManager::class.java) ?: return null
        val tm = if (subId != null && subId != SubscriptionManager.INVALID_SUBSCRIPTION_ID) {
            base.createForSubscriptionId(subId)
        } else {
            base
        }
        tm.simOperator?.takeIf { it.isNotBlank() }
    }.getOrNull()

    private fun subscriptionAwareManager(subId: Int?): SmsManager = when {
        subId == null -> @Suppress("DEPRECATION") SmsManager.getDefault()
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            context.getSystemService(SmsManager::class.java).createForSubscriptionId(subId)
        else -> @Suppress("DEPRECATION") SmsManager.getSmsManagerForSubscriptionId(
            if (subId == SubscriptionManager.INVALID_SUBSCRIPTION_ID) {
                SubscriptionManager.getDefaultSmsSubscriptionId()
            } else subId,
        )
    }

    companion object {
        const val ACTION_MMS_DOWNLOADED: String = "com.filestech.sms.action.MMS_DOWNLOADED"
        const val EXTRA_PDU_FILE: String = "com.filestech.sms.extra.PDU_FILE"
        const val EXTRA_TRANSACTION_ID: String = "com.filestech.sms.extra.TRANSACTION_ID"
        const val EXTRA_SENDER: String = "com.filestech.sms.extra.SENDER"
        /** v1.22.0 — SIM d'arrivée du MMS, propagée du WAP push jusqu'au write-back Room. */
        const val EXTRA_SUBSCRIPTION_ID: String = "com.filestech.sms.extra.SUBSCRIPTION_ID"

        /** 2026-11-06 — l'adresse de téléchargement, pour la réponse au MMSC. */
        const val EXTRA_CONTENT_LOCATION: String = "com.filestech.sms.extra.CONTENT_LOCATION"

        /** 2026-11-06 — le `transactionId` de la notification, en octets, pour la réponse au MMSC. */
        const val EXTRA_NOTIFICATION_TRANSACTION_ID: String = "com.filestech.sms.extra.NOTIFICATION_TRANSACTION_ID"

        /**
         * v1.3.10 (SEC-05) — `http://` ou `https://`, rien d'autre. 2026-11-06 — sortie du corps de
         * [download] : la réponse au MMSC, qui peut viser cette même adresse, applique la même règle.
         */
        fun adresseHttp(url: String): Boolean {
            val scheme = url.substringBefore("://", missingDelimiterValue = "").lowercase()
            return scheme == "http" || scheme == "https"
        }

        /**
         * Cache subdirectory (relative to [Context.getCacheDir]) where this class drops the
         * empty file handed to `SmsManager.downloadMultimediaMessage` and where the OS later
         * writes the `RetrieveConf` PDU. Exposed publicly so [MmsDownloadedReceiver] can
         * sandbox-verify the `EXTRA_PDU_FILE` path before reading it (v1.3.10 SEC-04).
         */
        const val MMS_IN_DIR: String = "mms_incoming"
    }
}
