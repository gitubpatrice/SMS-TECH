package com.filestech.sms.domain.safetycall.journal

/**
 * v1.28.0 — nature d'une ligne du journal technique du Safety call.
 *
 * ⚠️ **Non câblé à ce stade.** Ce paquet fournit le journal ; ses points d'appel dans le moteur, le
 * réglage d'activation et la destruction à l'effacement panique restent à brancher. Voir
 * `idees/IDEES.md`, entrée « Journal technique du moteur Safety Call ».
 *
 * # Pourquoi une énumération fermée et non du texte libre
 *
 * Le journal doit être **triable et parseable**, y compris par un test : c'est ce qui le transforme
 * d'aide humaine en contrat vérifiable sur le récit du moteur. Un champ libre aurait dérivé au
 * premier ajout de fonctionnalité, et deux formulations pour le même fait auraient rendu tout
 * dénombrement faux.
 *
 * # Les deux valeurs qui portent l'essentiel : [NEXT] et [HEARTBEAT]
 *
 * Aucun défaut réel de ce moteur n'a jamais été « l'application a fait la mauvaise chose ». Ils ont
 * tous été **« l'application n'a rien fait »** : le deadman qui se désarmait avant d'envoyer,
 * l'alarme jamais reprogrammée, un `stateIn` jamais hydraté. Un journal qui ne noterait que les
 * événements survenus serait donc **muet exactement là où vivent les défauts**, et son silence se
 * lirait comme du repos.
 *
 * [NEXT] et [HEARTBEAT] existent pour rendre le silence lisible. Le 2026-08-06, une surveillance
 * `adb` cassée est restée silencieuse 90 minutes et ce silence a d'abord été lu comme « aucune
 * relance n'est arrivée » — alors que quatre étaient bien parties. La leçon vaut pour l'application
 * elle-même : **un observateur qui n'émet pas de preuve de vie ne produit pas de donnée.**
 */
enum class SafetyCallJournalEvent {

    /**
     * Un réveil d'alarme a été reçu. Porte l'heure nominale attendue et le **retard mesuré** : c'est
     * ce qui départage « Android a différé l'alarme » de « le moteur a calculé la mauvaise
     * échéance », ambiguïté qui a exigé de lire `dumpsys alarm` à la main le 2026-08-06.
     */
    WAKE,

    /**
     * **Déclaration de rendez-vous** : « prochain réveil à telle heure, pour telle raison ». La
     * ligne dont l'**absence de suite** est une contradiction lisible plutôt qu'un silence. Sans
     * elle, un moteur qui cesse d'être programmé ne laisse aucune trace de sa mort.
     */
    NEXT,

    /** La séquence s'ouvre : le seuil d'inactivité est franchi et l'alerte va partir. */
    TRIGGER,

    /**
     * Un envoi vers **un** destinataire, avec son issue. Le moteur ne conserve qu'un total
     * envoyés/échoués — raison pour laquelle l'historique utilisateur ne peut écrire que « adressé
     * à » et non « reçu par ». Cette ligne-ci est le seul endroit où l'issue par destinataire
     * existe, et donc **la seule façon de prouver qu'aucun contact n'a reçu deux fois la même
     * relance**.
     */
    SEND,

    /** Prise ou renouvellement du bail. Le renouvellement **entre deux contacts** est le chemin qui,
     * s'il fautait, produirait ce doublon. */
    LEASE,

    /** Le minuteur est remis à zéro, **avec sa cause** : ouverture réelle de l'application, bouton
     * « je vais bien », action de notification. Aujourd'hui la cause est indevinable après coup. */
    RESET,

    /** Le dispositif s'éteint, avec son motif — fin de séquence menée à terme, désactivation
     * manuelle, absence de contacts. */
    DISARM,

    /**
     * **Preuve de vie** : le moteur est toujours programmé. Écrite à chaque réveil même quand rien
     * n'est dû. Un trou dans les battements dit « le moteur a cessé d'être programmé » — la seule
     * façon de distinguer *armé et vivant* de *armé et mort*, aujourd'hui indiscernables.
     */
    HEARTBEAT,
}
