package com.filestech.sms.domain.model

/**
 * v1.28.3 (F21) — ce qu'un envoi a **réellement** fait, destinataire par destinataire.
 *
 * # Ce que la valeur de retour disait avant
 *
 * `SendSmsUseCase` rendait la seule liste des identifiants remis à la pile téléphonie, et la
 * traitait comme un succès dès qu'elle n'était pas vide. Un envoi à trois personnes dont deux
 * échouaient rendait donc exactement la même chose qu'un envoi parfait : le composeur effaçait
 * le brouillon et n'affichait rien. Les deux bulles rouges existaient bien, mais chacune dans la
 * conversation individuelle de son destinataire — SMS n'ayant pas de vrai groupe, un envoi
 * multiple se scinde en autant de fils —, c'est-à-dire là où l'expéditeur n'allait pas regarder.
 *
 * Les destinataires **bloqués**, eux, ne laissaient aucune trace du tout : la boucle d'envoi les
 * sautait par un `continue` muet, sans ligne, sans message, sans compte.
 *
 * # Pourquoi trois listes et non un compteur
 *
 * Les trois issues appellent des mots différents à l'écran, et les confondre serait mentir :
 *  - [dispatched] — remis à la pile téléphonie. Ce n'est pas encore « reçu » : le sort réel
 *    arrive plus tard par accusé (cf. `SmsSentReceiver`) ;
 *  - [failed] — la pile a refusé sur-le-champ. La ligne existe, en échec, relançable ;
 *  - [blocked] — l'application a refusé d'envoyer, parce que l'utilisateur a lui-même bloqué ce
 *    numéro. Ce n'est pas une panne, et le proposer à la relance n'aurait aucun sens.
 */
data class SendReport(
    /** Identifiants Room des messages effectivement remis à la pile téléphonie. */
    val dispatched: List<Long>,
    /** Destinataires que la pile a refusés immédiatement. Leur ligne existe, en échec. */
    val failed: List<PhoneAddress>,
    /** Destinataires écartés parce qu'ils figurent dans la liste de blocage de l'utilisateur. */
    val blocked: List<PhoneAddress>,
) {
    /** Tous les destinataires visés ont été remis à la pile. */
    val isComplete: Boolean get() = failed.isEmpty() && blocked.isEmpty()

    /** Nombre de destinataires qui n'ont rien reçu, quelle qu'en soit la raison. */
    val refusedCount: Int get() = failed.size + blocked.size
}
