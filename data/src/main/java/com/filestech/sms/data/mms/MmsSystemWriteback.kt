package com.filestech.sms.data.mms

import android.content.ContentResolver
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.provider.Telephony
import com.filestech.sms.core.ext.stripInvisibleChars
import com.filestech.sms.di.IoDispatcher
import com.filestech.sms.domain.mms.MmsAttachment
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Writes outgoing-MMS rows + addresses + parts into the **system content provider**
 * (`content://mms`), so an MMS sent through SMS Tech survives a re-install (system rows
 * persist while our local SQLCipher mirror is wiped) and is also visible to other SMS apps
 * the user might switch to later.
 *
 * Background: `SmsManager.sendMultimediaMessage` only dispatches the PDU — it does **not**
 * automatically mirror the outgoing message into `Telephony.Mms`. Most third-party Android
 * SMS apps (including Google Messages on AOSP) write the row themselves. The pattern is:
 *
 *  1. **`insertOutbox(...)`** before dispatch — row appears with `msg_box = OUTBOX (4)`.
 *  2. The OS `MmsService` takes over and runs the MMSC POST.
 *  3. On the result callback (`MmsSentReceiver`), call **`markSent(id)`** (→ `msg_box = SENT (2)`)
 *     or **`delete(id)`** on failure so we don't leave a stale outbox row.
 *
 * Reading + writing `content://mms` requires being the default SMS app. Calls return null /
 * false silently when that's not the case — the caller logs but doesn't crash.
 */
@Singleton
class MmsSystemWriteback @Inject constructor(
    @ApplicationContext private val context: Context,
    @IoDispatcher private val io: CoroutineDispatcher,
) {

    private val resolver: ContentResolver get() = context.contentResolver

    private inline fun <T> safe(label: String, block: () -> T): T? =
        runCatching(block).onFailure {
            Timber.w(it, "MmsSystemWriteback.%s failed", label)
        }.getOrNull()

    /**
     * Strips whitespace, dashes and parens so callers passing `"+33 6 12 34 56 78"` and
     * `"+33612345678"` resolve to the same canonical-addresses row inside `getOrCreateThreadId`.
     * Without this, Samsung One UI's `canonical_addresses` table can index the two forms as
     * distinct entries, producing duplicate threads after a reinstall.
     */
    private fun canonicalRecipients(raw: Collection<String>): Set<String> =
        raw.asSequence()
            // v1.2.7 audit S2 : strip d'abord les chars bidi/zero-width (RLO, LRE, BOM…)
            // pour ne pas hit un thread_id distinct côté Samsung canonical_addresses pour le
            // même destinataire visuel — sinon un caller fournissant `"+33‮6 12…"` peut
            // créer une conversation fantôme.
            .map { it.stripInvisibleChars() }
            .map { it.replace(Regex("[\\s\\-()]"), "") }
            .filter { it.isNotBlank() }
            .toHashSet()

    /**
     * Rejects mime types that fail a strict shape check OR attachment files that escape the
     * app's private cache. Both are defense-in-depth — today's callers only stage files under
     * `cache/voice_mms/` or `cache/media_outgoing/` and pick mime types from
     * `ContentResolver.getType`, but a future feature (share-from-other-app, ACTION_SEND) could
     * pass arbitrary input. Without these guards an exotic mime string can crash Samsung's
     * `SemMmsProvider` and a `File("/data/data/<other>/secret")` would be streamed into our row.
     */
    private fun piecesSures(attachments: List<Piece>): Boolean {
        val mimeRegex = Regex("^[a-zA-Z0-9.+/-]{3,80}$")
        val sandboxRoots = listOf(
            runCatching { context.cacheDir.canonicalPath }.getOrNull(),
            runCatching { context.filesDir.canonicalPath }.getOrNull(),
        ).filterNotNull()
        for (a in attachments) {
            if (!mimeRegex.matches(a.mimeType)) {
                Timber.w("MmsSystemWriteback: rejecting suspicious mimeType '%s'", a.mimeType)
                return false
            }
            val canonical = runCatching { a.file.canonicalPath }.getOrNull() ?: run {
                Timber.w("MmsSystemWriteback: cannot canonicalise attachment path '%s'", a.file.path)
                return false
            }
            // v1.27.2 (audit externe Gemini 2026-08-04) — comparaison par SEGMENT, pas par
            // préfixe de chaîne. `startsWith` nu acceptait aussi un dossier FRÈRE dont le nom
            // commence par celui de la racine (« …/cache_autre » passait la garde de
            // « …/cache »). Les deux autres gardes sandbox du dépôt le font déjà correctement
            // — `MmsDownloadedReceiver` via `Path.startsWith`, `OutgoingAttachmentStoreImpl` en
            // concaténant le séparateur ; celle-ci était la seule des trois à comparer des
            // chaînes nues. Non exploitable aujourd'hui (seule l'application peut écrire dans
            // son propre répertoire de données), comme le dit déjà le KDoc : c'est une défense
            // en profondeur, on la rend cohérente avec ses jumelles.
            if (sandboxRoots.none { canonical == it || canonical.startsWith(it + File.separator) }) {
                Timber.w("MmsSystemWriteback: attachment escapes sandbox: %s", canonical)
                return false
            }
        }
        return true
    }

    /**
     * Mirrors an outgoing MMS into `content://mms` *before* dispatch, returning the system
     * `_id` so the receiver can flip it to SENT or delete it on failure. Returns `null` if we
     * are not the default SMS app (provider refuses the insert).
     *
     * **Why** : `SmsManager.sendMultimediaMessage` does not writeback to `content://mms` on
     * Samsung One UI — without this, sent MMS disappear at reinstall and other SMS apps never
     * see them. Inline AOSP conventions in the code:
     *  - date in seconds, msg_box = OUTBOX (4)
     *  - attachment bytes streamed via `openOutputStream` (Android 10+ rejects `_data`)
     *  - text body added as a `text/plain` part alongside binary parts.
     */
    suspend fun insertOutbox(
        recipients: List<String>,
        attachments: List<MmsAttachment>,
        textBody: String?,
    ): Long? = withContext(io) {
        if (recipients.isEmpty()) return@withContext null
        if (!piecesSures(attachments.map { Piece(it.file, it.mimeType) })) return@withContext null

        val nowSec = System.currentTimeMillis() / 1000L

        // Resolve (or create) the AOSP thread_id for this recipient set. Without this, the row
        // ends up in a phantom thread separate from the SMS conversation, so after a reinstall
        // our reimport pipeline groups the sent MMS into a new "conversation" duplicate of the
        // existing one. We canonicalise the recipients (strip whitespace/dashes/parens) before
        // resolving so `"+33 6 12 34 56 78"` and `"+33612345678"` hit the same canonical-address
        // row — Samsung One UI's `canonical_addresses` is sensitive to exact string form.
        val systemThreadId: Long = safe("getOrCreateThreadId") {
            Telephony.Threads.getOrCreateThreadId(context, canonicalRecipients(recipients))
        } ?: 0L

        // 1. Insert the main MMS row in /outbox.
        val mmsValues = ContentValues().apply {
            put(Telephony.Mms.DATE, nowSec)
            put(Telephony.Mms.DATE_SENT, nowSec)
            put(Telephony.Mms.MESSAGE_BOX, Telephony.Mms.MESSAGE_BOX_OUTBOX)
            put(Telephony.Mms.READ, 1)
            put(Telephony.Mms.SEEN, 1)
            put(Telephony.Mms.MESSAGE_TYPE, MSG_TYPE_SEND_REQ)
            put(Telephony.Mms.MMS_VERSION, MMS_VERSION_1_0)
            put(Telephony.Mms.PRIORITY, PRIORITY_NORMAL)
            put(Telephony.Mms.CONTENT_TYPE, "application/vnd.wap.multipart.related")
            if (systemThreadId > 0L) put(Telephony.Mms.THREAD_ID, systemThreadId)
        }
        val mmsUri = safe("insertOutbox.row") {
            resolver.insert(Telephony.Mms.Outbox.CONTENT_URI, mmsValues)
        } ?: return@withContext null
        val mmsId = mmsUri.lastPathSegment?.toLongOrNull() ?: run {
            Timber.w("MmsSystemWriteback: cannot parse mmsId from %s", mmsUri)
            return@withContext null
        }
        // 2026-11-10 (issue #87) — un refus ne lève pas : il rend `…/0` (`ContentProvider.rejectInsert`).
        // Le KDoc promettait `null` dans ce cas ; on rendait 0, et la suite écrivait sous `content://mms/0`.
        if (mmsId <= 0L) {
            Timber.w("MmsSystemWriteback: content://mms a refusé l'écriture (rôle SMS ?)")
            return@withContext null
        }

        // 2. Insert addresses — each insert in its own safe() so a single failure (e.g. FROM
        // rejected) does not silently skip the remaining TO rows. Without `addr` rows, the MMS
        // shows up in other SMS apps without any recipient label.
        val addrUri = Uri.parse("content://mms/$mmsId/addr")
        safe("addr.from") {
            resolver.insert(
                addrUri,
                ContentValues().apply {
                    put("address", "insert-address-token")
                    put("type", ADDR_TYPE_FROM)
                    put("charset", CHARSET_UTF8)
                },
            )
        }
        for (to in recipients) {
            safe("addr.to") {
                resolver.insert(
                    addrUri,
                    ContentValues().apply {
                        put("address", to)
                        put("type", ADDR_TYPE_TO)
                        put("charset", CHARSET_UTF8)
                    },
                )
            }
        }

        // 3. Insert parts. Optional text caption first, then each attachment.
        // 2026-11-19 (issue #98) — écriture partagée avec la copie d'un MMS reçu ([insertInbox]). Ici, comme
        // avant, une partie refusée n'arrête pas l'envoi : le résultat est ignoré.
        ecrireParties(mmsId, textBody, attachments.map { Piece(it.file, it.mimeType) })

        Timber.i("MmsSystemWriteback: inserted outbox row id=%d (%d recipient(s), %d attachment(s))", mmsId, recipients.size, attachments.size)
        mmsId
    }

    /** 2026-11-19 (issue #98) — un fichier à écrire dans une partie de `content://mms`. */
    private data class Piece(val file: File, val mimeType: String)

    /**
     * Les parties d'une ligne de `content://mms` : le texte d'abord, puis chaque pièce, ses octets copiés par
     * flux (`_data` est interdit depuis Android 10). Rend `true` seulement si TOUTES ont été écrites.
     *
     * 2026-11-19 (issue #98) — extrait de [insertOutbox] pour servir aussi à [insertInbox]. Une insertion qui rend
     * `…/0` (refus du fournisseur) compte désormais comme un échec, et l'on n'écrit pas d'octets dans `part/0`
     * (relecture GPT) ; pour un envoi, le résultat reste ignoré, comme avant.
     */
    private fun ecrireParties(mmsId: Long, textBody: String?, pieces: List<Piece>): Boolean {
        var toutEcrit = true
        val partsUri = Uri.parse("content://mms/$mmsId/part")
        if (!textBody.isNullOrBlank()) {
            val texte = ContentValues().apply {
                put("mid", mmsId)
                put("ct", "text/plain")
                put("chset", CHARSET_UTF8)
                put("cid", "<text>")
                put("cl", "text.txt")
                put("text", textBody)
            }
            if (idCree(safe("part.text") { resolver.insert(partsUri, texte) }) == null) toutEcrit = false
        }
        pieces.forEachIndexed { index, att ->
            val partValues = ContentValues().apply {
                put("mid", mmsId)
                put("ct", att.mimeType)
                put("cid", "<part$index>")
                put("cl", att.file.name)
                put("name", att.file.name)
            }
            val partUri = safe("part.bin#$index") {
                resolver.insert(partsUri, partValues)
            }?.takeIf { idCree(it) != null } ?: run {
                toutEcrit = false
                return@forEachIndexed
            }
            // Stream the file bytes into the part — `_data` is forbidden on Android 10+.
            // `use { copyTo }` flushes + closes both streams on exit.
            val copie = safe("part.stream#$index") {
                resolver.openOutputStream(partUri)?.use { os ->
                    att.file.inputStream().use { it.copyTo(os) }
                }
            }
            if (copie == null) toutEcrit = false
        }
        return toutEcrit
    }

    /**
     * 2026-11-19 (issue #98, plusieurs profils) — **écrit un MMS REÇU dans `content://mms`**, comme on y écrit déjà
     * les SMS reçus (`TelephonyReader.insertInboxSms`) et les MMS envoyés ([insertOutbox]). Rend son `_id`, ou `null`.
     *
     * Android garde UN stockage de messages pour tous les profils du téléphone, mais ne remet les messages entrants
     * qu'à l'application du profil principal. Les autres profils ne voient donc que ce que celle-ci y écrit : un MMS
     * reçu, gardé seulement dans la base chiffrée de SMS Tech, n'y arrivait jamais (issue #98, GrapheneOS). Il
     * manquait aussi aux autres applications et après une réinstallation, contrairement à ce que dit `PRIVACY.md`.
     *
     * **Invisible tant qu'elle n'est pas complète** (relecture Claude du 2026-11-19). La ligne naît dans la boîte
     * d'ENVOI, que l'import des autres profils ne prend jamais (`TelephonyReader.TrancheMms`) ; elle ne passe dans la
     * boîte de réception qu'une fois ses adresses et toutes ses parties écrites. Écrite directement en réception, un
     * autre profil — qui ne partage pas notre verrou — pouvait la lire sans adresse ni photo, et la garder ainsi.
     *
     * **Entière ou rien.** Une écriture refusée — exception, `null`, ou adresse `…/0` (`ContentProvider.rejectInsert`,
     * relecture GPT du 2026-11-19) — efface la ligne et rend `null`.
     *
     * @param dateMs l'heure du message chez nous ; la copie la garde à la seconde, ce que
     *   `SystemCopyEraser.matchesSystemRow` vérifie avant d'effacer.
     * @param membresDuFil le fil Android de la copie, pour qu'un autre profil la range au même endroit.
     * @param creee appelée dès que la ligne existe, avant tout le reste : l'appelant la note pour pouvoir l'effacer si
     *   ce passage s'interrompt ou si le message disparaît, cf. `CopieMmsRecus`.
     */
    @Suppress("LongParameterList")
    suspend fun insertInbox(
        expediteur: String,
        destinataires: List<String>,
        membresDuFil: List<String>,
        dateMs: Long,
        lu: Boolean,
        subId: Int?,
        textBody: String?,
        fichiers: List<Pair<File, String>>,
        creee: suspend (Long) -> Unit,
    ): Long? = withContext(io) {
        if (expediteur.isBlank()) return@withContext null
        val pieces = fichiers.map { (file, mime) -> Piece(file, mime) }
        if (!piecesSures(pieces)) return@withContext null
        val dateSec = dateMs / 1000L
        val fil = canonicalRecipients(membresDuFil.ifEmpty { listOf(expediteur) })
        val systemThreadId: Long = safe("inbox.getOrCreateThreadId") {
            Telephony.Threads.getOrCreateThreadId(context, fil)
        } ?: 0L
        val mmsValues = ContentValues().apply {
            // En plan, la ligne porte l'heure d'ÉCRITURE : c'est sur elle que [purgerCopiesEnPlan] juge qu'une copie
            // est abandonnée. Elle reçoit l'heure du message en passant dans la réception ([versLaReception]).
            put(Telephony.Mms.DATE, System.currentTimeMillis() / 1000L)
            put(Telephony.Mms.DATE_SENT, dateSec)
            put(Telephony.Mms.MESSAGE_BOX, Telephony.Mms.MESSAGE_BOX_OUTBOX)
            put(Telephony.Mms.READ, if (lu) 1 else 0)
            put(Telephony.Mms.SEEN, if (lu) 1 else 0)
            put(Telephony.Mms.MESSAGE_TYPE, MSG_TYPE_RETRIEVE_CONF)
            put(Telephony.Mms.MMS_VERSION, MMS_VERSION_1_0)
            put(Telephony.Mms.CONTENT_TYPE, "application/vnd.wap.multipart.related")
            subId?.let { put(Telephony.Mms.SUBSCRIPTION_ID, it) }
            if (systemThreadId > 0L) put(Telephony.Mms.THREAD_ID, systemThreadId)
        }
        val mmsId = idCree(safe("insertInbox.row") { resolver.insert(Telephony.Mms.Outbox.CONTENT_URI, mmsValues) })
        if (mmsId == null) {
            Timber.w("MmsSystemWriteback: content://mms a refusé la copie d'un MMS reçu (rôle SMS ?)")
            return@withContext null
        }
        // Sans note au journal, plus rien ne retrouverait cette ligne : elle part (relectures Claude et GPT, 2e vague).
        try {
            creee(mmsId)
        } catch (t: Throwable) {
            withContext(NonCancellable) { effacerCopieRecue(mmsId) }
            throw t
        }
        val complete = ecrireAdresses(mmsId, expediteur, destinataires) &&
            ecrireParties(mmsId, textBody, pieces) &&
            versLaReception(mmsId, dateSec)
        if (!complete) {
            Timber.w("MmsSystemWriteback: copie du MMS reçu incomplète, effacée (id=%d)", mmsId)
            effacerCopieRecue(mmsId)
            return@withContext null
        }
        Timber.i(
            "MmsSystemWriteback: inserted inbox copy id=%d (%d recipient(s), %d attachment(s))",
            mmsId,
            destinataires.size,
            pieces.size,
        )
        mmsId
    }

    private fun ecrireAdresses(mmsId: Long, expediteur: String, destinataires: List<String>): Boolean {
        val addrUri = Uri.parse("content://mms/$mmsId/addr")
        val adresses = listOf(expediteur to ADDR_TYPE_FROM) + destinataires.map { it to ADDR_TYPE_TO }
        return adresses.all { (adresse, type) ->
            val valeurs = ContentValues().apply {
                put("address", adresse)
                put("type", type)
                put("charset", CHARSET_UTF8)
            }
            idCree(safe("inbox.addr") { resolver.insert(addrUri, valeurs) }) != null
        }
    }

    /**
     * La copie complète passe de la boîte d'envoi à la réception, à l'heure du message (à la seconde, ce que
     * `SystemCopyEraser.matchesSystemRow` vérifie) : c'est là seulement que l'import la prend.
     */
    private fun versLaReception(mmsId: Long, dateSec: Long): Boolean {
        val valeurs = ContentValues().apply {
            put(Telephony.Mms.MESSAGE_BOX, Telephony.Mms.MESSAGE_BOX_INBOX)
            put(Telephony.Mms.DATE, dateSec)
        }
        return safe("inbox.toInbox") {
            resolver.update(ContentUris.withAppendedId(Telephony.Mms.CONTENT_URI, mmsId), valeurs, null, null)
        } == 1
    }

    /**
     * 2026-11-19 (issue #98) — efface une copie de MMS REÇU, et rien d'autre : la sélection exige le type
     * `m-retrieve-conf` et la boîte de réception ou d'envoi (copie en cours d'écriture). Un MMS envoyé
     * (`m-send-req`) ne peut donc jamais être effacé par ce chemin, même sur un identifiant faux. Rend le nombre de
     * lignes effacées (0 ou 1), ou `null` si le fournisseur n'a pas répondu.
     */
    suspend fun effacerCopieRecue(mmsId: Long): Int? = withContext(io) {
        if (mmsId <= 0L) return@withContext 0
        safe("effacerCopieRecue($mmsId)") {
            resolver.delete(
                Telephony.Mms.CONTENT_URI,
                "${Telephony.Mms._ID}=? AND ${Telephony.Mms.MESSAGE_TYPE}=? AND ${Telephony.Mms.MESSAGE_BOX} IN (?, ?)",
                arrayOf(
                    mmsId.toString(),
                    MSG_TYPE_RETRIEVE_CONF.toString(),
                    Telephony.Mms.MESSAGE_BOX_INBOX.toString(),
                    Telephony.Mms.MESSAGE_BOX_OUTBOX.toString(),
                ),
            )
        }
    }

    /**
     * 2026-11-19 (issue #98, relectures Claude et GPT, 2e vague) — efface les copies de MMS reçus restées EN PLAN
     * (boîte d'envoi, `m-retrieve-conf`) depuis avant [avantMs] : processus tué entre la création de la ligne et sa
     * note au journal. Une copie en cours d'écriture dure quelques secondes ; une heure de marge protège celle qu'un
     * autre profil écrit au même moment. Aucune autre ligne ne répond à ce sélecteur : un envoi est `m-send-req`, et
     * aucune application ne range un `m-retrieve-conf` dans la boîte d'envoi.
     */
    suspend fun purgerCopiesEnPlan(avantMs: Long): Int = withContext(io) {
        safe("purgerCopiesEnPlan") {
            resolver.delete(
                Telephony.Mms.CONTENT_URI,
                "${Telephony.Mms.MESSAGE_TYPE}=? AND ${Telephony.Mms.MESSAGE_BOX}=? AND ${Telephony.Mms.DATE}<?",
                arrayOf(
                    MSG_TYPE_RETRIEVE_CONF.toString(),
                    Telephony.Mms.MESSAGE_BOX_OUTBOX.toString(),
                    (avantMs / 1000L).toString(),
                ),
            )
        } ?: 0
    }

    /** L'identifiant d'une ligne créée, ou `null` pour une insertion refusée (`null`, ou `…/0`). */
    private fun idCree(uri: Uri?): Long? = uri?.lastPathSegment?.toLongOrNull()?.takeIf { it > 0L }

    /** Promotes a previously inserted outbox row to SENT (`msg_box = 2`). */
    suspend fun markSent(mmsSystemId: Long): Boolean = withContext(io) {
        if (mmsSystemId <= 0L) return@withContext false
        val uri = ContentUris.withAppendedId(Telephony.Mms.CONTENT_URI, mmsSystemId)
        val values = ContentValues().apply {
            put(Telephony.Mms.MESSAGE_BOX, Telephony.Mms.MESSAGE_BOX_SENT)
            put(Telephony.Mms.DATE_SENT, System.currentTimeMillis() / 1000L)
        }
        safe("markSent($mmsSystemId)") {
            resolver.update(uri, values, null, null) > 0
        } ?: false
    }

    /** Deletes the outbox row on dispatch failure. */
    suspend fun delete(mmsSystemId: Long): Boolean = withContext(io) {
        if (mmsSystemId <= 0L) return@withContext false
        val uri = ContentUris.withAppendedId(Telephony.Mms.CONTENT_URI, mmsSystemId)
        safe("delete($mmsSystemId)") {
            resolver.delete(uri, null, null) > 0
        } ?: false
    }

    /**
     * v1.2.6 audit F4 — remplace le placeholder `"insert-address-token"` posé par
     * [insertOutbox] sur la row FROM par le vrai MSISDN du SIM, dès qu'on a une valeur
     * fiable (passée par le caller, typiquement le receiver sur `RESULT_OK` après que le
     * système ait potentiellement complété la row). Sur les ROM (Samsung One UI surtout)
     * qui n'écrasent pas le placeholder elles-mêmes, ça évite que la chaîne `addr` de la
     * MMS sortante affiche littéralement `"insert-address-token"` côté autres apps SMS.
     *
     * No-op silencieux si `msisdn` est null/blank, ou si la row n'a plus de FROM placeholder
     * (l'OS l'a déjà remplacé). Pas fatal — la convention AOSP de skipper le token à
     * l'import (voir `TelephonyReader.readMmsAddress`) couvre déjà le cas dégradé.
     */
    suspend fun finalizeFromAddress(mmsSystemId: Long, msisdn: String?): Boolean = withContext(io) {
        if (mmsSystemId <= 0L || msisdn.isNullOrBlank()) return@withContext false
        val addrUri = Uri.parse("content://mms/$mmsSystemId/addr")
        val values = ContentValues().apply { put("address", msisdn) }
        // v1.2.7 audit S1+Q14 : defense-in-depth.
        //  - `AND mid=?` : double sécurité au cas où l'URI scoping serveur-side serait laxiste
        //    sur certaines ROM (l'URI `content://mms/{id}/addr` est censée scoper par mid mais
        //    on ne lui fait pas confiance aveugle).
        //  - Premier essai : on ne touche que les rows portant exactement le placeholder.
        //    Deuxième essai (Free Mobile FR, certaines MVNO) : si l'OS a remplacé le
        //    placeholder par `NULL` au lieu de notre MSISDN, on accepte ce cas dégradé aussi —
        //    la sélection reste bornée à `type=FROM AND mid=this row`, donc on ne risque pas
        //    d'écraser une vraie adresse d'une autre MMS.
        safe("finalizeFromAddress($mmsSystemId)") {
            val updated = resolver.update(
                addrUri,
                values,
                "type=? AND mid=? AND address=?",
                arrayOf(ADDR_TYPE_FROM.toString(), mmsSystemId.toString(), "insert-address-token"),
            )
            if (updated > 0) {
                true
            } else {
                // Fallback : OS a déjà nullé le placeholder. Tenter d'écrire si address IS NULL.
                resolver.update(
                    addrUri,
                    values,
                    "type=? AND mid=? AND address IS NULL",
                    arrayOf(ADDR_TYPE_FROM.toString(), mmsSystemId.toString()),
                ) > 0
            }
        } ?: false
    }

    /**
     * Watchdog: deletes MMS rows **written by SMS Tech** that are stuck in `msg_box = OUTBOX`
     * past [olderThanMs]. Used by [TelephonySyncWorker] to clean up after a [MmsSentReceiver]
     * that never fired (process killed mid-dispatch, force-stop, Doze + reboot). Returns the
     * number of rows deleted, or 0 if we are not default SMS app (provider returns 0 silently).
     *
     * # v1.28.3 (F18) — la suppression exige desormais une PREUVE DE PROPRIETE
     *
     * Le selecteur ne portait que sur `msg_box = OUTBOX` et sur l'age. Il ne demandait jamais
     * qui avait cree la ligne, et supprimait donc **toute** ligne du fournisseur systeme en
     * OUTBOX vieille de plus de quinze minutes — y compris celles d'une autre application :
     * une application constructeur cohabitante, ou celle utilisee avant SMS Tech, dont un MMS
     * avait echoue. Le chien de garde tournait toutes les douze heures.
     *
     * C'est le seul defaut de la relecture externe qui detruise la donnee d'un TIERS, et
     * l'identifiant qui manquait existait deja : `messages.mms_system_id`, que
     * [MmsSentReceiver] consulte precisement pour refuser d'agir sur une ligne qui n'est pas la
     * sienne. La purge etait le seul chemin destructeur du depot a ne pas faire ce controle,
     * alors qu'elle est le plus large.
     *
     * [ownedIds] vient de `MessageDao.ownedMmsSystemIds`. Vide, on ne supprime RIEN : une liste
     * vide signifie « aucune ligne prouvee a nous », pas « tout est a nous ». C'est le sens
     * d'echec qui compte ici — un repli sur l'ancien comportement redonnerait au chien de garde
     * le pouvoir qu'on lui retire.
     *
     * Le decoupage en lots de [SQLITE_MAX_VARIABLES] respecte la limite de parametres de
     * SQLite : un utilisateur ancien peut porter des milliers de MMS, et un `IN (…)` trop long
     * ferait echouer la requete entiere — donc ne purgerait plus rien, en silence.
     */
    suspend fun purgeStaleOutbox(olderThanMs: Long, ownedIds: List<Long>): Int = withContext(io) {
        if (ownedIds.isEmpty()) return@withContext 0
        val cutoffSec = (System.currentTimeMillis() - olderThanMs) / 1000L
        var supprimees = 0
        for (lot in ownedIds.chunked(SQLITE_MAX_VARIABLES)) {
            val (selection, args) = selecteurOutboxPossedee(cutoffSec, lot)
            supprimees += safe("purgeStaleOutbox") {
                resolver.delete(Telephony.Mms.CONTENT_URI, selection, args)
            } ?: 0
        }
        supprimees
    }

    private companion object {
        /**
         * v1.28.3 (F18) — plafond de parametres d'une requete SQLite. La valeur reelle est 999
         * avant SQLite 3.32 et 32766 ensuite ; on s'aligne sur la plus basse, la version
         * embarquee variant selon l'appareil. Depasser ferait echouer la requete ENTIERE, donc
         * ne purgerait plus rien — en silence, le `safe` avalant l'exception.
         */
        const val SQLITE_MAX_VARIABLES: Int = 900

        // PduHeaders constants — kept inline (hidden in the framework, can't import).
        const val MSG_TYPE_SEND_REQ: Int = 128

        /** 2026-11-19 (issue #98) — `m-retrieve-conf`, le type d'un MMS reçu (AOSP `PduHeaders`). */
        const val MSG_TYPE_RETRIEVE_CONF: Int = 132

        const val MMS_VERSION_1_0: Int = 16
        const val PRIORITY_NORMAL: Int = 129
        const val ADDR_TYPE_FROM: Int = 137
        const val ADDR_TYPE_TO: Int = 151
        const val CHARSET_UTF8: Int = 106
    }
}

/**
 * v1.28.3 (F18) — construit le sélecteur du chien de garde de l'OUTBOX système.
 *
 * Extrait de [MmsSystemWriteback.purgeStaleOutbox] pour être **testable sans appareil**. Le
 * défaut que la relecture externe a trouvé était entièrement dans ce sélecteur : il ne portait
 * que sur `msg_box` et sur la date, donc SMS Tech détruisait les MMS en échec laissés par
 * d'autres applications. La classe, elle, dépend d'un `Context` et exigerait d'être application
 * SMS par défaut pour être exercée — ce qui aurait laissé le défaut sans garde-fou.
 *
 * Le troisième critère, `_id IN (…)`, est celui qui porte la preuve de propriété. Un correctif
 * qui le retirerait ferait tomber `MmsOutboxPurgeSelectorTest`.
 */
internal fun selecteurOutboxPossedee(
    cutoffSec: Long,
    lot: List<Long>,
): Pair<String, Array<String>> {
    require(lot.isNotEmpty()) { "un lot vide ne doit jamais atteindre le selecteur" }
    val placeholders = lot.joinToString(",") { "?" }
    val selection = "${Telephony.Mms.MESSAGE_BOX}=? AND ${Telephony.Mms.DATE}<? AND " +
        "${Telephony.Mms._ID} IN ($placeholders)"
    val args = arrayOf(
        Telephony.Mms.MESSAGE_BOX_OUTBOX.toString(),
        cutoffSec.toString(),
    ) + lot.map { it.toString() }
    return selection to args
}
