package com.filestech.sms.security

import android.content.Context
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import com.filestech.sms.data.local.datastore.SettingsRepository
import com.filestech.sms.di.ApplicationScope
import com.filestech.sms.domain.settings.AutoLockDelay
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import timber.log.Timber
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Watches the app's process lifecycle and triggers [AppLockManager.forceLock] after the configured
 * auto-lock delay once the app moves to background.
 *
 * Audit F13: when the app gets locked, we also purge `files/exports/` (PDFs, eventual `.smsbk`
 * staging). Without this, conversation PDFs stay readable to anyone with file-access. The user
 * already shared them — they can re-export — so eager deletion is the right trade-off.
 */
@Singleton
class AutoLockObserver @Inject constructor(
    @ApplicationContext private val context: Context,
    private val appLock: AppLockManager,
    // v1.24.0 SEC-CRIT — `Lazy` obligatoire. `VaultManager` injecte `ConversationRepository`,
    // donc `AppDatabase` : une résolution eager depuis `MainApplication.onCreate` provisionnait la
    // base — et avec elle la réparation zéro-clé — sur le main thread. `register()` doit rester sur
    // le main thread (`ProcessLifecycleOwner`), donc c'est bien la dépendance qui est différée, pas
    // l'observateur. Le seul usage est déjà dans une coroutine.
    private val vaultLazy: dagger.Lazy<VaultManager>,
    private val settings: SettingsRepository,
    @ApplicationScope private val scope: CoroutineScope,
) : DefaultLifecycleObserver {

    private var pendingLock: Job? = null

    fun register() {
        ProcessLifecycleOwner.get().lifecycle.addObserver(this)
    }

    override fun onStart(owner: LifecycleOwner) {
        pendingLock?.cancel()
        pendingLock = null
    }

    override fun onStop(owner: LifecycleOwner) {
        pendingLock?.cancel()
        pendingLock = scope.launch {
            // v1.26.1 (audit H15) — cette lecture n'était pas protégée, et c'était LE point de
            // rupture réel de tout le verrouillage automatique : si DataStore levait (corruption
            // — aucun `corruptionHandler` n'est posé sur ce magasin), la coroutine mourait ici et
            // NI `forceLock()` NI `purgeTransientCaches()` ne s'exécutaient jamais. L'application
            // ne se reverrouillait donc plus en passant en arrière-plan, et les PDF d'export en
            // clair restaient sur le disque.
            //
            // L'ironie est que le `runCatching` ci-dessous avait justement été ajouté pour ne pas
            // « sauter les deux » — mais il protège une ligne située APRÈS la rupture.
            //
            // En cas d'échec on applique le chemin LE PLUS STRICT plutôt que d'abandonner :
            // verrouiller et purger tout de suite. Ne rien faire serait le repli permissif.
            // ⚠️ `try/catch` explicite et NON `runCatching` : `runCatching` attrape aussi
            // `CancellationException`, or `onStart` annule précisément ce job quand l'utilisateur
            // revient au premier plan. Avec `runCatching`, ce retour aurait été lu comme un échec
            // de lecture, donc traité par le « chemin le plus strict » — l'application se serait
            // verrouillée et aurait purgé ses caches (brouillon vocal non envoyé compris) au
            // moment même où l'utilisateur la rouvre. On relance donc l'annulation telle quelle.
            val s = try {
                settings.flow.first()
            } catch (ce: kotlin.coroutines.cancellation.CancellationException) {
                throw ce
            } catch (t: Throwable) {
                Timber.w(t, "AutoLockObserver: settings read failed, applying strictest path")
                null
            }
            if (s == null) {
                runCatching { vaultLazy.get().lock() }
                    .onFailure { Timber.w(it, "AutoLockObserver: vault relock skipped") }
                appLock.forceLock()
                purgeTransientCaches()
                return@launch
            }
            // Audit F33: vault relocks immediately when the user opts in.
            // best-effort : le verrouillage du coffre est un flag Room, donc inopérant si la base
            // est inouvrable — alors que `forceLock()` et la purge des exports EN CLAIR qui suivent
            // sont inconditionnellement nécessaires. Sans ce `runCatching`, un échec ici les sautait
            // toutes les deux et laissait les PDF d'export sur le disque.
            if (s.security.lockVaultOnLeave) {
                runCatching { vaultLazy.get().lock() }
                    .onFailure { Timber.w(it, "AutoLockObserver: vault relock skipped") }
            }
            // Audit R9 (v1.14.8) — PanicDecoy a un cycle de vie strict : doit se réinitialiser
            // dès que l'app passe en background, INDÉPENDAMMENT de NEXT_LAUNCH. Sinon la session
            // décoy persistait indéfiniment et l'user était piégé sans pouvoir revenir à sa
            // vraie session sans force-close. NEXT_LAUNCH s'applique au flow normal Unlocked,
            // pas au flow contraint de défense en situation d'urgence.
            val isPanicDecoy = appLock.state.value is AppLockManager.LockState.PanicDecoy
            val ms = when (s.security.autoLockDelay) {
                AutoLockDelay.IMMEDIATE -> 0L
                AutoLockDelay.FIFTEEN_SECONDS -> 15_000L
                AutoLockDelay.ONE_MINUTE -> 60_000L
                AutoLockDelay.FIVE_MINUTES -> 5 * 60_000L
                AutoLockDelay.NEXT_LAUNCH -> if (isPanicDecoy) 0L else Long.MAX_VALUE
            }
            if (ms == Long.MAX_VALUE) {
                // v1.26.1 (audit B7) — la PURGE n'est plus couplée au délai de verrouillage.
                //
                // Le `return@launch` sautait `forceLock()` ET `purgeTransientCaches()`. En
                // « prochain lancement seulement », un PDF d'export EN CLAIR — disponible sur
                // TOUTES les conversations, coffre compris — restait donc indéfiniment sur le
                // disque, hors du chiffrement SQLCipher. Or les artefacts en clair n'ont aucune
                // raison d'attendre le verrouillage : l'utilisateur a quitté l'application, il
                // peut toujours ré-exporter.
                purgeTransientCaches()
                return@launch
            }
            if (ms > 0) delay(ms)
            appLock.forceLock()
            // Audit F13 + S-P2-3: when the lock kicks in, purge generated PDFs, export staging
            // AND the transient audio caches (un-sent voice MMS drafts, sent-PDU staging). Each
            // of those holds plaintext sensitive bytes that could survive a force-stop or a
            // post-mortem analysis. The user always retains the ability to re-record or re-export.
            purgeTransientCaches()
        }
    }

    /**
     * Cleans the plaintext caches that hold sensitive bytes between sessions:
     *
     *  - `files/exports/` — generated PDFs and `.smsbk` staging (audit F13)
     *  - `cache/voice_mms/` — un-sent voice-message drafts (audit S-P2-3)
     *  - `cache/mms_outgoing/` — built PDU files for in-flight MMS (audit S-P2-3)
     *
     * Recursive delete + isolated `runCatching` per folder so a partial failure on one path does
     * not skip the others. Inbound attachments (v1.14.7 = `filesDir/mms_attachments/`,
     * legacy v1.3.10→v1.14.6 = `cache/mms_incoming/`) are intentionally **not** purged here:
     * they are referenced by `AttachmentEntity.localUri` for in-app playback / display, and
     * dropping them would surface broken bubbles after every lock cycle. They are wiped instead
     * by [PanicService.nukeEverything] (qui wipe filesDir/mms_attachments + cacheDir entier).
     */
    private fun purgeTransientCaches() {
        // Audit P1-5 (v1.2.0): `deleteRecursively()` walks every depth — the previous
        // `listFiles()` only swept the first level and would have left any future
        // sub-directory (re-encode staging, tmp ffmpeg work-dirs, etc.) on disk.
        val targets = listOf(
            File(context.filesDir, "exports"),
            File(context.cacheDir, "voice_mms"),
        )
        for (dir in targets) {
            runCatching { if (dir.exists()) dir.deleteRecursively() }
                .onFailure { Timber.w(it, "AutoLockObserver: purge of %s failed", dir.absolutePath) }
        }
        purgeStaleOutgoingPdus()
    }

    /**
     * v1.26.1 (audit M10) — `cache/mms_outgoing` est purgé PAR ÂGE, pas en bloc.
     *
     * Ces fichiers ne sont pas de simples résidus : `MmsSender` y écrit le PDU, en dérive une URI
     * FileProvider et la confie au service MMS du SYSTÈME, qui le lit **plus tard** et de façon
     * différée — Doze, absence de couverture, réessais opérateur. Les supprimer en bloc à chaque
     * verrouillage détruisait donc le PDU d'un envoi encore en vol : le MMS ne partait jamais, et
     * le chien de garde le basculait en échec un quart d'heure plus tard, sans cause visible.
     *
     * Aggravant : `forceLock()` est un no-op quand aucun verrou n'est armé — la configuration par
     * défaut — alors que la purge, elle, s'exécutait quand même. On détruisait un envoi pour
     * protéger un verrou qui n'existait pas.
     *
     * Le seuil DIFFÈRE volontairement de celui de `TelephonySyncWorker` (24 h) : celui-là fait
     * du ménage best-effort, celui-ci arbitre une fenêtre d'exposition. Voir
     * [OUTGOING_PDU_MAX_AGE_MS]. Les PDU restent des octets en clair, mais ils ne
     * contiennent que ce que l'utilisateur vient lui-même d'envoyer, et ils disparaissent au plus
     * tard au bout de ce délai.
     */
    private fun purgeStaleOutgoingPdus() {
        val dir = File(context.cacheDir, "mms_outgoing")
        if (!dir.exists()) return
        val cutoff = System.currentTimeMillis() - OUTGOING_PDU_MAX_AGE_MS
        runCatching {
            dir.listFiles()?.forEach { f ->
                if (f.lastModified() < cutoff) f.deleteRecursively()
            }
        }.onFailure { Timber.w(it, "AutoLockObserver: purge of %s failed", dir.absolutePath) }
    }

    private companion object {
        /**
         * v1.26.1 (audit M10) — âge au-delà duquel un PDU sortant est considéré abandonné.
         *
         * UNE HEURE, et non 24 h. Une première version reprenait le seuil de 24 h du nettoyage
         * périodique ; la revue a fait remarquer qu'il répond à un besoin différent — du ménage
         * best-effort — alors qu'ici on arbitre une FENÊTRE D'EXPOSITION : ces fichiers
         * contiennent le corps du message, les destinataires et les octets bruts des pièces
         * jointes, en clair, et ils survivaient auparavant à peine quelques secondes puisque la
         * purge était en bloc à chaque verrouillage.
         *
         * Une heure couvre très largement un envoi réellement en vol : le chien de garde bascule
         * déjà un envoi resté `PENDING` en échec au bout de 15 minutes. Le plafond de 24 h reste
         * appliqué par ailleurs, ce n'est donc pas un relâchement du pire cas.
         */
        const val OUTGOING_PDU_MAX_AGE_MS = 60L * 60L * 1_000L
    }
}
