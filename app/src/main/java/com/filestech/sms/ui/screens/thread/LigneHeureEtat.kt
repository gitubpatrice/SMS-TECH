package com.filestech.sms.ui.screens.thread

import com.filestech.sms.R
import com.filestech.sms.domain.model.Message
import com.filestech.sms.ui.components.ActionHeure

/**
 * 2026-11-12 (issue #87) — **quand la ligne « heure • état » s'affiche sous un message.**
 *
 * Elle ne s'affichait qu'en tête d'une rafale (plus de [RAFALE_HEURE_MS] après le message précédent), ou sur un échec.
 * L'état d'un message envoyé était donc celui du PREMIER de la série, le moins utile : trois SMS envoyés d'affilée
 * montraient un seul « Remis », sous le premier, et l'utilisateur ne voyait pas que les deux autres l'étaient aussi.
 *
 * Désormais elle s'affiche aussi sous le DERNIER message envoyé d'une série, et, pour tout autre message, à la
 * demande ([actionHeure]). Une série, c'est la même règle que la rafale : des messages envoyés qui se suivent à moins
 * de [RAFALE_HEURE_MS], le même jour — et non les 60 s de l'étiquette « Vous », qui ne règle que le regroupement des
 * bulles.
 */
internal const val RAFALE_HEURE_MS: Long = 5 * 60_000L

/**
 * Deux instants du même jour civil, dans le fuseau du téléphone. La frontière du séparateur de jour et du regroupement
 * des bulles (`computeBurstPosition`), et, depuis le 2026-11-12, celle des rafales et des séries : un message de 23 h 59
 * et le suivant à 0 h 01 sont de part et d'autre d'un séparateur, chacun garde donc sa ligne (relecture Gemini). Déplacée
 * ici depuis `ThreadScreen`, où elle était privée : une seule définition pour les quatre usages.
 */
internal fun sameDay(a: Long, b: Long): Boolean {
    val ca = java.util.Calendar.getInstance().apply { timeInMillis = a }
    val cb = java.util.Calendar.getInstance().apply { timeInMillis = b }
    return ca.get(java.util.Calendar.YEAR) == cb.get(java.util.Calendar.YEAR) &&
        ca.get(java.util.Calendar.DAY_OF_YEAR) == cb.get(java.util.Calendar.DAY_OF_YEAR)
}

/**
 * Tête de rafale : le premier message affiché, plus de [RAFALE_HEURE_MS] après le précédent, ou un autre jour que lui.
 * Règle d'avant, plus la frontière du jour.
 */
internal fun ouvreUneRafale(msg: Message, prev: Message?): Boolean =
    prev == null || msg.date - prev.date > RAFALE_HEURE_MS || !sameDay(prev.date, msg.date)

/**
 * Le dernier message ENVOYÉ d'une série : le suivant est reçu, plus de [RAFALE_HEURE_MS] plus tard, un autre jour, ou
 * absent (le plus récent de la fenêtre chargée est le plus récent de la conversation).
 */
internal fun fermeUneSerieEnvoyee(msg: Message, next: Message?): Boolean =
    msg.isOutgoing &&
        (next == null || !next.isOutgoing || next.date - msg.date > RAFALE_HEURE_MS || !sameDay(msg.date, next.date))

/** La ligne s'affiche-t-elle d'office ? Un échec se dit toujours (cf. `MessageStatusLine`). */
internal fun ligneHeureDOffice(msg: Message, prev: Message?, next: Message?): Boolean =
    ouvreUneRafale(msg, prev) || fermeUneSerieEnvoyee(msg, next) || msg.status == Message.Status.FAILED

/**
 * Les messages dont l'utilisateur a demandé l'heure et l'état : seuls ceux-là, par identifiant, au niveau de l'écran.
 *
 * L'état vivait d'abord dans chaque élément de la liste (`rememberSaveable(msg.id)`). La relecture Gemini l'a relevé :
 * la liste garde l'état sauvegardable de CHAQUE élément déjà affiché, même à `false`, et le sérialise dans le `Bundle`
 * de l'activité à la mise en arrière-plan — un long historique parcouru approche alors la limite d'une transaction
 * Binder. Ici, rien n'est sérialisé : c'est un coup d'œil, qui ne survit ni à la rotation ni au redémarrage, et la carte
 * ne contient que les messages révélés. Lue pendant la composition, elle la relance à chaque bascule.
 */
@androidx.compose.runtime.Stable
internal class HeuresRevelees {
    private val revelees = androidx.compose.runtime.mutableStateMapOf<Long, Boolean>()

    fun estRevelee(id: Long): Boolean = revelees[id] == true

    fun basculer(id: Long) {
        if (revelees.remove(id) == null) revelees[id] = true
    }

    /** Le nombre de messages révélés : la carte ne grossit que de ceux-là. */
    val nombre: Int get() = revelees.size
}

/** `null` quand la ligne est affichée d'office : rien à basculer, ni au toucher ni au menu. */
internal fun actionHeure(dOffice: Boolean, revelee: Boolean, basculer: () -> Unit): ActionHeure? = when {
    dOffice -> null
    revelee -> ActionHeure(R.string.action_hide_time, basculer)
    else -> ActionHeure(R.string.action_show_time, basculer)
}

/**
 * Le toucher d'une bulle de texte. Un message en ÉCHEC garde la relance, et elle seule : relancer ENVOIE un SMS, et
 * c'est pour elle que la règle Y6 retire les liens d'une bulle en échec (`MessageBubble`). Sinon, l'heure se bascule.
 * Un toucher sur un lien peut aussi la basculer selon l'appareil : sans effet de bord, cela ne justifie pas de retirer
 * les liens.
 */
internal fun surToucherBulle(msg: Message, actionHeure: ActionHeure?, relancer: () -> Unit) {
    if (msg.status == Message.Status.FAILED) relancer() else actionHeure?.basculer?.invoke()
}
