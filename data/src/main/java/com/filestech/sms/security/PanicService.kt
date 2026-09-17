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
        //
        // v1.28.9 (septième note d'Andrew, constat 2) — UNE LISTE ILLISIBLE N'EST PAS UNE LISTE VIDE.
        // `getOrDefault(emptyList())` faisait passer l'échec de lecture pour « aucune conversation » :
        // aucune copie système n'était présentée au fournisseur, et le dialogue annonçait pourtant
        // « Rien ne reviendra ». La destruction locale continue — on purge parce que quelqu'un va
        // prendre le téléphone, et une base illisible n'est pas une raison de la laisser —, mais le
        // compte rendu le dit.
        val toutes = runCatching { conversationDao.idsToutes() }
            .onFailure { Timber.w(it, "wipe: enumeration des conversations") }
            .getOrNull()
        val balayage = effacerConversations(toutes.orEmpty())
        // v1.28.9 — chaque étape locale rend compte, et les dossiers sont VÉRIFIÉS après coup. Les
        // booléens de `delete()` et les exceptions étaient avalés : la purge se disait complète quoi
        // qu'il reste sur le disque.
        var echecs = balayage.echecsLocaux
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
        runCatching { keyManager.destroyKeyFile() }.onFailure {
            echecs++
            Timber.w(it, "destroy key file")
        }
        // v1.28.9 (audit data-room du 2026-09-14, DR1) — chaque alias est RELU après sa suppression.
        // `KeystoreManager.deleteKey` avale lui-même ses exceptions : le `runCatching` qui entourait les
        // quatre appels ne pouvait voir aucun échec, et une clé qui résistait laissait la purge se dire
        // complète. Un alias qui résiste n'empêche pas les suivants de partir.
        // La clé de la porte biométrique part aussi, qui manquait à la liste : elle ne chiffre rien, mais sa
        // seule présence dit qu'un verrou biométrique a existé — ce que « tout effacer » doit taire, comme
        // l'empreinte du PIN du coffre (v1.28.6).
        // v1.28.12 (audit B2) — `ALIAS_VAULT_KEK` n'est créé par RIEN aujourd'hui : la seconde
        // enveloppe du coffre n'a jamais été construite. On l'efface quand même, préventivement, pour
        // que le jour où elle le sera, « tout effacer » la couvre sans qu'on ait à y revenir.
        val aliasADetruire = listOf(
            KeystoreManager.ALIAS_DB_MASTER,
            KeystoreManager.ALIAS_VAULT_KEK,
            KeystoreManager.ALIAS_SETTINGS_AEAD,
            KeystoreManager.ALIAS_PANIC_DECOY,
            KeystoreManager.ALIAS_BIOMETRIC_GATE,
        )
        for (alias in aliasADetruire) {
            runCatching {
                keystore.deleteKey(alias)
                check(!keystore.containsAlias(alias)) { "alias toujours present apres suppression" }
            }.onFailure {
                echecs++
                Timber.w(it, "delete keystore alias")
            }
        }
        runCatching { context.deleteDatabase(AppDatabase.DATABASE_NAME) }
            .onFailure { Timber.w(it, "deleteDatabase") }
        // v1.24.0 SEC — `deleteDatabase` ne connaît que `<db>`, `-journal`, `-wal` et `-shm`. La
        // réparation zéro-clé ([LegacyZeroKeyRekey]) peut laisser un `<db>.rekeyold` ou
        // `<db>.rekeytmp` si le processus est tué en plein échange. Or un `.rekeyold` est
        // l'historique COMPLET chiffré avec 32 octets nuls — une constante publique. Sans cette
        // purge, « supprimer toutes mes données » détruisait tout SAUF le seul fichier lisible
        // sans clé.
        //
        // v1.28.9 — puis on VÉRIFIE qu'aucun fichier de la base ne subsiste : c'est la seule preuve
        // que `deleteDatabase` et ces suppressions ont abouti. Son booléen n'en est pas une — il
        // vaut aussi `false` pour une base déjà absente.
        runCatching {
            val dbName = AppDatabase.DATABASE_NAME
            val dossierBase = context.getDatabasePath(dbName).parentFile
            dossierBase?.listFiles { f -> f.name.startsWith(dbName) }?.forEach { it.delete() }
            // `listFiles` rend `null` sans lever sur un dossier illisible : ne rien voir n'est pas ne
            // rien trouver (relecture GPT 5.2 du 2026-09-14, constat 5). Absent, il n'a rien à rendre.
            val restants = dossierBase?.listFiles { f -> f.name.startsWith(dbName) }
            echecs += when {
                dossierBase == null || !dossierBase.exists() -> 0
                restants == null -> 1
                else -> restants.size
            }
        }.onFailure {
            echecs++
            Timber.w(it, "wipe database residues")
        }
        // Le marqueur de complétion de la réparation n'a aucune valeur secrète, mais « tout
        // effacer » doit être total.
        runCatching {
            context.getSharedPreferences("db_repair", android.content.Context.MODE_PRIVATE)
                .edit().clear().commit()
        }.onFailure { Timber.w(it, "clear db_repair prefs") }
        runCatching {
            if (!File(context.filesDir, "mms_attachments").deleteRecursively()) echecs++
            if (!File(context.filesDir, "db").deleteRecursively()) echecs++
            echecs += effacerLesFichiersTransitoires()
        }.onFailure {
            echecs++
            Timber.w(it, "wipe file dirs")
        }
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
        //
        // v1.28.9 — journalisés, et désormais COMPTÉS : une empreinte de PIN restée sur le disque ne
        // doit pas laisser la purge se dire complète.
        runCatching { securityStore.clearAll() }.onFailure {
            echecs++
            Timber.w(it, "wipe: magasin securise")
        }
        runCatching { settings.update { AppSettings() } }.onFailure {
            echecs++
            Timber.w(it, "wipe: settings")
        }
        return Residu(balayage.copiesSystemeRestantes, listeIllisible = toutes == null, echecsLocaux = echecs)
    }

    /**
     * v1.28.6 — ce qui a RÉSISTÉ, pour que l'application le dise au lieu de promettre le contraire.
     *
     * [copiesSystemeRestantes] compte les conversations dont la copie dans `content://sms` est
     * toujours là. Cause quasi unique : SMS Tech n'est pas l'application SMS par défaut, et le
     * système lui refuse alors la suppression. Un échec dont on ne sait rien compte ici aussi —
     * le doute se résout du côté « nous n'avons pas tout effacé », jamais de l'autre.
     *
     * v1.28.9 (septième note d'Andrew, constat 2) — deux autres causes, comptées à part parce que le
     * dialogue ne dit pas la même chose : [listeIllisible], la liste des conversations n'a pas pu
     * être lue, donc aucune copie système n'a été présentée au fournisseur — elle passait pour
     * vide ; [echecsLocaux], ce qui n'a pas pu être effacé sur l'appareil lui-même (fichier,
     * dossier, clé, magasin sécurisé, réglages). Le doute se résout du même côté.
     */
    data class Residu(
        val copiesSystemeRestantes: Int,
        val listeIllisible: Boolean,
        val echecsLocaux: Int,
    ) {
        /** Rien n'a résisté, nulle part : la seule condition sous laquelle l'écran confirme. */
        val complet: Boolean get() = copiesSystemeRestantes == 0 && !listeIllisible && echecsLocaux == 0
    }

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
        // v1.28.9 — en mode ordinaire la ligne part quoi qu'il arrive, mais un fichier ou un envoi
        // programmé qui a résisté le dit (`localeComplete`) : il reste sur l'appareil, et cela compte.
        var echecsLocaux = 0
        for (id in ids) {
            runCatching { eraser.erase(id, ConversationEraser.Mode.ORDINAIRE) }
                .onSuccess {
                    if (!it.systemCopyGone) restantes++
                    if (!it.localeComplete) echecsLocaux++
                }
                .onFailure {
                    // Le doute se résout des deux côtés : ce qui remonte d'`erase` vient de sa partie
                    // LOCALE (transaction, DAO), et l'on ne sait rien de la copie système. Relecture
                    // GPT 5.2 du 2026-09-14 : compté seulement en copie, le dialogue donnait la
                    // mauvaise cause.
                    restantes++
                    echecsLocaux++
                    Timber.w(it, "wipe: conversation %d non supprimee", id)
                }
        }
        Timber.i(
            "wipe: %d conversation(s), %d copie(s) systeme restante(s), %d echec(s) local(aux)",
            ids.size,
            restantes,
            echecsLocaux,
        )
        Residu(restantes, listeIllisible = false, echecsLocaux = echecsLocaux)
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
        // v1.28.9 (constat 2, le jumeau que la note ne citait pas) — même aveu qu'en session réelle.
        // Une liste illisible n'effaçait RIEN ici, et le dialogue disait « effacé » devant une liste
        // restée pleine ; il le dit désormais, avec les mêmes mots que la purge totale.
        val ids = runCatching { conversationDao.idsHorsCoffre() }
            .onFailure { Timber.w(it, "decoy wipe: listing") }
            .getOrNull()
        val balayage = effacerConversations(ids.orEmpty())
        var echecs = balayage.echecsLocaux
        runCatching { echecs += effacerLesFichiersTransitoires() }.onFailure {
            echecs++
            Timber.w(it, "decoy wipe: files")
        }
        runCatching { settings.update { AppSettings(security = it.security) } }.onFailure {
            echecs++
            Timber.w(it, "decoy wipe: settings")
        }
        balayage.copy(listeIllisible = ids == null, echecsLocaux = echecs)
    }

    /**
     * Exports et cache : sans contenu du coffre qui ne soit déjà purgé à chaque verrouillage.
     *
     * v1.28.9 — rend le nombre d'éléments qui ont résisté, au lieu de l'avaler.
     */
    private fun effacerLesFichiersTransitoires(): Int {
        var echecs = 0
        if (!File(context.filesDir, "exports").deleteRecursively()) echecs++
        context.cacheDir.listFiles()?.forEach { if (!it.deleteRecursively()) echecs++ }
        return echecs
    }
}
