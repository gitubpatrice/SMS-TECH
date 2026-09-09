package com.filestech.sms.domain.model

/**
 * Lifecycle state of a scheduled message — a domain concept, moved out of
 * `data/local/db/entity` (v1.24.0, Étage 2.1).
 *
 * **Not `@Serializable`**: a scheduled message is never part of `BackupPayload` (only conversations
 * and messages are), so no custom serialiser is needed and no serialised format changes.
 *
 * Room stores it as `INTEGER` via [com.filestech.sms.data.local.db.MessageEnumConverters], which
 * maps `rawValue ↔ Int`. Package-independent, so this move changes nothing on disk.
 */
enum class ScheduledState(val rawValue: Int) {
    PENDING(0),
    SENT(1),
    FAILED(2),
    CANCELLED(3),

    /**
     * v1.26.1 (audit H6) — l'envoi a été REVENDIQUÉ par une exécution du worker et est en vol.
     *
     * Sert de verrou : la revendication `PENDING → SENDING` est un UPDATE conditionnel atomique
     * (`ScheduledMessageDao.claimForSending`). Sans elle, la séquence était « lire l'état,
     * envoyer, écrire SENT » — et une mort du processus entre l'envoi réussi et l'écriture
     * faisait ré-exécuter le travail par WorkManager, qui retrouvait `PENDING` et **renvoyait le
     * message**.
     *
     * ⚠️ Cet état est délibérément inclus dans `observePending` : une ligne interrompue en vol
     * doit RESTER VISIBLE. C'est exactement le piège des lignes `CANCELLED`, qu'aucune liste
     * n'affiche et qui étaient donc devenues inatteignables — on ne le reproduit pas ici.
     *
     * Rétro-compatible : un downgrade lit `4` via [fromRaw], qui retombe sur `PENDING` — l'envoi
     * redevient simplement éligible.
     */
    SENDING(4),

    /**
     * v1.28.3 (F20) — l'envoi a été revendiqué, puis l'exécution est morte sans le régler :
     * **on ne sait pas s'il est parti**.
     *
     * # Pourquoi un état de plus
     *
     * [SENDING] devait être transitoire ; rien ne le terminait. Une mort de processus en vol —
     * tueur OEM, OOM, force-stop, la raison même d'être du verrou — laissait la ligne `SENDING`
     * pour toujours : le replay WorkManager la relisait, la voyait non-`PENDING`, rendait
     * `Result.success()` et s'arrêtait là. Elle restait affichée dans « Programmés » avec une
     * échéance passée, son bouton « Annuler » ne pouvait rien contre elle
     * ([ScheduledMessageDao.cancelIfPending] ne matche que `PENDING`) et aucune autre liste ne
     * l'atteignait. Un garde sans issue, exactement le motif relevé en v1.28.2.
     *
     * # Pourquoi ne PAS renvoyer
     *
     * La tentation est de rendre la ligne à `PENDING` après expiration du bail. Ce serait
     * rouvrir le défaut que [SENDING] a fermé : le processus a pu mourir **après** que
     * `SmsManager` a accepté le message, et le renvoyer coûte un second SMS facturé, reçu deux
     * fois. L'issue est réellement inconnue, et c'est ce que cet état dit. L'utilisateur, seul,
     * tranche : relancer (en acceptant le doublon) ou retirer.
     *
     * Rétro-compatible comme [SENDING] : un downgrade lit `5` via [fromRaw] et retombe sur
     * `PENDING`.
     */
    INTERRUPTED(5);
    companion object {
        fun fromRaw(rawValue: Int): ScheduledState = entries.firstOrNull { it.rawValue == rawValue }
            ?: PENDING.also { timber.log.Timber.w("Unknown ScheduledState int %d — defaulting to PENDING", rawValue) }
    }
}
