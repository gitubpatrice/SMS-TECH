package com.filestech.sms.domain.safetycall

/**
 * v1.27.3 — **trace durable d'un déclenchement du Safety call**, archivée au moment où le cycle se
 * referme.
 *
 * # Le besoin auquel ce type répond
 *
 * La notification de fin de séquence ne répond qu'à « une alerte vient de partir ». Elle ne répond
 * pas à **« est-ce que ça s'est déjà déclenché, quand, et vers qui ? »** — et elle ne peut pas :
 * elle se balaie, elle ne survit pas au redémarrage, et elle disparaît dès qu'on la tape, y compris
 * sans l'avoir lue.
 *
 * Or c'est précisément la question qu'on se pose après coup, parfois des semaines plus tard, et
 * aujourd'hui l'application n'a aucun moyen d'y répondre : [SafetyCallConfig.withActivityReset]
 * remet `triggeredAt` et `messagesSent` à zéro, et l'information est perdue pour toujours.
 *
 * # Ce qu'on garde, et ce qu'on ne garde pas
 *
 * Quand, combien, vers qui. **Jamais le corps du message.** Un historique qui conserve le texte
 * exact des alertes devient, dans les mains de quelqu'un qui s'emparerait du téléphone, la
 * description écrite d'un réseau de soutien et de la façon de l'alerter. Le compte et les
 * destinataires suffisent à répondre à la question posée.
 *
 * ⚠️ **Le mode leurre doit masquer cet historique en entier.** Voir [SafetyCallNotice] : un mode
 * leurre qui laisse une trace n'en est pas un, et la garde doit porter sur l'**accès** à la liste,
 * pas sur son affichage.
 *
 * @param triggeredAt instant du **premier** message de la séquence (horloge murale). C'est la date
 *   que l'utilisateur reconnaît — celle où il n'a plus touché son téléphone assez longtemps.
 * @param messagesDelivered nombre d'envois **conclus**, jamais de créneaux réservés — voir
 *   [SafetyCallConfig.messagesDelivered]. Une séquence interrompue par un « je vais bien » en garde
 *   donc un compte partiel, ce qui est l'information juste.
 * @param totalMessages total visé **à l'époque du déclenchement**. Figé dans l'enregistrement plutôt
 *   que relu depuis [SafetyCallConfig.TOTAL_MESSAGES] : si cette constante change un jour, un
 *   historique ancien continuera d'afficher « 2 sur 4 » et non « 2 sur 6 », qui serait un mensonge
 *   rétroactif.
 * @param recipients libellés des contacts **à qui l'alerte a été adressée** : le nom s'il était
 *   renseigné, le numéro sinon. Recopiés et non référencés, parce que la liste de contacts peut avoir
 *   changé depuis, et que « vers qui c'est parti » est une question sur le passé.
 *
 *   ⚠️ **Ce champ dit « adressé à », jamais « reçu par »** — et l'interface le formule ainsi
 *   (relecture Codex du 2026-08-06, SC-1273-04). `sendToContacts` ne conserve qu'un total
 *   `sent`/`failed` et conclut une passe dès qu'**un** envoi réussit : un contact dont les quatre
 *   envois ont échoué — numéro invalide, par exemple — figure donc dans cette liste. Affirmer qu'il a
 *   été alerté serait un mensonge sur une fonction de sécurité.
 *
 *   Le fermer vraiment exigerait de persister les issues par destinataire **dans la transaction de
 *   conclusion**, c'est-à-dire sur le chemin d'envoi, que cette fonctionnalité ne doit pas toucher.
 *   Le libellé honnête est donc préféré au champ trompeur.
 */
data class SafetyCallTriggerRecord(
    val triggeredAt: Long,
    val messagesDelivered: Int,
    val totalMessages: Int,
    val recipients: List<String>,
) {
    /**
     * `true` si l'enregistrement décrit une séquence menée jusqu'au bout.
     *
     * Utile à l'affichage pour distinguer « les quatre alertes sont parties » d'un « arrêté au
     * bout de deux, parce que la personne a confirmé aller bien » — deux issues très différentes
     * que le seul couple de nombres rendrait mal.
     */
    val isComplete: Boolean get() = messagesDelivered >= totalMessages
}
