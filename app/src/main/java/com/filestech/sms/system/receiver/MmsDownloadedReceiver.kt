package com.filestech.sms.system.receiver

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.telephony.SubscriptionManager
import com.filestech.sms.core.io.LectureBornee
import com.filestech.sms.data.mms.MmsDownloader
import com.filestech.sms.data.mms.PdusEnAttente
import com.filestech.sms.di.ApplicationScope
import com.filestech.sms.pdu.PduParser
import com.filestech.sms.pdu.RetrieveConf
import com.filestech.sms.system.notifications.MmsFailureNotifier
import com.filestech.sms.system.scheduler.RepriseMmsWorker
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import timber.log.Timber
import java.io.File

/**
 * v1.28.3 (F17) — etat d'une transaction MMS en cours de traitement.
 *
 * @property vu horodatage d'entree, pour la purge par age.
 * @property consigne `true` seulement quand le message a REELLEMENT ete ecrit en base. C'est la
 *   seule condition sous laquelle le PDU d'un rejeu peut etre supprime : tant qu'elle est fausse,
 *   le PDU reste la seule copie du media et doit survivre.
 */
private data class EtatTransaction(val vu: Long, val consigne: Boolean)

/**
 * Receives the result of [MmsDownloader.download]. The OS has written the binary RetrieveConf
 * PDU into the cache file whose path we passed in the PendingIntent. We check and read that file,
 * parse the PDU, and hand it to [TraitementMmsRecu], which writes the message and its attachments.
 *
 * **v1.3.10** — No more `@AndroidEntryPoint`. Same root cause as [MmsWapPushReceiver]: silent Hilt
 * injection crash on Android 10 OEM ROMs when the receiver is dispatched at cold-start.
 * [EntryPointAccessors.fromApplication] is resolved on-demand inside [onReceive].
 *
 * **v1.28.9 (F17)** — la lecture du `RetrieveConf`, l'écartement, le groupe, l'écriture et les
 * notifications ont quitté ce receveur pour [TraitementMmsRecu] : la reprise d'un PDU gardé
 * ([RepriseMmsWorker]) en a besoin à l'identique. Reste ici ce qui est propre à l'arrivée : le résultat du
 * téléchargement, le bac à sable du chemin, la lecture bornée, l'exemplaire répété par l'opérateur, et le
 * sort du fichier.
 */
class MmsDownloadedReceiver : BroadcastReceiver() {

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface MmsDownloadedEntryPoint {
        // Audit R2 (v1.14.8) — Notification user lorsque le download MMS échoue (rc != OK).
        fun mmsFailureNotifier(): MmsFailureNotifier

        // v1.28.9 (F17) — lecture, écartement (H5, F26), groupe (v1.28.4), écriture et notifications,
        // partagés avec la reprise des PDU gardés.
        fun traitementMmsRecu(): TraitementMmsRecu

        @ApplicationScope
        fun applicationScope(): CoroutineScope
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != MmsDownloader.ACTION_MMS_DOWNLOADED) return
        val pduPath = intent.getStringExtra(MmsDownloader.EXTRA_PDU_FILE)
        val senderHint = intent.getStringExtra(MmsDownloader.EXTRA_SENDER)
        // v1.22.0 (fix double SIM) — SIM d'arrivée propagée par [MmsDownloader]. Encodée
        // INVALID_SUBSCRIPTION_ID quand inconnue → retraduite en `null` (colonne `sub_id`
        // laissée nulle, comme les MMS reçus avant cette version).
        val subId = intent
            .getIntExtra(MmsDownloader.EXTRA_SUBSCRIPTION_ID, SubscriptionManager.INVALID_SUBSCRIPTION_ID)
            .takeIf { it != SubscriptionManager.INVALID_SUBSCRIPTION_ID }
        val rc = resultCode
        val appContext = context.applicationContext

        val entry = try {
            EntryPointAccessors.fromApplication(
                appContext,
                MmsDownloadedEntryPoint::class.java,
            )
        } catch (t: Throwable) {
            Timber.e(t, "Hilt entry point resolution failed in MmsDownloadedReceiver")
            return
        }
        // v1.24.0 SEC-CRIT — `entry.traitementMmsRecu()` provisionne `AppDatabase`, donc la réparation
        // zéro-clé. Résoudre ici l'exécutait sur le main thread d'`onReceive`, sous un timeout ANR de
        // broadcast de 10 s. Seul le scope est résolu en amont : il n'ouvre aucune base.
        val scope = entry.applicationScope()

        val pending = goAsync()
        // v1.27.2 (audit externe Gemini 2026-08-04) — le fichier RÉELLEMENT validé par la garde
        // sandbox ci-dessous, seul autorisé à être supprimé dans le `finally`. Cf. le commentaire
        // qui accompagne cette suppression.
        var validatedPdu: File? = null
        // v1.27.2 (relecture Codex 2026-08-04) — le PDU n'est CONSOMMÉ que lorsque son sort est
        // réglé : message écrit en base, expéditeur bloqué pour de bon, doublon déjà traité, ou
        // contenu inexploitable. Tant que ce drapeau est faux, le fichier est la SEULE copie du
        // MMS et de sa pièce jointe — on ne le supprime pas.
        var pduConsumed = false
        // v1.28.3 (F17) — retenu hors du `try` pour que le `catch` puisse RETIRER la marque de
        // transaction. Sans quoi un echec de persistance laissait le txId marque, et le rejeu
        // qui aurait pu rattraper le message etait ecarte comme un doublon.
        var txIdPourReprise: String? = null
        scope.launch {
            try {
                if (rc != Activity.RESULT_OK) {
                    // Audit R2 (v1.14.8) — avant : log + return silencieux, l'user ne savait
                    // pas qu'un MMS lui était destiné. Maintenant on poste une notification
                    // sur le canal FAILED pour l'inviter à vérifier le signal et retry depuis
                    // l'app système (ou ressayer plus tard). `senderHint` peut être null si
                    // MmsDownloader n'a pas pu l'extraire — le notifier fallback alors sur
                    // "un contact inconnu".
                    Timber.w("MMS download failed rc=%d path=%s", rc, pduPath)
                    entry.mmsFailureNotifier().notifyFailure(
                        reason = MmsFailureNotifier.Reason.DOWNLOAD_FAILED,
                        senderAddress = senderHint,
                    )
                    return@launch
                }
                val pduFile = pduPath?.let { File(it) }
                if (pduFile == null) {
                    Timber.w("MMS PDU missing or empty: %s", pduPath)
                    return@launch
                }
                // v1.3.10 (SEC-04) — sandbox check: the EXTRA_PDU_FILE path is set by
                // [MmsDownloader] inside our own process and the receiver is `exported=false`,
                // so a malicious external sender cannot reach this code with a forged path
                // today. We still canonicalize + verify the resolved file lives inside our
                // `cacheDir/mms_incoming/` sandbox so an accidental future regression (a new
                // intent-filter, an `exported` flip, a test helper) cannot turn this receiver
                // into a "read any file the app can see" primitive.
                val sandboxDir = runCatching {
                    File(appContext.cacheDir, MmsDownloader.MMS_IN_DIR).canonicalFile
                }.getOrNull()
                val canonicalPdu = runCatching { pduFile.canonicalFile }.getOrNull()
                if (sandboxDir == null || canonicalPdu == null ||
                    !canonicalPdu.toPath().startsWith(sandboxDir.toPath())
                ) {
                    Timber.w("MMS PDU path outside sandbox: %s", pduPath)
                    return@launch
                }
                validatedPdu = canonicalPdu
                // v1.27.2 (audit externe Gemini 2026-08-04) — le test d'existence/taille est
                // passé APRÈS la garde sandbox : il touchait auparavant un chemin non validé, et
                // surtout un PDU vide sortait avant elle — donc, une fois la suppression
                // restreinte au fichier validé, il n'aurait plus jamais été nettoyé du cache.
                // v1.28.9 (relecture GPT 5.2 du code F17, constat 4) — et il porte, comme la lecture et
                // la suppression, sur le fichier VALIDÉ : le chemin brut n'est plus touché après la garde.
                if (!canonicalPdu.exists() || canonicalPdu.length() == 0L) {
                    Timber.w("MMS PDU missing or empty: %s", pduPath)
                    // Rien à préserver : le fichier est absent ou vide.
                    pduConsumed = true
                    return@launch
                }
                // v1.28.3 (F27) — la lecture du PDU est BORNEE.
                //
                // `readBytes()` allouait le fichier entier sans aucune limite. Le chemin est
                // atteint depuis un broadcast systeme, sur un fichier ecrit par la pile
                // telephonie : sa taille ne depend pas de nous, et un MMS reel ne depasse pas
                // quelques centaines de kilooctets. Un fichier aberrant — anomalie de ROM,
                // stockage partage abime — faisait donc tomber le processus sur un
                // `OutOfMemoryError`. v1.28.9 — le plafond vit dans [PdusEnAttente], partagé
                // avec la reprise qui relit les mêmes fichiers.
                if (canonicalPdu.length() > PdusEnAttente.PLAFOND_OCTETS) {
                    Timber.w("MMS PDU too large (%d B): %s", canonicalPdu.length(), pduPath)
                    // Definitivement inexploitable : le conserver n'ouvrirait aucune reprise.
                    pduConsumed = true
                    return@launch
                }
                // v1.28.4 (F27) — la lecture passe par la borne TESTÉE (`LectureBornee.lire`),
                // qui refuse sur la taille avant d'allouer ; le garde ci-dessus garde son
                // journal et sa décision de consommer, la fonction garantit l'allocation.
                val bytes = LectureBornee.lire(canonicalPdu, PdusEnAttente.PLAFOND_OCTETS)
                if (bytes == null) {
                    Timber.w("Cannot read MMS PDU bytes: %s", pduPath)
                    return@launch
                }
                val parsed = runCatching { PduParser(bytes).parse() }.getOrNull()
                if (parsed !is RetrieveConf) {
                    Timber.w("MMS PDU is not RetrieveConf (parsed=%s)", parsed?.javaClass?.simpleName)
                    // Contenu définitivement inexploitable : le conserver ne mènerait à rien.
                    pduConsumed = true
                    return@launch
                }

                // Audit M-10: in-memory replay guard. A flaky carrier can deliver the same
                // `m-notification.ind` twice, which the OS dutifully turns into two
                // `RetrieveConf` PDUs — each parsed here would mirror the same MMS twice and
                // surface duplicates in the thread. We dedup by the PDU's `transactionId` for
                // [DEDUP_TTL_MS] (5 min): replays in the wild always come back within seconds,
                // and the bounded set means we cap the singleton's memory footprint.
                // v1.28.9 (relecture GPT 5.2 du code F17, constat 1) — la marque porte AUSSI la SIM,
                // cf. [marqueDeTransaction] : deux SIM, deux MMSC, et parfois un même identifiant.
                val txId = marqueDeTransaction(parsed.transactionId, subId)
                if (!txId.isNullOrEmpty()) {
                    val now = System.currentTimeMillis()
                    // v1.28.3 (F17) — deux etats, et non plus un seul horodatage.
                    //
                    // Le txId etait marque ICI, donc AVANT l'ecriture du message. Si l'insertion
                    // echouait — base indisponible, exactement la situation que le repli ouvert
                    // de la liste noire laisse passer — le `catch` avalait l'erreur, le PDU
                    // survivait, mais le txId restait marque. Un rejeu porteur du meme txId dans
                    // les cinq minutes etait alors declare doublon, et LUI voyait son PDU
                    // supprime : la seule occasion de rattraper le message partait avec.
                    //
                    // `EN_COURS` protege du traitement concurrent de deux exemplaires ; seul
                    // `CONSIGNE`, pose apres persistance reussie, autorise a supprimer le PDU
                    // d'un rejeu. Sur echec, la marque est RETIREE (cf. le `catch` plus bas) pour
                    // qu'un rejeu reparte de zero.
                    synchronized(processedTransactions) {
                        processedTransactions.entries.removeAll { now - it.value.vu > DEDUP_TTL_MS }
                        val deja = processedTransactions[txId]
                        if (deja != null) {
                            Timber.i("MMS replay suppressed: txId=%s consigne=%b", txId, deja.consigne)
                            // Le PDU du rejeu ne part QUE si l'exemplaire precedent a reellement
                            // abouti. Sinon on le conserve : il est la seule copie du media.
                            pduConsumed = deja.consigne
                            return@launch
                        }
                        processedTransactions[txId] = EtatTransaction(vu = now, consigne = false)
                        txIdPourReprise = txId
                    }
                }

                // v1.28.9 (F17) — la clé de transaction est lue dans le NOM du PDU, où [MmsDownloader]
                // l'a écrite avant le téléchargement ; la reprise la lit au même endroit. La porte
                // `exists()` est relue dans la transaction d'écriture : le PDU part avec son message quand
                // l'utilisateur supprime celui-ci.
                val cle = PdusEnAttente.lireNom(canonicalPdu.name)?.cle
                val issue = entry.traitementMmsRecu()
                    .traiter(parsed, cle, subId, senderHint) { canonicalPdu.exists() }
                // v1.27.2 (relecture Codex 2026-08-04) — le message est en base : le PDU a rempli son
                // office et peut être supprimé. v1.28.3 (F15) — sauf si un média n'a pas pu être écrit :
                // le PDU le porte encore, et le supprimer le perdrait ; v1.28.9 — la reprise le rouvrira.
                pduConsumed = !issue.garderLePdu
                // v1.28.3 (F17) — la transaction n'est CONSIGNEE qu'ici, une fois la ligne
                // reellement ecrite. Marquee plus haut, elle faisait passer un rejeu pour un
                // doublon abouti alors que rien n'avait ete persiste.
                if (issue.consigner && !txId.isNullOrEmpty()) {
                    synchronized(processedTransactions) {
                        processedTransactions[txId] =
                            EtatTransaction(vu = System.currentTimeMillis(), consigne = true)
                    }
                }
            } catch (ce: kotlinx.coroutines.CancellationException) {
                // v1.27.2 (relecture Codex 2026-08-04) — une annulation n'est PAS un traitement
                // abouti. Le `catch (Throwable)` ci-dessous l'absorbait, et le `finally`
                // supprimait alors le PDU d'un message jamais persisté — y compris celle que
                // [isBlockedFailOpen] relance justement pour ne pas la transformer en « non
                // bloqué ». On la laisse remonter, PDU intact.
                throw ce
            } catch (t: Throwable) {
                Timber.w(t, "MMS download handling failed")
                // v1.28.3 (F17) — l'echec RETIRE la marque, pour qu'un rejeu reparte de zero au
                // lieu d'etre pris pour un doublon deja traite.
                if (!txIdPourReprise.isNullOrEmpty()) {
                    synchronized(processedTransactions) { processedTransactions.remove(txIdPourReprise) }
                }
            } finally {
                // v1.27.2 (audit externe Gemini 2026-08-04) — on supprime le fichier VALIDÉ par
                // la garde sandbox, et uniquement lui.
                //
                // Avant : `File(pduPath).delete()` sur le chemin BRUT, exécuté dès que
                // `rc == RESULT_OK` — donc y compris après le `return@launch` du contrôle
                // sandbox. La garde couvrait la LECTURE et laissait passer la SUPPRESSION,
                // pourtant la plus destructrice des deux. Son propre commentaire promettait
                // qu'une régression future (nouvel intent-filter, `exported` basculé, aide de
                // test) ne pourrait pas transformer ce receveur en primitive sur des fichiers
                // arbitraires : c'était vrai en lecture, faux en suppression.
                //
                // Non atteignable aujourd'hui (`exported=false`, chemin posé par notre propre
                // processus) — c'est bien de la défense en profondeur qu'on rend cohérente, pas
                // une faille ouverte que l'on ferme.
                //
                // v1.27.2 (relecture Codex 2026-08-04) — et SEULEMENT si son sort est réglé.
                //
                // La condition ne portait que sur `rc == RESULT_OK`, c'est-à-dire sur la
                // réussite du TÉLÉCHARGEMENT, jamais sur celle du traitement. Si l'écriture du
                // message échouait — base indisponible, la situation même que le repli ouvert de
                // la liste noire laisse passer — le `catch` absorbait l'erreur et le `finally`
                // supprimait quand même le PDU. Le MMS et sa pièce jointe n'existaient alors
                // NULLE PART : ni en base, ni dans le fournisseur système, ni sur le disque.
                // Irrécupérables.
                //
                // Le fichier est désormais conservé tant que rien n'a réglé son sort. Il reste
                // dans `cacheDir`, que le système récupère sous pression — préférer quelques
                // kilo-octets orphelins à la perte d'un message est le bon sens d'échec pour
                // cette application.
                if (rc == Activity.RESULT_OK && pduConsumed) {
                    validatedPdu?.let { pdu -> runCatching { pdu.delete() } }
                } else if (rc == Activity.RESULT_OK && validatedPdu != null) {
                    // v1.28.9 (F17) — UN PDU GARDÉ EST REPRIS. Jusqu'ici il attendait le balayage de
                    // 24 h sans que rien ne le rouvre : le média perdu l'était pour de bon.
                    runCatching { RepriseMmsWorker.planifier(appContext) }
                        .onFailure { Timber.w(it, "Reprise MMS: planification echouee") }
                }
                pending.finish()
            }
        }
    }

    // v1.26.1 (audit H5) — `stripMmsAddressSuffix` a déménagé dans `core/ext/StringExt.kt` :
    // la garde de liste noire des MMS entrants en a besoin des deux côtés du pipeline, et une
    // copie privée par récepteur aurait garanti qu'un des deux finisse par l'oublier.

    private companion object {
        /** Audit M-10: TTL for the in-memory dedup set. 5 min covers real-world carrier replays. */
        const val DEDUP_TTL_MS: Long = 5 * 60 * 1_000L

        /**
         * v1.3.10 (SEC-03/P4) — hard ceiling on the dedup set. A storm of distinct fresh
         * transaction-ids (carrier hiccup or hypothetical replay flood from an internal
         * component) would otherwise let the map grow unbounded for the full TTL window.
         * 256 entries × ~40 B ≈ 10 KiB worst case — still well under any sane budget for
         * 5 minutes of MMS bursts, and gives the LinkedHashMap-LRU eviction priority over
         * the time-based prune when both kick in.
         */
        const val DEDUP_MAX_ENTRIES: Int = 256

        /**
         * Process-wide cache of recently-mirrored MMS transaction IDs. Bounded by
         * [DEDUP_TTL_MS] (time) AND [DEDUP_MAX_ENTRIES] (count). The [LinkedHashMap] preserves
         * insertion order so [removeEldestEntry] gives us a free LRU eviction; the existing
         * `removeAll { now - it.value > DEDUP_TTL_MS }` sweep still removes time-expired entries
         * on every new insert.
         */
        @JvmStatic
        private val processedTransactions =
            object : LinkedHashMap<String, EtatTransaction>(64, 0.75f, false) {
                override fun removeEldestEntry(eldest: Map.Entry<String, EtatTransaction>): Boolean =
                    size > DEDUP_MAX_ENTRIES
            }
    }
}
