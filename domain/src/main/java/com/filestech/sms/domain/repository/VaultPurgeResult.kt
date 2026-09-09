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
 * # v1.28.3 (F09) — `failed` melangeait DEUX natures d'echec, et la sortie forcee les acceptait
 * toutes les deux
 *
 * Un seul compteur portait a la fois « la copie systeme resiste » et « la ligne Room n'a pas pu
 * etre supprimee ». Ce sont pourtant deux choses opposees du point de vue de la seule question
 * qui compte avant de retirer le PIN — **reste-t-il quelque chose a proteger ICI ?** :
 *
 *  - un residu SYSTEME est hors de notre portee, visible par toute application ayant `READ_SMS`,
 *    et c'est exactement ce que la sortie assumee de la v1.28.2 propose a l'utilisateur
 *    d'accepter en connaissance de cause ;
 *  - un echec LOCAL veut dire que la conversation est encore dans le coffre, chiffree, et que
 *    retirer le PIN l'ouvrirait. Aucun echange ne justifie cela, et le texte de consentement
 *    n'en parle meme pas : il promet que ce qui subsiste est « dans le stockage SMS du
 *    telephone » (`strings.xml`), ce qui serait faux.
 *
 * La branche `force` de `SettingsViewModel.forgetVaultPinAndPurge` retirait le PIN sans regarder
 * ni l'un ni l'autre. Les compteurs sont donc separes, et la sortie forcee n'accepte plus que
 * [residuSystemeSeul].
 *
 * @property deleted conversations dont la copie locale ET la copie systeme sont parties.
 * @property systemResidue conversations dont la copie systeme resiste. En purge ordinaire la
 *   ligne locale est alors CONSERVEE — elle est le journal de reprise (v1.28.1) — et compte donc
 *   aussi dans [remaining]. En purge forcee la ligne locale part, et seul le residu subsiste.
 * @property localFailures conversations dont la suppression LOCALE a leve. La donnee protegee
 *   est toujours la, sur cet appareil.
 * @property remaining conversations encore dans le coffre, **relu apres la boucle**. Couvre le
 *   cas qu'aucun compteur ne voit : une conversation deplacee dans le coffre pendant la purge.
 */
data class VaultPurgeResult(
    val deleted: Int,
    val systemResidue: Int,
    val localFailures: Int,
    val remaining: Int,
) {
    /**
     * Vrai seulement si le coffre est **demontrablement** vide, ici comme chez le fournisseur du
     * systeme. C'est la seule condition sous laquelle retirer le PIN ne demande aucun arbitrage :
     * l'echange propose a l'utilisateur est « l'acces contre la destruction », et la destruction
     * a eu lieu partout.
     */
    val isComplete: Boolean get() = systemResidue == 0 && localFailures == 0 && remaining == 0

    /**
     * Il ne subsiste QUE du residu cote fournisseur du systeme : plus rien n'est protege sur cet
     * appareil par le PIN qu'on s'apprete a retirer.
     *
     * C'est la seule situation ou la sortie assumee de la v1.28.2 a un sens. Elle etait jusqu'ici
     * accordee des que `force` etait demande, y compris avec des conversations encore en base.
     */
    val residuSystemeSeul: Boolean get() = localFailures == 0 && remaining == 0 && systemResidue > 0

    /** Ce qui n'est pas parti, toutes natures confondues — pour l'affichage seul. */
    val reste: Int get() = systemResidue + localFailures + remaining
}
