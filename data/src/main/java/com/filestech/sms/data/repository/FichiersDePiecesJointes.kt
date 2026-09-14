package com.filestech.sms.data.repository

import android.content.Context
import com.filestech.sms.core.result.runCatchingCancellable
import com.filestech.sms.data.local.db.SQLITE_HOST_PARAM_LIMIT
import com.filestech.sms.data.local.db.ScheduledAttachmentCodec
import com.filestech.sms.data.local.db.dao.AttachmentDao
import com.filestech.sms.data.local.db.dao.ScheduledMessageDao
import dagger.hilt.android.qualifiers.ApplicationContext
import timber.log.Timber
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * v1.28.9 (septième note d'Andrew, MR !38458, constats 1 et 3) — **qui possède un fichier de pièce
 * jointe**, et donc quand il peut partir.
 *
 * # Le défaut
 *
 * Un envoi à plusieurs destinataires promeut UNE copie durable de chaque pièce jointe
 * (`SendMediaMmsUseCase`, `SendVoiceMmsUseCase`) et la fait citer par la ligne de chaque
 * destinataire, par l'écho de groupe s'il y en a un, et — pour un envoi programmé — par la ligne
 * `scheduled_messages` elle-même, puisque l'envoi réutilise les fichiers promus à la programmation
 * (`OutgoingAttachmentStoreImpl.promoteToDurable` rend tel quel un fichier déjà durable). Les cinq
 * chemins qui effaçaient ces fichiers ne comptaient personne : supprimer la photo dans le fil du
 * groupe la retirait des fils individuels, supprimer un envoi programmé en échec vidait les bulles
 * des tentatives déjà écrites.
 *
 * # La règle, écrite une fois
 *
 * Un fichier ne part que lorsque plus aucune ligne ne le cite — ni `attachments`, ni
 * `scheduled_messages` —, une fois écartées les citations qui partent avec ce que l'appelant
 * supprime ([Exclusion]). Compter plutôt que copier : aucune écriture de plus à l'envoi, donc aucun
 * nouvel échec possible au moment d'envoyer, et tous les effaceurs passent déjà par ici.
 *
 * # Le sens de l'échec
 *
 * Ne pas savoir qui cite un fichier n'est pas savoir que personne ne le cite. Si la lecture des
 * citations échoue, rien n'est effacé et chaque fichier compte en échec : la purge du coffre
 * conserve alors le parent, la suppression ordinaire le journalise. C'est la leçon de R01/R02
 * appliquée aux références.
 *
 * Deux garde-fous repris de F04 : un `content://` n'est pas notre fichier, et un chemin hors du bac
 * à sable est refusé sans compter comme un échec — une sauvegarde venue d'un autre appareil peut
 * porter des chemins étrangers.
 *
 * Limite assumée : décider et effacer ne sont pas atomiques. Une citation nouvelle ne naît que
 * d'une ligne qui cite déjà le fichier, et celle-là est comptée ; reste le transfert de la pièce
 * d'un message pendant qu'on le supprime.
 */
@Singleton
class FichiersDePiecesJointes @Inject constructor(
    private val attachmentDao: AttachmentDao,
    private val scheduledDao: ScheduledMessageDao,
    @ApplicationContext private val context: Context,
) {

    /** Les citations qui partent avec ce que l'appelant supprime, et ne retiennent donc pas le fichier. */
    sealed interface Exclusion {
        /** Rien n'est écarté : l'appelant a déjà supprimé ses lignes. */
        object Aucune : Exclusion

        /** Une conversation entière : ses messages ET ses envois programmés. */
        data class Conversation(val id: Long) : Exclusion

        /** Un seul message. Tous les envois programmés comptent. */
        data class Message(val id: Long) : Exclusion

        /** Un seul envoi programmé. Tous les messages comptent. */
        data class EnvoiProgramme(val id: Long) : Exclusion
    }

    /**
     * Efface chacun des [chemins] que plus rien ne cite, [exclusion] écartée.
     *
     * @return le nombre d'échecs : fichier que `delete()` refuse, exception, ou citations illisibles
     *   — chaque fichier concerné compte alors. Zéro ne veut pas dire « tout effacé » : un fichier
     *   encore cité est conservé à dessein, et ce n'est pas un échec.
     */
    suspend fun effacerSiPlusCites(chemins: Collection<String>, exclusion: Exclusion = Exclusion.Aucune): Int {
        var echecs = 0
        val racines = racines()
        val possedes = LinkedHashSet<String>()
        for (chemin in chemins) {
            when (estPossede(chemin, racines)) {
                true -> possedes += chemin
                false -> Unit
                null -> echecs++
            }
        }
        if (possedes.isEmpty()) return echecs
        val cites = runCatchingCancellable { encoreCites(possedes, exclusion) }
            .onFailure { Timber.w(it, "pieces jointes : citations illisibles, %d fichier(s) conserve(s)", possedes.size) }
            .getOrNull()
            ?: return echecs + possedes.size
        for (chemin in possedes) {
            if (chemin in cites) continue
            runCatching {
                val fichier = File(chemin)
                // Un `delete()` qui rend `false` n'est pas une exception — mais c'est un échec.
                if (fichier.exists() && !fichier.delete()) {
                    echecs++
                    Timber.w("pieces jointes : fichier non efface")
                }
            }.onFailure {
                echecs++
                Timber.w(it, "pieces jointes : effacement echoue")
            }
        }
        return echecs
    }

    /**
     * `true` : un fichier de notre bac à sable. `false` : pas le nôtre, ignoré sans échec.
     * `null` : chemin illisible, compté en échec.
     */
    private fun estPossede(chemin: String, racines: List<String>): Boolean? {
        if (chemin.isBlank() || chemin.startsWith("content://")) return false
        return runCatching {
            val canonique = File(chemin).canonicalPath
            racines.any { canonique.startsWith(it) }.also { dedans ->
                if (!dedans) Timber.w("pieces jointes : chemin hors du bac a sable ignore")
            }
        }.onFailure { Timber.w(it, "pieces jointes : chemin illisible") }.getOrNull()
    }

    private fun racines(): List<String> =
        listOfNotNull(context.filesDir, context.cacheDir).map { it.canonicalPath + File.separator }

    /** Ceux des [chemins] qu'une ligne non écartée cite encore. */
    private suspend fun encoreCites(chemins: Set<String>, exclusion: Exclusion): Set<String> {
        val cites = HashSet<String>()
        for (lot in chemins.chunked(SQLITE_HOST_PARAM_LIMIT)) {
            for (citation in attachmentDao.findCitations(lot)) {
                val ecartee = when (exclusion) {
                    is Exclusion.Conversation -> citation.conversationId == exclusion.id
                    is Exclusion.Message -> citation.messageId == exclusion.id
                    Exclusion.Aucune, is Exclusion.EnvoiProgramme -> false
                }
                if (!ecartee) cites += citation.localUri
            }
        }
        for (envoi in scheduledDao.findWithAttachments()) {
            val ecarte = when (exclusion) {
                is Exclusion.Conversation -> envoi.conversationId == exclusion.id
                is Exclusion.EnvoiProgramme -> envoi.id == exclusion.id
                Exclusion.Aucune, is Exclusion.Message -> false
            }
            if (ecarte) continue
            for (piece in ScheduledAttachmentCodec.decode(envoi.attachmentsJson)) {
                val chemin = piece.file.absolutePath
                if (chemin in chemins) cites += chemin
            }
        }
        return cites
    }

    /**
     * v1.28.9 (F17) — les PDU MMS gardés pour reprise des messages de clés [cles].
     *
     * Ce sont aussi des fichiers possédés par un message, et plus sensibles que ses pièces jointes : le
     * PDU porte le message ENTIER, en clair. Rejoué après la suppression du message, il le
     * ressusciterait — la clé ne trouvant plus de ligne, la reprise l'écrirait à nouveau. Ils partent
     * donc AVANT les lignes, et chaque appelant décide de ce qu'un PDU qui résiste lui impose.
     *
     * @return le nombre d'échecs, cf. [com.filestech.sms.data.mms.PdusEnAttente.effacerPourCles].
     */
    fun effacerPdusGardes(cles: Collection<String>): Int =
        runCatching { com.filestech.sms.data.mms.PdusEnAttente(context).effacerPourCles(cles) }
            .onFailure { Timber.w(it, "pdu gardes : effacement echoue") }
            .getOrDefault(if (cles.isEmpty()) 0 else 1)
}
