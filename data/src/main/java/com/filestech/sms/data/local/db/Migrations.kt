package com.filestech.sms.data.local.db

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.filestech.sms.data.sms.canonicalTelephonyUri

/**
 * Room migrations for the SQLCipher-backed [AppDatabase].
 *
 * Each migration preserves user data — a row already imported into the previous schema must
 * remain readable byte-for-byte after the migration runs. New columns are nullable or
 * default-valued so legacy rows project cleanly.
 *
 * Les migrations sont **additives par défaut**, et l'étaient toutes jusqu'à la v9 : on ne
 * supprime ni ne renomme une colonne existante. [MIGRATION_8_9] est la première exception, et
 * elle doit rester rare — SQLite exigeant une recréation de table pour retirer un `NOT NULL`,
 * elle passe par un `DROP TABLE` qui cascaderait sur `messages` si les clés étrangères étaient
 * actives. Son KDoc détaille les trois précautions que cela impose ; les reprendre avant
 * d'écrire une seconde migration de ce type.
 *
 * SQLCipher caveat: `ALTER TABLE` runs through the cipher layer exactly like a normal SQL
 * statement — no special handling required. The migration is wrapped in a transaction by Room.
 */
object Migrations {

    /**
     * v1 → v2 (2026-05-15) — contextual reply feature (#8).
     *
     *  - Adds `messages.reply_to_message_id INTEGER` (nullable). Default NULL = "this message
     *    is not a reply", which matches both legacy rows and freshly imported ones from the
     *    system provider (Telephony.Sms has no concept of reply targeting).
     *  - Adds the matching index so a UI lookup "did anyone reply to this message?" stays cheap.
     *
     * No data migration: legacy rows simply carry NULL.
     */
    val MIGRATION_1_2: Migration = object : Migration(1, 2) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE messages ADD COLUMN reply_to_message_id INTEGER")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_messages_reply_to_message_id ON messages(reply_to_message_id)")
        }
    }

    /**
     * v2 → v3 (2026-05-16, v1.2.6 audit F2 idempotence retry).
     *
     *  - Adds `messages.mms_system_id INTEGER` (nullable). NULL for legacy rows; the new
     *    [com.filestech.sms.data.mms.MmsSender] populates it after a successful writeback.
     *  - Adds the matching index so the retry-path lookup "previous mms_system_id for this
     *    Room message" stays O(log n).
     *
     * Strictly additive — legacy rows are not touched.
     */
    val MIGRATION_2_3: Migration = object : Migration(2, 3) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE messages ADD COLUMN mms_system_id INTEGER")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_messages_mms_system_id ON messages(mms_system_id)")
        }
    }

    /**
     * v3 → v4 (2026-05-16, v1.3.0).
     *
     *  - Ajoute `messages.reaction_emoji TEXT` (nullable). NULL pour les legacy rows.
     *  - Pas d'index — la colonne n'est jamais filtrée. L'index `index_messages_date`
     *    nécessaire à l'auto-purge a été extrait dans une migration v4→v5 dédiée pour
     *    absorber proprement les users qui ont reçu un build v1.3.0 intermédiaire avec
     *    schema v4 sans cet index.
     *
     * Strictement additive — `ALTER TABLE ADD COLUMN`, aucune row touchée.
     */
    val MIGRATION_3_4: Migration = object : Migration(3, 4) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE messages ADD COLUMN reaction_emoji TEXT")
        }
    }

    /**
     * v4 → v5 (2026-05-16, v1.3.0 audit P1).
     *
     *  - Crée `index_messages_date` pour que l'auto-purge `WHERE date < cutoff` (tick
     *    `TelephonySyncWorker`) ne fasse plus de full scan SQLCipher (~1 s sur 50 k rows
     *    chiffrés). L'index composite existant `(conversation_id, date)` est inopérant ici
     *    puisque la purge est inter-conversations.
     *  - `IF NOT EXISTS` rend l'opération idempotente : un user qui aurait reçu une variante
     *    intermédiaire ayant déjà créé cet index voit la migration ne rien faire, sans crash.
     *
     * Strictement additive — pas de row touchée.
     */
    val MIGRATION_4_5: Migration = object : Migration(4, 5) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("CREATE INDEX IF NOT EXISTS index_messages_date ON messages(date)")
        }
    }

    /**
     * v5 → v6 (2026-05-17, v1.3.7 G4 audit).
     *
     * **Cleanup uniquement** — drop la table `conversation_overrides` (entity + DAO existaient
     * mais aucun consommateur métier ; vérifié par grep transversal v1.3.5 → v1.3.7). La table
     * était vide pour 100 % des utilisateurs (jamais d'INSERT du côté code), donc le DROP ne
     * supprime aucune donnée utilisateur. Si la table a été créée par Room sur une version
     * antérieure (ce qui est le cas pour tout install ≥ v1.0), elle est nettoyée ; sinon
     * `IF EXISTS` rend l'opération idempotente sur les rares installations où elle aurait été
     * absente (Room compatible mode, builds custom).
     *
     * Pas de rollback nécessaire — la table était déjà invisible côté code Kotlin (entity et
     * DAO sont supprimés dans le même commit v1.3.7).
     */
    val MIGRATION_5_6: Migration = object : Migration(5, 6) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("DROP TABLE IF EXISTS conversation_overrides")
        }
    }

    /**
     * v6 → v7 (2026-05-22, v1.11.0) — apparence par contact (Sujet 5).
     *
     *  - Ajoute `conversations.bubble_color_argb INTEGER` (nullable). NULL pour
     *    les legacy rows = utilise le bleu marque par défaut pour la bulle
     *    sortante. L'user choisit une couleur dans la palette WCAG-safe via
     *    le dialog "Personnaliser apparence".
     *  - Ajoute `conversations.avatar_uri TEXT` (nullable). NULL = fallback à
     *    l'avatar contact Android natif. URI `content://` persistée via
     *    `takePersistableUriPermission` au pick.
     *
     * Strictement additive — `ALTER TABLE ADD COLUMN` × 2, aucune row touchée.
     * Downgrade v7 → v6 safe : Room ignore les colonnes inconnues, données
     * préservées dans la DB tant qu'on ne ré-écrit pas la row.
     *
     * **NOTE atomicité** (v1.11.0 audit SEC-V7) : `ALTER TABLE ADD COLUMN`
     * n'est PAS idempotente en SQLite (pas de `IF NOT EXISTS` pour cette
     * directive). Room wrappe le `migrate()` entier dans une transaction
     * SQLite via `db.beginTransaction()` / `setTransactionSuccessful()`. Si
     * le process est killé entre les deux `execSQL`, SQLite rollback la
     * transaction (WAL) — la version reste 6 et la migration sera ré-exécutée
     * intégralement au prochain démarrage. Re-exécution partielle impossible.
     * NE PAS ajouter de guard `IF NOT EXISTS` ici (syntax-error garantie).
     */
    val MIGRATION_6_7: Migration = object : Migration(6, 7) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE conversations ADD COLUMN bubble_color_argb INTEGER")
            db.execSQL("ALTER TABLE conversations ADD COLUMN avatar_uri TEXT")
        }
    }

    /**
     * v7 → v8 (2026-09-07, v1.27.11) — **normalise `messages.telephony_uri`**, et supprime les
     * doublons que sa divergence de forme a produits. Aucun changement de schema : c'est une
     * migration de DONNEES.
     *
     * # Le defaut, reproduit avant d'etre corrige
     *
     * Selon la version d'Android, `ContentResolver.insert` rend `content://sms/sent/<id>`
     * (mesure : Galaxy S9 / Android 10) ou `content://sms/<id>` (Galaxy S24 / Android 16).
     * `TelephonyReader` enregistrait la forme rendue, telle quelle, pour tout message que
     * SMS Tech ecrit lui-meme ; l'import depuis le systeme, lui, a toujours construit la forme
     * canonique. L'index `UNIQUE(telephony_uri)` compare des CHAINES : les deux ecritures
     * designant la meme ligne systeme ne se rencontraient jamais. Une resynchronisation
     * complete — le bouton « Resynchroniser », qui remet le curseur a zero — reimportait donc
     * chaque message ecrit par l'application **en double**.
     *
     * # Pourquoi supprimer, et laquelle des deux lignes
     *
     * Normaliser sans supprimer violerait l'index des la premiere collision, et la migration
     * echouerait — la base resterait en v7 a chaque demarrage. On supprime donc d'abord.
     *
     * **La ligne conservee est celle que l'APPLICATION a ecrite**, celle qui porte le dossier,
     * et jamais celle venue de l'import. Ce n'est pas arbitraire : elle seule porte la reaction
     * emoji, la citation (`reply_to_message_id`), le favori, l'etat d'envoi et l'identifiant
     * MMS. Elle appartient surtout a la conversation d'ORIGINE — qui peut etre dans le coffre,
     * la ou son jumeau importe se serait pose en clair. Conserver l'import aurait donc pu sortir
     * un message du coffre.
     *
     * # v1.28.1 (revue externe !38458, 3e passe) — on ne supprime plus sans preuve
     *
     * La v1.27.11 supprimait TOUTE ligne portant l'URI canonique, sans comparer un seul champ.
     * Elle tenait pour acquis qu'une collision est un doublon. Elle ne l'est pas toujours : la
     * restauration d'avant la v1.27.10 recopiait les `telephony_uri` du telephone SOURCE, et
     * `content://sms/42` designe un message ici et un autre la-bas. Une base pouvait donc porter
     * legitimement, sous cette URI, un message n'ayant rien a voir avec celui qu'on normalise —
     * et la migration l'effacait, definitivement.
     *
     * La suppression est desormais conditionnee a l'egalite de l'adresse, du corps, de la date,
     * du sens, du type **et de la conversation**. Ce qui ne se prouve pas **n'est pas touche du
     * tout** : ni supprime, ni normalise. La ligne garde sa forme a dossier, que
     * `SystemCopyEraser.canonicalUri` sait de toute facon normaliser a la lecture ; le seul cout
     * est un doublon possible a la resynchronisation, jamais une donnee perdue.
     *
     * ⚠ **Une premiere version de ce correctif mettait la liaison en collision a `NULL`. C'etait
     * un defaut, trouve en relecture externe le 2026-09-08.** `NULL` ne veut pas dire « liaison
     * incertaine » dans cette base : il veut dire « ce message n'a jamais eu de copie systeme »,
     * et `SystemCopyEraser.erase` en conclut `true`, c'est-a-dire « rien ne survit ». Effacer une
     * liaison LEGITIME aurait donc fait declarer partie une copie systeme bien vivante — et
     * rouvert, par la migration, exactement la fuite que la v1.28.1 corrige par ailleurs.
     *
     * La conversation entre dans la preuve pour une raison distincte : deux copies identiques
     * peuvent vivre l'une dans le coffre et l'autre en clair. Les fusionner reviendrait a choisir
     * a l'aveugle laquelle des deux visibilites survit, et laisserait la conversation perdante
     * avec un apercu et un compteur faux.
     *
     * ⚠ Cette correction ne repare que les bases encore en v7 ou anterieures. Une base deja
     * passee en v8 par la v1.27.11 ou la v1.28.0 a subi l'ancienne regle ; rien dans le schema
     * ne permet de savoir ce qui a ete efface, ni de le rendre.
     *
     * # Pourquoi une boucle Kotlin plutot qu'un `UPDATE` unique
     *
     * La forme canonique se calcule par une regle — cf. [canonicalTelephonyUri] — qui s'ecrit
     * mal en SQLite et se relit encore plus mal. La boucle porte sur les seules lignes au
     * format `content://…/<dossier>/<id>`, qui sont peu nombreuses au regard de la table, et
     * Room execute deja tout `migrate()` dans une transaction : soit l'ensemble passe, soit
     * rien ne bouge et la migration sera rejouee au prochain demarrage.
     */
    val MIGRATION_7_8: Migration = object : Migration(7, 8) {
        override fun migrate(db: SupportSQLiteDatabase) {
            val aNormaliser = mutableListOf<Pair<Long, String>>()
            db.query(
                "SELECT id, telephony_uri FROM messages " +
                    "WHERE telephony_uri IS NOT NULL AND telephony_uri LIKE 'content://%/%/%'",
            ).use { cursor ->
                while (cursor.moveToNext()) {
                    val id = cursor.getLong(0)
                    val uri = cursor.getString(1) ?: continue
                    val canonique = canonicalTelephonyUri(uri)
                    if (canonique != uri) aNormaliser += id to canonique
                }
            }
            for ((id, canonique) in aNormaliser) {
                // 1. Le vrai jumeau — meme adresse, meme corps, meme date, meme sens, meme type —
                //    s'efface AVANT la normalisation, sans quoi l'index UNIQUE refuse. `IS`
                //    plutot que `=` : comparaison sure meme sur une colonne nulle.
                db.execSQL(
                    """
                    DELETE FROM messages
                     WHERE telephony_uri = ? AND id <> ?
                       AND EXISTS (
                           SELECT 1 FROM messages src
                            WHERE src.id = ?
                              AND src.address   IS messages.address
                              AND src.body      IS messages.body
                              AND src.date      IS messages.date
                              AND src.direction IS messages.direction
                              AND src.type      IS messages.type
                              AND src.conversation_id IS messages.conversation_id)
                    """.trimIndent(),
                    arrayOf<Any>(canonique, id, id),
                )
                // 2. La normalisation n'a lieu que si la place est LIBRE. Si une ligne porte
                //    encore l'URI canonique, c'est qu'elle n'a pas ete prouvee identique : on ne
                //    lui prend pas sa liaison et on ne la detruit pas. Cette ligne-ci gardera sa
                //    forme a dossier, que la lecture normalise de toute facon.
                db.execSQL(
                    """
                    UPDATE messages SET telephony_uri = ?
                     WHERE id = ?
                       AND NOT EXISTS (
                           SELECT 1 FROM messages autre
                            WHERE autre.telephony_uri = ? AND autre.id <> ?)
                    """.trimIndent(),
                    arrayOf<Any>(canonique, id, canonique, id),
                )
            }
        }
    }

    /**
     * v8 → v9 (2026-09-09, v1.28.3) — **`conversations.thread_id` devient nullable**, et toute
     * sentinelle « pas de fil système » devient `NULL`.
     *
     * # Le défaut
     *
     * `thread_id` porte un index UNIQUE, et les deux chemins qui créent une conversation sans
     * fil système — la composition (`findOrCreate`) et la réception (`ensureConversation`) —
     * y écrivaient tous deux `0L`. La seconde conversation locale entrait donc en conflit sur
     * l'index, et `OnConflictStrategy.REPLACE` **supprime** la ligne en conflit avant d'insérer
     * la nouvelle : la première conversation disparaissait, ses messages et ses pièces jointes
     * partant avec elle par `ForeignKey.CASCADE`. Reproduit sur émulateur par la relecture
     * externe (F01) avec deux brouillons créés d'affilée.
     *
     * `NULL` est la réponse juste plutôt qu'une sentinelle mieux choisie : SQLite tient deux
     * `NULL` pour distincts sous un index UNIQUE, donc autant de conversations sans fil système
     * que nécessaire coexistent, et l'absence de valeur cesse d'être codée par une valeur.
     *
     * # Pourquoi elle enfreint la règle « additive » du fichier
     *
     * SQLite ne sait pas retirer un `NOT NULL` d'une colonne : il faut recréer la table. C'est
     * la seule migration du projet à le faire, et elle prend donc trois précautions que les
     * autres n'ont pas besoin de prendre :
     *
     *  - **La cascade.** `messages.conversation_id` référence `conversations(id)` en CASCADE.
     *    Si les clés étrangères étaient actives, le `DROP TABLE` ci-dessous exécuterait un
     *    `DELETE FROM` implicite et **effacerait tous les messages**. Room n'active
     *    `PRAGMA foreign_keys` que dans `onOpen`, donc après les migrations — mais cela ne se
     *    suppose pas : `MigrationTest.migrateAll_v1ToCurrent_preservesUserData` insère un
     *    message avant de migrer et échouerait si la cascade se déclenchait.
     *  - **Les identifiants.** `id` est recopié tel quel, jamais régénéré : les
     *    `messages.conversation_id` déjà écrits doivent continuer de désigner la même
     *    conversation. L'`AUTOINCREMENT` est conservé pour que `sqlite_sequence` ne réattribue
     *    pas un identifiant déjà utilisé par des lignes supprimées.
     *  - **Les index.** `DROP TABLE` les emporte ; les quatre sont recréés à l'identique, sous
     *    les noms que Room attend (`runMigrationsAndValidate` refuserait le moindre écart).
     *
     * # La conversion
     *
     * `thread_id <= 0 → NULL` couvre les deux sentinelles qui ont existé : le `0L` de la
     * composition et de la réception, et les valeurs négatives que `BackupService` fabriquait
     * depuis la v1.15.2 pour contourner ce même piège sur le chemin de la restauration. Aucune
     * collision n'est possible en chemin : l'index UNIQUE garantissait déjà l'unicité de ces
     * valeurs, et elles deviennent toutes `NULL`, que l'index ne compare pas entre eux.
     */
    val MIGRATION_8_9: Migration = object : Migration(8, 9) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `conversations_new` (
                    `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    `thread_id` INTEGER,
                    `addresses_csv` TEXT NOT NULL,
                    `display_name` TEXT,
                    `last_message_at` INTEGER NOT NULL,
                    `last_message_preview` TEXT,
                    `unread_count` INTEGER NOT NULL,
                    `pinned` INTEGER NOT NULL,
                    `archived` INTEGER NOT NULL,
                    `muted` INTEGER NOT NULL,
                    `in_vault` INTEGER NOT NULL,
                    `draft` TEXT,
                    `notification_channel_id` TEXT,
                    `bubble_color_argb` INTEGER,
                    `avatar_uri` TEXT
                )
                """.trimIndent(),
            )
            db.execSQL(
                """
                INSERT INTO `conversations_new`
                    (id, thread_id, addresses_csv, display_name, last_message_at,
                     last_message_preview, unread_count, pinned, archived, muted, in_vault,
                     draft, notification_channel_id, bubble_color_argb, avatar_uri)
                SELECT
                    id,
                    CASE WHEN thread_id > 0 THEN thread_id ELSE NULL END,
                    addresses_csv, display_name, last_message_at,
                    last_message_preview, unread_count, pinned, archived, muted, in_vault,
                    draft, notification_channel_id, bubble_color_argb, avatar_uri
                  FROM `conversations`
                """.trimIndent(),
            )
            db.execSQL("DROP TABLE `conversations`")
            db.execSQL("ALTER TABLE `conversations_new` RENAME TO `conversations`")
            db.execSQL(
                "CREATE UNIQUE INDEX IF NOT EXISTS `index_conversations_thread_id` " +
                    "ON `conversations` (`thread_id`)",
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_conversations_pinned_last_message_at` " +
                    "ON `conversations` (`pinned`, `last_message_at`)",
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_conversations_archived` " +
                    "ON `conversations` (`archived`)",
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_conversations_in_vault` " +
                    "ON `conversations` (`in_vault`)",
            )
        }
    }

    /**
     * v9 → v10 (2026-09-09, F20) — `scheduled_messages.claimed_at`.
     *
     * Retour à une migration **additive** : une seule colonne nullable, aucune recréation de
     * table, aucun index touché. Les lignes existantes projettent `NULL`, ce qui est exactement
     * la valeur voulue — cf. le KDoc du champ dans `ScheduledMessageEntity` : sur une ligne déjà
     * `SENDING`, `NULL` signifie « bail expiré », et c'est ce qui débloque les envois coincés en
     * vol par les versions précédentes plutôt que de les y laisser.
     */
    val MIGRATION_9_10: Migration = object : Migration(9, 10) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE `scheduled_messages` ADD COLUMN `claimed_at` INTEGER")
        }
    }

    /**
     * v10 → v11 (2026-09-09, F23) — `messages.send_attempt`.
     *
     * Additive, `NOT NULL DEFAULT 0` : les lignes existantes sont toutes « tentative 0 », ce qui
     * est exactement leur état — aucune n'a été relancée sous une version qui comptait. Le
     * `defaultValue` est répété sur le `@ColumnInfo` de l'entité, sans quoi
     * `runMigrationsAndValidate` refuserait l'écart entre le schéma exporté et la table réelle.
     */
    val MIGRATION_10_11: Migration = object : Migration(10, 11) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE `messages` ADD COLUMN `send_attempt` INTEGER NOT NULL DEFAULT 0")
        }
    }

    /**
     * v1.28.3 — groupes nommés : `conversations.custom_name TEXT`, nul sur l'existant. Additive.
     * Le nom choisi vit dans sa propre colonne parce que `display_name` est RÉÉCRITE par la
     * résolution des contacts (rattrapage à l'ouverture, changement de contact) : un nom posé
     * là aurait été effacé à la première synchronisation.
     */
    val MIGRATION_11_12: Migration = object : Migration(11, 12) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE `conversations` ADD COLUMN `custom_name` TEXT")
        }
    }

    /** All migrations registered in [DatabaseFactory]. Append new ones here in version order. */
    val ALL: Array<Migration> = arrayOf(
        MIGRATION_1_2,
        MIGRATION_2_3,
        MIGRATION_3_4,
        MIGRATION_4_5,
        MIGRATION_5_6,
        MIGRATION_6_7,
        MIGRATION_7_8,
        MIGRATION_8_9,
        MIGRATION_9_10,
        MIGRATION_10_11,
        MIGRATION_11_12,
    )
}
