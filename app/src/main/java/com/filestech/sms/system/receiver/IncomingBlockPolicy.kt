package com.filestech.sms.system.receiver

import com.filestech.sms.domain.repository.BlockedNumberRepository
import com.filestech.sms.domain.repository.ContactRepository
import com.filestech.sms.domain.settings.AppSettingsSource
import kotlinx.coroutines.CancellationException
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * v1.28.3 (F26) — décide si un message entrant doit être écarté. **Un seul endroit pour les
 * deux règles**, appelé par les trois receivers.
 *
 * # Le défaut
 *
 * Le réglage « Bloquer les numéros inconnus » (`BlockingSettings.blockUnknown`) était affiché,
 * persisté, restitué… et **jamais appliqué**. Dix occurrences dans le dépôt : une déclaration,
 * une clé DataStore, deux lectures de restitution, deux écritures, un affichage — et zéro point
 * d'effet, zéro test. `BlockedNumberRepository.isBlocked` n'interroge que la table des numéros
 * explicitement bloqués et n'a jamais consulté les réglages.
 *
 * Ce n'est pas une commodité inerte : `settings_block_unknown` s'affiche « Block unknown
 * numbers ». C'est une promesse de sécurité, faite à l'utilisateur, que rien ne tenait. Le
 * fichier `AppSettings` documente d'ailleurs le même motif deux lignes plus bas — `blockShortCodes`
 * y a été retiré en v1.3.5 comme « champ fantôme : aucun caller ne le lisait ». `blockUnknown`
 * est resté.
 *
 * # Le sens de l'échec, qui est ici tout le sujet
 *
 * Les deux règles échouent OUVERT, mais pour deux raisons différentes qu'il faut distinguer :
 *
 *  - la liste noire, parce que perdre un message légitime est irréversible alors que laisser
 *    passer celui d'un expéditeur bloqué pendant une panne de base ne l'est pas (politique
 *    v1.27.2, cf. [isBlockedFailOpen]) ;
 *  - le contrôle « inconnu », parce qu'il porte un piège d'une tout autre gravité. Si
 *    `READ_CONTACTS` est refusée ou révoquée, la recherche de contact échoue pour TOUS les
 *    numéros. Un repli fermé bloquerait alors **la totalité des SMS entrants**, en silence, et
 *    l'utilisateur ne verrait qu'un téléphone qui ne reçoit plus rien. Une permission absente ne
 *    doit jamais se traduire par « cette personne est inconnue » : elle se traduit par « je ne
 *    sais pas », et on ne bloque pas sur une ignorance.
 */
@Singleton
class IncomingBlockPolicy @Inject constructor(
    private val blockedRepo: BlockedNumberRepository,
    private val contacts: ContactRepository,
    private val settings: AppSettingsSource,
) {

    /**
     * `true` si le message doit être écarté. Ne lève jamais, sauf annulation de coroutine —
     * l'avaler transformerait une annulation normale en « non bloqué » et laisserait le
     * traitement continuer dans un scope annulé.
     */
    suspend fun doitEcarter(address: String): Boolean {
        if (address.isBlank()) return false
        if (blockedRepo.isBlockedFailOpen(address)) return true
        return estInconnuEtRefuse(address)
    }

    private suspend fun estInconnuEtRefuse(address: String): Boolean =
        try {
            val actif = settings.hydratedOrNull()?.blocking?.blockUnknown ?: false
            if (!actif) {
                false
            } else {
                // `null` = aucun contact ne porte ce numéro. La distinction avec « je n'ai pas pu
                // regarder » est faite par le `catch` ci-dessous, qui ne bloque pas.
                contacts.lookupByPhone(address) == null
            }
        } catch (ce: CancellationException) {
            throw ce
        } catch (t: Throwable) {
            // Permission contacts révoquée, fournisseur indisponible, réglages illisibles : on ne
            // sait pas si l'expéditeur est connu. On ne bloque donc pas. Cf. le KDoc de la classe :
            // le repli inverse couperait toute réception sans le dire.
            Timber.w(t, "blockUnknown: verification impossible — l'expediteur n'est PAS ecarte")
            false
        }
}
