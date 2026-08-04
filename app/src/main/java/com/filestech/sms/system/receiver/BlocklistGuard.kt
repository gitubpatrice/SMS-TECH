package com.filestech.sms.system.receiver

import com.filestech.sms.domain.repository.BlockedNumberRepository
import kotlinx.coroutines.CancellationException
import timber.log.Timber

/**
 * v1.27.2 (audit externe 2026-08-04, findings 2 et 5) — consultation de liste noire pour les
 * chemins de RÉCEPTION, avec un repli qui échoue du côté OUVERT.
 *
 * Politique unique aux trois receivers ([SmsDeliverReceiver], [MmsWapPushReceiver],
 * [MmsDownloadedReceiver]) : si la consultation échoue (Room/SQLCipher indisponible), l'expéditeur
 * est traité comme NON bloqué et le message poursuit vers la persistance. Perdre un message
 * légitime est irréversible ; laisser passer le message d'un expéditeur bloqué pendant une erreur
 * de base ne l'est pas. Avant ce correctif les trois chemins divergeaient : côté SMS l'exception
 * court-circuitait `insertInboxSms` et le message était perdu, côté MMS le `runCatching` échouait
 * déjà du bon côté mais avalait aussi `CancellationException`.
 *
 * `CancellationException` est relancée : l'avaler transformerait une annulation normale de la
 * coroutine en « non bloqué » et laisserait le traitement continuer dans un scope annulé.
 */
internal suspend fun BlockedNumberRepository.isBlockedFailOpen(address: String): Boolean =
    try {
        isBlocked(address)
    } catch (ce: CancellationException) {
        throw ce
    } catch (t: Throwable) {
        Timber.w(t, "Blocklist check failed — treating sender as NOT blocked so the message is preserved")
        false
    }
