package com.filestech.sms.data.mms

import android.content.Context
import androidx.annotation.VisibleForTesting
import com.filestech.sms.data.local.db.dao.AttachmentDao
import com.filestech.sms.data.local.db.dao.MessageDao
import com.filestech.sms.data.repository.ConversationMirror
import com.filestech.sms.data.repository.FichiersDePiecesJointes
import com.filestech.sms.data.repository.IncomingAttachment
import com.filestech.sms.domain.model.PhoneAddress
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * v1.28.9 (F17, septième note d'Andrew sur la MR !38458) — **l'écriture d'un MMS reçu, idempotente**, que
 * le receveur l'écrive à l'arrivée ou que la reprise rejoue un PDU gardé.
 *
 * # Ce qui manquait
 *
 * Le receveur gardait le PDU quand une pièce jointe n'avait pas pu être écrite, ou quand le traitement
 * échouait — c'est la seule copie du message, aucun MMS entrant n'étant écrit dans `content://mms` —, mais
 * personne ne le rouvrait : il partait au bout de 24 h. Rejouer demande de savoir si le message a déjà été
 * écrit, sinon on le duplique ; d'où la clé de transaction ([PdusEnAttente]) cherchée dans la transaction
 * d'écriture ([ConversationMirror.inscrireMmsRecu]).
 *
 * # Les issues
 *
 * - message nouveau, toutes les parties écrites : [Resultat.Ecrit] ;
 * - message nouveau, une partie non écrite : [Resultat.EcritIncomplet] — la légende et la trace valent mieux
 *   que rien, mais le PDU doit être gardé ;
 * - message déjà présent, pièces complètes : [Resultat.DejaComplet] — second exemplaire, ou reprise après un
 *   commit ;
 * - message déjà présent, pièces incomplètes : la COMPLÉTION réécrit toutes les parties et les substitue d'un
 *   bloc ([Resultat.Complete]) ; si une partie échoue encore, rien ne change ([Resultat.ToujoursIncomplet]) ;
 * - message supprimé pendant le travail — pendant la complétion, ou avec son PDU pendant l'écriture des
 *   pièces : [Resultat.Disparu]. Rien n'est écrit pour lui.
 *
 * Une exception remonte : l'appelant garde alors le PDU. Tout fichier écrit pour une ligne qui n'a pas été
 * écrite est effacé par la règle de citation — il n'est cité par rien.
 */
@Singleton
class IntegrationMmsRecu @VisibleForTesting constructor(
    private val mirror: ConversationMirror,
    private val attachmentDao: AttachmentDao,
    private val messageDao: MessageDao,
    private val fichiers: FichiersDePiecesJointes,
    private val ecrivain: EcrivainDePieces,
) {

    @Inject constructor(
        @ApplicationContext context: Context,
        mirror: ConversationMirror,
        attachmentDao: AttachmentDao,
        messageDao: MessageDao,
        fichiers: FichiersDePiecesJointes,
    ) : this(mirror, attachmentDao, messageDao, fichiers, EcrivainDePiecesSurDisque(context))

    sealed interface Resultat {
        data class Ecrit(val messageId: Long) : Resultat
        data class EcritIncomplet(val messageId: Long) : Resultat
        data class DejaComplet(val messageId: Long) : Resultat
        data class Complete(val messageId: Long) : Resultat
        data class ToujoursIncomplet(val messageId: Long) : Resultat
        data object Disparu : Resultat
    }

    /**
     * Écrit une partie sur le disque, ou rend `null`. Un point d'injection pour les tests : sans lui, la
     * complétion PARTIELLE — une pièce écrite, l'autre non — serait du code que rien n'atteint.
     */
    fun interface EcrivainDePieces {
        fun ecrire(partie: PartieMms): IncomingAttachment?
    }

    /**
     * @param membres les membres du groupe reconstitués depuis l'en-tête, ou `null` pour une conversation
     *   ordinaire — cf. `GroupMmsMembers`.
     * @param cle la clé de transaction, lue dans le nom du PDU ; `null` : aucune reconnaissance possible.
     * @param pduPresent la porte de [ConversationMirror.inscrireMmsRecu] : le PDU existe-t-il encore ? Il
     *   part avec son message quand l'utilisateur supprime celui-ci.
     */
    suspend fun integrer(
        contenu: ContenuMmsRecu,
        subId: Int?,
        membres: List<PhoneAddress>?,
        cle: String?,
        pduPresent: () -> Boolean,
    ): Resultat {
        if (cle != null) {
            // Lecture rapide hors transaction : épargne l'écriture des pièces d'un doublon complet. Elle ne
            // fait pas foi — c'est `inscrireMmsRecu`, dans sa transaction, qui tranche.
            messageDao.findIdByTransactionKey(cle)?.let { return completer(it, contenu) }
        }
        val pieces = ecrire(contenu.parties)
        val inscription = try {
            mirror.inscrireMmsRecu(
                address = contenu.expediteur,
                pieces = pieces,
                caption = contenu.legende,
                previewLabel = contenu.libelleApercu,
                date = contenu.date,
                subId = subId,
                groupMembers = membres,
                transactionKey = cle,
                encoreVoulu = pduPresent,
            )
        } catch (t: Throwable) {
            // Transaction annulée : aucune ligne ne cite ces fichiers.
            effacerMalgreAnnulation(pieces)
            throw t
        }
        if (inscription == null) {
            // Le PDU est parti pendant l'écriture des pièces : son message venait d'être supprimé.
            effacer(pieces)
            return Resultat.Disparu
        }
        if (!inscription.nouveau) {
            // Une autre écriture de ce MMS l'a emporté entre la lecture rapide et la transaction.
            effacer(pieces)
            return completer(inscription.messageId, contenu)
        }
        return if (pieces.size < contenu.parties.size) {
            Timber.w("MMS: piece jointe non persistee — PDU conserve")
            Resultat.EcritIncomplet(inscription.messageId)
        } else {
            Resultat.Ecrit(inscription.messageId)
        }
    }

    /**
     * La complétude d'un message déjà écrit : autant de pièces que de parties RETENUES par
     * [LecteurRetrieveConf.partiesMedia] — la légende et la mise en page n'en sont pas —, et chaque fichier
     * présent. Incomplet, il est complété d'un bloc.
     */
    private suspend fun completer(messageId: Long, contenu: ContenuMmsRecu): Resultat {
        val presentes = attachmentDao.findForMessage(messageId)
        val complet = presentes.size >= contenu.parties.size &&
            presentes.all { it.localUri.startsWith("content://") || File(it.localUri).exists() }
        if (complet) return Resultat.DejaComplet(messageId)
        val pieces = ecrire(contenu.parties)
        if (pieces.size < contenu.parties.size) {
            effacer(pieces)
            return Resultat.ToujoursIncomplet(messageId)
        }
        val anciennes = try {
            mirror.remplacerPiecesMmsRecu(messageId, pieces)
        } catch (t: Throwable) {
            effacerMalgreAnnulation(pieces)
            throw t
        }
        if (anciennes == null) {
            effacer(pieces)
            return Resultat.Disparu
        }
        // Les anciennes lignes sont remplacées : leurs fichiers ne partent que s'ils ne sont plus cités.
        val echecs = fichiers.effacerSiPlusCites(anciennes)
        if (echecs > 0) Timber.w("MMS: %d ancienne(s) piece(s) de %d non effacee(s)", echecs, messageId)
        return Resultat.Complete(messageId)
    }

    private fun ecrire(parties: List<PartieMms>): List<IncomingAttachment> = parties.mapNotNull(ecrivain::ecrire)

    private suspend fun effacer(pieces: List<IncomingAttachment>) {
        if (pieces.isEmpty()) return
        val echecs = fichiers.effacerSiPlusCites(pieces.map { it.file.absolutePath })
        if (echecs > 0) Timber.w("MMS: %d piece(s) ecrite(s) pour rien non effacee(s)", echecs)
    }

    /**
     * Le ménage d'une écriture qui a levé va au bout, même quand c'est une annulation qui l'interrompt, et
     * ne remplace jamais l'exception d'origine.
     */
    private suspend fun effacerMalgreAnnulation(pieces: List<IncomingAttachment>) {
        withContext(NonCancellable) {
            runCatching { effacer(pieces) }.onFailure { Timber.w(it, "MMS: menage des pieces echoue") }
        }
    }
}

/**
 * Écrit une partie dans `filesDir/mms_attachments/`, par `.tmp` + renommage.
 *
 * **v1.3.10 (Q5)** — atomique : un processus tué en pleine écriture laissait un fichier tronqué que la
 * visionneuse faisait planter. **v1.14.7** — `filesDir` et non plus `cacheDir`, que le système vide.
 * **v1.28.9** (septième note d'Andrew, point mineur 2) — le `.tmp` d'une écriture qui LÈVE est effacé :
 * seul l'échec du renommage le faisait, et un disque plein laissait un fichier à moitié écrit, sans ligne
 * pour y mener.
 *
 * Rend `null` sur tout échec : c'est à l'appelant de voir qu'une partie manque.
 */
class EcrivainDePiecesSurDisque(private val context: Context) : IntegrationMmsRecu.EcrivainDePieces {

    override fun ecrire(partie: PartieMms): IncomingAttachment? {
        var temporaire: File? = null
        return try {
            val dossier = File(context.filesDir, DOSSIER_PIECES).apply { mkdirs() }
            val nom = "in-${System.currentTimeMillis()}-${UUID.randomUUID().toString().take(8)}." +
                extension(partie.mime)
            val final = File(dossier, nom)
            val tmp = File(dossier, "$nom.tmp")
            temporaire = tmp
            tmp.writeBytes(partie.octets)
            if (tmp.renameTo(final)) {
                IncomingAttachment(file = final, mimeType = partie.mime)
            } else {
                tmp.delete()
                Timber.w("MMS: renommage refuse pour %s", final.name)
                null
            }
        } catch (t: Throwable) {
            temporaire?.let { runCatching { it.delete() } }
            Timber.w(t, "MMS: ecriture de piece jointe echouee mime=%s", partie.mime)
            null
        }
    }

    private fun extension(mime: String): String = when (mime.lowercase()) {
        "audio/mp4", "audio/aac", "audio/mp4a-latm" -> "m4a"
        "audio/amr", "audio/3gpp" -> "amr"
        "audio/mpeg", "audio/mp3" -> "mp3"
        "audio/ogg", "audio/opus" -> "ogg"
        "image/jpeg", "image/jpg" -> "jpg"
        "image/png" -> "png"
        "image/gif" -> "gif"
        "image/webp" -> "webp"
        "image/heic" -> "heic"
        "video/mp4" -> "mp4"
        "video/3gpp" -> "3gp"
        "video/webm" -> "webm"
        "application/pdf" -> "pdf"
        "application/vnd.openxmlformats-officedocument.wordprocessingml.document" -> "docx"
        "application/msword" -> "doc"
        "application/zip" -> "zip"
        else -> "bin"
    }

    private companion object {
        /** Racine durable des pièces reçues, partagée avec les envois et le FileProvider. */
        const val DOSSIER_PIECES = "mms_attachments"
    }
}
