package com.filestech.sms.data.sms

import android.content.Context
import android.net.Uri
import android.provider.Telephony
import com.filestech.sms.core.ext.blockKey
import com.filestech.sms.data.local.db.entity.MessageEntity
import com.filestech.sms.domain.model.MessageDirection
import dagger.hilt.android.qualifiers.ApplicationContext
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * v1.28.1 (revue externe GitLab !38458, 3e passe) — efface la copie qu'un message possede dans
 * le fournisseur SMS/MMS du systeme.
 *
 * # Pourquoi une interface, et pourquoi ce code a quitte le repository
 *
 * Il y vivait en `private`, au milieu de onze dependances dont dix n'ont rien a voir avec la
 * suppression. Aucun test ne pouvait l'atteindre sans construire tout le repository, et c'est
 * exactement ce qui a laisse passer DEUX defauts de suite sur le meme chemin : la v1.27.10 y
 * jetait le resultat du fournisseur, la v1.27.11 y prouvait l'identite d'une ligne par sa seule
 * date. Ce qui ne se teste pas ne se corrige qu'au hasard des relectures.
 *
 * L'interface existe pour que le contrat destructeur soit **falsifiable** : un test peut donner
 * un effaceur qui refuse toujours et verifier ce que l'appelant en fait, sans emulateur, sans
 * role SMS et sans mock d'une classe finale. [TelephonySystemCopyEraser], elle, se mesure contre
 * le VRAI fournisseur (`SystemRowIdentityTest`) — un faux fournisseur affirmerait ce qu'on veut
 * bien lui faire dire, et c'est ainsi qu'on a cru la suppression systeme fonctionnelle pendant
 * des mois.
 */
interface SystemCopyEraser {

    /**
     * `true` signifie « la copie systeme n'est plus la » : supprimee, ou deja absente, ou jamais
     * miroir d'une ligne systeme. `false` signifie « quelque chose subsiste », y compris le cas
     * ou l'on a deliberement refuse de supprimer.
     */
    fun erase(message: MessageEntity): Boolean
}

@Singleton
class TelephonySystemCopyEraser @Inject constructor(
    @ApplicationContext private val context: Context,
) : SystemCopyEraser {

    /**
     * Deletes a single SMS / MMS row from the system content provider, identified by the URI we
     * captured at insert/import time. No-op when the URI is null (e.g. drafts created before the
     * row was mirrored) or when the OS refuses the delete (SecurityException — we are no longer
     * the default SMS app). Failures do not propagate: the Room delete must still succeed, the
     * user expects the message to disappear from the app even if the system row lingers and gets
     * cleaned up the next time we are default.
     *
     * v1.27.11 (revue externe GitLab !38458, constats 2 et 3) — deux changements :
     *
     *  1. la fonction **rend** ce qui s'est passe. Elle jetait le nombre de lignes rendu par
     *     `delete`, si bien qu'un refus du fournisseur ressemblait trait pour trait a une
     *     suppression reussie. `ConversationEraser.purgeVault` s'appuie sur cette valeur ;
     *  2. elle **verifie l'identite de la ligne avant d'y toucher**, cf. [matchesSystemRow].
     *
     * `true` signifie « la copie systeme n'est plus la » : supprimee, ou deja absente, ou jamais
     * miroir d'une ligne systeme. `false` signifie « quelque chose subsiste », y compris le cas
     * ou l'on a deliberement refuse de supprimer.
     */
    override fun erase(message: MessageEntity): Boolean {
        val telephonyUri = lienSysteme(message) ?: return true
        val uri = canonicalUri(telephonyUri) ?: return false
        val identite = matchesSystemRow(uri, message)
        // La POLITIQUE est une table, ecrite une seule fois et testee pour elle-meme, cf.
        // [SystemRowMatch.issueSansToucherAuFournisseur]. Elle etait auparavant melee au `when`
        // ci-dessous, ou seul un vrai fournisseur pouvait l'atteindre — et ou UNKNOWN partageait
        // en silence la branche de MATCH.
        identite.issueSansToucherAuFournisseur?.let { issue ->
            if (!issue) {
                Timber.w("Refused to delete %s: system row not proven to be this message", telephonyUri)
            }
            return issue
        }
        return runCatching {
            context.contentResolver.delete(uri, null, null) > 0
        }.onFailure {
            Timber.w(it, "Failed to delete %s from system provider", telephonyUri)
        }.getOrDefault(false)
    }

    /**
     * v1.28.3 (F10) — **le lien vers la ligne systeme, qui n'est pas toujours `telephony_uri`.**
     *
     * `erase` sortait sur `true` — « la copie systeme n'est plus la » — des que `telephony_uri`
     * etait vide. Or `ConversationMirror.upsertOutgoingMms` et `upsertOutgoingMediaMms` ecrivent
     * tous deux `telephonyUri = null` : **aucun MMS sortant n'a jamais eu de `telephony_uri`**.
     * Leur seul lien vers le fournisseur est `mms_system_id`, que `MmsSender` enregistre apres
     * l'ecriture dans `content://mms`.
     *
     * Consequence, et ce n'est pas un cas limite : tout MMS envoye par l'application restait dans
     * `content://mms` apres suppression de sa conversation, lisible par toute application ayant
     * `READ_SMS`, et une resynchronisation complete le ramenait. Pire, ce `true` etait rendu a
     * `ConversationEraser.purgeVault`, qui s'en sert comme **preuve** avant de retirer le PIN du
     * coffre : le coffre s'annoncait purge alors que ses MMS etaient toujours la.
     *
     * `null` ne signifie donc plus « pas de `telephony_uri` » mais « aucun lien d'aucune sorte »,
     * ce qui est le seul cas ou l'on peut honnetement repondre qu'il n'y a rien a supprimer.
     * L'identite est verifiee ensuite comme pour n'importe quelle autre ligne : `matchesSystemRow`
     * sait deja lire `content://mms` — date en secondes, boite, adresse via `…/addr`.
     */
    private fun lienSysteme(message: MessageEntity): String? {
        message.telephonyUri?.takeIf { it.isNotBlank() }?.let { return it }
        return message.mmsSystemId?.takeIf { it > 0L }?.let { "$MMS_URI_PREFIX/$it" }
    }

    /**
     * v1.27.11 — voir [canonicalTelephonyUri] pour la regle et la mesure qui la justifie.
     *
     * Normaliser ICI, en plus du chemin d'ecriture, n'est pas redondant : cela couvre les lignes
     * **deja** enregistrees dans toutes les bases installees, que la migration `7 → 8` rattrape
     * en base mais qu'un profil non migre porterait encore.
     */
    private fun canonicalUri(raw: String): Uri? =
        runCatching { Uri.parse(canonicalTelephonyUri(raw)) }.getOrNull()

    /**
     * v1.27.11 (revue externe GitLab !38458, constat 3) — la ligne systeme designee par [uri]
     * est-elle bien CE message ?
     *
     * La question ne se posait pas tant qu'un `telephony_uri` ne pouvait venir que de cet
     * appareil. La restauration en a fait venir d'ailleurs : jusqu'a la v1.27.10 elle recopiait
     * tels quels les identifiants du telephone SOURCE, et `content://sms/42` designe un message
     * ici et un autre la-bas. [com.filestech.sms.data.backup.BackupService] ne cree plus de
     * telles liaisons, mais les lignes deja restaurees par les versions anterieures en portent —
     * et rien dans le schema ne permet de les reconnaitre apres coup. C'est donc le
     * consommateur qu'on protege, pas la donnee qu'on repare.
     *
     * Attention aux unites : `content://mms` compte en SECONDES la ou `content://sms` compte en
     * millisecondes — cf. `TelephonyReader`, qui stocke `dateSec * 1000L`.
     *
     * La tolerance d'une minute n'est pas de la prudence molle, elle est necessaire : les dates
     * des MMS sortants sont posees par la pile du systeme, pas par nous.
     *
     * # v1.28.1 (meme revue, 3e passe, constat 2) — la date seule ne prouvait rien
     *
     * Elle ne separait pas deux messages echanges la meme minute, ce qui est le cas ORDINAIRE
     * d'une conversation. On compare desormais tout ce que le fournisseur expose et que Room
     * detient a l'identique :
     *
     *  - `content://sms` : date, **corps**, et **sens**. Le corps est byte pour byte celui que
     *    l'on a ecrit ([TelephonyReader.insertSentSms] pose le meme `body` et la meme `date` que
     *    la ligne Room) ou celui que l'on a importe de cette meme ligne. Le SENS plutot que le
     *    type brut : la pile fait passer un envoi par `QUEUED` puis `SENT` sans nous prevenir.
     *  - `content://mms` : date et sens seulement. Le corps d'un MMS vit dans `part`, pas dans la
     *    table designee par l'URI ; une jointure pour l'atteindre couterait plus que ce qu'elle
     *    apporte ici.
     *
     * L'adresse est volontairement ECARTEE. Elle n'ajoute aucun pouvoir discriminant que le
     * couple corps + sens ne donne deja, et c'est le seul champ dont on ne maitrise pas la forme
     * de bout en bout — un faux refus y serait paye tres cher, cf. ci-dessous.
     *
     * # Pourquoi les deux refus coutent cher, et pourquoi on refuse quand meme
     *
     * Depuis v1.28.1, un `false` rendu ici CONSERVE la conversation du coffre — c'est le
     * correctif du constat 1. Un refus a tort n'est donc plus anodin : il bloque la porte de
     * sortie « PIN oublie ». C'est malgre tout le bon cote ou echouer, parce que l'echec inverse
     * est SILENCIEUX (une conversation du coffre ressuscitee en clair) la ou celui-ci est VISIBLE
     * — l'utilisateur lit « purge incomplete, le PIN n'a PAS ete retire ».
     *
     * [UNKNOWN] bascule pour la meme raison, et son ancienne justification se retournait contre
     * elle : « sans role SMS on ne lit pas plus qu'on ne supprime ». C'est exact — et cela veut
     * dire que refuser ici ne ferme aucune porte que l'absence de role ne fermait deja. Le seul
     * cas ou l'ancien comportement changeait quelque chose est celui ou la lecture echoue alors
     * que la suppression aurait reussi : on y supprimait une ligne dont rien ne disait qu'elle
     * etait la notre.
     */
    private fun matchesSystemRow(uri: Uri, message: MessageEntity): SystemRowMatch = runCatching {
        val isMms = uri.toString().startsWith(MMS_URI_PREFIX)
        val projection = if (isMms) {
            arrayOf(SYSTEM_DATE_COLUMN, MMS_BOX_COLUMN)
        } else {
            arrayOf(SYSTEM_DATE_COLUMN, Telephony.Sms.BODY, Telephony.Sms.TYPE, Telephony.Sms.ADDRESS)
        }
        context.contentResolver
            .query(uri, projection, null, null, null)
            .use { cursor ->
                when {
                    cursor == null -> SystemRowMatch.UNKNOWN
                    !cursor.moveToFirst() -> SystemRowMatch.ABSENT
                    else -> {
                        // Une LISTE plutot qu'une conjonction : chaque critere se lit et se
                        // discute seul, et en ajouter un n'oblige pas a relire une condition qui
                        // s'allonge. Tous doivent tenir — l'identite se prouve, elle ne se vote
                        // pas a la majorite.
                        val criteres = listOf(
                            sameDate(cursor.getLong(0), isMms, message.date),
                            message.direction == if (isMms) {
                                mmsBoxToDirection(cursor.getInt(1))
                            } else {
                                smsTypeToDirection(cursor.getInt(2))
                            },
                            // Le corps d'un MMS n'est pas dans la table designee par l'URI : il
                            // vit dans `part`, et l'atteindre demanderait de reassembler les
                            // morceaux. Le sens et l'adresse suffisent a le distinguer.
                            isMms || (cursor.getString(1) ?: "") == message.body,
                            sameAddress(
                                if (isMms) mmsAddress(uri, message.direction) else cursor.getString(3),
                                message.address,
                            ),
                        )
                        if (criteres.all { it }) SystemRowMatch.MATCH else SystemRowMatch.MISMATCH
                    }
                }
            }
    }.getOrElse { SystemRowMatch.UNKNOWN }

    /**
     * v1.28.1 — l'adresse d'un MMS, seule chose qui separe deux MMS voisins.
     *
     * **Trou mesure le 2026-09-08, signale par personne.** Cote MMS, l'identite se reduisait a
     * date + sens : deux MMS recus a moins d'une minute d'intervalle etaient indiscernables, et
     * une liaison venue d'un autre telephone faisait supprimer le MMS de quelqu'un d'autre. Le
     * test `MmsRowIdentityTest` l'a reproduit sur un Galaxy S9 avant que ceci n'existe.
     *
     * Une requete de plus sur `content://mms/<id>/addr`, sur un chemin destructeur et rare. La
     * regle est celle de l'import — [readMmsAddress] — et non une seconde ecriture de la meme
     * chose : elle encode le repli sur le type 129 des ROM qui rangent l'expediteur hors norme.
     */
    private fun mmsAddress(uri: Uri, direction: MessageDirection): String? =
        uri.lastPathSegment?.toLongOrNull()?.let {
            readMmsAddress(context.contentResolver, it, direction)
        }

    /**
     * v1.28.1 (relecture externe GPT, point 5) — l'adresse etait ECARTEE, au motif qu'elle
     * n'ajouterait rien au couple corps + sens. **C'etait faux, et l'exemple est banal** : « OK »
     * envoye a deux destinataires dans la meme minute passe date, corps et sens, et seule
     * l'adresse separe les deux.
     *
     * La comparaison passe par [blockKey] et non par l'egalite de chaine : le fournisseur peut
     * rendre `+33612345678` la ou Room garde `06 12 34 56 78`, et un refus a tort coute cher
     * (voir le KDoc de [matchesSystemRow]). La cle ramene les deux formes au meme resultat, et
     * distingue toujours deux destinataires differents.
     *
     * Une adresse systeme absente ne fait PAS echouer la comparaison : elle n'apporte alors
     * aucune information, et exiger son egalite reviendrait a refuser sur une donnee manquante.
     * Le pouvoir discriminant retombe sur corps + sens + date, c'est-a-dire l'etat d'avant.
     */
    private fun sameAddress(systemAddress: String?, localAddress: String): Boolean {
        val systemKey = systemAddress?.takeIf { it.isNotBlank() }?.blockKey() ?: return true
        val localKey = localAddress.takeIf { it.isNotBlank() }?.blockKey() ?: return true
        return systemKey == localKey
    }

    /**
     * v1.28.1 (relecture externe GPT, point 6) — `abs(Long.MIN_VALUE)` reste NEGATIF, et
     * `dateSec * 1000` peut deborder. Une date aberrante — sauvegarde abimee, ou forgee — pouvait
     * donc satisfaire la tolerance par debordement et faire passer une ligne quelconque pour la
     * notre. Sur un garde qui precede une SUPPRESSION, c'est inacceptable : les deux operations
     * sont donc verifiees, et un debordement vaut « pas la meme date ».
     */
    private fun sameDate(rawSystemDate: Long, isMms: Boolean, localDate: Long): Boolean =
        runCatching {
            val systemDateMs = if (isMms) Math.multiplyExact(rawSystemDate, 1000L) else rawSystemDate
            val ecart = Math.subtractExact(systemDateMs, localDate)
            kotlin.math.abs(ecart) <= SYSTEM_DATE_TOLERANCE_MS
        }.getOrDefault(false)

    private companion object {
        /**
         * v1.27.11 — colonne commune a `content://sms` et `content://mms`, servant de
         * discriminant d'identite dans [matchesSystemRow]. Litteral et non
         * `Telephony.Sms.DATE` : la meme constante vaut pour les deux tables, mais pas la
         * meme unite.
         */
        const val SYSTEM_DATE_COLUMN = "date"
        const val MMS_URI_PREFIX = "content://mms"

        /** v1.27.11 — voir [matchesSystemRow] pour le choix d'une minute. */
        const val SYSTEM_DATE_TOLERANCE_MS = 60_000L
    }
}

/**
 * v1.28.1 — l'identite d'une ligne du fournisseur au regard d'un message local.
 *
 * Sorti de [TelephonySystemCopyEraser] pour que la POLITIQUE qui en decoule soit falsifiable sans
 * appareil. La distinction compte : un test instrumente peut montrer qu'une autorite inexistante
 * ne declenche pas de suppression, mais il ne DISTINGUE pas les deux politiques possibles pour
 * [UNKNOWN] — la suppression y echouerait de toute facon. Verifie a la mesure le 2026-09-08 : le
 * test instrumente restait vert avec l'ancienne politique remise en place. Un test qui ne peut
 * pas echouer ne prouve rien, et c'est ici que la regle se prouve.
 */
internal enum class SystemRowMatch {
    /** La ligne est bien ce message : il faut la supprimer. */
    MATCH,

    /** La ligne existe mais decrit un AUTRE message — typiquement un `telephony_uri` restaure. */
    MISMATCH,

    /** Plus rien sous cet URI : une autre application SMS l'a deja efface. */
    ABSENT,

    /** Identite invérifiable : la lecture a echoue, ou n'a rien rendu d'exploitable. */
    UNKNOWN,

    ;

    /**
     * Ce que l'on sait AVANT de toucher au fournisseur. `null` signifie « il faut tenter la
     * suppression », les autres valeurs sont deja le resultat de [SystemCopyEraser.erase].
     *
     * [UNKNOWN] rend `false` depuis la v1.28.1 (revue externe !38458, 3e passe) : pour une
     * operation destructrice, une identite non etablie doit echouer du cote sur. L'ancienne
     * justification — « sans role SMS on ne lit pas plus qu'on ne supprime » — se retournait
     * contre elle : elle dit precisement que refuser ne ferme aucune porte que l'absence de role
     * ne fermait deja. Le seul cas ou l'ancienne regle changeait quelque chose est celui ou la
     * lecture echoue alors que la suppression aurait reussi ; on y supprimait une ligne dont rien
     * ne disait qu'elle etait la notre.
     */
    val issueSansToucherAuFournisseur: Boolean?
        get() = when (this) {
            ABSENT -> true
            MISMATCH, UNKNOWN -> false
            MATCH -> null
        }
}
