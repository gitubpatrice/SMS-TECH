package com.filestech.sms.domain.repository

/**
 * v1.27.11 (revue externe GitLab !38458, constat 2) — issue d'une purge de coffre.
 *
 * [ConversationRepository.deleteAllInVault] renvoyait un `Int` : le nombre de conversations
 * effacees avec succes. La porte de sortie « PIN du coffre oublie » retirait le PIN sur cette
 * seule valeur, sans jamais la regarder — y compris quand elle valait zero.
 *
 * Or un compte de succes ne repond pas a la question qui precede l'ouverture du coffre : **reste-
 * t-il quelque chose a proteger ?** Deux etages avalaient l'echec sans le dire :
 *
 *  - la boucle de purge attrapait l'exception de chaque conversation et continuait ;
 *  - la suppression cote fournisseur du systeme attrapait l'exception **et** ignorait le nombre
 *    de lignes rendu, si bien qu'un refus (role SMS perdu, ROM restrictive) etait indiscernable
 *    d'une suppression reussie. La copie systeme survivait, et la resynchronisation suivante la
 *    ressuscitait — hors du coffre, desormais sans PIN.
 *
 * Les trois champs disent trois choses differentes, et il faut les trois :
 *
 * @property deleted conversations dont la copie locale ET la copie systeme sont parties.
 * @property failed conversations dont quelque chose n'est pas parti. Une copie systeme laissee
 *   derriere compte ici meme si la ligne Room a bien disparu : elle reviendra.
 * @property remaining conversations encore dans le coffre, **relu apres la boucle**. Couvre le
 *   cas que ni [deleted] ni [failed] ne voient : une conversation deplacee dans le coffre
 *   pendant la purge.
 */
data class VaultPurgeResult(
    val deleted: Int,
    val failed: Int,
    val remaining: Int,
) {
    /**
     * Vrai seulement si le coffre est **demontrablement** vide. C'est la seule condition sous
     * laquelle il est legitime de retirer le PIN : l'echange propose a l'utilisateur est
     * « l'acces contre la destruction », et la destruction doit avoir eu lieu.
     */
    val isComplete: Boolean get() = failed == 0 && remaining == 0
}
