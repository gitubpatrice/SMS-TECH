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
    SENDING(4);
    companion object {
        fun fromRaw(rawValue: Int): ScheduledState = entries.firstOrNull { it.rawValue == rawValue }
            ?: PENDING.also { timber.log.Timber.w("Unknown ScheduledState int %d — defaulting to PENDING", rawValue) }
    }
}
