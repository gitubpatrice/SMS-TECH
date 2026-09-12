package com.filestech.sms.security

import com.filestech.sms.core.crypto.KeystoreManager
import com.filestech.sms.data.local.datastore.SecurityStore
import com.filestech.sms.data.local.db.AppDatabase
import com.filestech.sms.data.local.db.DatabaseKeyManager
import com.filestech.sms.data.local.db.dao.ConversationDao
import com.filestech.sms.data.repository.ConversationEraser
import com.filestech.sms.di.IoDispatcher
import com.filestech.sms.domain.clipboard.ClipboardCleaner
import com.filestech.sms.domain.notification.AllNotificationsCanceller
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
    private val barriere: VaultPurgeBarrier,
    private val notifications: AllNotificationsCanceller,
    private val pressePapiers: ClipboardCleaner,
    @IoDispatcher private val io: CoroutineDispatcher,
) {
    /**
     * v1.28.6 (audit 3 axes pré-release) — **non annulable de bout en bout**, et non plus sur son
     * seul bloc final.
     *
     * La v1.28.5 avait mis les écritures DataStore de fin sous `NonCancellable` pour cette raison
     * précise : l'appel vit dans un `viewModelScope`, et quitter les Réglages pendant la purge
     * annulait le job. Cette version a ajouté AU-DESSUS un balayage suspendu et long — la barrière
     * prend un `Mutex`, l'effaceur parle au fournisseur du système pour chaque message — sans
     * reporter la leçon d'un cran plus haut. Annulé en cours de balayage, on détruisait la base
     * sans avoir propagé les suppressions : les messages revenaient à la synchronisation suivante,
     * c'est-à-dire exactement le défaut que cette même version ferme. Et le dialogue aurait
     * attribué le résidu à la mauvaise cause.
     *
     * La garantie est donc portée par la fonction entière, une fois, et non par un bloc interne
     * qu'un ajout ultérieur contournerait encore.
     */
    suspend fun nukeEverything(): Residu = withContext(io + NonCancellable) {
        val residu = if (panicState.isPanicDecoyActive) {
            effacerCeQueLeLeurreMontre()
        } else {
            purgeTotale()
        }
        // v1.28.6 — CE QUI EST EFFACÉ NE DOIT PLUS S'AFFICHER. Les notifications déjà posées
        // survivaient à la purge : le volet gardait expéditeur et texte, et le raccourci
        // d'urgence, qui est `ongoing`, n'est même pas balayable à la main. Le pire moment pour
        // laisser ça — on purge parce que quelqu'un va prendre le téléphone.
        //
        // Dans LES DEUX sessions, au même endroit, avec le même effet visible : une purge de
        // leurre qui laisserait des notifications là où la vraie les efface serait une différence
        // observable, donc la fuite que I1 interdit. Rien du coffre ne s'y trouve de toute façon —
        // une conversation du coffre ne notifie jamais.
        runCatching { notifications.cancelAll() }
            .onFailure { Timber.w(it, "wipe: annulation des notifications") }
        // v1.28.6 — ET LE PRESSE-PAPIERS, pour la meme raison et au meme endroit. Cette version
        // ajoute la copie d'un EXTRAIT de message : on selectionne, on copie, on purge — et le
        // texte restait dans le presse-papiers du telephone, lisible par toute application. Le
        // presse-papiers est deja hors perimetre du coffre (I7/N4, limite assumee et ecrite) ;
        // ce qui ne l'etait pas, c'est qu'une purge se disant irreversible le laisse garni.
        // Dans les deux sessions, comme les notifications : une difference observable entre
        // leurre et session reelle serait la fuite que I1 interdit.
        // Sous filet, comme chaque autre etape : c'est la derniere ligne avant que la purge ne
        // rende compte. Une exception ici — `getSystemService` intercepte par une ROM ou un EMM —
        // remonterait APRES la destruction de la base et des cles, et AVANT le dialogue de
        // confirmation : un plantage au lieu du « Donnees effacees » que cette version ajoute.
        // Trouve par la relecture securite du delta final (S1).
        runCatching { pressePapiers.clear() }
            .onFailure { Timber.w(it, "wipe: presse-papiers") }
        residu
    }

    private suspend fun purgeTotale(): Residu {
        // v1.28.6 — LES MESSAGES DU TÉLÉPHONE PARTENT AVANT LA BASE, ET PAR LE MÊME CHEMIN QU'UNE
        // SUPPRESSION À LA MAIN.
        //
        // Jusqu'ici la purge supprimait le FICHIER de base, sans jamais passer par
        // [ConversationEraser] : la copie de chaque message restait donc dans `content://sms`, et
        // la resynchronisation du lancement suivant — curseur remis à 0 par cette même purge —
        // les ramenait TOUS. Le dialogue promettait « irréversible » ; il était faux. Pire, les
        // conversations du COFFRE revenaient dans la liste principale, en clair : leur copie
        // système n'avait jamais été supprimée (limite N2, assumée) et le drapeau `in_vault` ne
        // vivait que dans la base qu'on venait de détruire.
        //
        // Sous la barrière, comme la purge du coffre : on balaie des conversations du coffre, et
        // personne ne doit y entrer pendant ce temps.
        val toutes = runCatching { conversationDao.idsToutes() }
            .onFailure { Timber.w(it, "wipe: enumeration des conversations") }
            .getOrDefault(emptyList())
        val residu = effacerConversations(toutes)
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
        // v1.28.5 (balayage des `runCatching`, constat de SECURITE) — les ecritures DataStore
        // ci-dessous sont NON ANNULABLES, et leurs echecs sont journalises. Depuis la v1.28.6 la
        // garantie vient de la tete de [nukeEverything], qui la porte pour toute la fonction ;
        // elle etait ici seule, et l'ajout du balayage passait au-dessus d'elle.
        //
        // v1.28.6 — LE MAGASIN SECURISE PART EN ENTIER, et non par liste de cles nommees.
        // Elle en nommait huit sur dix-huit : le PIN DU COFFRE (v1.13.0), sa temporisation
        // (v1.27.10), l'horodatage du dernier deverrouillage et le jeton de notification ne s'y
        // sont jamais ajoutes. Ils survivaient donc a « supprimer toutes mes donnees », dans un
        // DataStore de preferences NON chiffre — une empreinte PBKDF2 de code a quatre chiffres se
        // casse hors ligne, et sa seule presence prouvait qu'un coffre avait existe, ce que le
        // leurre existe pour taire. Voir [SecurityStore.clearAll] : une liste a tenir a jour est
        // un rendez-vous manque a chaque nouvelle cle, et il a ete manque trois fois.
        //
        // Ce que l'audit S-P2-2 demandait est tenu par construction : les compteurs d'echec et de
        // temporisation partent avec le reste, donc une reinscription ne les herite plus.
        runCatching { securityStore.clearAll() }.onFailure { Timber.w(it, "wipe: magasin securise") }
        runCatching { settings.update { AppSettings() } }
            .onFailure { Timber.w(it, "wipe: settings") }
        return residu
    }

    /**
     * v1.28.6 — ce qui a RÉSISTÉ, pour que l'application le dise au lieu de promettre le contraire.
     *
     * [copiesSystemeRestantes] compte les conversations dont la copie dans `content://sms` est
     * toujours là. Cause quasi unique : SMS Tech n'est pas l'application SMS par défaut, et le
     * système lui refuse alors la suppression. Un échec dont on ne sait rien compte ici aussi —
     * le doute se résout du côté « nous n'avons pas tout effacé », jamais de l'autre.
     */
    data class Residu(val copiesSystemeRestantes: Int)

    /**
     * Supprime [ids] par [ConversationEraser] en mode ordinaire — copie système, envois
     * programmés et fichiers compris —, et compte ce qui reste dans le téléphone.
     *
     * Mode ORDINAIRE et non COFFRE : ici la ligne locale doit partir quoi qu'il arrive, la base
     * entière étant détruite juste après ; conserver un parent comme journal de reprise n'aurait
     * aucun sens. Ce que l'on veut de ce balayage, c'est la propagation au fournisseur du système.
     */
    private suspend fun effacerConversations(ids: List<Long>): Residu = barriere.pendant {
        var restantes = 0
        for (id in ids) {
            runCatching { eraser.erase(id, ConversationEraser.Mode.ORDINAIRE) }
                .onSuccess { if (!it.systemCopyGone) restantes++ }
                .onFailure {
                    restantes++
                    Timber.w(it, "wipe: conversation %d non supprimee", id)
                }
        }
        Timber.i("wipe: %d conversation(s), %d copie(s) systeme restante(s)", ids.size, restantes)
        Residu(restantes)
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
     * à moitié fait est le défaut que la v1.28.5 a fermé. La garantie vient désormais de la tête
     * de [nukeEverything], qui couvre les deux chemins.
     */
    private suspend fun effacerCeQueLeLeurreMontre(): Residu = withContext(NonCancellable) {
        val ids = runCatching { conversationDao.idsHorsCoffre() }
            .onFailure { Timber.w(it, "decoy wipe: listing") }
            .getOrDefault(emptyList())
        val residu = effacerConversations(ids)
        runCatching { effacerLesFichiersTransitoires() }.onFailure { Timber.w(it, "decoy wipe: files") }
        runCatching { settings.update { AppSettings(security = it.security) } }
            .onFailure { Timber.w(it, "decoy wipe: settings") }
        residu
    }

    /** Exports et cache : sans contenu du coffre qui ne soit déjà purgé à chaque verrouillage. */
    private fun effacerLesFichiersTransitoires() {
        File(context.filesDir, "exports").deleteRecursively()
        context.cacheDir.listFiles()?.forEach { it.deleteRecursively() }
    }
}
