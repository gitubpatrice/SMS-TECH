package com.filestech.sms.security

import com.filestech.sms.core.crypto.KeystoreManager
import com.filestech.sms.data.local.datastore.SecurityStore
import com.filestech.sms.data.local.db.AppDatabase
import com.filestech.sms.data.local.db.DatabaseKeyManager
import com.filestech.sms.data.local.db.dao.ConversationDao
import com.filestech.sms.data.repository.ConversationEraser
import com.filestech.sms.di.IoDispatcher
import com.filestech.sms.domain.security.PanicStateProvider
import com.filestech.sms.domain.settings.AppSettings
import com.filestech.sms.domain.settings.AppSettingsSource
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Hard wipe of all locally stored sensitive data. Triggered by user action (settings →
 * "Supprimer toutes mes données"). Order matters: drop the SQLCipher key file first so even
 * a crash mid-wipe leaves the DB unreadable.
 *
 * v1.28.6 — **en session leurre, la purge n'efface que ce que le leurre montre.** Le bouton reste
 * visible en leurre, pour la même raison que « Réinitialiser tous les réglages » (v1.27.11) : une
 * application SMS ordinaire sait s'effacer, et son absence serait un indice. Mais son effet était
 * total : depuis une session leurre, il détruisait le coffre réel, le PIN et le code panique —
 * précisément ce que le leurre existe pour préserver. La branche est prise ICI, au point d'entrée
 * unique, et non dans l'écran : un garde d'écran ne dit rien du prochain point d'entrée.
 */
@Singleton
class PanicService @Inject constructor(
    @ApplicationContext private val context: android.content.Context,
    private val database: AppDatabase,
    private val keyManager: DatabaseKeyManager,
    private val keystore: KeystoreManager,
    private val securityStore: SecurityStore,
    private val settings: AppSettingsSource,
    private val panicState: PanicStateProvider,
    private val conversationDao: ConversationDao,
    private val eraser: ConversationEraser,
    @IoDispatcher private val io: CoroutineDispatcher,
) {
    suspend fun nukeEverything(): Unit = withContext(io) {
        if (panicState.isPanicDecoyActive) {
            effacerCeQueLeLeurreMontre()
            return@withContext
        }
        // Order matters (audit F29):
        //  1. Close the Room/SQLCipher database synchronously so no transaction can re-write
        //     after we delete its on-disk files.
        //  2. Drop the wrapped DB key BEFORE touching the actual database files — if anything
        //     crashes mid-wipe, the residual DB is unreadable.
        //  3. Drop the Keystore aliases so the wrapped key blob can't be reconstructed.
        //  4. Delete the database + its WAL/SHM sidecars via Context.deleteDatabase (the only
        //     way to also nuke `<db>-journal`, `<db>-wal` and `<db>-shm`).
        //  5. Wipe cache + exports + attachments.
        //  6. Reset preferences.
        runCatching { database.close() }.onFailure { Timber.w(it, "PanicService: db close") }
        runCatching { keyManager.destroyKeyFile() }.onFailure { Timber.w(it, "destroy key file") }
        runCatching {
            keystore.deleteKey(KeystoreManager.ALIAS_DB_MASTER)
            keystore.deleteKey(KeystoreManager.ALIAS_VAULT_KEK)
            keystore.deleteKey(KeystoreManager.ALIAS_SETTINGS_AEAD)
            keystore.deleteKey(KeystoreManager.ALIAS_PANIC_DECOY)
        }.onFailure { Timber.w(it, "delete keystore aliases") }
        runCatching { context.deleteDatabase(AppDatabase.DATABASE_NAME) }
            .onFailure { Timber.w(it, "deleteDatabase") }
        // v1.24.0 SEC — `deleteDatabase` ne connaît que `<db>`, `-journal`, `-wal` et `-shm`. La
        // réparation zéro-clé ([LegacyZeroKeyRekey]) peut laisser un `<db>.rekeyold` ou
        // `<db>.rekeytmp` si le processus est tué en plein échange. Or un `.rekeyold` est
        // l'historique COMPLET chiffré avec 32 octets nuls — une constante publique. Sans cette
        // purge, « supprimer toutes mes données » détruisait tout SAUF le seul fichier lisible
        // sans clé.
        runCatching {
            val dbName = AppDatabase.DATABASE_NAME
            context.getDatabasePath(dbName).parentFile
                ?.listFiles { f -> f.name.startsWith(dbName) }
                ?.forEach { it.delete() }
        }.onFailure { Timber.w(it, "wipe database residues") }
        // Le marqueur de complétion de la réparation n'a aucune valeur secrète, mais « tout
        // effacer » doit être total.
        runCatching {
            context.getSharedPreferences("db_repair", android.content.Context.MODE_PRIVATE)
                .edit().clear().commit()
        }.onFailure { Timber.w(it, "clear db_repair prefs") }
        runCatching {
            File(context.filesDir, "mms_attachments").deleteRecursively()
            File(context.filesDir, "db").deleteRecursively()
            effacerLesFichiersTransitoires()
        }.onFailure { Timber.w(it, "wipe file dirs") }
        // v1.28.5 (balayage des `runCatching`, constat de SECURITE) — les quatre ecritures
        // DataStore ci-dessous sont NON ANNULABLES, et leurs echecs sont journalises. L'appel
        // vit dans un `viewModelScope` : si l'ecran des Reglages quittait la pile a cet instant,
        // les etapes synchrones (base, cles, fichiers) etaient deja faites, puis chaque `suspend`
        // levait une annulation que `runCatching` avalait SANS un mot — et « supprimer toutes
        // mes donnees » rendait la main en laissant le PIN, le code panique, les compteurs de
        // verrouillage et tous les reglages (contacts du Safety call, « Mon numero »).
        withContext(NonCancellable) {
            runCatching { securityStore.clearPin() }.onFailure { Timber.w(it, "wipe: clearPin") }
            runCatching { securityStore.clearPanic() }.onFailure { Timber.w(it, "wipe: clearPanic") }
            // Audit S-P2-2: clearPin / clearPanic above remove the credential snapshots themselves
            // but leave the surrounding bookkeeping (`failCount`, `lockoutUntil`) untouched in the
            // DataStore. After a wipe the user re-onboards with a brand-new lock; if the previous
            // session had been close to the lockout threshold, the new setup would inherit those
            // counters and lock the user out before they had a chance to authenticate.
            runCatching {
                securityStore.setFailCount(0)
                // v1.14.8 R7 — clearLockout wipe les 3 fields (wall + mono baseline + duration).
                securityStore.clearLockout()
            }.onFailure { Timber.w(it, "wipe: lockout counters") }
            runCatching { settings.update { AppSettings() } }
                .onFailure { Timber.w(it, "wipe: settings") }
        }
    }

    /**
     * v1.28.6 — la purge vue depuis une session leurre : le même résultat visible qu'une purge
     * réelle, sans toucher à ce que le leurre protège.
     *
     * Ce qui part : chaque conversation hors coffre, par [ConversationEraser.erase] en mode
     * ordinaire — donc avec sa copie système, ses envois programmés et ses fichiers, exactement
     * comme une suppression faite à la main —, les fichiers transitoires (exports, cache), et
     * les réglages, ramenés aux défauts **en préservant le bloc sécurité**, comme
     * « Réinitialiser tous les réglages » depuis la v1.27.11 ; le splash de première ouverture se
     * rejoue donc, comme après une purge réelle.
     *
     * Ce qui ne bouge pas : la base et sa clé, les alias du Keystore, le PIN, le code panique,
     * les compteurs de verrouillage, et le dossier `mms_attachments` en bloc — il porte aussi les
     * pièces jointes du coffre ; l'effaceur retire celles des conversations qu'il supprime.
     *
     * Le tout est non annulable, pour la raison écrite dans [nukeEverything] : rendre la main
     * à moitié fait est le défaut que la v1.28.5 a fermé.
     */
    private suspend fun effacerCeQueLeLeurreMontre() = withContext(NonCancellable) {
        val ids = runCatching { conversationDao.idsHorsCoffre() }
            .onFailure { Timber.w(it, "decoy wipe: listing") }
            .getOrDefault(emptyList())
        var echecs = 0
        for (id in ids) {
            runCatching { eraser.erase(id, ConversationEraser.Mode.ORDINAIRE) }
                .onFailure {
                    echecs++
                    Timber.w(it, "decoy wipe: conversation %d", id)
                }
        }
        Timber.i("decoy wipe: %d conversation(s), %d failure(s)", ids.size, echecs)
        runCatching { effacerLesFichiersTransitoires() }.onFailure { Timber.w(it, "decoy wipe: files") }
        runCatching { settings.update { AppSettings(security = it.security) } }
            .onFailure { Timber.w(it, "decoy wipe: settings") }
    }

    /** Exports et cache : sans contenu du coffre qui ne soit déjà purgé à chaque verrouillage. */
    private fun effacerLesFichiersTransitoires() {
        File(context.filesDir, "exports").deleteRecursively()
        context.cacheDir.listFiles()?.forEach { it.deleteRecursively() }
    }
}
