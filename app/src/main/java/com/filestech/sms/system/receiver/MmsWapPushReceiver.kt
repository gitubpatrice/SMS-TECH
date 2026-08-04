package com.filestech.sms.system.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.filestech.sms.core.ext.stripMmsAddressSuffix
import com.filestech.sms.data.mms.MmsDownloader
import com.filestech.sms.di.ApplicationScope
import com.filestech.sms.pdu.NotificationInd
import com.filestech.sms.pdu.PduParser
import com.filestech.sms.system.notifications.MmsFailureNotifier
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * Receives MMS WAP_PUSH notifications. The intent carries the binary `m-notification.ind` PDU
 * in the `data` byte[] extra. We parse it to extract the MMSC `contentLocation` URL, then ask
 * [MmsDownloader] to trigger SmsManager.downloadMultimediaMessage. The actual message payload
 * arrives later via [MmsDownloadedReceiver].
 *
 * **v1.3.10 reception hardening** — we intentionally do NOT annotate the class with
 * `@AndroidEntryPoint`. Symptom observed on Samsung Galaxy S9 Android 10 One UI + Redmi 9A
 * MIUI 12 (2026-05-18): the Hilt-generated wrapper crashes silently during field injection
 * BEFORE `onReceive` is ever called when the system dispatches WAP_PUSH at cold-start (app
 * killed in background, the normal state for an SMS app). Result: every incoming MMS is
 * dropped, no log, no crash, no notification. Resolving the entry point on-demand inside
 * `onReceive` is safe (the [android.app.Application] is guaranteed initialized before any
 * broadcast dispatch, so the Singleton component is ready) and avoids the silent failure.
 *
 * Audit notes:
 *   - Malformed / truncated PDUs simply log a warning and bail (no crash).
 *   - We DO NOT auto-download messages larger than [MAX_AUTO_DOWNLOAD_BYTES] (default 1 MB) to
 *     guard against runaway data usage — the user can re-trigger from the conversation later.
 *     For SMS Tech's voice-only flow we should never hit that ceiling (clips capped at 300 KB).
 */
class MmsWapPushReceiver : BroadcastReceiver() {

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface MmsWapPushEntryPoint {
        fun mmsDownloader(): MmsDownloader
        // Audit R1 (v1.14.8) — notification user lorsque le MMS dépasse le cap auto-download.
        fun mmsFailureNotifier(): MmsFailureNotifier

        // v1.26.1 (audit H5) — filtrage des MMS entrants par la liste noire, cf. `onReceive`.
        fun blockedNumberRepository(): com.filestech.sms.domain.repository.BlockedNumberRepository

        @ApplicationScope
        fun applicationScope(): CoroutineScope
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != "android.provider.Telephony.WAP_PUSH_DELIVER") return
        val pdu = intent.getByteArrayExtra("data")
        if (pdu == null || pdu.isEmpty()) {
            Timber.w("WAP_PUSH intent missing PDU data")
            return
        }
        // v1.22.0 (fix double SIM) — SIM d'arrivée du MMS. Sur un appareil multi-SIM, sans
        // ce subId le téléchargement partait toujours sur la SIM par défaut ([SmsManager
        // .getDefault]) → APN/MMSC de la mauvaise SIM → échec silencieux / retry différé pour
        // tout MMS reçu sur la SIM secondaire (ex. puce étrangère). `null` = comportement
        // historique inchangé (mono-SIM ou ROM qui ne renseigne pas l'extra).
        val subId = intent.extractIncomingSubId()

        val entry = try {
            EntryPointAccessors.fromApplication(
                context.applicationContext,
                MmsWapPushEntryPoint::class.java,
            )
        } catch (t: Throwable) {
            Timber.e(t, "Hilt entry point resolution failed in MmsWapPushReceiver")
            return
        }
        val downloader = entry.mmsDownloader()
        val failureNotifier = entry.mmsFailureNotifier()
        val blockedRepo = entry.blockedNumberRepository()
        val scope = entry.applicationScope()

        val pending = goAsync()
        scope.launch {
            try {
                val parsed = runCatching { PduParser(pdu).parse() }.getOrNull()
                if (parsed !is NotificationInd) {
                    Timber.w(
                        "WAP_PUSH PDU is not a NotificationInd (parsed=%s)",
                        parsed?.javaClass?.simpleName,
                    )
                    return@launch
                }
                val contentLocation = parsed.contentLocation?.let { String(it) }
                if (contentLocation.isNullOrBlank()) {
                    Timber.w("NotificationInd has no contentLocation")
                    return@launch
                }
                // v1.26.1 (audit H5) — la liste noire ne couvrait QUE les SMS entrants.
                //
                // Recensement fait : `isBlocked(` n'existait que dans [SmsDeliverReceiver] et
                // dans les trois use-cases d'ENVOI. Le côté envoi était parfaitement symétrique
                // sur les trois transports ; le côté réception ne couvrait qu'un des deux. Un
                // correspondant bloqué voyait donc ses SMS disparaître, mais ses MMS étaient
                // téléchargés, écrits en base ET notifiés — l'utilisatrice constatait que « le
                // blocage ne marche pas » sans comprendre pourquoi.
                //
                // Le contrôle est placé AVANT `downloader.download(...)` : cela évite aussi la
                // consommation de données, et la notification « MMS trop volumineux » de la
                // branche `else`, qui trahirait elle aussi l'arrivée du message.
                // ⚠️ `stripMmsAddressSuffix` est INDISPENSABLE : l'en-tête `From:` d'un PDU porte
                // le suffixe passerelle `/TYPE=PLMN`. Sans ce nettoyage, `blockKey()` voit des
                // lettres, traite l'adresse comme un expéditeur alphanumérique et ne peut jamais
                // correspondre à la clé numérique stockée — la garde serait un no-op silencieux.
                val fromAddress = parsed.from?.string?.stripMmsAddressSuffix()
                // v1.27.2 (audit externe 2026-08-04 #5) — cf. [isBlockedFailOpen] : même repli
                // ouvert qu'avant, sans avaler `CancellationException`.
                if (!fromAddress.isNullOrBlank() && blockedRepo.isBlockedFailOpen(fromAddress)) {
                    Timber.i("Dropping incoming MMS from blocked sender")
                    return@launch
                }
                // OMA-MMS-ENC §7.3.21: `X-Mms-Message-Size` is OPTIONAL in m-notification.ind.
                // [PduHeaders.getLongInteger] returns -1 when the header is absent — observed
                // in the wild on several MVNOs (Lebara, Lycamobile) and a number of non-EU
                // carriers. Treat "absent" identically to "present and within cap"; only an
                // explicitly oversized PDU is skipped.
                val size = parsed.messageSize
                val sizeUnknown = size <= 0L
                val sizeAcceptable = size in 1..MAX_AUTO_DOWNLOAD_BYTES
                if (sizeUnknown || sizeAcceptable) {
                    val transactionId = parsed.transactionId?.let { String(it) }
                    val sender = parsed.from?.string
                    val res = downloader.download(contentLocation, transactionId, sender, subId)
                    // v1.6.1 (audit SEC-06) — `loc=%s` retiré : `contentLocation` est
                    // l'URL MMSC opérateur qui peut contenir un token de session ou un
                    // identifiant de transaction dans le path/query. En debug logcat
                    // c'était lisible par toute app détentrice de READ_LOGS.
                    Timber.i("MMS auto-download triggered size=%d outcome=%s", size, res)
                } else {
                    // Audit R1 (v1.14.8) — avant : silence total côté user. Maintenant on
                    // notifie : "MMS de %sender% trop volumineux (%size% KB) — touchez pour
                    // ouvrir les paramètres MMS". L'user peut soit augmenter le cap dans les
                    // settings opérateur, soit demander à l'expéditeur de réduire/refragmenter.
                    Timber.w("MMS auto-download skipped (size=%d > %d)", size, MAX_AUTO_DOWNLOAD_BYTES)
                    val sender = parsed.from?.string
                    failureNotifier.notifyFailure(
                        reason = MmsFailureNotifier.Reason.TOO_LARGE,
                        senderAddress = sender,
                        sizeBytes = size,
                    )
                }
            } catch (t: Throwable) {
                // Symétrie avec SmsDeliverReceiver/MmsDownloadedReceiver/MmsSentReceiver :
                // une exception dans le pipeline parse → download → notify ne doit JAMAIS
                // remonter non capturée d'un `launch` sur l'ApplicationScope (SupervisorJob),
                // sinon elle atteint le handler d'exception global → crash du process.
                // Le `finally` garantit déjà `pending.finish()` ; ce catch évite le crash.
                Timber.w(t, "MMS WAP_PUSH handling failed")
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        /** 1 MiB safety ceiling. SMS Tech voice clips are ≤ 300 KB, this is a defence in depth. */
        private const val MAX_AUTO_DOWNLOAD_BYTES: Long = 1024L * 1024L
    }
}
