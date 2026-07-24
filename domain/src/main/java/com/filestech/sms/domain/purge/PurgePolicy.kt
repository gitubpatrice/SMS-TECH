package com.filestech.sms.domain.purge

/**
 * v1.6.1 (audit QUAL-01 / QUAL-16) — source unique de vérité pour la politique de
 * purge automatique de l'historique des messages. Avant : trois sites définissaient
 * leur propre `SAFETY_NET_DAYS = 5L` + `MS_PER_DAY = 86_400_000L` et recalculaient
 * `purgeCutoffMs` chacun à leur sauce (ConversationRepositoryImpl + TelephonySyncWorker).
 * Un changement de la valeur safety net devait être propagé manuellement aux deux
 * endroits, sous peine de divergence silencieuse entre la purge déclenchée par
 * l'utilisateur ("Effacer maintenant") et la purge auto mensuelle du worker.
 *
 * Tout consommateur de la politique de purge doit lire ses constantes ici et
 * appeler [purgeCutoffMs] au lieu de recalculer inline.
 */

/** Millisecondes par jour, partagé par toutes les bornes temporelles de purge. */
const val MS_PER_DAY: Long = 24L * 60L * 60L * 1_000L

/**
 * Filet de sécurité interne : aucun message des N derniers jours n'est jamais
 * purgé, même si la rétention exposée à l'utilisateur (30 / 60 / 180 j) descendait
 * sous ce seuil par un futur réglage avancé. Strictement plus large que toutes les
 * options actuelles donc neutre, mais conservé comme garantie défensive.
 */
const val SAFETY_NET_DAYS: Long = 5L

/**
 * Calcule le cutoff effectif (timestamp Unix ms) à passer à `MessageDao.purgeOlderThan`.
 * Tout message avec `date < cutoff` est éligible à la purge (le DAO épargne déjà les
 * starred via son WHERE).
 *
 * @param days rétention en jours choisie par l'utilisateur (≥ 1).
 * @param now horloge injectable pour les tests ; défaut = `System.currentTimeMillis()`.
 */
fun purgeCutoffMs(days: Int, now: Long = System.currentTimeMillis()): Long {
    val retentionCutoff = now - days.toLong() * MS_PER_DAY
    val safetyNetCutoff = now - SAFETY_NET_DAYS * MS_PER_DAY
    return minOf(retentionCutoff, safetyNetCutoff)
}
