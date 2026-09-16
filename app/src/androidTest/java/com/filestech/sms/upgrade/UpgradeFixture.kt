package com.filestech.sms.upgrade

import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Le jeu d'essai de la mise à jour en place : ce que l'ANCIENNE version écrit, ce que la NOUVELLE
 * doit relire — au mot près.
 *
 * # Pourquoi du SQL brut plutôt que les entités Room
 *
 * Ce fichier est **copié tel quel** dans l'arbre de travail du tag précédent par le job CI, puis
 * compilé avec la chaîne de l'époque. Tout ce qu'il touche doit donc exister des deux côtés. Les
 * entités et les DAO bougent à chaque version — un paramètre ajouté, une méthode renommée — et le
 * job deviendrait rouge pour une raison qui n'a rien à voir avec les données qu'il surveille. Les
 * noms de tables et de colonnes, eux, sont tenus par les migrations Room : si une colonne
 * disparaît, le semis échoue, et c'est une information juste.
 *
 * Les déclencheurs que Room pose pour tenir `messages_fts` sont de vrais déclencheurs SQL : un
 * `INSERT` brut dans `messages` alimente l'index plein texte exactement comme le ferait un DAO.
 *
 * # Ce que le jeu d'essai couvre
 *
 * Cinq des sept tables, et les quatre états d'une conversation qui ont chacun leur colonne :
 * ordinaire, épinglée avec des non-lus, archivée, **au coffre**. Le coffre porte en plus la pièce
 * jointe et le seul mot que l'index plein texte doit retrouver.
 */
object UpgradeFixture {

    /** 2026-09-01T12:00:00Z, en dur : un jeu d'essai ne lit pas l'horloge. */
    const val DATE_BASE = 1_756_728_000_000L

    /** Écart entre deux fils, pour que `last_message_at` les ordonne sans ambiguïté. */
    private const val PAS_ENTRE_FILS = 60_000L

    /**
     * Un mot qui n'apparaît nulle part ailleurs dans la base. L'index plein texte est la structure
     * la plus fragile d'une migration — c'est une table virtuelle dont le contenu est reconstruit
     * par des déclencheurs — et un index vide se lit comme « aucun résultat », jamais comme une
     * erreur. Le chercher est le seul moyen de savoir qu'il a survécu.
     */
    const val JETON_PLEIN_TEXTE = "artichautsentinelle2026"

    const val NOM_DU_FIL_AU_COFFRE = "Coffre"
    const val NUMERO_BLOQUE = "+33600000099"
    const val CORPS_PROGRAMME = "message programme qui doit survivre a la mise a jour"
    const val PIECE_JOINTE_NOM = "photo-du-coffre.jpg"
    const val PIECE_JOINTE_TAILLE = 4242L

    data class Message(val corps: String, val entrant: Boolean = true)

    data class Fil(
        val threadId: Long,
        val adresses: String,
        val nomAffiche: String?,
        val messages: List<Message>,
        val epingle: Boolean = false,
        val archive: Boolean = false,
        val nonLus: Int = 0,
        val auCoffre: Boolean = false,
    )

    val FILS: List<Fil> = listOf(
        Fil(
            threadId = 9001,
            adresses = "+33600000001",
            nomAffiche = "Alice",
            epingle = true,
            nonLus = 2,
            messages = listOf(
                Message("premier message d'Alice"),
                Message("deuxieme message d'Alice"),
                Message("ma reponse a Alice", entrant = false),
            ),
        ),
        Fil(
            threadId = 9002,
            adresses = "+33600000002",
            nomAffiche = "Bruno",
            archive = true,
            messages = listOf(Message("message archive de Bruno")),
        ),
        Fil(
            threadId = 9003,
            adresses = "+33600000003;+33600000004",
            nomAffiche = null,
            messages = listOf(Message("message d'un groupe sans nom")),
        ),
        Fil(
            threadId = 9004,
            adresses = "+33600000005",
            nomAffiche = NOM_DU_FIL_AU_COFFRE,
            auCoffre = true,
            messages = listOf(
                Message("message du coffre avec $JETON_PLEIN_TEXTE"),
                Message("second message du coffre", entrant = false),
            ),
        ),
    )

    val NOMBRE_DE_FILS: Int get() = FILS.size
    val NOMBRE_DE_MESSAGES: Int get() = FILS.sumOf { it.messages.size }
    val FIL_AU_COFFRE: Fil get() = FILS.single { it.auCoffre }
    val FIL_EPINGLE: Fil get() = FILS.single { it.epingle }
    val FIL_ARCHIVE: Fil get() = FILS.single { it.archive }

    /** Écrit le jeu d'essai. Idempotent : rejouable à la main sans que les comptes dérivent. */
    fun semer(db: SupportSQLiteDatabase) {
        db.beginTransaction()
        try {
            viderDansLOrdre(db)
            FILS.forEachIndexed { rang, fil -> insererLeFilEtSesMessages(db, rang, fil) }
            insererLaPieceJointeDuCoffre(db)
            insererLeMessageProgramme(db)
            insererLeNumeroBloque(db)
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    /**
     * Vide table par table, **jamais par cascade**.
     *
     * Les déclencheurs que Room pose sur `messages` pour tenir `messages_fts` ne se déclenchent pas
     * sur une suppression provoquée par `ON DELETE CASCADE` tant que `PRAGMA recursive_triggers`
     * vaut zéro — ce qui est le défaut de SQLite. Un semis rejoué laisserait donc un index plein
     * texte peuplé de fantômes, et la vérification compterait deux résultats là où elle en attend un.
     */
    private fun viderDansLOrdre(db: SupportSQLiteDatabase) {
        db.execSQL("DELETE FROM attachments")
        db.execSQL("DELETE FROM messages")
        db.execSQL("DELETE FROM conversations")
        db.execSQL("DELETE FROM scheduled_messages")
        db.execSQL("DELETE FROM blocked_numbers")
    }

    private fun insererLeFilEtSesMessages(db: SupportSQLiteDatabase, rang: Int, fil: Fil) {
        val dateDuFil = DATE_BASE + rang * PAS_ENTRE_FILS
        db.execSQL(
            """
            INSERT INTO conversations
                (thread_id, addresses_csv, display_name, last_message_at,
                 last_message_preview, unread_count, pinned, archived, muted, in_vault)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, 0, ?)
            """.trimIndent(),
            arrayOf<Any?>(
                fil.threadId,
                fil.adresses,
                fil.nomAffiche,
                dateDuFil,
                fil.messages.last().corps,
                fil.nonLus,
                fil.epingle.enEntier(),
                fil.archive.enEntier(),
                fil.auCoffre.enEntier(),
            ),
        )
        val idFil = unEntier(db, "SELECT id FROM conversations WHERE thread_id = ?", arrayOf<Any?>(fil.threadId))
        fil.messages.forEachIndexed { rangMessage, message ->
            db.execSQL(
                """
                INSERT INTO messages
                    (conversation_id, telephony_uri, address, body, type, direction, date,
                     date_sent, read, starred, status, attachments_count)
                VALUES (?, ?, ?, ?, 0, ?, ?, ?, ?, 0, ?, ?)
                """.trimIndent(),
                arrayOf<Any?>(
                    idFil,
                    "content://sms/${fil.threadId}$rangMessage",
                    fil.adresses.substringBefore(';'),
                    message.corps,
                    if (message.entrant) DIRECTION_ENTRANTE else DIRECTION_SORTANTE,
                    dateDuFil + rangMessage,
                    dateDuFil + rangMessage,
                    if (message.entrant) 0 else 1,
                    if (message.entrant) STATUT_RECU else STATUT_DELIVRE,
                    if (fil.auCoffre && rangMessage == 0) 1 else 0,
                ),
            )
        }
    }

    private fun insererLaPieceJointeDuCoffre(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            INSERT INTO attachments (message_id, mime_type, file_name, size_bytes, local_uri)
            VALUES (?, 'image/jpeg', ?, ?, ?)
            """.trimIndent(),
            arrayOf<Any?>(
                idDuPremierMessageDuCoffre(db),
                PIECE_JOINTE_NOM,
                PIECE_JOINTE_TAILLE,
                "file:///data/user/0/paquet/files/mms_attachments/$PIECE_JOINTE_NOM",
            ),
        )
    }

    private fun insererLeMessageProgramme(db: SupportSQLiteDatabase) {
        val fil = FILS.first()
        db.execSQL(
            """
            INSERT INTO scheduled_messages
                (conversation_id, addresses_csv, body, scheduled_at, state, created_at)
            VALUES (?, ?, ?, ?, 0, ?)
            """.trimIndent(),
            arrayOf<Any?>(
                unEntier(db, "SELECT id FROM conversations WHERE thread_id = ?", arrayOf<Any?>(fil.threadId)),
                fil.adresses,
                CORPS_PROGRAMME,
                DATE_BASE + 86_400_000L,
                DATE_BASE,
            ),
        )
    }

    private fun insererLeNumeroBloque(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            INSERT INTO blocked_numbers (normalized_number, raw_number, label, created_at)
            VALUES (?, ?, 'demarchage', ?)
            """.trimIndent(),
            arrayOf<Any?>(NUMERO_BLOQUE, NUMERO_BLOQUE, DATE_BASE),
        )
    }

    fun idDuPremierMessageDuCoffre(db: SupportSQLiteDatabase): Long = unEntier(
        db,
        """
        SELECT m.id FROM messages m
        JOIN conversations c ON c.id = m.conversation_id
        WHERE c.in_vault = 1 ORDER BY m.id LIMIT 1
        """.trimIndent(),
        emptyArray(),
    )

    fun compter(db: SupportSQLiteDatabase, table: String): Long =
        unEntier(db, "SELECT COUNT(*) FROM $table", emptyArray())

    fun unEntier(db: SupportSQLiteDatabase, sql: String, args: Array<Any?>): Long =
        db.query(sql, args).use { curseur ->
            check(curseur.moveToFirst()) { "aucune ligne rendue par : $sql" }
            curseur.getLong(0)
        }

    fun unTexte(db: SupportSQLiteDatabase, sql: String, args: Array<Any?>): String? =
        db.query(sql, args).use { curseur ->
            check(curseur.moveToFirst()) { "aucune ligne rendue par : $sql" }
            if (curseur.isNull(0)) null else curseur.getString(0)
        }

    fun lesTextes(db: SupportSQLiteDatabase, sql: String, args: Array<Any?>): List<String> =
        db.query(sql, args).use { curseur ->
            buildList {
                while (curseur.moveToNext()) add(curseur.getString(0))
            }
        }

    private fun Boolean.enEntier(): Int = if (this) 1 else 0

    // Valeurs brutes des énumérations du domaine, écrites en clair : le jeu d'essai parle SQL, et
    // importer `MessageDirection` ou `MessageStatus` rattacherait ce fichier à un module dont la
    // forme peut changer entre les deux versions qu'il doit compiler.
    private const val DIRECTION_ENTRANTE = 0
    private const val DIRECTION_SORTANTE = 1
    private const val STATUT_DELIVRE = 2
    private const val STATUT_RECU = 4
}
