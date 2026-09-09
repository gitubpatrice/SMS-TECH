package com.filestech.sms.data.repository

import android.content.Context
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.filestech.sms.data.local.db.AppDatabase
import com.filestech.sms.data.local.db.entity.ConversationEntity
import com.filestech.sms.data.local.db.entity.MessageEntity
import com.filestech.sms.data.sms.SystemCopyEraser
import com.filestech.sms.domain.model.MessageDirection
import com.filestech.sms.domain.model.MessageStatus
import com.filestech.sms.domain.model.MessageType
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * v1.28.1 (revue externe GitLab !38458, 3e passe) — **la porte du coffre ne s'ouvre pas au second
 * essai**.
 *
 * # Ce que le testeur a reproduit sur emulateur, et que ce test fige
 *
 * Andrew Pozdnakov a injecte un refus d'ecriture cote fournisseur, puis a choisi deux fois
 * « PIN du coffre oublie → Vider et retirer ». Le premier essai refusait correctement de retirer
 * le PIN — mais avait **deja efface la ligne Room**. Au second essai `idsInVault()` etait vide,
 * `VaultPurgeResult` valait `0/0/0`, et `isComplete` etait vrai **par vacuite** : le PIN partait,
 * la copie systeme survivait, et la resynchronisation suivante la ressuscitait en clair.
 *
 * La v1.27.11 avait rendu la purge HONNETE — elle disait ce qui avait echoue. Elle ne l'avait pas
 * rendue REPRENABLE : rendre compte d'un echec ne sert a rien si l'on detruit au passage l'etat
 * dont la reprise a besoin.
 *
 * # L'injection de faute est explicite, et c'est tout l'interet de l'extraction
 *
 * [RefusSystematique] refuse toute suppression systeme. Aucun role SMS, aucun vrai message, aucun
 * comportement d'appareil dans l'equation : le refus est une donnee du test et non une propriete
 * d'environnement qui le rendrait vert un jour et rouge le lendemain. C'est exactement le
 * scenario du testeur, ramene a sa cause.
 *
 * [leCoffreSeVideQuandLaCopieSystemeEstBienPartie] est le **controle negatif** sans lequel ce
 * fichier ne prouverait rien : un effaceur casse ferait passer le premier test tout seul.
 */
@RunWith(AndroidJUnit4::class)
class VaultPurgeRetryTest {

    private lateinit var db: AppDatabase

    private val context: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    /** Le fournisseur refuse tout : c'est la faute injectee par le testeur. */
    private object RefusSystematique : SystemCopyEraser {
        override fun erase(message: MessageEntity): Boolean = false
    }

    /** Le fournisseur cooperant, pour le controle negatif. */
    private object ToutSEfface : SystemCopyEraser {
        override fun erase(message: MessageEntity): Boolean = true
    }

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun tearDown() = db.close()

    @Test
    fun leSecondEssaiNAnnoncePasUnCoffreVideApresUnRefusDuFournisseur() = runBlocking<Unit> {
        seedVaultConversation()
        val eraser = eraserAvec(RefusSystematique)

        val premier = eraser.purgeVault()

        assertThat(premier.deleted).isEqualTo(0)
        assertThat(premier.failed).isEqualTo(1)
        assertThat(premier.isComplete).isFalse()
        // Le coeur du correctif : la conversation est CONSERVEE. C'est elle, et rien d'autre, qui
        // fait office de journal de purge en attente — elle survit au redemarrage parce qu'elle
        // est en base, la ou un compteur en memoire ne survivrait pas.
        assertThat(db.conversationDao().idsInVault()).containsExactly(VAULT_ID)

        val second = eraser.purgeVault()

        // La regression exacte du rapport : ici, avant le correctif, `0/0/0` et `isComplete` vrai.
        assertThat(second.isComplete).isFalse()
        assertThat(second.failed).isEqualTo(1)
        assertThat(second.remaining).isEqualTo(1)
        assertThat(db.conversationDao().idsInVault()).containsExactly(VAULT_ID)
    }

    /**
     * Le point que le testeur souleve derriere le sien : la reprise doit survivre a un
     * redemarrage. Un nouvel [ConversationEraser] — donc aucun etat en memoire conserve — reprend
     * la ou le precedent s'est arrete, parce que la ligne du coffre est en base.
     */
    @Test
    fun laReprisePasseParLaBaseEtNonParUnEtatEnMemoire() = runBlocking<Unit> {
        seedVaultConversation()
        eraserAvec(RefusSystematique).purgeVault()

        // Le fournisseur se remet a cooperer, et c'est un objet neuf qui reprend.
        val apresRedemarrage =
            eraserAvec(ToutSEfface).purgeVault()

        assertThat(apresRedemarrage.deleted).isEqualTo(1)
        assertThat(apresRedemarrage.isComplete).isTrue()
        assertThat(db.conversationDao().idsInVault()).isEmpty()
    }

    @Test
    fun leCoffreSeVideQuandLaCopieSystemeEstBienPartie() = runBlocking<Unit> {
        // Controle negatif : sans lui, un `purgeVault` casse satisferait le premier test.
        seedVaultConversation()
        val eraser = eraserAvec(ToutSEfface)

        val resultat = eraser.purgeVault()

        assertThat(resultat.deleted).isEqualTo(1)
        assertThat(resultat.failed).isEqualTo(0)
        assertThat(resultat.isComplete).isTrue()
        assertThat(db.conversationDao().idsInVault()).isEmpty()
    }

    /**
     * La suppression ORDINAIRE garde son contrat oppose : l'utilisateur qui efface un fil le voit
     * disparaitre, meme si la copie systeme resiste. Sans ce test, un correctif zele finirait par
     * aligner les deux chemins et laisserait a l'ecran une conversation que l'on vient
     * d'effacer.
     */
    @Test
    fun laSuppressionOrdinaireEffaceLaLigneLocaleMemeSiLeSystemeRefuse() = runBlocking<Unit> {
        seedVaultConversation()
        val eraser = eraserAvec(RefusSystematique)

        val systemeParti = eraser.erase(VAULT_ID)

        assertThat(systemeParti).isFalse()
        assertThat(db.conversationDao().idsInVault()).isEmpty()
    }

    /**
     * v1.28.1 (relecture externe GPT, point 4) — un message **arrive pendant le balayage** n'a
     * jamais ete presente au fournisseur. Sans le controle ajoute, la conversation partait avec
     * lui, sa copie systeme restait, et la purge se declarait complete : la meme fuite, par une
     * autre porte.
     *
     * L'effaceur sert ici de point d'injection temporel : il insere un second message au moment
     * ou le premier est traite, c'est-a-dire exactement dans la fenetre de la course.
     */
    @Test
    fun unMessageArriveEnCoursDeBalayage_empecheDeDeclarerLaPurgeComplete() = runBlocking<Unit> {
        seedVaultConversation()
        val intrus = object : SystemCopyEraser {
            var dejaInsere = false
            override fun erase(message: MessageEntity): Boolean {
                if (!dejaInsere) {
                    dejaInsere = true
                    runBlocking { db.messageDao().insert(message.copy(id = 0, telephonyUri = null)) }
                }
                return true // le fournisseur COOPERE : seule la course doit faire echouer.
            }
        }

        val resultat = eraserAvec(intrus).purgeVault()

        assertThat(resultat.isComplete).isFalse()
        assertThat(resultat.failed).isEqualTo(1)
        assertThat(db.conversationDao().idsInVault()).containsExactly(VAULT_ID)
    }

    /**
     * v1.28.2 — la SORTIE ASSUMEE. Le refus prudent de la v1.28.1 est juste tant que l'echec est
     * passager ; il devient une impasse definitive quand la liaison est durablement fausse — un
     * `telephony_uri` restaure d'un autre telephone. `force` rend son coffre a l'utilisateur, en
     * lui disant ce qui subsiste.
     */
    @Test
    fun laSortieAssumeeEffaceLaLigneLocaleMaisContinueDeCompterLEchec() = runBlocking<Unit> {
        seedVaultConversation()

        val resultat = eraserAvec(RefusSystematique).purgeVault(force = true)

        // Le coffre est vide : l'utilisateur retrouve l'acces.
        assertThat(db.conversationDao().idsInVault()).isEmpty()
        // Mais l'echec n'est PAS efface du compte-rendu — l'appelant doit pouvoir le dire.
        assertThat(resultat.failed).isEqualTo(1)
        assertThat(resultat.deleted).isEqualTo(0)
    }

    /**
     * Controle negatif : `force` est un choix de l'appelant, jamais un defaut. Sans lui, la garde
     * de la v1.28.1 tient — c'est le meme appel, au drapeau pres.
     */
    @Test
    fun sansLeDrapeauLaGardeDeLaVersionPrecedenteTientToujours() = runBlocking<Unit> {
        seedVaultConversation()

        val resultat = eraserAvec(RefusSystematique).purgeVault()

        assertThat(db.conversationDao().idsInVault()).containsExactly(VAULT_ID)
        assertThat(resultat.isComplete).isFalse()
    }

    /** Un seul point de construction : la signature a deja bouge une fois. */
    private fun eraserAvec(systemCopy: SystemCopyEraser) =
        ConversationEraser(db, db.conversationDao(), db.messageDao(), systemCopy)

    private suspend fun seedVaultConversation() {
        db.conversationDao().insert(
            ConversationEntity(
                id = VAULT_ID,
                threadId = VAULT_ID,
                addressesCsv = "+33612345678",
                displayName = null,
                lastMessagePreview = "",
                lastMessageAt = 0L,
                unreadCount = 0,
                inVault = true,
            ),
        )
        db.messageDao().insert(
            MessageEntity(
                conversationId = VAULT_ID,
                telephonyUri = "content://sms/9164",
                address = "+33612345678",
                body = "Message du coffre",
                type = MessageType.SMS,
                direction = MessageDirection.INCOMING,
                date = 1_700_000_000_000L,
                dateSent = 1_700_000_000_000L,
                read = true,
                starred = false,
                status = MessageStatus.RECEIVED,
                errorCode = null,
                subId = null,
                scheduledAt = null,
                attachmentsCount = 0,
            ),
        )
    }

    private companion object {
        const val VAULT_ID = 1L
    }
}
