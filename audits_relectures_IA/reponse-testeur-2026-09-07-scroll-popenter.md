# Réponse au testeur — défilement bloqué au retour vers la liste

- **Reçu le** : 2026-09-07, sur l'e-mail SMS Tech
- **Corrigé dans** : v1.28.0 (289)
- **Statut** : prête à envoyer, telle quelle

---

**Subject: Re: Conversation list not scrollable right after back navigation — fixed in 1.28.0**

Hi,

Thank you for this report. It was accurate, reproducible, and your diagnosis was correct — including the part most people would have missed: that only `popEnter` is affected, while opening a conversation stays interactive during its animation. That detail pointed straight at the cause.

**Confirmed.** Our `NavHost` declared no transitions, so it inherited the navigation-compose defaults (we're on 2.9.8). I reproduced it on a Galaxy S9 by comparing the accessibility hierarchy before and after a gesture: a swipe sent immediately after the back press left the list **byte-identical** — it simply never received the gesture. The same swipe a second later scrolled it normally.

**Fixed in 1.28.0**: the `NavHost` now declares explicit transitions with an 80 ms fade for enter, exit, popEnter and popExit. After the change, the same immediate-swipe sequence moves the list.

**One thing I want to be straight about**, because it matters if you test it again: this does not make the entering screen interactive *during* the animation. `NavHost` exposes no way to do that — the input blocking lives in the library, not in our code. What the change does is shrink the window from several hundred milliseconds to 80, which puts it below the threshold where a dropped gesture is noticeable. It is a mitigation, and it is documented as one in the source rather than dressed up as a cure. If you find a way to make the entering destination accept input while the transition runs, I would genuinely like to hear it.

I kept a short fade rather than removing the animation entirely: with `EnterTransition.None` the visual continuity between the two screens is lost, and 80 ms is enough to preserve it while staying imperceptible.

Your report also had a side effect worth mentioning: setting up the device tests it called for is what surfaced two much older defects, including an encrypted backup that had been impossible for months. Both are fixed in the same release.

Thanks again — a precise symptom, clean steps, a stated expectation, and a hypothesis kept separate from the observation. That is a rare and useful thing to receive.

Best regards,
Patrice — SMS Tech

---

## Pour mémoire, ce que le rapport a réellement déclenché

Le correctif du défilement lui-même est court. Ce que ce rapport a coûté et rapporté, c'est la
campagne de tests sur appareil qu'il a rendue nécessaire — et c'est elle qui a fait sortir :

- la suppression système qui ne fonctionnait pas pour les messages écrits par l'application
  (forme d'URI refusée par le fournisseur, exception avalée) ;
- les doublons après resynchronisation complète, même cause ;
- et, en remontant la chaîne, la sauvegarde chiffrée impossible depuis la v1.27.2.

Aucun des trois n'était visible en lisant le code.
