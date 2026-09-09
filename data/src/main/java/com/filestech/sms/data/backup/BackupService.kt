package com.filestech.sms.data.backup

import android.content.Context
import android.net.Uri
import androidx.room.withTransaction
import com.filestech.sms.core.crypto.AeadCipher
import com.filestech.sms.core.crypto.PasswordKdf
import com.filestech.sms.core.crypto.wipe
import com.filestech.sms.core.result.AppError
import com.filestech.sms.core.result.Outcome
import com.filestech.sms.core.result.runCatchingOutcome
import com.filestech.sms.data.local.db.AppDatabase
import com.filestech.sms.data.local.db.dao.ConversationDao
import com.filestech.sms.data.local.db.dao.MessageDao
import com.filestech.sms.data.local.db.entity.ConversationEntity
import com.filestech.sms.data.local.db.entity.MessageEntity
import com.filestech.sms.di.IoDispatcher
import com.filestech.sms.domain.backup.BackupRestorer
import com.filestech.sms.domain.backup.RestoreResult
import com.filestech.sms.security.AppLockManager
import com.filestech.sms.security.VaultSecondFactor
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import timber.log.Timber
import java.io.ByteArrayOutputStream
import javax.inject.Inject
import javax.inject.Singleton

/**
 * `.smsbk` format (v1):
 *
 *   "SMBK"(4) || version(1) || salt(16) || iter(4 BE) || aeadBlob
 *
 * `aeadBlob` = `AeadCipher.encryptRaw(rawKey, json, aad = MAGIC || version || salt || iter)`
 *  - `rawKey` = PBKDF2-HMAC-SHA512(password, salt, iter, 32 bytes)
 *  - KDF parameters are bound to the ciphertext via AAD: flipping `salt`/`iter` makes
 *    decryption fail-closed (fixes audit F26).
 *
 * XML SMS-Backup-Restore compat: unencrypted by design (user opt-in).
 */
@Singleton
class BackupService @Inject constructor(
    @ApplicationContext private val context: Context,
    private val database: AppDatabase,
    private val conversationDao: ConversationDao,
    private val messageDao: MessageDao,
    private val kdf: PasswordKdf,
    private val aead: AeadCipher,
    // v1.26.1 (audit C2) — nécessaire pour refuser l'export en session leurre, cf. [writeSmsbk].
    private val appLock: com.filestech.sms.security.AppLockManager,
    // v1.27.2 (audit externe 2026-08-04 #4) — le second facteur du Coffre garde aussi l'export,
    // cf. [writeSmsbk].
    private val vaultSession: com.filestech.sms.security.VaultSessionState,
    // v1.27.13 — « y a-t-il un second facteur a prouver ? », lu au meme endroit que la porte du
    // coffre. Voir [com.filestech.sms.security.VaultSecondFactorPolicy] pour la raison.
    private val vaultFactor: com.filestech.sms.security.VaultSecondFactorPolicy,
    @IoDispatcher private val io: CoroutineDispatcher,
) : BackupRestorer {

    override suspend fun restore(uriString: String, password: CharArray): Outcome<RestoreResult> =
        readSmsbk(Uri.parse(uriString), password)

    /**
     * v1.27.13 — `true` quand [writeSmsbk] refusera parce que le coffre n'est pas vide et que sa
     * session n'est pas ouverte.
     *
     * Existe pour que l'ECRAN puisse poser la question AVANT de faire travailler l'utilisateur.
     * Le refus arrivait apres le choix d'une destination et la saisie d'une passphrase — deux
     * gestes rendus inutiles par une condition que l'application connaissait des le depart. Pire,
     * `CreateDocument` cree le fichier au moment du choix : un `.smsbk` vide restait sur le
     * stockage.
     *
     * La condition n'est ecrite qu'ICI et [writeSmsbk] l'appelle : deux copies finiraient par
     * diverger, et l'ecran annoncerait alors autre chose que ce que le service applique.
     *
     * **Rend `false` en session leurre**, et ce n'est pas un oubli : la nommer y trahirait
     * l'existence d'un coffre au porteur du code panique. Sur ce chemin [writeSmsbk] refuse de
     * toute facon, un cran plus haut, avec un message generique.
     */
    suspend fun exportSecondFactor(): VaultSecondFactor = withContext(io) {
        val enLeurre = appLock.state.value is AppLockManager.LockState.PanicDecoy
        when {
            // En session leurre, l'export est refuse un cran plus haut avec un message
            // generique : nommer le coffre ici trahirait son existence.
            enLeurre -> VaultSecondFactor.NONE
            // Deja prouve pour cette session.
            vaultSession.isUnlocked -> VaultSecondFactor.NONE
            // Rien a proteger.
            conversationDao.countInVault() == 0 -> VaultSecondFactor.NONE
            else -> vaultFactor.current()
        }
    }

    /**
     * @property sourceDeviceId v1.27.11 (revue externe GitLab !38458, constat 3) — empreinte de
     *   l'installation qui a ecrit la sauvegarde, cf. [currentDeviceId]. `null` sur toute
     *   sauvegarde anterieure a la v1.27.11 : provenance inconnue, donc traitee comme etrangere.
     *
     *   Le champ a une valeur par defaut, donc les anciennes sauvegardes se relisent malgre le
     *   parseur strict (`ignoreUnknownKeys = false` refuse les cles INCONNUES, pas les cles
     *   absentes). L'inverse n'est pas vrai : une v1.27.10 ne relira pas un `.smsbk` ecrit ici.
     */
    @Serializable
    data class BackupHeader(
        val createdAt: Long,
        val app: String,
        val version: Int,
        val sourceDeviceId: String? = null,
    )

    @Serializable
    data class BackupPayload(
        val header: BackupHeader,
        val conversations: List<ConversationEntity>,
        val messages: List<MessageEntity>,
    )

    /**
     * Strict JSON parser: unknown keys are rejected to prevent forged-field injection (audit F9).
     */
    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = false }

    /**
     * Writes an encrypted `.smsbk` to [uri]. The [password] CharArray is wiped on return.
     *
     * **Encryption is mandatory** (fixes F4). Pass an empty CharArray to get a `Validation`
     * failure — the call site is the only place where an explicit user passphrase enters the
     * pipeline, so we keep the contract strict.
     */
    suspend fun writeSmsbk(uri: Uri, password: CharArray): Outcome<Uri> = withContext(io) {
        // v1.26.1 (audit C2) — refus en session leurre, garde côté ACCÈS.
        //
        // `listAllIncludingArchived()` lit les conversations du coffre COMPRISES (son propre
        // KDoc le dit). Toutes les autres voies de lecture du coffre étaient gardées — listes,
        // recherche FTS, badges, notifications, `observeVault` — sauf celle-ci. Un agresseur en
        // session leurre ouvrait donc Réglages → Sauvegarde, choisissait SA passphrase et SA
        // destination, et repartait avec l'intégralité du coffre, déchiffrable hors de
        // l'appareil. Le leurre était contourné par le seul chemin qui ne trahissait rien.
        //
        // La section est aussi masquée dans les Réglages, mais masquer est une énumération
        // d'écrans : c'est ce garde-ci qui compte, et lui seul couvre un futur point d'entrée.
        //
        // Le `password` est effacé ici : le `finally` qui s'en charge d'ordinaire est situé
        // dans le bloc ci-dessous, qu'on ne rejoint pas sur ce chemin.
        if (appLock.state.value is com.filestech.sms.security.AppLockManager.LockState.PanicDecoy) {
            password.wipe()
            return@withContext Outcome.Failure(AppError.Locked())
        }
        // v1.27.2 (audit externe 2026-08-04 #4) — le second facteur du Coffre garde aussi
        // l'EXPORT. `buildPayload()` lit coffre compris (cf. `listAllIncludingArchived`) ;
        // sans ce garde, quiconque passait le verrou principal exportait l'intégralité du
        // coffre, déchiffrable HORS de l'appareil avec la passphrase de SON choix —
        // contournement complet du second facteur, définitif une fois le fichier copié.
        // On REFUSE plutôt que d'amputer silencieusement la sauvegarde : un `.smsbk` sans le
        // coffre serait une perte de données à la restauration. Coffre vide = rien à
        // protéger, l'export reste sans friction.
        if (exportSecondFactor() != VaultSecondFactor.NONE) {
            password.wipe()
            return@withContext Outcome.Failure(AppError.Locked())
        }
        runCatchingOutcome(
            block = {
                require(password.isNotEmpty()) { "password is required" }
                val payload = buildPayload()
                // v1.28.3 (F32) — l'ECRIVAIN applique enfin les bornes de son propre LECTEUR.
                //
                // `readAllBytesBounded` refuse au-dela de MAX_RESTORE_BYTES, et `importPayload`
                // au-dela de MAX_RESTORE_CONVERSATIONS / MAX_RESTORE_MESSAGES. L'export, lui,
                // n'en verifiait aucune : un historique volumineux produisait un `.smsbk` que
                // l'application refusait ensuite de relire. Le refus n'arrivait qu'a la
                // RESTAURATION, c'est-a-dire au pire moment — apres une reinstallation, quand la
                // sauvegarde est la seule chose qui reste.
                //
                // Le controle a lieu AVANT `openOutputStream`, et ce n'est pas un detail :
                // ouvrir en « w » TRONQUE le fichier existant. Echouer apres l'ouverture
                // detruirait la sauvegarde precedente pour la remplacer par une inutilisable.
                verifierBornesDuLecteur(payload)
                val plainBytes = json.encodeToString(BackupPayload.serializer(), payload)
                    .toByteArray(Charsets.UTF_8)
                val out = ByteArrayOutputStream()
                val magic = MAGIC.toByteArray(Charsets.US_ASCII)
                out.write(magic)
                out.write(byteArrayOf(VERSION.toByte()))
                val salt = kdf.newSalt()
                val iter = kdf.calibrate()
                val iterBytes = intToBytesBE(iter)
                out.write(salt)
                out.write(iterBytes)
                val aad = ByteArrayOutputStream(magic.size + 1 + salt.size + iterBytes.size).apply {
                    write(magic)
                    write(byteArrayOf(VERSION.toByte()))
                    write(salt)
                    write(iterBytes)
                }.toByteArray()
                try {
                    val rawKey = kdf.derive(password, salt, iter)
                    try {
                        when (val blob = aead.encryptRaw(rawKey, plainBytes, aad = aad)) {
                            is Outcome.Success -> out.write(blob.value)
                            is Outcome.Failure -> error("encrypt failed: ${blob.error}")
                        }
                    } finally {
                        rawKey.wipe()
                    }
                } finally {
                    password.wipe()
                }
                // v1.6.1 (audit QUAL-04) — diagnostic explicite si openOutputStream
                // retourne null (URI révoqué, disque plein, provider crashé). Avant le
                // `!!` produisait un NPE générique enveloppé dans AppError.Storage sans
                // message métier — debugging à l'aveugle.
                val octets = out.toByteArray()
                if (octets.size > MAX_RESTORE_BYTES) {
                    error(
                        "backup would be ${octets.size} B, above the ${MAX_RESTORE_BYTES} B cap " +
                            "its own reader enforces",
                    )
                }
                val os = context.contentResolver.openOutputStream(uri, "w")
                    ?: error("openOutputStream returned null for backup URI")
                os.use {
                    it.write(octets)
                    it.flush()
                }
                // v1.28.3 (F32) — on RELIT ce qui a ete ecrit.
                //
                // Un fichier tronque — disque plein, processus tue, fournisseur de document qui
                // abandonne — passe la validation d'en-tete : MAGIC, version, sel et iterations
                // sont ecrits en PREMIER. L'echec ne se manifestait donc qu'a l'ouverture de
                // l'AEAD, que l'utilisateur lit comme « mauvais mot de passe ». Il cherchait un
                // mot de passe pour un fichier incomplet, sur une sauvegarde qui venait
                // d'ecraser la precedente.
                //
                // La taille suffit a le detecter et ne coute rien ; verifier par dechiffrement
                // demanderait une seconde derivation de cle, calibree pour etre lente, et le mot
                // de passe est deja efface a ce stade.
                val ecrits = runCatching {
                    context.contentResolver.openInputStream(uri)?.use { flux ->
                        var total = 0L
                        val tampon = ByteArray(READ_CHUNK_BYTES)
                        while (true) {
                            val lus = flux.read(tampon)
                            if (lus <= 0) break
                            total += lus
                        }
                        total
                    }
                }.getOrNull()
                if (ecrits != null && ecrits != octets.size.toLong()) {
                    error("backup written incompletely: $ecrits B on disk, ${octets.size} B expected")
                }
                uri
            },
            errorMapper = { AppError.Storage(it) },
        )
    }

    /**
     * v1.28.3 (F32) — refuse d'ecrire ce que la restauration refuserait de lire.
     *
     * Les deux bornes sont celles de `importPayload`, litteralement les memes constantes : deux
     * seuils qui divergeraient reproduiraient le defaut sous une autre forme. Le message nomme le
     * depassement plutot que d'echouer « au stockage », parce que c'est la seule chose qui
     * permette a l'utilisateur d'agir — reduire sa retention, purger, exporter par morceaux.
     */
    private fun verifierBornesDuLecteur(payload: BackupPayload) {
        if (payload.conversations.size > MAX_RESTORE_CONVERSATIONS) {
            error(
                "backup holds ${payload.conversations.size} conversations, above the " +
                    "$MAX_RESTORE_CONVERSATIONS its own reader accepts",
            )
        }
        if (payload.messages.size > MAX_RESTORE_MESSAGES) {
            error(
                "backup holds ${payload.messages.size} messages, above the " +
                    "$MAX_RESTORE_MESSAGES its own reader accepts",
            )
        }
    }

    private suspend fun buildPayload(): BackupPayload {
        val (convs, msgs) = listSync()
        return BackupPayload(
            header = BackupHeader(
                createdAt = System.currentTimeMillis(),
                app = APP_TAG,
                version = VERSION,
                sourceDeviceId = currentDeviceId(),
            ),
            conversations = convs,
            messages = msgs,
        )
    }

    /**
     * v1.27.11 (revue externe GitLab !38458, constat 3) — empreinte stable de « cet appareil,
     * cette installation », ou `null` si le systeme ne la donne pas.
     *
     * Elle sert a une seule question, posee a la restauration : les identifiants de fournisseur
     * que porte cette sauvegarde designent-ils des lignes de CE telephone ? `ANDROID_ID` a
     * exactement la semantique voulue — il survit a une reinstallation de l'application (c'est
     * le cas d'usage meme de la restauration) et change d'un appareil a l'autre comme apres une
     * remise a zero d'usine, deux situations ou les identifiants deviennent effectivement
     * caducs.
     *
     * On stocke son **empreinte SHA-256**, jamais sa valeur : la comparaison d'egalite n'a pas
     * besoin de plus, et un `.smsbk` n'a aucune raison de transporter un identifiant d'appareil
     * en clair. Rien n'en sort de l'appareil — SMS Tech ne parle a aucun serveur.
     */
    private fun currentDeviceId(): String? = runCatching {
        val raw = android.provider.Settings.Secure.getString(
            context.contentResolver,
            android.provider.Settings.Secure.ANDROID_ID,
        )
        if (raw.isNullOrBlank()) {
            null
        } else {
            java.security.MessageDigest.getInstance("SHA-256")
                .digest(raw.toByteArray(Charsets.UTF_8))
                .joinToString(separator = "") { byte -> "%02x".format(byte) }
        }
    }.getOrNull()

    /**
     * Single-shot, transactional read of all conversations + all messages.
     * Fixes audit Q6 (was an N+1 with `Flow.first()` inside `flatMap`).
     */
    private suspend fun listSync(): Pair<List<ConversationEntity>, List<MessageEntity>> =
        // v1.22.x (audit) — réellement transactionnel : les deux lectures voient un instantané
        // cohérent. Sinon une écriture concurrente (ex. dédup au cold-start supprimant une
        // conversation entre les deux SELECT) produirait un `.smsbk` incohérent (conversation
        // vide → doublon fantôme à la restauration).
        database.withTransaction {
            val convs = conversationDao.listAllIncludingArchived()
            val msgs = messageDao.listAll()
            convs to msgs
        }

    private fun intToBytesBE(value: Int): ByteArray = byteArrayOf(
        ((value ushr 24) and 0xFF).toByte(),
        ((value ushr 16) and 0xFF).toByte(),
        ((value ushr 8) and 0xFF).toByte(),
        (value and 0xFF).toByte(),
    )

    // ============================================================================
    // v1.15.2 — Restore .smsbk : déchiffrement + import Room atomique.
    // ============================================================================

    /**
     * Résultat d'un restore — compte des objets importés vs réutilisés/skippés. Permet à l'UI
     * d'afficher un récap précis ("X conversations dont Y nouvelles, Z messages importés sur
     * N total dans la sauvegarde, M ignorés car déjà présents").
     */
    /**
     * Lit, déchiffre et importe un fichier `.smsbk` v1.
     *
     * **Format attendu** (identique à [writeSmsbk]) :
     *   `"SMBK"(4) || version(1) || salt(16) || iter(4 BE) || aeadBlob`
     * Le `aeadBlob` est lié à `magic||version||salt||iter` via AAD, donc toute manipulation
     * du préfixe fait échouer le déchiffrement (fail-closed contre attaques par confusion).
     *
     * **Conflict resolution** :
     *  - Conversations : recherche par `addressesCsv` canonique. Si trouvée → réutilise l'id.
     *    Sinon → INSERT new avec id=0 (auto-generate Room).
     *  - Messages : INSERT avec `OnConflictStrategy.IGNORE`. Les rows existantes (mêmes
     *    `telephony_uri` unique index) sont silencieusement skippées — pas de duplication.
     *  - **Transaction atomique** ([AppDatabase.withTransaction]) : un crash / kill mi-import
     *    n'écrit rien (rollback complet). Vigilance MAX : pas d'état intermédiaire en Room.
     *
     * **Sécurité** :
     *  - Cap taille fichier (50 MB) pour éviter OOM sur un fichier hostile.
     *  - JSON parser strict (`ignoreUnknownKeys = false`) pour rejeter les payloads forgés.
     *  - `password` + `rawKey` wipés après usage.
     *  - `AppError.Validation` typé pour : magic invalide / version inconnue / mot de passe faux.
     *
     * Le [password] CharArray est consommé (wipé) sur retour, succès ou échec.
     */
    suspend fun readSmsbk(uri: Uri, password: CharArray): Outcome<RestoreResult> = withContext(io) {
        runCatchingOutcome(
            block = {
                require(password.isNotEmpty()) { "password is required" }
                val bytes = readBackupFile(uri)
                val header = parseHeader(bytes)
                val payload = decryptPayload(bytes, header, password)
                importPayload(payload)
            },
            errorMapper = { throwable ->
                // On préserve la nature de l'erreur côté UI : Validation (mauvais format /
                // mot de passe) vs Storage (problème de lecture) — pour afficher un message
                // ciblé. `runCatchingOutcome` wrap les exceptions ; on regarde le message.
                val msg = throwable.message.orEmpty()
                when {
                    msg.contains("invalid magic") ||
                        msg.contains("unsupported version") ||
                        msg.contains("decrypt failed") ||
                        msg.contains("invalid JSON") -> AppError.Validation(msg)
                    else -> AppError.Storage(throwable)
                }
            },
        ).also {
            // password wipé dans tous les chemins (succès comme échec) — `also` court-circuite
            // pas le retour de l'outcome.
            password.wipe()
        }
    }

    /**
     * Lecture en streaming chunked avec cap STRICT — audit SECU-H1 v1.15.2.
     *
     * Avant : `stream.readBytes()` allouait la totalité avant le check de cap → un fichier
     * hostile de 49,9 MB déclenchait 50 MB d'allocation heap AVANT le test, combiné au JSON
     * déchiffré (copie #2) puis la liste désérialisée (copie #3) → OOM probable sur S9
     * (~2 GB heap appli). Maintenant : lecture par buffers de 8 KB avec compteur cumulé,
     * abort dès dépassement de [MAX_RESTORE_BYTES] sans avoir alloué le reste. Cap dur.
     */
    private fun readBackupFile(uri: Uri): ByteArray {
        val input = context.contentResolver.openInputStream(uri)
            ?: error("openInputStream returned null for restore URI")
        return input.use { stream ->
            val buf = ByteArray(READ_CHUNK_BYTES)
            // Pré-dimensionne à la borne basse pour limiter les ré-allocations sur backup
            // légitime ; le ByteArrayOutputStream croît en x2 au-delà.
            val out = ByteArrayOutputStream(64 * 1024)
            var total = 0L
            while (true) {
                val read = stream.read(buf, 0, buf.size)
                if (read < 0) break
                total += read
                if (total > MAX_RESTORE_BYTES) {
                    // Le buf en cours n'est pas accumulé — on coupe net.
                    error("backup file too large (>$MAX_RESTORE_BYTES bytes)")
                }
                out.write(buf, 0, read)
            }
            out.toByteArray()
        }
    }

    /**
     * Header parsé après validation magic + version + extraction salt + iter.
     *
     * v1.17.0 audit KOTLIN-L3 — Non-`data class` car `data class` avec `ByteArray` génère
     * un `equals` / `hashCode` par référence (et non par contenu) — piège silencieux pour
     * tout futur usage `Set<ParsedHeader>` / `Map<ParsedHeader, …>`. Type purement transitoire
     * (passé par valeur dans le flow restore), pas besoin de `equals` content-based ici.
     */
    private class ParsedHeader(val salt: ByteArray, val iter: Int, val aad: ByteArray, val aeadOffset: Int)

    private fun parseHeader(bytes: ByteArray): ParsedHeader {
        val minLen = MAGIC.length + 1 + SALT_LEN + ITER_LEN
        if (bytes.size < minLen) error("invalid backup: too short for header")
        val magicBytes = MAGIC.toByteArray(Charsets.US_ASCII)
        for (i in magicBytes.indices) {
            if (bytes[i] != magicBytes[i]) error("invalid magic — not a SMS Tech backup")
        }
        val version = bytes[MAGIC.length].toInt() and 0xFF
        if (version != VERSION) error("unsupported version $version (expected $VERSION)")
        val salt = bytes.copyOfRange(MAGIC.length + 1, MAGIC.length + 1 + SALT_LEN)
        val iterOffset = MAGIC.length + 1 + SALT_LEN
        val iter = bytesBEToInt(bytes, iterOffset)
        if (iter < KDF_ITER_MIN || iter > KDF_ITER_MAX) {
            error("invalid KDF iteration count $iter (out of $KDF_ITER_MIN..$KDF_ITER_MAX)")
        }
        val aeadOffset = iterOffset + ITER_LEN
        val aad = bytes.copyOfRange(0, aeadOffset)
        return ParsedHeader(salt = salt, iter = iter, aad = aad, aeadOffset = aeadOffset)
    }

    private fun decryptPayload(bytes: ByteArray, header: ParsedHeader, password: CharArray): BackupPayload {
        val aeadBlob = bytes.copyOfRange(header.aeadOffset, bytes.size)
        val rawKey = kdf.derive(password, header.salt, header.iter)
        val plain = try {
            when (val out = aead.decryptRaw(rawKey, aeadBlob, aad = header.aad)) {
                is Outcome.Success -> out.value
                is Outcome.Failure -> error("decrypt failed — wrong passphrase or corrupted file")
            }
        } finally {
            rawKey.wipe()
        }
        val payload = try {
            json.decodeFromString(BackupPayload.serializer(), plain.toString(Charsets.UTF_8))
        } catch (t: Throwable) {
            // Audit SECU-L2 v1.15.2 — `Timber.w` sans throwable pour ne pas leaker un fragment
            // du payload via le message d'exception en debug build.
            Timber.w("BackupService.readSmsbk: invalid JSON payload")
            error("invalid JSON payload")
        } finally {
            // Wipe plaintext bytes ASAP — contient potentiellement des SMS sensibles.
            // Note SECU-L1 : la `String` UTF-8 dérivée reste en heap (immuable JVM, non
            // wipeable) — limitation acceptée dans le threat model in-process documenté.
            plain.fill(0)
        }
        // Audit SECU-M1 v1.15.2 — Cap dur sur la volumétrie du payload pour bloquer un
        // fichier forgé contenant 10⁷ MessageEntity vides (DoS via boucle d'import qui gèle
        // l'app plusieurs minutes). Caps généreux pour un usage légitime (10⁴ conv ≈ 50 ans
        // d'utilisation intensive, 10⁶ msgs ≈ 100 msgs/jour pendant 27 ans).
        if (payload.conversations.size > MAX_RESTORE_CONVERSATIONS) {
            error("payload too large: ${payload.conversations.size} conversations")
        }
        if (payload.messages.size > MAX_RESTORE_MESSAGES) {
            error("payload too large: ${payload.messages.size} messages")
        }
        return payload
    }

    /**
     * Import atomique du payload en Room. Wrap dans [AppDatabase.withTransaction] — soit tout
     * passe, soit rollback complet (vigilance MAX : pas d'état intermédiaire). Réutilise les
     * conversations existantes par `addressesCsv` canonique ; insère les messages via
     * [MessageDao.insert] (OnConflictStrategy.IGNORE skip les dupes via index unique
     * `telephony_uri`).
     */
    /**
     * v1.28.3 (F24) — reverse sur la ligne DEJA PRESENTE ce que la sauvegarde seule transporte.
     *
     * Ces deux champs sont purement locaux : le fournisseur du systeme ne les connait pas, donc
     * une ligne venue de la resynchronisation ne peut pas les avoir. Les laisser tomber revenait
     * a perdre a la restauration precisement ce que l'utilisateur avait sauvegarde.
     *
     *  - **favori** : fusionne par OU ;
     *  - **reaction** : posee seulement si la ligne existante n'en a pas.
     *
     * ⚠️ **Ce KDoc affirmait « la reaction courante de l'utilisateur, s'il en a mis une depuis,
     * est plus recente que celle du fichier ». C'etait une garantie que le code ne tient pas**,
     * et l'audit du 2026-09-09 l'a relevee. `reactionEmoji == null` code DEUX etats que rien ne
     * distingue : « n'a jamais reagi » et « a reagi, puis a RETIRE sa reaction » — un geste
     * delibere que l'application propose. Idem pour `starred = false`. La fusion repose donc les
     * deux drapeaux dans le second cas, alors que l'utilisateur les avait retires.
     *
     * Les distinguer demanderait d'horodater le dernier changement de chaque drapeau, c'est-a-dire
     * deux colonnes et une migration, pour arbitrer par fraicheur. **Le compromis est assume en
     * l'etat** : entre reposer une reaction retiree — visible, et defaisable d'une tape — et
     * perdre a la restauration tout ce que la sauvegarde transportait seule (le defaut F24, qui
     * vidait la restauration de sa substance), le second coute infiniment plus cher. Mais il faut
     * l'ecrire comme un compromis, pas comme une garantie : c'est ce projet qui a paye quatre fois
     * le prix d'une affirmation d'exhaustivite devenue fausse.
     *
     * Ce qui n'est volontairement PAS fusionne : l'etat « lu ». Il change des deux cotes pour des
     * raisons legitimes, et le reecrire depuis un instantane ancien ferait reapparaitre des
     * pastilles de non-lu — ou effacerait celles qui comptent. Ni l'un ni l'autre ne serait une
     * restauration.
     */
    private suspend fun fusionnerDrapeauxLocaux(
        existant: com.filestech.sms.data.local.db.entity.MessageEntity,
        sauvegarde: com.filestech.sms.data.local.db.entity.MessageEntity,
    ) {
        if (sauvegarde.starred && !existant.starred) {
            runCatching { messageDao.setStarred(existant.id, true) }
        }
        if (existant.reactionEmoji == null && sauvegarde.reactionEmoji != null) {
            runCatching { messageDao.setReaction(existant.id, sauvegarde.reactionEmoji) }
        }
    }

    private suspend fun importPayload(payload: BackupPayload): RestoreResult {
        return database.withTransaction {
            var reused = 0
            var created = 0
            // Mapping ancien id Room du backup → nouvel id Room dans la DB cible.
            val convIdMap = HashMap<Long, Long>(payload.conversations.size)
            // v1.28.3 (F01) — les placeholders `thread_id` négatifs de l'audit SECU-M3 (v1.15.2)
            // ont disparu : la colonne est désormais nullable, et `null` dit exactement ce que
            // les négatifs simulaient — « aucun fil système connu ».
            //
            // Ce commentaire décrivait déjà, en v1.15.2, le mécanisme complet de F01 : index
            // UNIQUE sur `thread_id`, sentinelle partagée, `REPLACE` qui « supprime
            // silencieusement les précédentes ». Le constat était juste ; il n'a été appliqué
            // qu'ici. La composition et la réception ont gardé la sentinelle `0L` pendant
            // quatorze versions, jusqu'à ce que la relecture externe la reproduise. La leçon
            // vaut plus que le correctif : un défaut compris sur un chemin doit être cherché
            // sur tous ses jumeaux le jour même.
            //
            // On n'importe JAMAIS le `thread_id` de la sauvegarde, même à `sameDevice` : il
            // désigne un fil du fournisseur de l'appareil SOURCE et peut, ici, pointer une
            // autre conversation — c'est le défaut exact que la v1.27.11 a corrigé sur
            // `telephony_uri`. `TelephonySyncWorker` réassignera le vrai fil à la resynchro.
            for (backupConv in payload.conversations) {
                // v1.26.1 (audit H12) — on ne réutilise une conversation existante que si elle a
                // le MÊME statut de coffre que celle de la sauvegarde.
                //
                // `findByAddressesCsv` ne filtre pas `in_vault`. Si la sauvegarde contenait
                // « Marie » DANS le coffre et que la base cible avait déjà une « Marie » HORS
                // coffre — cas courant après une réinstallation suivie d'une synchronisation
                // système — les messages du coffre étaient insérés dans la conversation EN
                // CLAIR : le contenu protégé réapparaissait dans la liste ordinaire, visible y
                // compris en mode leurre. En cas de statut différent on crée une conversation
                // distincte, qui conserve le `inVault` de la sauvegarde.
                val existing = conversationDao.findByAddressesCsv(backupConv.addressesCsv)
                    ?.takeIf { it.inVault == backupConv.inVault }
                if (existing != null) {
                    convIdMap[backupConv.id] = existing.id
                    reused++
                } else {
                    val newId = conversationDao.insert(
                        backupConv.copy(id = 0L, threadId = null),
                    )
                    convIdMap[backupConv.id] = newId
                    created++
                }
            }
            // v1.27.11 (revue externe GitLab !38458, constat 3) — la sauvegarde vient-elle de
            // CET appareil ? Cf. [currentDeviceId] et le `copy` ci-dessous. Calcule une seule
            // fois : `ANDROID_ID` ne change pas en cours de restauration.
            val sameDevice = payload.header.sourceDeviceId != null &&
                payload.header.sourceDeviceId == currentDeviceId()
            var imported = 0
            var skipped = 0
            // Audit SECU-M4 v1.15.2 — Remapping en 2 passes pour préserver les `replyToMessageId`
            // (citations contextuelles). PASSE 1 : insert tous les messages, build map
            // <backupMsgId → newMsgId>. PASSE 2 : UPDATE replyToMessageId pour les messages
            // qui en avaient un dans le backup. Sans ce remapping, toutes les citations
            // restaurées pointaient vers des ids morts → UI affichait "Message supprimé"
            // pour chaque réponse, perte d'information utilisateur.
            val msgIdMap = HashMap<Long, Long>(payload.messages.size)
            for (backupMsg in payload.messages) {
                val newConvId = convIdMap[backupMsg.conversationId]
                if (newConvId == null) {
                    // Message orphelin (conversation absente du backup malgré référence).
                    // Skip défensif — ne pas créer une conv fantôme à partir d'un message.
                    skipped++
                    continue
                }
                // Reset à id=0 (auto), conversationId remappé, replyToMessageId temporairement
                // null (passe 2 le remettra).
                //
                // v1.26.1 (audit M7) — `attachmentsCount` est REMIS À ZÉRO. La sauvegarde ne
                // transporte que les conversations et les messages : ni la table `attachments`,
                // ni les fichiers eux-mêmes. Le compteur était pourtant restauré tel quel, si
                // bien qu'après restauration les bulles annonçaient N pièces jointes
                // introuvables et s'affichaient vides. Mieux vaut une donnée honnête — le
                // message reste lisible, il n'annonce simplement plus ce qu'il n'a pas.
                // v1.27.11 (revue externe GitLab !38458, constat 3) — LES LIAISONS LOCALES A
                // L'APPAREIL NE TRAVERSENT PLUS.
                //
                // Le `copy` remappait les identifiants Room et laissait passer `telephonyUri`,
                // `mmsSystemId` et `subId` — des references au telephone SOURCE, qui devenaient
                // des liaisons vivantes sur celui de DESTINATION. `content://sms/42` designe un
                // message ici et un tout autre la-bas, d'ou deux consequences :
                //
                //   - a la restauration, l'index UNIQUE sur `telephony_uri` et le
                //     `OnConflictStrategy.IGNORE` faisaient entrer en collision deux messages
                //     sans rapport : celui de la sauvegarde etait ecarte en silence, expediteur
                //     et contenu differents compris ;
                //   - a la suppression, cet URI partait tel quel au fournisseur du systeme de la
                //     destination, qui pouvait effacer la ligne de quelqu'un d'autre.
                //
                // Quand la sauvegarde vient du meme appareil, ces references sont exactes et on
                // les garde : c'est le cas d'usage courant (reinstallation), et les priver de
                // `telephonyUri` ferait re-importer chaque message en double a la
                // resynchronisation suivante, `ConversationMirror` ne dedoublonnant que par cet
                // index. Provenance etrangere ou inconnue : on coupe. Les lignes ainsi privees
                // d'URI sont dedoublonnees par [MessageDao.findRestoreDuplicate] juste en
                // dessous, chemin qui existait deja pour les MMS sortants.
                val toInsert = toLocalRow(backupMsg, newConvId, sameDevice)
                // v1.26.1 (audit M6) — repli de déduplication pour les lignes SANS
                // `telephony_uri` : l'index UNIQUE ne les dédoublonne pas, SQLite traitant deux
                // NULL comme distincts. Sans ce contrôle, restaurer deux fois la même sauvegarde
                // dupliquait tous les MMS sortants — sans limite.
                val existingId = if (toInsert.telephonyUri == null) {
                    messageDao.findRestoreDuplicate(
                        conversationId = newConvId,
                        date = toInsert.date,
                        direction = toInsert.direction,
                        body = toInsert.body,
                        // v1.28.3 (F25) — trois discriminants de plus. Voir le KDoc de la requete :
                        // un MMS restaure a souvent un corps VIDE, et la cle d'origine confondait
                        // alors deux messages distincts de la meme seconde.
                        type = toInsert.type,
                        dateSent = toInsert.dateSent,
                        subId = toInsert.subId,
                    )
                } else {
                    null
                }
                if (existingId != null) {
                    skipped++
                    msgIdMap[backupMsg.id] = existingId
                    continue
                }
                val rowId = messageDao.insert(toInsert)
                if (rowId == -1L) {
                    skipped++
                    // v1.28.3 (F24) — UNE COLLISION N'EST PAS UN ECHEC, c'est une RENCONTRE.
                    //
                    // `insert` est en `OnConflictStrategy.IGNORE` : il rend `-1` quand l'index
                    // UNIQUE sur `telephony_uri` designe deja cette ligne. On se contentait de
                    // compter un « ignore » et de passer, sans rien inscrire dans `msgIdMap`.
                    //
                    // Or c'est le scenario NOMINAL, pas un cas limite : apres reinstallation, la
                    // resynchronisation reimporte tout l'historique depuis `content://sms` AVANT
                    // que l'utilisateur ne restaure. Chaque message de la sauvegarde entrait donc
                    // en collision, `imported` valait 0, la carte restait vide — et la passe 2,
                    // qui recolle les citations, sortait sur `?: continue` pour chacune d'elles.
                    // Toutes les reponses citees affichaient « Message supprime ».
                    //
                    // La sauvegarde ne rendait alors RIEN de ce qu'elle seule transporte : ni les
                    // citations, ni les favoris, ni les reactions — le fournisseur du systeme
                    // n'en connait aucun. Elle devenait une operation sans effet, qui s'annoncait
                    // reussie.
                    val existant = toInsert.telephonyUri?.let { messageDao.findByTelephonyUri(it) }
                    if (existant != null) {
                        msgIdMap[backupMsg.id] = existant.id
                        fusionnerDrapeauxLocaux(existant, toInsert)
                    }
                } else {
                    imported++
                    msgIdMap[backupMsg.id] = rowId
                }
            }
            // Passe 2 — réécrit replyToMessageId pour les messages qui avaient une citation
            // dans le backup et dont la cible a été elle-même importée (pas une cible orpheline).
            for (backupMsg in payload.messages) {
                val replyTarget = backupMsg.replyToMessageId ?: continue
                val newId = msgIdMap[backupMsg.id] ?: continue
                val newReplyTarget = msgIdMap[replyTarget] ?: continue
                messageDao.setReplyTarget(newId, newReplyTarget)
            }
            RestoreResult(
                conversationsReused = reused,
                conversationsCreated = created,
                messagesImported = imported,
                messagesSkipped = skipped,
            )
        }
    }

    /** Big-endian Int read at [offset] (4 bytes). Inverse de [intToBytesBE]. */
    private fun bytesBEToInt(bytes: ByteArray, offset: Int): Int {
        return ((bytes[offset].toInt() and 0xFF) shl 24) or
            ((bytes[offset + 1].toInt() and 0xFF) shl 16) or
            ((bytes[offset + 2].toInt() and 0xFF) shl 8) or
            (bytes[offset + 3].toInt() and 0xFF)
    }

    companion object {
        /**
         * Transforme une ligne de sauvegarde en ligne locale prete a inserer.
         *
         * Extraite du corps de la restauration parce que c'est **la** decision de la
         * restauration, et qu'elle doit pouvoir etre verifiee sans Room, sans SQLCipher et sans
         * appareil : ce qui suit est le resultat de deux revues successives, et rien ici n'est
         * arbitraire.
         *
         * - `id` repart a zero, `conversationId` est remappe : les identifiants Room de la
         *   source ne veulent rien dire ici.
         * - `replyToMessageId` est mis a null puis reecrit par la seconde passe, une fois la
         *   table de correspondance des messages connue (audit SECU-M4 v1.15.2).
         * - `attachmentsCount` repart a zero : la sauvegarde ne transporte ni la table des
         *   pieces jointes ni les fichiers, et le compteur restaure tel quel faisait annoncer
         *   aux bulles des pieces jointes introuvables (audit M7 v1.26.1).
         * - **v1.27.11 (revue externe GitLab !38458, constat 3)** — `telephonyUri`,
         *   `mmsSystemId` et `subId` ne traversent que si la sauvegarde vient de CET appareil.
         *   Ce sont des references au telephone source ; ailleurs, `content://sms/42` designe un
         *   autre message. Elles entraient en collision sur l'index UNIQUE a la restauration —
         *   le message de la sauvegarde etait ecarte en silence — et repartaient telles quelles
         *   au fournisseur du systeme a la suppression.
         *
         * Pourquoi ne pas couper TOUJOURS, ce qui serait plus simple : sur le meme appareil ces
         * references sont exactes, et les couper ferait re-importer chaque message en double a
         * la resynchronisation suivante, `ConversationMirror` ne dedoublonnant que par cet
         * index. La reinstallation suivie d'une restauration est le cas d'usage courant ; on ne
         * repare pas le cas rare en cassant le frequent.
         */
        internal fun toLocalRow(
            backupMsg: MessageEntity,
            conversationId: Long,
            sameDevice: Boolean,
        ): MessageEntity = backupMsg.copy(
            id = 0L,
            conversationId = conversationId,
            replyToMessageId = null,
            attachmentsCount = 0,
            telephonyUri = if (sameDevice) backupMsg.telephonyUri else null,
            mmsSystemId = if (sameDevice) backupMsg.mmsSystemId else null,
            subId = if (sameDevice) backupMsg.subId else null,
        )

        const val MAGIC = "SMBK"
        const val VERSION = 1
        private const val APP_TAG = "SMS Tech"
        // v1.15.2 — Cap de sécurité sur la taille d'un .smsbk à restaurer. 50 MB couvre des
        // archives très volumineuses (centaines de milliers de SMS) tout en bloquant un
        // fichier hostile qui tenterait un OOM. Lecture en streaming chunked (SECU-H1).
        private const val MAX_RESTORE_BYTES = 50 * 1024 * 1024
        private const val READ_CHUNK_BYTES = 8 * 1024
        private const val SALT_LEN = 16
        private const val ITER_LEN = 4
        // v1.17.0 audit KOTLIN-L2 — Bornes header `.smsbk` alignées sur les constantes
        // canoniques [com.filestech.sms.core.crypto.PasswordKdf]. Avant : duplicate ici
        // (10k..2M) divergeant des bornes PasswordKdf (210k..4M) → header avec iter=50k
        // passait notre validation mais déclenchait `require(iter >= MIN_ITERATIONS)`
        // dans `kdf.derive` → exception Storage au lieu de Validation, UX dégradée.
        // Désormais : single source of truth.
        private val KDF_ITER_MIN = com.filestech.sms.core.crypto.PasswordKdf.MIN_ITERATIONS
        private val KDF_ITER_MAX = com.filestech.sms.core.crypto.PasswordKdf.MAX_ITERATIONS
        // Audit SECU-M1 v1.15.2 — Caps dur sur le nombre d'objets désérialisés du JSON.
        // Un fichier de 50 MB compressé peut contenir des dizaines de millions d'entités si
        // chaque entité est minimaliste — DoS de boucle d'import. Caps généreux :
        //  - 10 000 conv = ~50 ans d'utilisation intensive sur un single SIM
        //  - 1 000 000 msgs = ~100 msgs/jour pendant 27 ans
        private const val MAX_RESTORE_CONVERSATIONS = 10_000
        private const val MAX_RESTORE_MESSAGES = 1_000_000
    }
}
