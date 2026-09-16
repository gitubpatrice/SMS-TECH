package com.filestech.sms.upgrade

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.filestech.sms.data.local.db.AppDatabase
import com.google.common.truth.Truth.assertThat
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import javax.inject.Inject

/**
 * Seconde moitié du contrôle de mise à jour : relit, depuis la version COURANTE installée par
 * `adb install -r` par-dessus la précédente, le jeu d'essai que [UpgradeSeedTest] a écrit.
 *
 * # Ce que chaque assertion protège
 *
 * L'injection elle-même est déjà une mesure : si la clé scellée par le Keystore n'était plus
 * descellable après la mise à jour, ou si la nouvelle version de SQLCipher n'ouvrait pas un fichier
 * écrit par l'ancienne, [com.filestech.sms.data.local.db.DatabaseFactory] lèverait avant la première
 * assertion. Le reste vérifie que les LIGNES sont là — conversations, coffre, tables annexes, index
 * plein texte — parce qu'une base qui s'ouvre en ayant perdu son contenu est le pire des deux mondes :
 * l'application démarre, et l'utilisateur découvre le vide.
 *
 * # Le mode de défaillance visé
 *
 * Ce n'est pas un plantage. C'est une perte de données chez quelqu'un qui a seulement accepté une
 * mise à jour — et la v1.28.10 a retiré les onze plafonds Dependabot qui gelaient SQLCipher et Room,
 * les deux bibliothèques qui touchent aux données stockées. Leurs montées arrivent désormais toutes
 * seules, en PR, avec une CI verte.
 *
 * Le job qui exécute ce fichier comporte **deux contrôles négatifs** : il exige que ces tests
 * ÉCHOUENT sur une base corrompue, puis sur des données effacées. Sans eux, rien ne distinguerait
 * une vérification qui passe d'une vérification qui ne peut pas échouer.
 * Cf. `.github/workflows/upgrade-test.yml`.
 */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
@UpgradeTest
class UpgradeVerifyTest {

    @get:Rule
    val hilt = HiltAndroidRule(this)

    @Inject
    lateinit var base: AppDatabase

    @Before
    fun injecter() {
        hilt.inject()
    }

    @After
    fun fermer() {
        base.close()
    }

    @Test
    fun lesConversationsEtLeursMessagesSontRelus() {
        val db = base.openHelper.writableDatabase

        assertThat(UpgradeFixture.compter(db, "conversations")).isEqualTo(UpgradeFixture.NOMBRE_DE_FILS.toLong())
        assertThat(UpgradeFixture.compter(db, "messages")).isEqualTo(UpgradeFixture.NOMBRE_DE_MESSAGES.toLong())

        UpgradeFixture.FILS.forEach { fil ->
            val corps = UpgradeFixture.lesTextes(
                db,
                """
                SELECT m.body FROM messages m
                JOIN conversations c ON c.id = m.conversation_id
                WHERE c.thread_id = ? ORDER BY m.date
                """.trimIndent(),
                arrayOf<Any?>(fil.threadId),
            )
            assertThat(corps).containsExactlyElementsIn(fil.messages.map { it.corps }).inOrder()
        }

        val epingle = UpgradeFixture.FIL_EPINGLE
        assertThat(
            UpgradeFixture.unEntier(
                db,
                "SELECT unread_count FROM conversations WHERE thread_id = ? AND pinned = 1",
                arrayOf<Any?>(epingle.threadId),
            ),
        ).isEqualTo(epingle.nonLus.toLong())

        assertThat(
            UpgradeFixture.unEntier(db, "SELECT COUNT(*) FROM conversations WHERE archived = 1", emptyArray()),
        ).isEqualTo(1L)
    }

    @Test
    fun leContenuDuCoffreEstRelu() {
        val db = base.openHelper.writableDatabase
        val coffre = UpgradeFixture.FIL_AU_COFFRE

        assertThat(
            UpgradeFixture.unEntier(db, "SELECT COUNT(*) FROM conversations WHERE in_vault = 1", emptyArray()),
        ).isEqualTo(1L)
        assertThat(
            UpgradeFixture.unTexte(
                db,
                "SELECT display_name FROM conversations WHERE in_vault = 1",
                emptyArray(),
            ),
        ).isEqualTo(coffre.nomAffiche)

        // Le drapeau et le contenu séparément : une conversation qui SORTIRAIT du coffre à la mise
        // à jour garderait ses messages tout en les rendant visibles hors du coffre — une perte de
        // confidentialité, pas une perte de données, et aucun compte global ne la verrait.
        val corpsDuCoffre = UpgradeFixture.lesTextes(
            db,
            """
            SELECT m.body FROM messages m
            JOIN conversations c ON c.id = m.conversation_id
            WHERE c.in_vault = 1 ORDER BY m.date
            """.trimIndent(),
            emptyArray(),
        )
        assertThat(corpsDuCoffre).containsExactlyElementsIn(coffre.messages.map { it.corps }).inOrder()
    }

    @Test
    fun lesTablesAnnexesSontRelues() {
        val db = base.openHelper.writableDatabase

        assertThat(UpgradeFixture.compter(db, "attachments")).isEqualTo(1L)
        assertThat(
            UpgradeFixture.unTexte(db, "SELECT file_name FROM attachments", emptyArray()),
        ).isEqualTo(UpgradeFixture.PIECE_JOINTE_NOM)
        assertThat(
            UpgradeFixture.unEntier(db, "SELECT size_bytes FROM attachments", emptyArray()),
        ).isEqualTo(UpgradeFixture.PIECE_JOINTE_TAILLE)

        // La pièce jointe pend au message du coffre : si la clé étrangère avait été reconstruite de
        // travers par une migration, elle pointerait ailleurs sans que le compte bouge.
        assertThat(
            UpgradeFixture.unEntier(db, "SELECT message_id FROM attachments", emptyArray()),
        ).isEqualTo(UpgradeFixture.idDuPremierMessageDuCoffre(db))

        assertThat(UpgradeFixture.compter(db, "scheduled_messages")).isEqualTo(1L)
        assertThat(
            UpgradeFixture.unTexte(db, "SELECT body FROM scheduled_messages", emptyArray()),
        ).isEqualTo(UpgradeFixture.CORPS_PROGRAMME)

        assertThat(UpgradeFixture.compter(db, "blocked_numbers")).isEqualTo(1L)
        assertThat(
            UpgradeFixture.unTexte(db, "SELECT normalized_number FROM blocked_numbers", emptyArray()),
        ).isEqualTo(UpgradeFixture.NUMERO_BLOQUE)
    }

    @Test
    fun lIndexPleinTexteRetrouveLeMessageDuCoffre() {
        val db = base.openHelper.writableDatabase

        assertThat(
            UpgradeFixture.unEntier(
                db,
                "SELECT COUNT(*) FROM messages_fts WHERE body MATCH ?",
                arrayOf<Any?>(UpgradeFixture.JETON_PLEIN_TEXTE),
            ),
        ).isEqualTo(1L)
    }

    @Test
    fun leSchemaEstALaVersionDeCetteBuildSurLaBaseSEMEE() {
        val db = base.openHelper.writableDatabase

        // Ce premier contrôle n'est pas une redite du test précédent : sans lui, ce test PASSERAIT
        // sur une base recréée de zéro, qui porte le même `user_version` en étant vide. Mesuré :
        // sur un `pm clear`, les quatre autres tombaient et celui-ci restait vert. Un test qui ne
        // peut pas échouer pour la raison qui le justifie ne protège rien.
        assertThat(UpgradeFixture.compter(db, "conversations")).isEqualTo(UpgradeFixture.NOMBRE_DE_FILS.toLong())

        // `user_version` est là où Room inscrit son numéro de schéma : l'égalité prouve que les
        // migrations ont tourné sur CE fichier-ci, celui qui porte le jeu d'essai.
        assertThat(UpgradeFixture.unEntier(db, "PRAGMA user_version", emptyArray()))
            .isEqualTo(AppDatabase.SCHEMA_VERSION.toLong())
    }
}
